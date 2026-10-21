#!/bin/sh
# Полная копия базы Orda: облачный Supabase -> локальный стек /opt/supabase/orda.
#
# ВНИМАНИЕ: каждый запуск ПЕРЕСОЗДАЁТ локальную базу с нуля (всё, что в ней было, стирается).
# Облако только читается.
#
# Что переносится: схема и данные public, пользователи и сессии (auth), бакеты и записи файлов
# (storage; сами файлы — sync-storage.mjs), история миграций, правила доступа к файлам,
# подписки Realtime, настройки ролей.
# Не переносятся пустые служебные таблицы, которых нет в self-hosted Auth (SCIM, MFA recovery),
# и одноразовые токены (живут минуты).
set -eu

SB=/opt/supabase/orda
PG_IMAGE=$(grep -E '^\s+image: supabase/postgres:' "$SB/docker-compose.yml" | head -1 | awk '{print $2}')
. /opt/orda/cloud-db.env
LOCAL_PW=$(grep '^POSTGRES_PASSWORD=' "$SB/.env" | cut -d= -f2-)
# REUSE=/opt/orda/sync/<папка> — залить уже скачанный дамп ещё раз, не качая из облака.
WORK=${REUSE:-/opt/orda/sync/$(date -u +%Y%m%d-%H%M%S)}
mkdir -p "$WORK" && chmod 700 "$WORK"
log() { echo "$(date -u +%T) $*"; }

# Клиент Postgres той же версии, что сервер, в одноразовом контейнере (не зависит от состояния локальной БД).
cloud() { docker run --rm -i -e U="$CLOUD_DB_URL" --entrypoint sh "$PG_IMAGE" -c "$1"; }
local_sql() { docker exec -i -e PGPASSWORD="$LOCAL_PW" supabase-db psql -h 127.0.0.1 -U supabase_admin -d postgres -X -v ON_ERROR_STOP=1 -q "$@"; }

if [ -z "${REUSE:-}" ]; then
log "1/6 схема public + история миграций"
# Схема public в свежем стеке уже есть — её CREATE выкидываем.
cloud 'pg_dump "$U" --schema-only -n public -n supabase_migrations' | sed '/^CREATE SCHEMA public;$/d' > "$WORK/schema.sql"

log "2/6 доп. объекты: подписки Realtime, правила storage, настройки ролей"
cloud 'psql "$U" -X -tA' > "$WORK/extra.sql" <<'SQL'
select format('alter publication supabase_realtime add table %I.%I;', schemaname, tablename)
  from pg_publication_tables where pubname = 'supabase_realtime';
select format('drop policy if exists %I on %I.%I; create policy %I on %I.%I as %s for %s to %s%s%s;',
    policyname, schemaname, tablename, policyname, schemaname, tablename, permissive, cmd,
    array_to_string(roles, ', '),
    case when qual is not null then ' using (' || qual || ')' else '' end,
    case when with_check is not null then ' with check (' || with_check || ')' else '' end)
  from pg_policies where schemaname = 'storage';
select format('alter role %I set %s = %s;', r.rolname, split_part(cfg, '=', 1),
    -- search_path — список, его в кавычки целиком брать нельзя
    case when split_part(cfg, '=', 1) = 'search_path' then substr(cfg, strpos(cfg, '=') + 1)
         else quote_literal(substr(cfg, strpos(cfg, '=') + 1)) end)
  from pg_db_role_setting s join pg_roles r on r.oid = s.setrole, unnest(s.setconfig) cfg
  where r.rolname in ('anon', 'authenticated', 'service_role', 'authenticator', 'postgres');
SQL

log "3/6 данные (public, auth, storage, миграции)"
cloud 'pg_dump "$U" --data-only -n public -n auth -n storage -n supabase_migrations \
  -T auth.schema_migrations -T storage.migrations -T auth.one_time_tokens \
  -T "auth.mfa_recovery_*" -T "auth.scim_*"' > "$WORK/data.sql"
fi
ls -lh "$WORK"

log "4/6 пересоздание локальной базы"
cd "$SB"
docker compose down
rm -rf volumes/db/data
# Записи файлов придут из облака заново — файлы нужно перезалить (sync-storage.py, из кэша).
rm -f /opt/orda/sync/storage-up.json
docker compose up -d
for i in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' supabase-db 2>/dev/null)" = healthy ] && break
  sleep 5
