#!/bin/sh
# Вызов одного крона Orda так же, как это делает Vercel: GET с Authorization: Bearer $CRON_SECRET.
# Лог — в /var/log/orda-cron.log (только код ответа, без тела).
set -u
URL="$1"
SECRET=$(grep '^CRON_SECRET=' /opt/orda/web.env | cut -d= -f2- | tr -d "'\"")
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 300 -H "Authorization: Bearer $SECRET" "$URL")
echo "$(date -u +%FT%TZ) $CODE $URL" >> /var/log/orda-cron.log
