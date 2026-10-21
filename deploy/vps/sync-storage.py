#!/usr/bin/env python3
"""Файлы Storage: облачный Supabase -> локальный стек. Докачивает только новые/изменённые.

Список объектов берётся из облачной БД (storage.objects), файлы качаются через Storage API облака
service-ключом в локальный кэш /opt/orda/sync/files и заливаются в локальный API с upsert.
Два состояния: что скачано (dl) и что залито (up). sync-db.sh стирает up-состояние при пересоздании
базы — тогда всё перезаливается из кэша (секунды), а из облака качается только новое.
"""
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

CLOUD_API = "https://tmudsqgagblmdctaosgw.supabase.co"
LOCAL_API = "http://127.0.0.1:8100"
CACHE = "/opt/orda/sync/files"
DL_STATE = "/opt/orda/sync/storage-dl.json"
UP_STATE = "/opt/orda/sync/storage-up.json"


def env_file(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            out[k] = v.strip().strip('"')
    return out


def cloud_objects(db_url, pg_image):
    sql = ("select json_agg(json_build_object('b', bucket_id, 'n', name, 'u', updated_at, "
           "'m', metadata->>'mimetype')) from storage.objects where name is not null")
    r = subprocess.run(["docker", "run", "--rm", "-e", f"U={db_url}", "--entrypoint", "sh", pg_image,
                        "-c", f'psql "$U" -X -tA -c "{sql}"'], capture_output=True, text=True, check=True)
    return json.loads(r.stdout.strip() or "[]") or []


def local_sql(sql):
    pw = env_file("/opt/supabase/orda/.env")["POSTGRES_PASSWORD"]
    r = subprocess.run(["docker", "exec", "-i", "-e", f"PGPASSWORD={pw}", "supabase-db", "psql", "-h", "127.0.0.1",
                        "-U", "supabase_admin", "-d", "postgres", "-X", "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql],
                       capture_output=True, text=True, check=True)
    return r.stdout.strip()


def lift_bucket_limits():
    """Старые файлы бывают тех типов, что бакет уже не принимает (в облаке ограничение ввели позже) —
    на время заливки снимаем ограничения бакетов, потом возвращаем как было."""
    saved = local_sql("select coalesce(json_agg(json_build_object('id', id, 'l', file_size_limit, "
                      "'m', allowed_mime_types)), '[]') from storage.buckets "
                      "where file_size_limit is not null or allowed_mime_types is not null")
    local_sql("update storage.buckets set file_size_limit = null, allowed_mime_types = null "
              "where file_size_limit is not null or allowed_mime_types is not null")
    return json.loads(saved)


def restore_bucket_limits(saved):
    for b in saved:
        mime = "null" if b["m"] is None else "array[" + ",".join("'" + m.replace("'", "''") + "'" for m in b["m"]) + "]"
        limit = "null" if b["l"] is None else str(int(b["l"]))
        bucket = b["id"].replace("'", "''")
        local_sql(f"update storage.buckets set file_size_limit = {limit}, allowed_mime_types = {mime} where id = '{bucket}'")


def main():
    key = env_file("/opt/orda/web.env")["SUPABASE_SERVICE_ROLE_KEY"]
    db_url = env_file("/opt/orda/cloud-db.env")["CLOUD_DB_URL"]
    pg_image = subprocess.run(
        "grep -E '^\\s+image: supabase/postgres:' /opt/supabase/orda/docker-compose.yml | head -1 | awk '{print $2}'",
        shell=True, capture_output=True, text=True).stdout.strip()
    load = lambda p: json.load(open(p)) if os.path.exists(p) else {}
    dl, up = load(DL_STATE), load(UP_STATE)
    objs = cloud_objects(db_url, pg_image)
    todo = [o for o in objs if up.get(f"{o['b']}/{o['n']}") != o["u"]]
    print(f"объектов в облаке: {len(objs)}, заливать: {len(todo)}, "
          f"из них качать: {sum(1 for o in todo if dl.get(o['b'] + '/' + o['n']) != o['u'])}", flush=True)
    failed = 0
    limits = lift_bucket_limits() if todo else []
    try:
        failed = copy_all(todo, key, dl, up)
    finally:
        restore_bucket_limits(limits)
    print(f"готово: скопировано {len(todo) - failed}, ошибок {failed}")
    sys.exit(1 if failed else 0)


def copy_all(todo, key, dl, up):
    failed = 0
    for i, o in enumerate(todo, 1):
        name = f"{o['b']}/{o['n']}"
        path = urllib.parse.quote(name)
        cached = os.path.join(CACHE, name)
        try:
            if dl.get(name) != o["u"] or not os.path.exists(cached):
                req = urllib.request.Request(f"{CLOUD_API}/storage/v1/object/authenticated/{path}",
                                             headers={"Authorization": f"Bearer {key}", "apikey": key})
                body = urllib.request.urlopen(req, timeout=120).read()
                os.makedirs(os.path.dirname(cached), exist_ok=True)
                open(cached, "wb").write(body)
                dl[name] = o["u"]
            body = open(cached, "rb").read()
            req = urllib.request.Request(f"{LOCAL_API}/storage/v1/object/{path}", data=body, method="POST",
                                         headers={"Authorization": f"Bearer {key}", "apikey": key,
                                                  "x-upsert": "true",
                                                  "Content-Type": o.get("m") or "application/octet-stream"})
            urllib.request.urlopen(req, timeout=120).read()
            up[name] = o["u"]
        except Exception as e:  # один битый файл не должен останавливать остальные
            failed += 1
            print(f"ОШИБКА {name}: {e}", flush=True)
        if i % 50 == 0:
            print(f"  {i}/{len(todo)}", flush=True)
            json.dump(dl, open(DL_STATE, "w")); json.dump(up, open(UP_STATE, "w"))
    json.dump(dl, open(DL_STATE, "w")); json.dump(up, open(UP_STATE, "w"))
    return failed


if __name__ == "__main__":
    main()
