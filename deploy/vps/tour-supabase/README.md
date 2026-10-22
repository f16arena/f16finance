# База Tour (tournament.f16-arena.kz) на cloud-001

**Переключено 22.10.2026 в 17:33 UTC** (простой ~3 мин). Облачный проект `dgpxlpjnjthyotjcccfl` больше не пишется
(последняя запись 17:30:11 UTC), проект Tour в Vercel на паузе. Синхронизация агентов: 4,4 с → 0,04 с.
Старые ключи облака — `/opt/sites/tour/web.env.cloud-bak`.

Было: Supabase Cloud `dgpxlpjnjthyotjcccfl` (Сидней, ~390 мс на запрос из KZ — агенты синхронизировались по 4–6 с).
Стало: `/opt/supabase/tour` — Postgres + PostgREST + Storage (те же образы, что у Orda), порты только на 127.0.0.1:
db 5440, rest 8210, storage 8211. Снаружи — Caddy `db.f16-arena.kz` (`/rest/v1`, `/storage/v1`). Ключи — `/opt/supabase/tour/.env` (600).

## Перенос
1. Схема — 25 миграций из репозитория Tour (их же функция `migrationTransaction`), под ролью postgres.
   Сверено с облаком по объектам (колонки, ограничения, индексы, функции, триггеры, права, RLS): совпадает;
   тексты функций отличаются только `\r` (в облаке 13 функций записаны с Windows-переносами).
2. Данные — `tour-sync.py` (через API управления Supabase, облако только читается): все таблицы public одной
   транзакцией, ссылки на Storage → `https://db.f16-arena.kz/storage/v1/`. Пробная копия: 1569 строк, 40 таблиц — совпало.
3. Файлы — `tour-storage-sync.py` на сервере: бакеты и 17 файлов.

## Миграции после переезда
`scripts/apply-migrations.mjs` в Tour ходит в API управления облака — на новую базу он НЕ применит.
Новые миграции: `docker exec -i tour-db psql -U postgres -d postgres < <транзакция из migrationTransaction>`.

## Доступ снаружи
`db.f16-arena.kz` (Caddy, deploy/vps/sites/f16-arena.caddy): без ключа сервиса — только публичные файлы
Storage, остальное 401 (как шлюз Supabase в облаке). Ключ для Caddy — `/etc/caddy/tour-db-key.caddy`.

## Бэкап
`backup.sh` (cron `/etc/cron.d/tour-backup`, 21:40 UTC): pg_dump public/storage + архив файлов в
`/opt/supabase/tour/backups`, 14 дней. Лог — `/var/log/tour-backup.log`.
