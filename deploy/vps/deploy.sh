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
# Тревога в Telegram мониторинга (бот и чат из web.env). Без них — молча, только лог.
notify() {
  TOKEN=$(grep '^TELEGRAM_MONITOR_BOT_TOKEN=' /opt/orda/web.env | cut -d= -f2- | tr -d "'\"")
  CHAT=$(grep '^TELEGRAM_MONITOR_CHAT_ID=' /opt/orda/web.env | cut -d= -f2- | tr -d "'\"")
  [ -n "$TOKEN" ] && [ -n "$CHAT" ] || return 0
  curl -s -o /dev/null --max-time 15 "https://api.telegram.org/bot$TOKEN/sendMessage"     --data-urlencode "chat_id=$CHAT" --data-urlencode "text=Orda: $*" || true
}
# Сайт поднялся после выкладки: /api/health/ready отвечает 200 (база видна), главные
# страницы не отдают 5xx. Ждём до 2 минут — Next стартует не мгновенно.
smoke() {
  i=0
  while [ $i -lt 24 ]; do
    if curl -fsS -o /dev/null --max-time 10 http://127.0.0.1:3200/api/health/ready; then
      for P in / /login; do
        CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://127.0.0.1:3200$P")
        case "$CODE" in 2*|3*) ;; *) echo "$P ответил $CODE"; return 1 ;; esac
      done
      return 0
    fi
    i=$((i + 1)); sleep 5
  done
  echo "/api/health/ready не ответил за 2 минуты"
  return 1
}
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
    # Коммит, на котором проверка уже провалилась, сам по себе не повторяем — ждём следующий.
    if [ -z "${FORCE:-}" ] && [ "$NEW" = "$(cat "$CONTROL/bad" 2>/dev/null || true)" ]; then exit 0; fi
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
  cp "$APP/deploy/vps/watchdog.sh" /opt/orda/bin/watchdog.sh.new && mv /opt/orda/bin/watchdog.sh.new /opt/orda/bin/watchdog.sh
fi

cd /opt/orda
# Рабочий образ — про запас: если новая версия не пройдёт проверку, вернём его без пересборки.
docker image inspect orda-web:latest >/dev/null 2>&1 && docker tag orda-web:latest orda-web:previous
# GITHUB_SHA из окружения перекрывает build.env — buildId и версия в мониторинге = коммит.
if GITHUB_SHA=$NEW docker compose -f app/deploy/vps/docker-compose.yml --env-file build.env up -d --build $RECREATE >> "$LOG" 2>&1; then
  # Кроны обновляем, только если они уже включены (до переключения их быть не должно:
  # пошли бы по тестовой копии и задвоили рассылки в Telegram).
  if [ -f /etc/cron.d/orda ]; then
    sh app/deploy/vps/install-crons.sh
  fi
  if ! WHY=$(smoke); then
    log "ПРОВЕРКА ПОСЛЕ ДЕПЛОЯ НЕ ПРОШЛА ($WHY), возвращаю прежнюю версию"
    echo "$NEW" > "$CONTROL/bad"
    if docker image inspect orda-web:previous >/dev/null 2>&1; then
      docker tag orda-web:previous orda-web:latest
      docker compose -f app/deploy/vps/docker-compose.yml --env-file build.env up -d --no-build --force-recreate >> "$LOG" 2>&1 || true
      (cd "$APP" && git reset -q --hard "$OLD")
    fi
    if RESULT=$(smoke); then RESULT="прежняя версия $(short "$OLD") работает"; else RESULT="ПРЕЖНЯЯ ТОЖЕ НЕ ОТВЕЧАЕТ: $RESULT"; fi
    log "откат: $RESULT"
    event "id=$DEPLOY_ID" "end=$(date +%s)" "status=failed" "error=проверка не прошла ($WHY), откат: $RESULT"
    notify "версия $(short "$NEW") не прошла проверку ($WHY). Откат: $RESULT. Следующий пуш попробует снова."
    exit 1
  fi
  rm -f "$CONTROL/bad"
  docker image prune -f >/dev/null 2>&1 || true
  # Кэш сборки растёт с каждым деплоем (за 10.2026 — 21 ГБ): держим только последнюю неделю.
  docker builder prune -f --filter until=168h >/dev/null 2>&1 || true
  log "ok"
  event "id=$DEPLOY_ID" "end=$(date +%s)" "status=ok"
else
  # Старый контейнер продолжает работать: compose не трогает его, если сборка упала.
  log "СБОРКА УПАЛА, работает прежняя версия"
  event "id=$DEPLOY_ID" "end=$(date +%s)" "status=failed" "error=сборка упала, работает прежняя версия"
  notify "сборка $(short "$NEW") упала, работает прежняя версия. Лог: /var/log/orda-deploy.log"
  exit 1
fi
