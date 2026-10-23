#!/bin/sh
# Сторож Orda: раз в минуту (/etc/cron.d/orda-watchdog) открывает ordaops.kz/api/health/ready
# через Caddy, как обычный посетитель. Три провала подряд — тревога в Telegram мониторинга,
# первый успешный ответ после тревоги — «починилось». Ничего не перезапускает.
# Смерть всего сервера отсюда не видна — для неё нужен сторож снаружи (README, «Сторож»).
set -u
URL=https://ordaops.kz/api/health/ready
STATE=/opt/orda/control/watchdog.state
LIMIT=3

notify() {
  TOKEN=$(grep '^TELEGRAM_MONITOR_BOT_TOKEN=' /opt/orda/web.env | cut -d= -f2- | tr -d "'\"")
  CHAT=$(grep '^TELEGRAM_MONITOR_CHAT_ID=' /opt/orda/web.env | cut -d= -f2- | tr -d "'\"")
  [ -n "$TOKEN" ] && [ -n "$CHAT" ] || return 0
  curl -s -o /dev/null --max-time 15 "https://api.telegram.org/bot$TOKEN/sendMessage" \
    --data-urlencode "chat_id=$CHAT" --data-urlencode "text=Orda: $*" || true
}

FAILS=$(cat "$STATE" 2>/dev/null || echo 0)
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$URL")

if [ "$CODE" = "200" ]; then
  [ "$FAILS" -ge "$LIMIT" ] && notify "сайт снова отвечает (не отвечал ~$FAILS мин)."
  echo 0 > "$STATE"
  exit 0
fi

FAILS=$((FAILS + 1))
echo "$FAILS" > "$STATE"
echo "$(date -u +%FT%TZ) $CODE подряд=$FAILS" >> /var/log/orda-watchdog.log
if [ "$FAILS" -eq "$LIMIT" ]; then
  if [ -e /run/orda-deploy.lock ] && ! flock -n /run/orda-deploy.lock true; then
    HINT=" Сейчас идёт деплой."
  else
    HINT=""
  fi
  notify "сайт не отвечает $LIMIT мин подряд (код $CODE).$HINT Проверь: docker ps, /var/log/orda-deploy.log."
fi
