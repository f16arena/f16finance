-- Роли ниши «Детейлинг» в organization_members.
--
-- «Команда» детейлинга пишет в organization_members роль человека
-- (detailing_admin / detailing_accountant / detailing_master), а проверка
-- роли знала только клубные. Из-за этого добавить человека в «Команде» было
-- нельзя: вставка падала, сотрудник откатывался.

alter table public.organization_members drop constraint if exists organization_members_role_check;
alter table public.organization_members
  add constraint organization_members_role_check
  check (role in ('owner', 'manager', 'marketer', 'other', 'detailing_admin', 'detailing_accountant', 'detailing_master'));
