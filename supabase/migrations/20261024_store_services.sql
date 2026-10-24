-- Услуги магазина: «Теннис 1 час», «Печать листа», «Аренда ракетки».
--
-- Раньше такое продавали «свободной ценой»: кассир сам писал название и сумму,
-- отчёт видел россыпь разных строк, а время игры никто не считал. Теперь
-- услуга заводится на сайте (Магазин → Склад → Услуги) с вариантами цены;
-- касса показывает её плиткой, цену берёт сервер. Остатка у услуги нет.
--
-- Вариант со временем (duration_minutes) после оплаты запускает таймер —
-- запись в store_service_sessions. Таймер живёт на сервере: переживает
-- перезапуск кассы и виден на всех кассах точки.
--
-- Строка продажи услуги — та же «универсальная» строка (item_id null,
-- universal_name = «Теннис · 1 час») плюс ссылка service_variant_id, так что
-- чеки, возвраты и отчёты по названию работают без изменений.
-- Код работает и без этой миграции (услуг просто нет).

create table if not exists public.store_services (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 80),
  -- Точки, где услуга продаётся. Пустой массив — на всех точках организации.
  company_ids uuid[] not null default '{}',
  is_active boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists store_services_org_idx on public.store_services (organization_id);

create table if not exists public.store_service_variants (
  id uuid primary key default gen_random_uuid(),
  service_id uuid not null references public.store_services(id) on delete cascade,
  name text not null check (length(btrim(name)) between 1 and 60),
  price numeric(12,2) not null check (price > 0),
  -- Минуты игры/аренды. null — услуга без времени (печать, ракетка).
  duration_minutes integer check (duration_minutes is null or duration_minutes between 1 and 1440),
  is_active boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamptz not null default now()
);
create index if not exists store_service_variants_service_idx on public.store_service_variants (service_id);

create table if not exists public.store_service_sessions (
  id uuid primary key default gen_random_uuid(),
  company_id uuid not null references public.companies(id) on delete cascade,
  service_id uuid references public.store_services(id) on delete set null,
  variant_id uuid references public.store_service_variants(id) on delete set null,
  sale_id uuid references public.point_sales(id) on delete set null,
  -- Название на момент продажи: «Теннис» — услугу могут переименовать позже.
  title text not null,
  started_at timestamptz not null default now(),
  ends_at timestamptz not null,
  -- Завершили раньше времени или закрыли после окончания.
  finished_at timestamptz,
  operator_id uuid,
  created_at timestamptz not null default now(),
  check (ends_at > started_at)
);
create index if not exists store_service_sessions_active_idx
  on public.store_service_sessions (company_id, ends_at) where finished_at is null;

alter table public.point_sale_items
  add column if not exists service_variant_id uuid references public.store_service_variants(id) on delete set null;

alter table public.store_services enable row level security;
alter table public.store_service_variants enable row level security;
alter table public.store_service_sessions enable row level security;
-- Только чтение своей организации из браузера; запись — через API (service role).
drop policy if exists store_services_tenant_read on public.store_services;
create policy store_services_tenant_read on public.store_services
  for select to authenticated
  using (public.can_access_organization(organization_id));
drop policy if exists store_service_variants_tenant_read on public.store_service_variants;
create policy store_service_variants_tenant_read on public.store_service_variants
  for select to authenticated
  using (exists (
    select 1 from public.store_services s
    where s.id = service_id and public.can_access_organization(s.organization_id)
  ));
drop policy if exists store_service_sessions_tenant_read on public.store_service_sessions;
create policy store_service_sessions_tenant_read on public.store_service_sessions
  for select to authenticated
  using (exists (
    select 1 from public.companies c
    where c.id = company_id and public.can_access_organization(c.organization_id)
  ));

-- Продажа: та же функция, строка дополнительно хранит service_variant_id.
create or replace function public.inventory_create_point_sale(p_company_id uuid, p_location_id uuid, p_point_device_id uuid, p_operator_id uuid, p_sale_date date, p_shift text, p_payment_method text, p_cash_amount numeric, p_kaspi_amount numeric, p_kaspi_before_midnight_amount numeric, p_kaspi_after_midnight_amount numeric, p_comment text, p_source text, p_local_ref text, p_items jsonb)
 returns table(sale_id uuid, total_amount numeric)
 language plpgsql
 set search_path to 'public', 'pg_temp'
as $function$
declare
  v_sale_id uuid;
  v_total numeric := 0;
  v_cash numeric := round(coalesce(p_cash_amount, 0), 2);
  v_kaspi numeric := round(coalesce(p_kaspi_amount, 0), 2);
  v_kaspi_before numeric := round(coalesce(p_kaspi_before_midnight_amount, 0), 2);
  v_kaspi_after numeric := round(coalesce(p_kaspi_after_midnight_amount, 0), 2);
  v_item jsonb;
  v_item_id uuid;
  v_universal_name text;
  v_qty numeric;
  v_unit_price numeric;
  v_line_total numeric;
  v_pd_loc_id uuid;
  v_pd_qty numeric;
  v_item_name text;
