#!/bin/sh
# Ночной бэкап базы Turanix (/etc/cron.d/turanix-backup): дамп Postgres (-Fc) и архив файлов
# Storage в /opt/supabase/turanix/backups, хранится 14 дней. Лог — /var/log/turanix-backup.log.
set -eu
DIR=/opt/supabase/turanix/backups
mkdir -p "$DIR" && chmod 700 "$DIR"
TS=$(date -u +%Y%m%d-%H%M)
docker exec turanix-db pg_dump -U supabase_admin -d postgres -Fc -n public -n auth -n storage -n supabase_migrations > "$DIR/turanix-db-$TS.dump"
tar -C /opt/supabase/turanix/volumes -czf "$DIR/turanix-storage-$TS.tgz" storage
find "$DIR" -type f -mtime +14 -delete
echo "$(date -u +%FT%TZ) ok db=$(du -h "$DIR/turanix-db-$TS.dump" | cut -f1) storage=$(du -h "$DIR/turanix-storage-$TS.tgz" | cut -f1)" >> /var/log/turanix-backup.log
