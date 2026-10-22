-- Платформа → «База данных»: обзор базы и таблиц для суперадмина (только чтение).
-- Вызывает только сервер (service_role): у anon/authenticated права нет.

create or replace function public.platform_db_overview()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_catalog
as $$
  select jsonb_build_object(
    'version', split_part(version(), ' on ', 1),
    'size', pg_database_size(current_database()),
    'connections', (select count(*) from pg_stat_activity where datname = current_database()),
    'active', (select count(*) from pg_stat_activity where datname = current_database() and state = 'active'),
    'max_connections', current_setting('max_connections')::int,
    'cache_hit', (select round(100 * sum(blks_hit)::numeric / nullif(sum(blks_hit) + sum(blks_read), 0), 2) from pg_stat_database where datname = current_database()),
    'started_at', pg_postmaster_start_time(),
    'tables', (select count(*) from pg_tables where schemaname = 'public'),
    'functions', (select count(*) from pg_proc where pronamespace = 'public'::regnamespace),
    'auth_users', (select count(*) from auth.users),
    'storage_objects', (select count(*) from storage.objects),
    'storage_bytes', (select coalesce(sum((metadata->>'size')::bigint), 0) from storage.objects),
    'long_queries', (
      select coalesce(jsonb_agg(jsonb_build_object('pid', pid, 'seconds', extract(epoch from now() - query_start)::int, 'state', state, 'query', left(query, 200)) order by query_start), '[]'::jsonb)
      from pg_stat_activity
      where datname = current_database() and backend_type = 'client backend' and state <> 'idle' and pid <> pg_backend_pid() and now() - query_start > interval '5 seconds'
    )
  )
$$;

create or replace function public.platform_db_tables()
returns jsonb
language sql
stable
security definer
set search_path = public, pg_catalog
as $$
  select coalesce(jsonb_agg(t order by (t->>'total_bytes')::bigint desc), '[]'::jsonb)
  from (
    select jsonb_build_object(
      'name', c.relname,
      'rows', greatest(coalesce(s.n_live_tup, 0), c.reltuples::bigint, 0),
      'total_bytes', pg_total_relation_size(c.oid),
      'table_bytes', pg_relation_size(c.oid),
      'index_bytes', pg_indexes_size(c.oid),
      'columns', (select count(*) from pg_attribute a where a.attrelid = c.oid and a.attnum > 0 and not a.attisdropped),
      'rls', c.relrowsecurity,
      'policies', (select count(*) from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname),
      'last_write', greatest(s.last_autovacuum, s.last_autoanalyze, s.last_vacuum, s.last_analyze),
      'inserts', coalesce(s.n_tup_ins, 0),
      'updates', coalesce(s.n_tup_upd, 0),
      'deletes', coalesce(s.n_tup_del, 0),
      'comment', obj_description(c.oid, 'pg_class')
    ) as t
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
    left join pg_stat_user_tables s on s.relid = c.oid
    where c.relkind in ('r', 'p')
  ) x
$$;

create or replace function public.platform_db_columns(p_table text)
returns jsonb
language sql
stable
security definer
set search_path = public, pg_catalog
as $$
  select coalesce(jsonb_agg(jsonb_build_object(
    'name', a.attname,
    'type', format_type(a.atttypid, a.atttypmod),
    'nullable', not a.attnotnull,
    'default', pg_get_expr(d.adbin, d.adrelid),
    'pk', exists (select 1 from pg_index i where i.indrelid = a.attrelid and i.indisprimary and a.attnum = any(i.indkey)),
    'fk', (select confrelid::regclass::text from pg_constraint k where k.conrelid = a.attrelid and k.contype = 'f' and a.attnum = any(k.conkey) limit 1)
  ) order by a.attnum), '[]'::jsonb)
  from pg_attribute a
  left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
  where a.attrelid = (select c.oid from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relname = p_table)
    and a.attnum > 0 and not a.attisdropped
$$;

revoke all on function public.platform_db_overview() from public, anon, authenticated;
revoke all on function public.platform_db_tables() from public, anon, authenticated;
revoke all on function public.platform_db_columns(text) from public, anon, authenticated;
grant execute on function public.platform_db_overview() to service_role;
grant execute on function public.platform_db_tables() to service_role;
grant execute on function public.platform_db_columns(text) to service_role;

notify pgrst, 'reload schema';
