# Orda на своём сервере (cloud-001, 185.113.132.3)

Переезд с Vercel + облачного Supabase (проект `tmudsqgagblmdctaosgw`, Токио). Всё на одной машине:

| Что | Где | Порт (только 127.0.0.1) |
|---|---|---|
| Supabase (стек `orda-supabase`) | `/opt/supabase/orda` | 8100 API, 5434/6544 Postgres через пулер |
| Веб (`orda-web`) | `/opt/orda/app` (клон репо) | 3200 |
| Кроны (36 шт., из `vercel.json`) | `/etc/cron.d/orda` ← `gen-crontab.mjs` | лог `/var/log/orda-cron.log` |
| Бэкап | `/etc/cron.d/orda-backup` → `backup.sh` | `/opt/orda/backups`, лог `/var/log/orda-backup.log` |
| HTTPS | Caddy, `/etc/caddy/sites/orda.caddy` + общий catch-all в `commrent.caddy` | 80/443 |

Секреты лежат только на сервере: `/opt/supabase/orda/.env`, `/opt/orda/web.env`, `/opt/orda/build.env`, `/opt/orda/cloud-db.env` (все 600).

## Что уже сверено с облаком (22.10.2026)

- **JWT:** облако на старом HS256-секрете (JWKS пустой). Стек поднят с тем же секретом → `anon`/`service_role` прежние, токены мобилки и iOS остаются валидными.
- **Имя cookie сессии** закреплено в коде (`lib/core/supabase-auth-key.ts`, `mobile/lib/supabase.ts`): `sb-tmudsqgagblmdctaosgw-auth-token`. Без этого supabase-js вывел бы имя из нового URL (`sb-db-auth-token`) и разлогинил бы всех. **Этот код должен быть задеплоен на Vercel до переключения** (ничего не меняет, пока база облачная).
- **Auth:** шаблоны писем, темы, Gmail SMTP, SITE_URL, редиректы, TOTP-MFA, код 8 цифр/1 час, без входа по телефону — `supabase/docker-compose.orda.yml` + `apply-supabase-config.sh`.
- **База:** 193 МБ, расширения btree_gist/hypopg/index_advisor/pgcrypto/uuid-ossp/vault (без pg_cron и pg_net), Edge Functions нет, триггеров на auth/storage нет, своих ролей нет, Realtime-публикация 11 таблиц (переносится в `sync-db.sh`).
- **Storage:** 8 бакетов, ~505 файлов, ~250 МБ. В БД ~1200 полных ссылок `https://tmudsqgagblmdctaosgw.supabase.co/storage/...` (расходы, приходы, фото товаров, чат…) — `sync-db.sh` переписывает их на `https://db.ordaops.kz/storage/...`.
- **Vercel:** 40 переменных окружения — все есть в `web.env` (последняя правка в Vercel 30.08, раньше копии). `@vercel/analytics` вне Vercel просто молчит.

## Клиенты

| Клиент | Как ходит | Что нужно при переезде |
|---|---|---|
| Касса (desktop/operator) | только Next API, адрес в настройках, по умолчанию `https://ordaops.kz` | ничего; apex отдаёт **308** (на 301 POST превратился бы в GET) |
| Киоск (desktop/kiosk) | Next API + Realtime broadcast; адрес Supabase берёт с сервера (`/api/kiosk/rtconfig`) | ничего |
| iOS (apple/) | только Next API (`www.ordaops.kz`) | ничего |
| Мобилка Expo (mobile/) | Next API + **Supabase Auth напрямую**, URL вшит в сборку (`eas.json`), OTA нет | новая сборка с `https://db.ordaops.kz` после переключения; старая продолжит логиниться в облако, пока оно живо |

## Обновление веба

```sh
cd /opt/orda/app && git pull
cd /opt/orda && docker compose -f app/deploy/vps/docker-compose.yml --env-file build.env up -d --build
node app/deploy/vps/gen-crontab.mjs > /etc/cron.d/orda   # если менялся vercel.json
```

## Подготовка (можно делать хоть сейчас, облако не трогается)

1. `git pull` на сервере, пересборка веба (см. выше).
2. `sh /opt/orda/app/deploy/vps/apply-supabase-config.sh` — настройки Auth и почты.
3. Тестовая копия: `sh app/deploy/vps/sync-db.sh && python3 app/deploy/vps/sync-storage.py`. Расхождения в числе строк на живых таблицах (продажи, аудит) — нормально, это записи за время копирования.
4. В DNS (Vercel DNS аккаунта **padash00** — у f16arena на записи 403) понизить TTL. Можно заранее завести `db.ordaops.kz` → A `185.113.132.3`: облако его не использует, Caddy сразу получит сертификат.

## Переключение (порядок важен)

Цель — ни одной записи в старую базу после финальной копии. Займёт ~15 минут; касса это время копит офлайн-очередь.

1. **Пауза записи:** в Vercel выключить кроны (Settings → Cron Jobs → Disable) и поставить проект на паузу (`pause_project`) — сайт отдаёт 503, касса уходит в офлайн.
2. **Финальная копия базы** облако → сервер (`sync-db.sh`), затем файлы (`sync-storage.py`). Проверить, что `rewrite-urls.log` показал переписанные ссылки.
3. **DNS** (аккаунт padash00): `ordaops.kz`, `www`, `*`, `db` → A `185.113.132.3` (у `*` и apex убрать записи Vercel).
4. **Caddy:** `cp /opt/orda/caddy-staged/orda.caddy /opt/orda/caddy-staged/commrent.caddy /etc/caddy/sites/ && caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy`. Старый `commrent.caddy` лежит рядом как `commrent.caddy.orig`. Правка commrent только добавляет ветку `*.ordaops.kz` в общий catch-all и в `ask`.
5. **Кроны:** `node app/deploy/vps/gen-crontab.mjs > /etc/cron.d/orda`, бэкап: `echo '30 21 * * * root sh /opt/orda/app/deploy/vps/backup.sh' > /etc/cron.d/orda-backup` (21:30 UTC = 02:30 Алматы).
6. **Проверка:** вход в веб без перелогина, продажа с кассы, команда на киоск, загрузка фото, письмо «сбросить пароль», `tail /var/log/orda-cron.log`.
7. **Мобилка:** `mobile/eas.json` → `EXPO_PUBLIC_SUPABASE_URL=https://db.ordaops.kz`, сборка, публикация.
8. **Облако не удалять** ещё 2–4 недели: старые сборки мобилки, откат. Supabase можно перевести на Free (пауза после 7 дней простоя), Vercel — Hobby.

**Откат** до шага 7: DNS обратно на Vercel, снять паузу проекта и включить кроны Vercel. Всё, что записано на сервере после переключения, придётся переносить руками — поэтому решать об откате в первый час.

## После переезда

- `TELEGRAM_*` вебхук смотрит на `https://www.ordaops.kz/...` — меняется вместе с DNS, перерегистрировать не надо.
- Supabase Studio доступна на `https://db.ordaops.kz` под логином `DASHBOARD_USERNAME/PASSWORD` из `.env` стека.
- Сменить токены доступа к Vercel и Supabase, которые использовались при переезде.
