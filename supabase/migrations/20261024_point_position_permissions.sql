-- Должность на точке перестаёт быть «как одна из трёх ролей».
--
-- permissions — что можно этой должности (lib/core/point-permissions.ts):
--   schedule_requests, point_tasks, shift_request_alerts.
--   null — как раньше: по base_role (старший — всё, оператор — ничего).
-- shift_bonus null — доплата не своя, а бонус старшего из правил зарплаты
--   (по base_role). Число — своя доплата, она заменяет бонус старшего.
alter table public.point_positions add column if not exists permissions text[];
alter table public.point_positions alter column shift_bonus drop not null;

comment on column public.point_positions.permissions is
  'Права на точке (schedule_requests, point_tasks, shift_request_alerts); null — по base_role';
comment on column public.point_positions.shift_bonus is
  'Доплата за смену; null — бонус старшего из правил зарплаты по base_role';
comment on column public.point_positions.base_role is
  'Какой бонус старшего брать из правил зарплаты, если shift_bonus null; роль назначения';

-- Три прежние роли — обычные должности организации: их можно переименовать,
-- поменять права и доплату. Доплата null = те же деньги, что и сейчас.
insert into public.point_positions (organization_id, company_id, name, base_role, shift_bonus, permissions, sort_order)
select o.id, null, t.name, t.base_role, null, t.permissions, t.sort_order
from public.organizations o
cross join (values
  ('Оператор', 'operator', '{}'::text[], 0),
  ('Старший оператор', 'senior_operator', '{schedule_requests,point_tasks,shift_request_alerts}'::text[], 1),
  ('Старший кассир', 'senior_cashier', '{schedule_requests,point_tasks,shift_request_alerts}'::text[], 2)
) as t(name, base_role, permissions, sort_order)
where exists (
    select 1 from public.operator_company_assignments a
    join public.companies c on c.id = a.company_id
    where c.organization_id = o.id
  )
  and not exists (
    select 1 from public.point_positions p
    where p.organization_id = o.id and p.company_id is null and p.is_active
      and lower(btrim(p.name)) = lower(t.name)
  );

-- Люди без своей должности получают готовую по своей роли. Деньги не меняются:
-- у готовых доплата null, расчёт берёт бонус старшего из правил, как и раньше.
update public.operator_company_assignments a
set position_id = p.id
from public.companies c, public.point_positions p
where a.company_id = c.id
  and a.position_id is null
  and p.organization_id = c.organization_id
  and p.company_id is null
  and p.is_active
  and p.shift_bonus is null
  and p.base_role = a.role_in_company
  and p.name in ('Оператор', 'Старший оператор', 'Старший кассир');
