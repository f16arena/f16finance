-- Память ИИ о бизнесе: каждый день крон /api/cron/ai-daily-learning пересчитывает
-- нормы по точкам (обычная выручка по дням недели, доля нала/безнала, день/ночь,
-- расходы по статьям) и копит закономерности, которые ИИ заметил в потоке данных,
-- с уверенностью: подтвердилось — растёт, опровергнуто или давно не видно — падает.
-- Все ИИ-разборы получают эту память как фон (lib/server/ai-business-context.ts).
--
-- Пишет и читает только сервер (service role): RLS включён, политик нет.

create table if not exists public.ai_business_memory (
  organization_id uuid primary key references public.organizations(id) on delete cascade,
  norms jsonb not null default '{}'::jsonb,
  facts jsonb not null default '[]'::jsonb,
  last_day date,
  days_learned integer not null default 0,
  updated_at timestamptz not null default now()
);

alter table public.ai_business_memory enable row level security;
revoke all on public.ai_business_memory from anon, authenticated;
