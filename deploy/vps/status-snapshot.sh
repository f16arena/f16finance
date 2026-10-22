#!/bin/sh
# Снимок для /platform/status (контейнер Orda не видит docker и сеть хоста):
# раз в минуту из /etc/cron.d/orda-status, внутри — каждые 5 секунд счётчики сети,
# каждые 20 секунд контейнеры Orda и хвосты их логов. Всё в /opt/orda/control
# (в контейнере — /control). Только чтение docker, ничего не перезапускает.
set -u
OUT=/opt/orda/control
exec 8>/run/orda-status-snapshot.lock
flock -n 8 || exit 0
mkdir -p "$OUT/logs"
IFACE=$(ip route show default 2>/dev/null | awk '{print $5; exit}')

i=0
while [ $i -lt 12 ]; do
  # сеть хоста: время, принято, отправлено (байты)
  awk -v ifc="$IFACE:" -v t="$(date +%s)" '$1==ifc{print t, $2, $10}' /proc/net/dev > "$OUT/hostnet.tmp" && mv "$OUT/hostnet.tmp" "$OUT/hostnet"

  if [ $((i % 4)) -eq 0 ]; then
    NAMES=$(docker ps -a --format '{{.Names}}' | grep -E '^(orda-|supabase-|realtime-dev\.supabase-)' || true)
    if [ -n "$NAMES" ]; then
      docker stats --no-stream --format '{{json .}}' $NAMES > "$OUT/stats.tmp" 2>/dev/null || true
      docker inspect --format '{{json .}}' $NAMES 2>/dev/null | python3 -c '
import sys, json
stats = {}
for l in open(sys.argv[1]):
    try:
        s = json.loads(l); stats[s["Name"]] = s
    except Exception: pass
def num(v):
    u = {"B":1,"KiB":1024,"MiB":1024**2,"GiB":1024**3,"kB":1e3,"MB":1e6,"GB":1e9}
    v = v.strip()
    for k in sorted(u, key=len, reverse=True):
        if v.endswith(k):
            try: return int(float(v[:-len(k)]) * u[k])
            except ValueError: return 0
    return 0
out = []
for l in sys.stdin:
    c = json.loads(l); n = c["Name"].lstrip("/"); st = stats.get(n, {})
    mem = st.get("MemUsage", "0B / 0B").split("/")
    out.append({"name": n, "image": c["Config"]["Image"], "state": c["State"]["Status"],
                "health": (c["State"].get("Health") or {}).get("Status"),
                "started_at": c["State"]["StartedAt"], "restarts": c.get("RestartCount", 0),
                "cpu": float(st["CPUPerc"].rstrip("%")) if st.get("CPUPerc") else None,
                "mem": num(mem[0]), "mem_limit": num(mem[1]) if len(mem) > 1 else 0})
json.dump({"at": __import__("time").time(), "containers": out}, open(sys.argv[2], "w"))
' "$OUT/stats.tmp" "$OUT/containers.tmp" && mv "$OUT/containers.tmp" "$OUT/containers.json"
      for n in $NAMES; do
        docker logs --tail 400 --timestamps "$n" > "$OUT/logs/$n.tmp" 2>&1 && mv "$OUT/logs/$n.tmp" "$OUT/logs/$n.log"
      done
    fi
  fi
  i=$((i + 1))
  [ $i -lt 12 ] && sleep 5
done
