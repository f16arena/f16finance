"""Копия файлов Storage Tour: Supabase Cloud -> своё хранилище (tour-storage на cloud-001).

Запускать на сервере: python3 tour-storage-sync.py
Ключи: облачный — SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY из /opt/sites/tour/web.env
(до переключения там ещё облако; после — передать CLOUD_URL/CLOUD_KEY окружением),
свой — SERVICE_ROLE_KEY из /opt/supabase/tour/.env. Облако только читается.
Повторный запуск докачивает новые и изменённые файлы, существующие перезаписывает.
"""
import json
import os
import urllib.request


def env_file(path):
    out = {}
    for line in open(path):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            out[k] = v.strip().strip("'\"")
    return out


web = env_file("/opt/sites/tour/web.env")
own = env_file("/opt/supabase/tour/.env")
CLOUD = os.environ.get("CLOUD_URL", web["SUPABASE_URL"]).rstrip("/") + "/storage/v1"
CLOUD_KEY = os.environ.get("CLOUD_KEY", web["SUPABASE_SERVICE_ROLE_KEY"])
LOCAL = "http://127.0.0.1:8211"
LOCAL_KEY = own["SERVICE_ROLE_KEY"]
if "supabase.co" not in CLOUD:
    raise SystemExit("SUPABASE_URL уже не облачный — передайте CLOUD_URL и CLOUD_KEY")


def call(base, key, method, path, body=None, raw=False, ctype="application/json"):
    data = body if raw else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"apikey": key, "Authorization": "Bearer " + key, "Content-Type": ctype})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def list_all(bucket, prefix=""):
    out, offset = [], 0
    while True:
        items = json.loads(call(CLOUD, CLOUD_KEY, "POST", "/object/list/" + bucket,
                                {"prefix": prefix, "limit": 1000, "offset": offset}))
        for it in items:
            name = prefix + it["name"]
            if it.get("id") is None:  # папка
                out += list_all(bucket, name + "/")
            else:
                out.append((name, (it.get("metadata") or {}).get("mimetype") or "application/octet-stream"))
        if len(items) < 1000:
            return out
        offset += 1000


buckets = json.loads(call(CLOUD, CLOUD_KEY, "GET", "/bucket"))
local_buckets = {b["id"] for b in json.loads(call(LOCAL, LOCAL_KEY, "GET", "/bucket"))}
copied = 0
for b in buckets:
    spec = {"id": b["id"], "name": b["name"], "public": b["public"],
            "file_size_limit": b.get("file_size_limit"), "allowed_mime_types": b.get("allowed_mime_types")}
    if b["id"] in local_buckets:
        call(LOCAL, LOCAL_KEY, "PUT", "/bucket/" + b["id"], spec)
    else:
        call(LOCAL, LOCAL_KEY, "POST", "/bucket", spec)
    for name, mime in list_all(b["id"]):
        blob = call(CLOUD, CLOUD_KEY, "GET", "/object/%s/%s" % (b["id"], urllib.request.quote(name)), raw=True)
        req = urllib.request.Request("%s/object/%s/%s" % (LOCAL, b["id"], urllib.request.quote(name)), data=blob, method="POST",
                                     headers={"apikey": LOCAL_KEY, "Authorization": "Bearer " + LOCAL_KEY,
                                              "Content-Type": mime, "x-upsert": "true"})
        urllib.request.urlopen(req, timeout=60).read()
        copied += 1
    print("%-20s public=%s" % (b["id"], b["public"]))
print("файлов скопировано:", copied)
