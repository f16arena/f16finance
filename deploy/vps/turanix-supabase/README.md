# Turanix (turanix.kz) на cloud-001

Было: сайт на Vercel (f16arena, проект turanix), база Supabase Cloud `fxvsvxnnkzudxgwkmovw` (eu-central-1).
Облачная база стояла на паузе (бесплатный план, без активности) — кабинет (вход) не работал; витрина с базой не связана;
восстановлена 22.10.2026 только для переноса.

Стало:
- сайт — `/opt/sites/turanix` (общие deploy/vps/sites), порт 3340, оба адреса turanix.kz и www.turanix.kz;
- база — `/opt/supabase/turanix`: Postgres + Auth (GoTrue v2.196, вход по почте и паролю) + PostgREST + Storage,
  порты на 127.0.0.1: db 5441, rest 8220, auth 8221, storage 8222. Снаружи — Caddy `db.turanix.kz`
  (deploy/vps/sites/turanix.caddy): без apikey (anon/service) открыты только ссылки из писем и подписанные файлы.

## Перенос
1. Схема — 5 миграций из `supabase_migrations.schema_migrations` облака (там сохранён их текст), под postgres,
   после запуска Auth (схема ссылается на auth.users). Сверено по объектам с правилами RLS и триггерами: 787 из 787.
2. Данные — `turanix-sync.py` (API управления, облако только читается): auth.users/identities с хешами паролей
   (пароли прежние), public. После вставки служебные поля токенов NULL -> '' (иначе GoTrue не читает пользователя).
3. Файлов в Storage не было (2 закрытых бакета с правилами — из миграций).

## Почта
SMTP у облачного проекта не был настроен (слал встроенный сервис Supabase). У своего Auth SMTP пустой —
письма подтверждения регистрации не уйдут, пока не заданы SMTP_* в `/opt/supabase/turanix/.env`.
