"""Перенос данных Tour: Supabase Cloud (только чтение) -> своя база на cloud-001.

Схема уже есть в своей базе (миграции из репозитория Tour, сверены с облаком по объектам).
Скрипт пишет SQL-файл с данными всех таблиц public: одной транзакцией, без триггеров,
со счётчиками последовательностей. Ссылки на облачное Storage переписываются на своё.

  python tour-sync.py <файл.sql>     # затем на сервере: psql -U supabase_admin < файл.sql

Доступ к облаку — через API управления (токен проекта из Vercel), пароль базы не нужен.
"""
import json
import sys
import time

import tourq  # sql(): запрос к облачной базе через API управления

OLD_STORAGE = "https://dgpxlpjnjthyotjcccfl.supabase.co/storage/v1/"
NEW_STORAGE = "https://db.f16-arena.kz/storage/v1/"
TAG = "$tourdata$"


def sql(query, tries=6):
    # API управления под нагрузкой иногда отвечает 401/429 — это ограничение частоты, повторяем.
    for i in range(tries):
        try:
            return tourq.sql(query)
        except Exception as e:
            if i == tries - 1 or getattr(e, "code", None) not in (401, 429, 500, 502, 503, 504):
                raise
            time.sleep(2 + 3 * i)


def main(out_path):
    tables = [r["t"] for r in sql(
        "select c.relname t from pg_class c where c.relnamespace='public'::regnamespace and c.relkind='r' order by 1")]
    cols = {}
    for r in sql("""select table_name t, string_agg(quote_ident(column_name), ', ' order by ordinal_position) c
        from information_schema.columns where table_schema='public' and is_generated='NEVER' group by 1"""):
        cols[r["t"]] = r["c"]
    seqs = sql("select schemaname||'.'||quote_ident(sequencename) s, last_value v from pg_sequences where schemaname='public'")

    out = ["begin;", "set session_replication_role = replica;",
           "truncate " + ", ".join("public.%s" % json.dumps(t) for t in tables) + " restart identity cascade;"]
    total = 0
    for t in tables:
        data = sql('select coalesce(json_agg(x), \'[]\'::json)::text j, count(*) n from public."%s" x' % t)[0]
        if not data["n"]:
            continue
        payload = data["j"].replace(OLD_STORAGE, NEW_STORAGE)
        if TAG in payload:
            raise SystemExit("в данных %s встретился %s" % (t, TAG))
        out.append('insert into public."%s" (%s) overriding system value select %s from json_populate_recordset(null::public."%s", %s%s%s);'
                   % (t, cols[t], cols[t], t, TAG, payload, TAG))
        total += data["n"]
        sys.stderr.write("%-28s %6d\n" % (t, data["n"]))
    for s in seqs:
        if s["v"] is not None:
            out.append("select setval('%s', %s);" % (s["s"], s["v"]))
    out.append("commit;")
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out) + "\n")
    sys.stderr.write("строк всего: %d, таблиц: %d\n" % (total, len(tables)))


if __name__ == "__main__":
    main(sys.argv[1])
