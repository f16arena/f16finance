-- Детейлинг: запись машин на боксы, прайс по классу кузова, машины клиентов.
--
-- Ниша — свойство точки (companies.industry = 'detailing'). Всё ниже живёт по
-- точке (company_id) и дублирует organization_id для быстрых проверок изоляции.
--
-- Клиент — та же public.customers (уникален по точке и телефону), к нему
-- добавляются машины. Мастер — сотрудник точки (public.operators): в программу
-- он не заходит, работы на него ставит администратор.
--
-- Запись хранит цену и материалы на момент записи: правка прайса не меняет
-- уже записанные и выданные машины. Процент мастера считается с
-- (цена − материалы).

create table if not exists public.detailing_boxes (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 40),
  note text check (note is null or length(note) <= 80),
  sort_order integer not null default 0,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);
create index if not exists detailing_boxes_company_idx on public.detailing_boxes (company_id, sort_order);

create table if not exists public.detailing_body_classes (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 40),
  sort_order integer not null default 0,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);
create index if not exists detailing_body_classes_company_idx on public.detailing_body_classes (company_id, sort_order);

create table if not exists public.detailing_services (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 80),
  -- Что входит в пакет: «капот, бампер, фары, зеркала». Просто подпись.
  package_note text check (package_note is null or length(package_note) <= 160),
  -- Сколько дней машина занимает бокс.
  days integer not null default 1 check (days between 1 and 30),
  sort_order integer not null default 0,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists detailing_services_company_idx on public.detailing_services (company_id, sort_order);

-- Цена и материалы услуги для класса кузова. Нет строки — услуга для этого
-- кузова не делается.
create table if not exists public.detailing_service_prices (
  service_id uuid not null references public.detailing_services(id) on delete cascade,
  body_class_id uuid not null references public.detailing_body_classes(id) on delete cascade,
  price numeric(12,2) not null check (price >= 0),
  materials numeric(12,2) not null default 0 check (materials >= 0),
  primary key (service_id, body_class_id)
);

create table if not exists public.customer_cars (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  customer_id uuid not null references public.customers(id) on delete cascade,
  -- Как ввели: «777 ABC 02». plate_norm — без пробелов, в верхнем регистре, для поиска.
  plate text not null check (length(btrim(plate)) between 1 and 20),
  plate_norm text not null,
  model text check (model is null or length(model) <= 80),
  body_class_id uuid references public.detailing_body_classes(id) on delete set null,
  note text check (note is null or length(note) <= 300),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index if not exists customer_cars_company_plate_uidx on public.customer_cars (company_id, plate_norm);
create index if not exists customer_cars_customer_idx on public.customer_cars (customer_id);

create table if not exists public.detailing_bookings (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  -- null — заявка с сайта, бокс ещё не выбран.
  box_id uuid references public.detailing_boxes(id) on delete set null,
  customer_id uuid not null references public.customers(id) on delete restrict,
  car_id uuid references public.customer_cars(id) on delete set null,
  body_class_id uuid references public.detailing_body_classes(id) on delete set null,
  master_operator_id uuid references public.operators(id) on delete set null,
  start_date date not null,
  days integer not null default 1 check (days between 1 and 60),
  status text not null default 'booked'
    check (status in ('requested', 'booked', 'in_box', 'done', 'no_show', 'cancelled')),
  price_total numeric(12,2) not null default 0 check (price_total >= 0),
  materials_total numeric(12,2) not null default 0 check (materials_total >= 0),
  -- true — материалы поправили руками, пересчёт по прайсу их не трогает.
  materials_manual boolean not null default false,
  comment text check (comment is null or length(comment) <= 500),
  source text not null default 'admin' check (source in ('admin', 'online')),
  created_by uuid references auth.users(id) on delete set null,
  issued_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists detailing_bookings_company_date_idx on public.detailing_bookings (company_id, start_date);
create index if not exists detailing_bookings_customer_idx on public.detailing_bookings (customer_id);

create table if not exists public.detailing_booking_services (
  id uuid primary key default gen_random_uuid(),
  booking_id uuid not null references public.detailing_bookings(id) on delete cascade,
  service_id uuid references public.detailing_services(id) on delete set null,
  -- Снимок на момент записи.
  name text not null,
  price numeric(12,2) not null check (price >= 0),
  materials numeric(12,2) not null default 0 check (materials >= 0),
  days integer not null default 1,
  sort_order integer not null default 0
);
create index if not exists detailing_booking_services_booking_idx on public.detailing_booking_services (booking_id);

-- Оплаты по записи (предоплата, доплата). Экран оплат — следующий этап;
-- таблица заводится сразу, чтобы не плодить миграции.
create table if not exists public.detailing_payments (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  company_id uuid not null references public.companies(id) on delete cascade,
  booking_id uuid not null references public.detailing_bookings(id) on delete cascade,
  paid_on date not null default current_date,
  amount numeric(12,2) not null check (amount > 0),
  method text not null check (method in ('cash', 'kaspi', 'card')),
  kind text not null default 'payment' check (kind in ('prepayment', 'payment')),
  -- Строка в общем журнале доходов, чтобы ОПиУ и Cash Flow видели деньги.
  income_id uuid,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now()
);
create index if not exists detailing_payments_booking_idx on public.detailing_payments (booking_id);
create index if not exists detailing_payments_company_date_idx on public.detailing_payments (company_id, paid_on);

-- RLS: из браузера только чтение своей организации; запись — через API.
do $$
declare t text;
begin
  foreach t in array array['detailing_boxes','detailing_body_classes','detailing_services','customer_cars','detailing_bookings','detailing_payments'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists %I on public.%I', t || '_tenant_read', t);
    execute format('create policy %I on public.%I for select to authenticated using (public.can_access_organization(organization_id))', t || '_tenant_read', t);
  end loop;
end $$;

alter table public.detailing_service_prices enable row level security;
drop policy if exists detailing_service_prices_tenant_read on public.detailing_service_prices;
create policy detailing_service_prices_tenant_read on public.detailing_service_prices
  for select to authenticated
  using (exists (
    select 1 from public.detailing_services s
    where s.id = service_id and public.can_access_organization(s.organization_id)
  ));

alter table public.detailing_booking_services enable row level security;
drop policy if exists detailing_booking_services_tenant_read on public.detailing_booking_services;
create policy detailing_booking_services_tenant_read on public.detailing_booking_services
  for select to authenticated
  using (exists (
    select 1 from public.detailing_bookings b
    where b.id = booking_id and public.can_access_organization(b.organization_id)
  ));

comment on table public.detailing_boxes is 'Боксы детейлинга: строки недельного календаря записи';
comment on table public.detailing_body_classes is 'Классы кузова точки (седан, кроссовер, джип…) — колонки прайса';
comment on table public.detailing_services is 'Услуги детейлинга: название, пакет, дней в боксе';
comment on table public.detailing_service_prices is 'Цена и материалы услуги по классу кузова';
comment on table public.customer_cars is 'Машины клиентов: госномер, модель, класс кузова';
comment on table public.detailing_bookings is 'Запись машины на бокс: дни, статус, цена и материалы на момент записи';
comment on table public.detailing_booking_services is 'Услуги в записи — снимок цены и материалов';
comment on table public.detailing_payments is 'Оплаты по записи: предоплата и доплаты';

notify pgrst, 'reload schema';
