#!/bin/sh
# Автодеплой вместо Vercel: раз в минуту (/etc/cron.d/orda-deploy) смотрит origin/main
# и, если есть новый коммит, пересобирает и перезапускает веб. Лог — /var/log/orda-deploy.log.
# Ручной запуск: sh /opt/orda/bin/deploy.sh   (FORCE=1 — пересобрать без нового коммита)
#
# Cron запускает копию из /opt/orda/bin, а не файл из репозитория: при откате репозиторий
# уходит на старый коммит, и его старый deploy.sh сам выкатил бы main обратно. Копия
# обновляется только обычным деплоем main.
#
# Кнопки на /platform/status кладут заявку в /opt/orda/control/request.json
# ({"action":"redeploy"} | {"action":"rollback","sha":"..."} | {"action":"unpin"}).
# Откат закрепляет версию (/opt/orda/control/pin): пока файл есть, новые коммиты не выкатываются.
set -eu

APP=/opt/orda/app
CONTROL=/opt/orda/control
LOG=/var/log/orda-deploy.log
exec 9>/run/orda-deploy.lock
flock -n 9 || exit 0  # сборка уже идёт

log() { echo "$(date -u +%FT%TZ) $*" >> "$LOG"; }
short() { echo "$1" | cut -c1-7; }
# Журнал деплоев для /platform/status: по строке JSON на начало и конец сборки.
event() { python3 -c 'import json,sys;print(json.dumps(dict(a.split("=",1) for a in sys.argv[1:]),ensure_ascii=False))' "$@" >> "$CONTROL/deploys.jsonl" 2>/dev/null || true; }

cd "$APP"
git fetch -q origin main
OLD=$(git rev-parse HEAD)
NEW=$(git rev-parse origin/main)
ACTION=""
REQ_SHA=""
RECREATE=""

if [ -f "$CONTROL/request.json" ]; then
  ACTION=$(python3 -c "import json;print(json.load(open('$CONTROL/request.json')).get('action',''))" 2>/dev/null || true)
  REQ_SHA=$(python3 -c "import json;print(json.load(open('$CONTROL/request.json')).get('sha',''))" 2>/dev/null || true)
  rm -f "$CONTROL/request.json"
fi

case "$ACTION" in
  rollback)
    TARGET=$(git rev-parse --verify -q "${REQ_SHA}^{commit}" || true)
    if [ -z "$TARGET" ]; then log "откат отклонён: нет коммита $REQ_SHA"; exit 1; fi
    echo "$TARGET" > "$CONTROL/pin"
    NEW=$TARGET
    log "rollback $(short "$OLD") -> $(short "$NEW") (версия закреплена, автодеплой на паузе)"
    ;;
  unpin)
    rm -f "$CONTROL/pin"
    log "автодеплой включён снова"
    FORCE=1
    ;;
  redeploy)
    [ -f "$CONTROL/pin" ] && NEW=$(cat "$CONTROL/pin")
    FORCE=1
    # Без изменений compose не трогает контейнер — по кнопке перезапускаем его всегда
    # (заодно подхватываются правки /opt/orda/web.env).
    RECREATE=--force-recreate
    log "пересборка по кнопке"
    ;;
  *)
    if [ -f "$CONTROL/pin" ]; then exit 0; fi  # версия закреплена откатом
    ;;
esac

[ "$OLD" = "$NEW" ] && [ -z "${FORCE:-}" ] && exit 0

log "deploy $(short "$OLD") -> $(short "$NEW")"
git reset -q --hard "$NEW"
DEPLOY_ID=$(date +%s)
event "id=$DEPLOY_ID" "start=$DEPLOY_ID" "from=$OLD" "sha=$NEW" "subject=$(git log -1 --format=%s "$NEW")" "action=${ACTION:-push}" "status=running"

# Свежая копия этого скрипта для cron — только с main, не со старого коммита.
if [ ! -f "$CONTROL/pin" ]; then
  mkdir -p /opt/orda/bin
  cp "$APP/deploy/vps/deploy.sh" /opt/orda/bin/deploy.sh.new && mv /opt/orda/bin/deploy.sh.new /opt/orda/bin/deploy.sh
  cp "$APP/deploy/vps/status-snapshot.sh" /opt/orda/bin/status-snapshot.sh.new && mv /opt/orda/bin/status-snapshot.sh.new /opt/orda/bin/status-snapshot.sh
fi

cd /opt/orda
# GITHUB_SHA из окружения перекрывает build.env — buildId и версия в мониторинге = коммит.
if GITHUB_SHA=$NEW docker compose -f app/deploy/vps/docker-compose.yml --env-file build.env up -d --build $RECREATE >> "$LOG" 2>&1; then
  # Кроны обновляем, только если они уже включены (до переключения их быть не должно:
  # пошли бы по тестовой копии и задвоили рассылки в Telegram).
  if [ -f /etc/cron.d/orda ]; then
    sh app/deploy/vps/install-crons.sh
  fi
  docker image prune -f >/dev/null 2>&1 || true
  # Кэш сборки растёт с каждым деплоем (за 10.2026 — 21 ГБ): держим только последнюю неделю.
  docker builder prune -f --filter until=168h >/dev/null 2>&1 || true
  log "ok"
  event "id=$DEPLOY_ID" "end=$(date +%s)" "status=ok"
else
  # Старый контейнер продолжает работать: compose не трогает его, если сборка упала.
  log "СБОРКА УПАЛА, работает прежняя версия"
  event "id=$DEPLOY_ID" "end=$(date +%s)" "status=failed" "error=сборка упала, работает прежняя версия"
  exit 1
fi
