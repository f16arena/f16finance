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

cd "$APP"
git fetch -q origin main
OLD=$(git rev-parse HEAD)
NEW=$(git rev-parse origin/main)
ACTION=""
REQ_SHA=""

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
    log "пересборка по кнопке"
    ;;
  *)
    if [ -f "$CONTROL/pin" ]; then exit 0; fi  # версия закреплена откатом
    ;;
esac

[ "$OLD" = "$NEW" ] && [ -z "${FORCE:-}" ] && exit 0

log "deploy $(short "$OLD") -> $(short "$NEW")"
git reset -q --hard "$NEW"

# Свежая копия этого скрипта для cron — только с main, не со старого коммита.
if [ ! -f "$CONTROL/pin" ]; then
  mkdir -p /opt/orda/bin
  cp "$APP/deploy/vps/deploy.sh" /opt/orda/bin/deploy.sh.new && mv /opt/orda/bin/deploy.sh.new /opt/orda/bin/deploy.sh
fi

cd /opt/orda
# GITHUB_SHA из окружения перекрывает build.env — buildId и версия в мониторинге = коммит.
if GITHUB_SHA=$NEW docker compose -f app/deploy/vps/docker-compose.yml --env-file build.env up -d --build >> "$LOG" 2>&1; then
  # Кроны обновляем, только если они уже включены (до переключения их быть не должно:
  # пошли бы по тестовой копии и задвоили рассылки в Telegram).
  if [ -f /etc/cron.d/orda ]; then
    sh app/deploy/vps/install-crons.sh
  fi
  docker image prune -f >/dev/null 2>&1 || true
  # Кэш сборки растёт с каждым деплоем (за 10.2026 — 21 ГБ): держим только последнюю неделю.
  docker builder prune -f --filter until=168h >/dev/null 2>&1 || true
  log "ok"
else
  # Старый контейнер продолжает работать: compose не трогает его, если сборка упала.
  log "СБОРКА УПАЛА, работает прежняя версия"
  exit 1
fi
