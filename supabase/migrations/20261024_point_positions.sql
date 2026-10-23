-- Свои должности у точки: «Бариста», «Официант», «Администратор зала».
--
-- Раньше на точке было ровно три роли (operator / senior_operator /
-- senior_cashier) — зашито под F16. Должность — это название + доплата за
-- смену; права она берёт у одной из трёх базовых ролей (base_role), поэтому
-- вход в кассу, стол старшего и прочая логика ролей не меняются.
--
-- company_id null — должность доступна на всех точках организации.
-- Код работает и без этой миграции (должностей просто нет).

create table if not exists public.point_positions (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid references public.companies(id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 60),
  base_role text not null default 'operator'
    check (base_role in ('operator', 'senior_operator', 'senior_cashier')),
  -- Доплата за смену сверх ставки точки. Заменяет бонус старшего из правил ЗП.
  shift_bonus numeric not null default 0 check (shift_bonus >= 0),
  is_active boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists point_positions_name_unique
  on public.point_positions (organization_id, coalesce(company_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(btrim(name)))
  where is_active;
create index if not exists point_positions_org_idx on public.point_positions (organization_id);

alter table public.point_positions enable row level security;
-- Только чтение своей организации из браузера; запись — через API (service role).
drop policy if exists point_positions_tenant_read on public.point_positions;
create policy point_positions_tenant_read on public.point_positions
  for select to authenticated
  using (public.can_access_organization(organization_id));

alter table public.operator_company_assignments
  add column if not exists position_id uuid references public.point_positions(id) on delete set null;
create index if not exists operator_company_assignments_position_idx
  on public.operator_company_assignments (position_id) where position_id is not null;

comment on table public.point_positions is 'Должности организации/точки: название, базовая роль (права), доплата за смену';
comment on column public.operator_company_assignments.position_id is 'Своя должность на точке; null — базовая роль role_in_company без доплаты должности';

notify pgrst, 'reload schema';
