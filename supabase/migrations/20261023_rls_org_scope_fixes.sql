-- Изоляция организаций на уровне базы (проверка 23.10.2026).
--
-- Браузер ходит в PostgREST с anon-ключом и своим JWT, поэтому RLS — последняя
-- линия обороны, даже если весь код идёт через API на service role (он RLS
-- обходит, и эти правки ему не мешают).

-- 1. Назначения операторов на точки, график смен, связи оператор↔сотрудник.
--    Помощники can_manage_* проверяют только «есть ли у этого email роль
--    owner/manager хоть где-то» — без организации. Управляющий любой организации
--    мог читать и править назначения и график всех остальных. Добавляем скоуп.
drop policy if exists operator_company_assignments_select on public.operator_company_assignments;
drop policy if exists operator_company_assignments_insert on public.operator_company_assignments;
drop policy if exists operator_company_assignments_update on public.operator_company_assignments;
drop policy if exists operator_company_assignments_delete on public.operator_company_assignments;
create policy operator_company_assignments_select on public.operator_company_assignments
  for select to authenticated
  using (public.can_manage_operator_structure() and public.can_access_company(company_id));
create policy operator_company_assignments_insert on public.operator_company_assignments
  for insert to authenticated
  with check (public.can_manage_operator_structure() and public.can_access_company(company_id) and public.can_access_operator(operator_id));
create policy operator_company_assignments_update on public.operator_company_assignments
  for update to authenticated
  using (public.can_manage_operator_structure() and public.can_access_company(company_id))
  with check (public.can_manage_operator_structure() and public.can_access_company(company_id) and public.can_access_operator(operator_id));
create policy operator_company_assignments_delete on public.operator_company_assignments
  for delete to authenticated
  using (public.can_manage_operator_structure() and public.can_access_company(company_id));

drop policy if exists shifts_select_admin_staff on public.shifts;
drop policy if exists shifts_insert_admin_staff on public.shifts;
drop policy if exists shifts_update_admin_staff on public.shifts;
drop policy if exists shifts_delete_admin_staff on public.shifts;
create policy shifts_select_admin_staff on public.shifts
  for select to authenticated
  using (public.can_manage_shifts() and public.can_access_company(company_id));
create policy shifts_insert_admin_staff on public.shifts
  for insert to authenticated
  with check (public.can_manage_shifts() and public.can_access_company(company_id));
create policy shifts_update_admin_staff on public.shifts
  for update to authenticated
  using (public.can_manage_shifts() and public.can_access_company(company_id))
  with check (public.can_manage_shifts() and public.can_access_company(company_id));
create policy shifts_delete_admin_staff on public.shifts
  for delete to authenticated
  using (public.can_manage_shifts() and public.can_access_company(company_id));

drop policy if exists operator_staff_links_select on public.operator_staff_links;
drop policy if exists operator_staff_links_insert on public.operator_staff_links;
drop policy if exists operator_staff_links_update on public.operator_staff_links;
drop policy if exists operator_staff_links_delete on public.operator_staff_links;
create policy operator_staff_links_select on public.operator_staff_links
  for select to authenticated
  using (
    (public.can_manage_operator_career() and public.can_access_operator(operator_id))
    or exists (select 1 from public.operator_auth oa where oa.operator_id = operator_staff_links.operator_id and oa.user_id = auth.uid())
  );
create policy operator_staff_links_insert on public.operator_staff_links
  for insert to authenticated
  with check (public.can_manage_operator_career() and public.can_access_operator(operator_id));
create policy operator_staff_links_update on public.operator_staff_links
  for update to authenticated
  using (public.can_manage_operator_career() and public.can_access_operator(operator_id))
  with check (public.can_manage_operator_career() and public.can_access_operator(operator_id));
create policy operator_staff_links_delete on public.operator_staff_links
  for delete to authenticated
  using (public.can_manage_operator_career() and public.can_access_operator(operator_id));

-- 2. «organization_id IS NULL OR …» для роли public (то есть и для anon):
--    строку без организации видел и мог записать любой, даже без входа —
--    например, сообщение в командный чат, видимое всем организациям.
--    Сейчас таких строк 0; закрываем, чтобы они не появились.
do $$
declare
  t text;
begin
  foreach t in array array['goals', 'news_posts', 'payroll_periods', 'reminders', 'supplier_debts', 'team_chat_messages'] loop
    execute format('drop policy if exists %I on public.%I', t || '_tenant', t);
    execute format(
      'create policy %I on public.%I for all to authenticated
         using (organization_id is not null and public.can_access_organization(organization_id))
         with check (organization_id is not null and public.can_access_organization(organization_id))',
      t || '_tenant', t
    );
  end loop;
end $$;

-- 3. Список файлов склада (накладные, фото приёмки) мог получить любой
--    вошедший пользователь любой организации. Загрузка и выдача идут через
--    сервер на service role — листинг из браузера не нужен.
drop policy if exists "inventory-attachments-select-authenticated" on storage.objects;
