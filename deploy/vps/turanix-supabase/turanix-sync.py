"""Перенос данных Turanix: Supabase Cloud (только чтение) -> своя база на cloud-001.

Схема уже есть (5 миграций из учёта облачного проекта, сверено по объектам).
Скрипт пишет SQL: пользователи Auth (auth.users, auth.identities — хеши паролей как есть,
пароли не меняются) и все таблицы public. Только общие колонки: версия Auth в облаке и у нас
может отличаться. Одной транзакцией, без триггеров.

  python turanix-sync.py <колонки-локально.json> <файл.sql>
  колонки-локально.json: {"схема.таблица": ["колонка", ...]} из своей базы (см. README).

Доступ к облаку — через API управления (turq.sql), пароль базы не нужен.
"""
import json
import sys
import time

import turq

TAG = "$turanixdata$"
AUTH_TABLES = ["auth.users", "auth.identities"]


def sql(query, tries=6):
    # API управления под нагрузкой отвечает 401/429/5xx — это не ошибка запроса, повторяем.
    for i in range(tries):
        try:
            return turq.sql(query)
        except Exception as e:
            if i == tries - 1 or getattr(e, "code", None) not in (401, 429, 500, 502, 503, 504, 544):
                raise
            time.sleep(2 + 3 * i)


def main(local_cols_path, out_path):
    local = json.load(open(local_cols_path, encoding="utf-8"))
    public = [r["t"] for r in sql(
        "select 'public.'||relname t from pg_class where relnamespace='public'::regnamespace and relkind='r' order by 1")]
    cloud = {}
    for r in sql("""select table_schema||'.'||table_name t, json_agg(column_name order by ordinal_position) c
        from information_schema.columns where table_schema in ('public','auth') and is_generated='NEVER' group by 1"""):
        cloud[r["t"]] = r["c"]

    tables = AUTH_TABLES + public
    out = ["begin;", "set session_replication_role = replica;",
           "truncate " + ", ".join(public + AUTH_TABLES) + " cascade;"]
    total = 0
    for t in tables:
        cols = [c for c in cloud.get(t, []) if c in set(local.get(t, []))]
        skipped = sorted(set(cloud.get(t, [])) - set(cols))
        data = sql("select coalesce(json_agg(x), '[]'::json)::text j, count(*) n from %s x" % t)[0]
        if not data["n"]:
            continue
        if TAG in data["j"]:
            raise SystemExit("в данных %s встретился %s" % (t, TAG))
        collist = ", ".join('"%s"' % c for c in cols)
        out.append("insert into %s (%s) select %s from json_populate_recordset(null::%s, %s%s%s);"
                   % (t, collist, collist, t, TAG, data["j"], TAG))
        total += data["n"]
        sys.stderr.write("%-34s %4d%s\n" % (t, data["n"], ("  без колонок: " + ", ".join(skipped)) if skipped else ""))
    # Пользователи, заведённые в облаке SQL-ом, имеют NULL в служебных полях токенов, а GoTrue
    # читает их как строки: без этого падают вход этого пользователя и список пользователей.
    out.append("""update auth.users set
  confirmation_token = coalesce(confirmation_token, ''), recovery_token = coalesce(recovery_token, ''),
  email_change_token_new = coalesce(email_change_token_new, ''), email_change = coalesce(email_change, ''),
  email_change_token_current = coalesce(email_change_token_current, ''), phone_change = coalesce(phone_change, ''),
  phone_change_token = coalesce(phone_change_token, ''), reauthentication_token = coalesce(reauthentication_token, '');""")
    out.append("commit;")
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    sys.stderr.write("строк всего: %d\n" % total)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
