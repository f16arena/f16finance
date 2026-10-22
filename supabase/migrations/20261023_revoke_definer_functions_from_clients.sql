-- Линтер Supabase (23.10.2026): SECURITY DEFINER-функции в public выполнялись ролями anon и
-- authenticated — то есть любым, у кого есть публичный ключ сайта, через /rest/v1/rpc/...
-- Часть из них (staff_salary_allocate_payment, staff_salary_reverse_payment, ...) вызывающего
-- не проверяет: проводки зарплаты можно было менять с улицы.
--
-- Код вызывает их только сервисным ключом (delete_organization_cascade, point_shift_admin_*,
-- inventory_integrity_check), остальное — триггеры и вызовы из других definer-функций, им право
-- EXECUTE у клиента не нужно. Оставлены помощники RLS can_access_* и customer_*: их вызывают
-- политики под ролью пользователя (в т.ч. политики «to public»), и они только отвечают да/нет.

do $$
declare f record;
begin
  for f in
    select p.oid::regprocedure as sig
    from pg_proc p
    where p.pronamespace = 'public'::regnamespace
      and p.prosecdef
      and p.proname not like 'can_access\_%'
      and p.proname not like 'customer\_%'
      and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')
  loop
    execute format('revoke execute on function %s from public, anon, authenticated', f.sig);
    execute format('grant execute on function %s to service_role', f.sig);
  end loop;
end $$;

-- Тот же линтер: функции без фиксированного search_path.
alter function public.forecast_snapshots_freeze set search_path = public, pg_temp;
alter function public.store_kpi_category_mix set search_path = public, pg_temp;
alter function public.store_kpi_guard_locked_plan set search_path = public, pg_temp;
alter function public.store_kpi_price_history set search_path = public, pg_temp;
alter function public.store_kpi_shift_facts set search_path = public, pg_temp;
alter function public.touch_operator_exams_updated_at set search_path = public, pg_temp;
alter function public.touch_org_role_capabilities_updated_at set search_path = public, pg_temp;
alter function public.touch_store_kpi_updated_at set search_path = public, pg_temp;
