-- Колонки, которые код давно пишет и читает, а в базе их не было (сверка кода со схемой 22.10.2026).

-- Отчёт смены из iOS-приложения оператора кладёт сюда монеты, Wipon, долги и
-- остаток на начало; страница смены в админке их показывает. Без колонки отчёт
-- падал целиком.
alter table public.incomes add column if not exists meta jsonb;

-- Крон knowledge-confirmations: когда последний раз напоминали оператору
-- подтвердить регламенты (чтобы не слать каждый день).
alter table public.operators add column if not exists knowledge_reminded_at timestamptz;

-- Квиз в кассе проходит оператор, а у оператора не всегда есть запись staff:
-- staff_id был обязательным, operator_id не было вовсе — квиз не сохранялся.
alter table public.knowledge_quiz_attempts
  add column if not exists operator_id uuid references public.operators(id) on delete cascade;
alter table public.knowledge_quiz_attempts alter column staff_id drop not null;
create index if not exists knowledge_quiz_attempts_operator_idx
  on public.knowledge_quiz_attempts (operator_id, completed_at desc);

notify pgrst, 'reload schema';
