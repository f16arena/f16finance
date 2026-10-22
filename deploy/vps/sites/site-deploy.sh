#!/bin/sh
# Автодеплой сайтов из /opt/sites (вместо Vercel): sh site-deploy.sh <site> — раз в минуту
# из /etc/cron.d/sites-deploy. Новый коммит в origin/main → пересборка и перезапуск.
# FORCE=1 — пересобрать без нового коммита. Лог — /var/log/site-<site>-deploy.log.
set -eu
SITE=$1
DIR=/opt/sites/$SITE
LOG=/var/log/site-$SITE-deploy.log
exec 9>/run/site-$SITE-deploy.lock
flock -n 9 || exit 0
log() { echo "$(date -u +%FT%TZ) $*" >> "$LOG"; }

cd "$DIR/app"
git fetch -q origin main
OLD=$(git rev-parse HEAD)
NEW=$(git rev-parse origin/main)
[ "$OLD" = "$NEW" ] && [ -z "${FORCE:-}" ] && exit 0

log "deploy $(echo "$OLD" | cut -c1-7) -> $(echo "$NEW" | cut -c1-7) $(git log -1 --format=%s "$NEW")"
git reset -q --hard "$NEW"
cp /opt/sites/bin/Dockerfile.next .Dockerfile.server
grep -qx ".Dockerfile.server" .git/info/exclude 2>/dev/null || echo ".Dockerfile.server" >> .git/info/exclude
# Ключи для сборки — секретом из переменной (см. compose.yml).
SITE_WEBENV=$(cat "$DIR/web.env"); export SITE_WEBENV
if docker compose -p "site-$SITE" -f /opt/sites/bin/compose.yml --env-file "$DIR/site.env" up -d --build >> "$LOG" 2>&1; then
  docker image prune -f >/dev/null 2>&1 || true
  # кэш сборки общий на сервер: держим неделю, как deploy.sh Orda
  docker builder prune -f --filter until=168h >/dev/null 2>&1 || true
  log "ok"
else
  log "СБОРКА УПАЛА, работает прежняя версия"
  exit 1
fi
