#!/bin/sh
# Ночной бэкап Orda: полная база (pg_dumpall не нужен — роли стандартные) + файлы Storage.
# Кладётся в /opt/orda/backups, хранится 14 дней. Запуск из /etc/cron.d/orda-backup.
#
# Копия на другую машину (обязательно, сервер один): в /opt/orda/backup.env задать
#   BACKUP_SCP_TARGET=user@host:/path   (ключ root без пароля должен туда пускать)
# Без него бэкап живёт только на этом же диске — от пожара/взлома сервера не спасёт.
set -eu

SB=/opt/supabase/orda
DIR=/opt/orda/backups
STAMP=$(date -u +%Y%m%d-%H%M)
mkdir -p "$DIR" && chmod 700 "$DIR"
LOCAL_PW=$(grep '^POSTGRES_PASSWORD=' "$SB/.env" | cut -d= -f2-)

docker exec -e PGPASSWORD="$LOCAL_PW" supabase-db \
  pg_dump -h 127.0.0.1 -U supabase_admin -d postgres -Fc -n public -n auth -n storage -n supabase_migrations \
  > "$DIR/db-$STAMP.dump"
tar -C "$SB/volumes" -czf "$DIR/storage-$STAMP.tgz" storage

find "$DIR" -name 'db-*.dump' -mtime +14 -delete
find "$DIR" -name 'storage-*.tgz' -mtime +14 -delete

if [ -f /opt/orda/backup.env ]; then
  . /opt/orda/backup.env
  if [ -n "${BACKUP_SCP_TARGET:-}" ]; then
    scp -q -o BatchMode=yes "$DIR/db-$STAMP.dump" "$DIR/storage-$STAMP.tgz" "$BACKUP_SCP_TARGET/"
  fi
fi
echo "$(date -u +%FT%TZ) ok db=$(du -h "$DIR/db-$STAMP.dump" | cut -f1) storage=$(du -h "$DIR/storage-$STAMP.tgz" | cut -f1)" >> /var/log/orda-backup.log
