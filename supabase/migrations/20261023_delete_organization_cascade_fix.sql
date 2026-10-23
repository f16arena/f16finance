-- Удаление организации падало: «violates foreign key constraint point_shifts_company_id_fkey».
-- Функция сносила точки раньше, чем ссылающиеся на них смены касс, а ещё восемь таблиц
-- ссылаются на точки и одиннадцать — на сотрудников без каскада. Плюс внутри оставался
-- запрет на billing_exempt, снятый на сайте 23.10 (тестовые орг-и тоже удаляются).
--
-- Теперь: сначала всё, что держит точки и сотрудников этой организации, потом точки,
-- сотрудники и сама организация. Функция атомарна: ошибка — ничего не удалено.

create or replace function public.delete_organization_cascade(p_org uuid)
returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
  v_slug      text;
  v_status    text;
  v_companies uuid[];
  v_staff     uuid[];
  v_locations uuid[];
  v_items     uuid[];
  v_suppliers uuid[];
  v_sales     uuid[];
begin
  select slug, status into v_slug, v_status from public.organizations where id = p_org;

  if v_slug is null then
    raise exception 'organization % not found', p_org;
  end if;
  if v_slug = 'f16' then
    raise exception 'F16 нельзя удалить';
  end if;
  if v_status is distinct from 'archived' then
    raise exception 'организацию сначала нужно архивировать (текущий статус: %)', v_status;
  end if;

  select coalesce(array_agg(id), '{}') into v_companies from public.companies where organization_id = p_org;
  select coalesce(array_agg(id), '{}') into v_staff from public.staff where organization_id = p_org;
  select coalesce(array_agg(id), '{}') into v_locations
    from public.inventory_locations where company_id = any(v_companies) or organization_id = p_org;
  select coalesce(array_agg(id), '{}') into v_items
    from public.inventory_items where company_id = any(v_companies) or organization_id = p_org;
  select coalesce(array_agg(id), '{}') into v_suppliers
    from public.inventory_suppliers where company_id = any(v_companies) or organization_id = p_org;
  select coalesce(array_agg(id), '{}') into v_sales
    from public.point_sales where company_id = any(v_companies) or location_id = any(v_locations);

  -- 0) Касса и склад: внутри каскада от точки они держат друг друга (RESTRICT) —
  --    продажа держит товар, заявка держит склад. Сносим явно, от листьев к корню.
  delete from public.point_sale_returns where sale_id = any(v_sales) or company_id = any(v_companies) or location_id = any(v_locations);
  delete from public.point_return_items where item_id = any(v_items)
    or return_id in (select id from public.point_returns where company_id = any(v_companies) or location_id = any(v_locations) or sale_id = any(v_sales));
  delete from public.point_returns where company_id = any(v_companies) or location_id = any(v_locations) or sale_id = any(v_sales);
  delete from public.point_sale_items where sale_id = any(v_sales) or item_id = any(v_items);
  delete from public.point_sales where id = any(v_sales);
  delete from public.inventory_request_items where item_id = any(v_items)
    or request_id in (select id from public.inventory_requests where source_location_id = any(v_locations) or target_location_id = any(v_locations));
  delete from public.inventory_requests where source_location_id = any(v_locations) or target_location_id = any(v_locations);
  delete from public.inventory_stocktake_items where item_id = any(v_items)
    or stocktake_id in (select id from public.inventory_stocktakes where location_id = any(v_locations));
  delete from public.inventory_stocktakes where location_id = any(v_locations);
  delete from public.inventory_writeoff_items where item_id = any(v_items)
    or writeoff_id in (select id from public.inventory_writeoffs where location_id = any(v_locations));
  delete from public.inventory_writeoffs where location_id = any(v_locations);
  delete from public.inventory_purchase_order_items where item_id = any(v_items)
    or order_id in (select id from public.inventory_purchase_orders where organization_id = p_org or supplier_id = any(v_suppliers));
  delete from public.inventory_purchase_orders where organization_id = p_org or supplier_id = any(v_suppliers);
  delete from public.inventory_receipt_items where item_id = any(v_items)
    or receipt_id in (select id from public.inventory_receipts where location_id = any(v_locations) or supplier_id = any(v_suppliers));
  delete from public.inventory_receipts where location_id = any(v_locations) or supplier_id = any(v_suppliers);
  delete from public.inventory_movements where item_id = any(v_items);
  delete from public.arena_sessions where company_id = any(v_companies)
    or station_id in (select id from public.arena_stations where company_id = any(v_companies));
  delete from public.server_monitor_alert_state where organization_id = p_org;
  delete from public.server_monitor_ingest_receipts where organization_id = p_org;

  -- 1) Что держит точки (RESTRICT / NO ACTION на companies)
  delete from public.incidents          where company_id = any(v_companies) or organization_id = p_org;
  delete from public.point_shifts       where company_id = any(v_companies) or organization_id = p_org;
  delete from public.operator_shifts    where company_id = any(v_companies) or operator_id = any(v_staff);
  delete from public.debts              where company_id = any(v_companies);
  delete from public.expense_templates  where company_id = any(v_companies);
  delete from public.recurring_expenses where company_id = any(v_companies);
  delete from public.tags               where company_id = any(v_companies);
  delete from public.tasks              where company_id = any(v_companies) or created_by = any(v_staff);

  -- 2) Точки — каскад уносит их данные (доходы, склад, касса…)
  delete from public.companies where organization_id = p_org;

  -- 3) Что держит сотрудников (NO ACTION на staff)
  update public.incomes        set created_by = null   where created_by = any(v_staff);
  update public.projects       set created_by = null   where created_by = any(v_staff);
  update public.task_checklist set completed_by = null where completed_by = any(v_staff);
  delete from public.task_comments     where staff_id = any(v_staff);
  delete from public.task_history      where staff_id = any(v_staff);
  delete from public.notifications     where staff_id = any(v_staff);
  delete from public.reminders         where staff_id = any(v_staff);
  delete from public.staff_adjustments where staff_id = any(v_staff);
  delete from public.staff_payments    where staff_id = any(v_staff);

  -- 4) Таблицы с ON DELETE SET NULL по организации — чистим, чтобы не осталось мусора
  delete from public.point_receipt_settings where organization_id = p_org;
  delete from public.staff_debt_payments    where organization_id = p_org;
  delete from public.customers              where organization_id = p_org;
  delete from public.staff                  where organization_id = p_org;

  -- 5) Сама организация — каскад уносит members, subscriptions, tenant_domains и т.д.
  delete from public.organizations where id = p_org;
end;
$function$;

revoke execute on function public.delete_organization_cascade(uuid) from public, anon, authenticated;
grant execute on function public.delete_organization_cascade(uuid) to service_role;
