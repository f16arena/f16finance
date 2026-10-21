#!/bin/sh
# Доводит self-hosted Supabase (/opt/supabase/orda) до настроек облачного проекта:
# письма Auth (шаблоны, темы, Gmail SMTP), SITE_URL и редиректы, MFA, лимиты, без входа по телефону.
# Повторный запуск безопасен. Секреты не печатаются: SMTP берётся из /opt/orda/web.env на месте.
set -eu

APP=/opt/orda/app/deploy/vps
SB=/opt/supabase/orda

mkdir -p "$SB/volumes/templates"
cp "$APP"/supabase/templates/*.html "$SB/volumes/templates/"
[ -f "$SB/docker-compose.orda.yml" ] && cp "$SB/docker-compose.orda.yml" "$SB/docker-compose.orda.yml.bak"
cp "$APP/supabase/docker-compose.orda.yml" "$SB/docker-compose.orda.yml"

python3 - "$SB/.env" /opt/orda/web.env <<'PY'
import sys
sb_path, web_path = sys.argv[1], sys.argv[2]

def read(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            out[k.strip()] = v.strip().strip("'\"")
    return out

web = read(web_path)
want = {
    # Как в облаке: {{ .SiteURL }} в письмах = корень, редиректы — все наши хосты.
    "SITE_URL": "https://ordaops.kz",
    "ADDITIONAL_REDIRECT_URLS": ",".join([
        "https://ordaops.kz/*", "https://www.ordaops.kz/*", "https://*.ordaops.kz/*",
        "https://f16finance.vercel.app/*", "http://localhost:3000/*",
    ]),
    "ENABLE_PHONE_SIGNUP": "false",
    "ENABLE_PHONE_AUTOCONFIRM": "false",
    "SMTP_HOST": web.get("SMTP_HOST", "smtp.gmail.com"),
    "SMTP_PORT": web.get("SMTP_PORT", "465"),
    "SMTP_USER": web["SMTP_USER"],
    "SMTP_PASS": web["SMTP_PASS"],
    "SMTP_ADMIN_EMAIL": web["SMTP_USER"],
    "SMTP_SENDER_NAME": '"Orda Control"',
}
lines = open(sb_path, encoding="utf-8").read().splitlines()
seen = set()
for i, line in enumerate(lines):
    k = line.split("=", 1)[0].strip() if "=" in line and not line.lstrip().startswith("#") else None
    if k in want:
        lines[i] = f"{k}={want[k]}"
        seen.add(k)
lines += [f"{k}={v}" for k, v in want.items() if k not in seen]
open(sb_path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("обновлено в .env:", ", ".join(want))
PY

cd "$SB"
docker compose up -d auth-templates auth
for i in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' supabase-auth 2>/dev/null)" = healthy ] && break
  sleep 3
done
docker exec supabase-auth-templates wget -qO- http://127.0.0.1:8080/invite.html >/dev/null && echo "шаблоны: OK"
echo "auth: $(docker inspect -f '{{.State.Health.Status}}' supabase-auth)"
