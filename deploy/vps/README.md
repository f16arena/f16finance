# Orda на своём сервере (cloud-001, 185.113.132.3)

**Переключено 22.10.2026 в 00:00 UTC (05:00 Алматы).** Vercel на паузе, облачный Supabase больше не пишется. Дальше — раздел «Полный уход».

Переезд с Vercel + облачного Supabase (проект `tmudsqgagblmdctaosgw`, Токио). Всё на одной машине:

| Что | Где | Порт (только 127.0.0.1) |
|---|---|---|
| Supabase (стек `orda-supabase`) | `/opt/supabase/orda` | 8100 API, 5434/6544 Postgres через пулер |
| Веб (`orda-web`) | `/opt/orda/app` (клон репо) | 3200 |
| Кроны (36 шт., из `vercel.json`) | `/etc/cron.d/orda` ← `gen-crontab.mjs` | лог `/var/log/orda-cron.log` |
| Бэкап | `/etc/cron.d/orda-backup` → `backup.sh` (21:30 UTC) | `/opt/orda/backups` (14 дней), лог `/var/log/orda-backup.log`; копия на ПК владельца — задача Windows «Orda backup pull» (`pull-backup-windows.ps1`, 09:00 и при входе) → `%USERPROFILE%\Backups\Orda` |
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
| Мобилка Expo (mobile/) | не используется (в работе нативный iOS) | если оживёт — `eas.json` → `EXPO_PUBLIC_SUPABASE_URL=https://db.ordaops.kz` |

## Обновление веба (вместо Vercel)

Автоматически: `/etc/cron.d/orda-deploy` раз в минуту запускает `deploy.sh` — если в `origin/main` новый коммит, сервер пересобирает и перезапускает `orda-web` (2–4 мин), обновляет кроны. Лог: `/var/log/orda-deploy.log`. Сборка упала — продолжает работать прежняя версия.

Cron запускает копию скрипта `/opt/orda/bin/deploy.sh` (её обновляет сам деплой `main`), а не файл из репозитория: при откате репозиторий уходит на старый коммит.

Кнопки на `/platform/status` («Пересобрать» — с перезапуском контейнера, подхватывает правки `web.env`, «Откатить сюда», «Вернуть автодеплой») кладут заявку в `/opt/orda/control/request.json` (смонтирована в контейнер как `/control`), её забирает `deploy.sh`. Откат закрепляет версию в `/opt/orda/control/pin` — пока файл есть, новые коммиты не выкатываются. Аварийно вернуть автодеплой с сервера: `rm /opt/orda/control/pin && FORCE=1 sh /opt/orda/bin/deploy.sh`.

Руками: `FORCE=1 sh /opt/orda/bin/deploy.sh`. Кроны: `sh /opt/orda/app/deploy/vps/install-crons.sh` (на хосте node нет, генератор крутится в контейнере).

Расписание кронов по-прежнему живёт в `vercel.json` — файл остаётся источником, хотя Vercel больше не используется.

## Подготовка (можно делать хоть сейчас, облако не трогается)

1. `git pull` на сервере, пересборка веба (см. выше).
2. `sh /opt/orda/app/deploy/vps/apply-supabase-config.sh` — настройки Auth и почты.
3. Тестовая копия: `sh app/deploy/vps/sync-db.sh && python3 app/deploy/vps/sync-storage.py`. Расхождения в числе строк на живых таблицах (продажи, аудит) — нормально, это записи за время копирования.
4. DNS: TTL уже 60 с. `db.ordaops.kz` → A `185.113.132.3` заведён 22.10, сертификат выпущен (в `/etc/caddy/sites/orda.caddy` пока только блок db).

## Переключение (порядок важен)

Цель — ни одной записи в старую базу после финальной копии. Займёт ~15 минут; касса это время копит офлайн-очередь.

1. **Пауза записи:** в Vercel выключить кроны (Settings → Cron Jobs → Disable) и поставить проект на паузу (`pause_project`) — сайт отдаёт 503, касса уходит в офлайн.
2. **Финальная копия базы** облако → сервер (`sync-db.sh`), затем файлы (`sync-storage.py`). Проверить, что `rewrite-urls.log` показал переписанные ссылки.
3. **DNS** (пока ещё Vercel DNS, аккаунт padash00): ALIAS `@` и `*` → заменить на A `185.113.132.3`. `www` покрывается `*`.
4. **Caddy:** `cp /opt/orda/caddy-staged/orda.caddy /opt/orda/caddy-staged/commrent.caddy /etc/caddy/sites/ && caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy`. Старый `commrent.caddy` лежит рядом как `commrent.caddy.orig`. Правка commrent только добавляет ветку `*.ordaops.kz` в общий catch-all и в `ask`.
5. **Кроны:** `sh app/deploy/vps/install-crons.sh`, бэкап: `echo '30 21 * * * root sh /opt/orda/app/deploy/vps/backup.sh' > /etc/cron.d/orda-backup` (21:30 UTC = 02:30 Алматы).
6. **Проверка:** вход в веб без перелогина, вход в iOS, продажа с кассы, команда на киоск, загрузка фото, письмо «сбросить пароль», `tail /var/log/orda-cron.log`.
7. **Vercel отключить от GitHub** (Settings → Git → Disconnect), чтобы пуши не выкатывались туда.

**Откат** в первый час: DNS обратно на ALIAS Vercel, снять паузу проекта, включить кроны Vercel, удалить `/etc/cron.d/orda`. Записи, сделанные на сервере после переключения, придётся переносить руками.

## Полный уход с Vercel и Supabase (после 1–2 дней без проблем)

1. **DNS в ps.kz.** Домен зарегистрирован в ICPS (ps.kz), DNS сейчас у Vercel (`ns1/ns2.vercel-dns.com`). В кабинете ps.kz включить их DNS-хостинг и завести записи:

   | Имя | Тип | Значение |
   |---|---|---|
   | `@` | A | `185.113.132.3` |
   | `*` | A | `185.113.132.3` |
   | `db` | A | `185.113.132.3` |
   | `@` | CAA | `0 issue "letsencrypt.org"` |

   Потом сменить NS домена на серверы ps.kz. Обе зоны указывают на сервер, поэтому простоя нет. Проверка: `nslookup -type=NS ordaops.kz 8.8.8.8`.
2. **Последний дамп облака** скачать и положить к бэкапам (на случай «а что там было»).
3. **Удалить** проект Supabase `F16finance` (ref `tmudsqgagblmdctaosgw`), проект Vercel `f16finance`, домен `ordaops.kz` из аккаунта Vercel padash00 (только после того, как NS уже на ps.kz).
4. **Сменить** токены Vercel/Supabase, пароль root и ключ из `server.txt` — они светились в переписке.

## После переезда

- `TELEGRAM_*` вебхук смотрит на `https://www.ordaops.kz/...` — меняется вместе с DNS, перерегистрировать не надо.
- Supabase Studio доступна на `https://db.ordaops.kz` под логином `DASHBOARD_USERNAME/PASSWORD` из `.env` стека.