begin
  if p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
    raise exception 'point-sale-items-required';
  end if;

  if p_shift not in ('day', 'night') then
    raise exception 'point-sale-shift-invalid';
  end if;

  if p_payment_method not in ('cash', 'kaspi', 'mixed') then
    raise exception 'point-sale-payment-method-invalid';
  end if;

  if v_cash < 0 or v_kaspi < 0 or v_kaspi_before < 0 or v_kaspi_after < 0 then
    raise exception 'point-sale-payment-invalid';
  end if;

  if abs(v_kaspi - (v_kaspi_before + v_kaspi_after)) > 0.01 then
    raise exception 'point-sale-kaspi-split-mismatch';
  end if;

  -- v6: касса бьёт только point_display
  select id into v_pd_loc_id
  from public.inventory_locations
  where company_id = p_company_id and location_type = 'point_display' and is_active = true
  limit 1;

  if v_pd_loc_id is null then
    raise exception 'point-sale-showcase-location-missing';
  end if;

  -- Валидация: на витрине должно хватить ТОЛЬКО для каталожных товаров.
  -- Универсальные товары и услуги (item_id = null + universal_name) пропускаем.
  for v_item in select * from jsonb_array_elements(p_items)
  loop
    v_item_id := nullif(v_item ->> 'item_id', '')::uuid;
    v_universal_name := nullif(trim(coalesce(v_item ->> 'universal_name', '')), '');
    v_qty := coalesce((v_item ->> 'quantity')::numeric, 0);
    v_unit_price := round(coalesce((v_item ->> 'unit_price')::numeric, 0), 2);

    if v_item_id is null and v_universal_name is null then
      raise exception 'point-sale-line-invalid';
    end if;

    if v_qty <= 0 then
      raise exception 'point-sale-line-invalid';
    end if;

    if v_unit_price < 0 then
      raise exception 'point-sale-unit-price-invalid';
    end if;

    if v_item_id is not null then
      select coalesce(quantity, 0) into v_pd_qty
      from public.inventory_balances
      where location_id = v_pd_loc_id and item_id = v_item_id;
      v_pd_qty := coalesce(v_pd_qty, 0);

      if v_qty > v_pd_qty + 0.0001 then
        select name into v_item_name from public.inventory_items where id = v_item_id;
        raise exception 'point-sale-showcase-insufficient: % (showcase: %, requested: %)',
          coalesce(v_item_name, v_item_id::text), v_pd_qty, v_qty;
      end if;
    end if;
  end loop;

  insert into public.point_sales (
    company_id, location_id, point_device_id, operator_id, sale_date, shift,
    payment_method, cash_amount, kaspi_amount, kaspi_before_midnight_amount,
    kaspi_after_midnight_amount, comment, source, local_ref
  )
  values (
    p_company_id, p_location_id, p_point_device_id, p_operator_id, p_sale_date, p_shift,
    p_payment_method, v_cash, v_kaspi, v_kaspi_before, v_kaspi_after,
    nullif(trim(coalesce(p_comment, '')), ''),
    coalesce(nullif(trim(coalesce(p_source, '')), ''), 'point-client'),
    nullif(trim(coalesce(p_local_ref, '')), '')
  )
  returning id into v_sale_id;

  for v_item in select * from jsonb_array_elements(p_items)
  loop
    v_item_id := nullif(v_item ->> 'item_id', '')::uuid;
    v_universal_name := nullif(trim(coalesce(v_item ->> 'universal_name', '')), '');
    v_qty := coalesce((v_item ->> 'quantity')::numeric, 0);
    v_unit_price := round(coalesce((v_item ->> 'unit_price')::numeric, 0), 2);
    v_line_total := round(v_qty * v_unit_price, 2);
    v_total := v_total + v_line_total;

    insert into public.point_sale_items (
      sale_id, item_id, universal_name, quantity, unit_price, total_price, comment, service_variant_id
    )
    values (
      v_sale_id, v_item_id, v_universal_name, v_qty, v_unit_price, v_line_total,
      nullif(trim(coalesce(v_item ->> 'comment', '')), ''),
      nullif(v_item ->> 'service_variant_id', '')::uuid
    );

    -- Списание с point_display только для каталожных товаров.
    -- Универсальные товары и услуги не списывают остаток (нечего списывать).
    if v_item_id is not null then
      perform public.inventory_apply_balance_delta(v_pd_loc_id, v_item_id, -v_qty);

      insert into public.inventory_movements (
        item_id, movement_type, from_location_id, quantity, total_amount,
        reference_type, reference_id, comment, actor_user_id
      )
      values (
        v_item_id, 'sale', v_pd_loc_id, v_qty, v_line_total,
        'point_sale', v_sale_id,
        nullif(trim(coalesce(v_item ->> 'comment', '')), ''), null
      );
    end if;
  end loop;

  v_total := round(v_total, 2);

  if abs(v_total - (v_cash + v_kaspi)) > 0.01 then
    raise exception 'point-sale-payment-total-mismatch';
  end if;

  update public.point_sales
  set total_amount = v_total
  where id = v_sale_id;

  return query select v_sale_id, v_total;
end;
$function$;

comment on table public.store_services is 'Услуги магазина: продаются без остатка, цена по вариантам';
comment on table public.store_service_variants is 'Варианты услуги: название, цена, минуты (null — без времени)';
comment on table public.store_service_sessions is 'Идущие таймеры услуг на точке: с момента продажи до ends_at';
comment on column public.point_sale_items.service_variant_id is 'Строка — продажа варианта услуги (item_id null)';

notify pgrst, 'reload schema';