done
# Auth и Storage создают свои таблицы при старте — ждём их, иначе данные некуда лить.
for i in $(seq 1 60); do
  n=$(local_sql -tA -c "select count(*) from information_schema.tables where (table_schema,table_name) in (('auth','users'),('storage','objects'))" 2>/dev/null || echo 0)
  [ "$n" = 2 ] && break
  sleep 5
done

log "5/6 заливка"
local_sql -c "create extension if not exists btree_gist schema public;"
local_sql -c "create extension if not exists hypopg schema extensions;" || true
local_sql -c "create extension if not exists index_advisor schema extensions;" || true
local_sql -f - < "$WORK/schema.sql" > "$WORK/restore-schema.log" 2>&1
local_sql -f - < "$WORK/extra.sql" > "$WORK/restore-extra.log" 2>&1
# replica: триггеры и проверки внешних ключей не срабатывают — данные уже согласованы в облаке.
{ echo "set session_replication_role = replica;"; cat "$WORK/data.sql"; } | local_sql -f - > "$WORK/restore-data.log" 2>&1

# Ссылки на файлы: загрузки сохраняют в БД полный getPublicUrl() облака
# (https://<ref>.supabase.co/storage/v1/...). Переводим на свой адрес во всех текстовых и json-колонках
# public и в метаданных пользователей. Триггеры выключены (replica) — updated_at и аудит не трогаются.
log "5b/6 ссылки на файлы -> db.ordaops.kz"
local_sql > "$WORK/rewrite-urls.log" 2>&1 <<'SQL'
set session_replication_role = replica;
do $$
declare
  c record; n bigint; total bigint := 0;
  old_url text := 'https://tmudsqgagblmdctaosgw.supabase.co/storage/';
  new_url text := 'https://db.ordaops.kz/storage/';
begin
  for c in
    select col.table_schema, col.table_name, col.column_name, col.data_type, col.udt_name
      from information_schema.columns col
      join information_schema.tables t using (table_schema, table_name)
     where t.table_type = 'BASE TABLE'
       and ((col.table_schema = 'public'
             and (col.data_type in ('text', 'character varying', 'json', 'jsonb')
                  or (col.data_type = 'ARRAY' and col.udt_name in ('_text', '_varchar'))))
         or (col.table_schema = 'auth' and col.table_name = 'users'
             and col.column_name in ('raw_user_meta_data', 'raw_app_meta_data')))
  loop
    execute format(
      'update %I.%I set %I = replace(%I::text, %L, %L)::%s where %I::text like %L',
      c.table_schema, c.table_name, c.column_name, c.column_name, old_url, new_url,
      case when c.data_type = 'ARRAY' then c.udt_name::regtype::text
           when c.data_type = 'character varying' then 'varchar' else c.data_type end,
      c.column_name, '%' || old_url || '%');
    get diagnostics n = row_count;
    if n > 0 then raise notice '%.%.%: %', c.table_schema, c.table_name, c.column_name, n; end if;
    total := total + n;
  end loop;
  raise notice 'всего строк со ссылками переписано: %', total;
end $$;
SQL
grep -E 'NOTICE' "$WORK/rewrite-urls.log" | tail -1 || true
# Realtime и PostgREST подхватывают новую схему.
docker restart supabase-rest realtime-dev.supabase-realtime >/dev/null

log "6/6 сверка числа строк"
COUNT_SQL="select format('select %L, count(*) from %I.%I;', schemaname||'.'||relname, schemaname, relname)
  from pg_stat_user_tables where schemaname in ('public','auth','storage') order by 1"
cloud "psql \"\$U\" -X -tA -c \"$COUNT_SQL\" | psql \"\$U\" -X -tA -F'|'" | sort > "$WORK/count-cloud.txt"
docker exec -i -e PGPASSWORD="$LOCAL_PW" supabase-db sh -c \
  "psql -h 127.0.0.1 -U supabase_admin -d postgres -X -tA -c \"$COUNT_SQL\" | psql -h 127.0.0.1 -U supabase_admin -d postgres -X -tA -F'|'" | sort > "$WORK/count-local.txt"
DIFF=$(join -t'|' -a1 -e MISSING -o 0,1.2,2.2 "$WORK/count-cloud.txt" "$WORK/count-local.txt" | awk -F'|' '$2 != $3')
if [ -n "$DIFF" ]; then
  echo "РАСХОЖДЕНИЯ (таблица|облако|локально):"; echo "$DIFF"
else
  echo "OK: все $(wc -l < "$WORK/count-cloud.txt") таблиц совпадают по числу строк"
fi
log "готово, файлы и логи: $WORK"
