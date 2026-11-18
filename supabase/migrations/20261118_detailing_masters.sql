-- Детейлинг: оплата мастеров и выплаты.
--
-- Мастер — сотрудник точки (public.operators), в программу не заходит.
-- Из чего складывается его оплата, владелец включает тумблерами: оклад,
-- бонус за месяц, процент с работы. Процент считается с (цена − материалы)
-- выданных машин, где он записан мастером.
--
-- Выплата — отдельная строка: по ней видно, сколько уже отдано за месяц.
-- Каждая выплата пишет расход «Зарплата» в общий журнал (expense_id), чтобы
-- деньги сошлись с кассой.

create table if not exists public.detailing_master_terms (
  operator_id uuid not null references public.operators(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  salary_on boolean not null default false,
  salary_amount numeric(12,2) not null default 0 check (salary_amount >= 0),
  bonus_on boolean not null default false,
  bonus_amount numeric(12,2) not null default 0 check (bonus_amount >= 0),
  percent_on boolean not null default true,
  percent numeric(5,2) not null default 30 check (percent >= 0 and percent <= 100),
  -- Чем занимается: «плёнка, керамика». Просто подпись в карточке.
  note text check (note is null or length(note) <= 120),
  updated_at timestamptz not null default now(),
  primary key (operator_id, company_id)
);
create index if not exists detailing_master_terms_company_idx on public.detailing_master_terms (company_id);

create table if not exists public.detailing_master_payouts (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  operator_id uuid not null references public.operators(id) on delete restrict,
  -- Первый день месяца, за который платят.
  period_month date not null check (extract(day from period_month) = 1),
  paid_on date not null default current_date,
  amount numeric(12,2) not null check (amount > 0),
  method text not null check (method in ('cash', 'kaspi', 'card')),
  expense_id uuid,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create index if not exists detailing_master_payouts_company_month_idx on public.detailing_master_payouts (company_id, period_month);
create index if not exists detailing_master_payouts_operator_idx on public.detailing_master_payouts (operator_id);

-- RLS: из браузера только чтение своей организации; запись — через API.
do $$
declare t text;
begin
  foreach t in array array['detailing_master_terms','detailing_master_payouts'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists %I on public.%I', t || '_tenant_read', t);
    execute format('create policy %I on public.%I for select to authenticated using (public.can_access_organization(organization_id))', t || '_tenant_read', t);
  end loop;
end $$;

comment on table public.detailing_master_terms is 'Из чего складывается оплата мастера детейлинга: оклад, бонус, процент с работы';
comment on table public.detailing_master_payouts is 'Выплаты мастерам детейлинга по месяцам; expense_id — расход «Зарплата» в общем журнале';

notify pgrst, 'reload schema';
