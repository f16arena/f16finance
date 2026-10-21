#!/bin/sh
# Ставит /etc/cron.d/orda из vercel.json. На хосте node нет — генератор запускается в контейнере
# того же образа, на котором собирается веб.
set -eu
docker run --rm -v /opt/orda/app:/app:ro node:22-bookworm-slim node /app/deploy/vps/gen-crontab.mjs > /etc/orda-cron.tmp
[ -s /etc/orda-cron.tmp ] && mv /etc/orda-cron.tmp /etc/cron.d/orda
echo "кронов: $(grep -c ' root ' /etc/cron.d/orda)"
