#!/bin/sh
# Автодеплой вместо Vercel: раз в минуту (/etc/cron.d/orda-deploy) смотрит origin/main
# и, если есть новый коммит, пересобирает и перезапускает веб. Лог — /var/log/orda-deploy.log.
# Ручной запуск: sh /opt/orda/app/deploy/vps/deploy.sh   (FORCE=1 — пересобрать без нового коммита)
set -eu

APP=/opt/orda/app
LOG=/var/log/orda-deploy.log
exec 9>/run/orda-deploy.lock
flock -n 9 || exit 0  # сборка уже идёт

cd "$APP"
git fetch -q origin main
OLD=$(git rev-parse HEAD)
NEW=$(git rev-parse origin/main)
[ "$OLD" = "$NEW" ] && [ -z "${FORCE:-}" ] && exit 0

log() { echo "$(date -u +%FT%TZ) $*" >> "$LOG"; }
log "deploy ${OLD%"${OLD#???????}"} -> ${NEW%"${NEW#???????}"}"
git reset -q --hard "$NEW"

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
