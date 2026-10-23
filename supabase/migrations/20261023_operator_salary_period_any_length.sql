-- Период зарплаты операторов стал настройкой организации (organizations.settings →
-- operator_pay_schedule): неделя с любого дня, две недели, половина месяца, месяц, день.
-- operator_salary_weeks.week_start/week_end теперь — начало и конец периода, а check
-- «week_end = week_start + 6» держал только неделю. У F16 (неделя пн–вс) записи прежние.

alter table public.operator_salary_weeks
  drop constraint if exists operator_salary_weeks_dates_check;

alter table public.operator_salary_weeks
  add constraint operator_salary_weeks_dates_check check (week_end >= week_start);

comment on column public.operator_salary_weeks.week_start is
  'Начало периода зарплаты по графику организации (у недели пн–вс — понедельник).';
comment on column public.operator_salary_weeks.week_end is
  'Конец периода зарплаты включительно.';
