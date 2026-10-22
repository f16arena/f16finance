#!/bin/sh
# Ночной бэкап базы Tour (/etc/cron.d/tour-backup): дамп Postgres (-Fc) и архив файлов
# Storage в /opt/supabase/tour/backups, хранится 14 дней. Лог — /var/log/tour-backup.log.
set -eu
DIR=/opt/supabase/tour/backups
mkdir -p "$DIR" && chmod 700 "$DIR"
TS=$(date -u +%Y%m%d-%H%M)
docker exec tour-db pg_dump -U supabase_admin -d postgres -Fc -n public -n storage -n supabase_migrations > "$DIR/tour-db-$TS.dump"
tar -C /opt/supabase/tour/volumes -czf "$DIR/tour-storage-$TS.tgz" storage
find "$DIR" -type f -mtime +14 -delete
echo "$(date -u +%FT%TZ) ok db=$(du -h "$DIR/tour-db-$TS.dump" | cut -f1) storage=$(du -h "$DIR/tour-storage-$TS.tgz" | cut -f1)" >> /var/log/tour-backup.log
