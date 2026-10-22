# База Tour (tournament.f16-arena.kz) на cloud-001

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
