-- График выплат админ-составу стал настройкой организации (organizations.settings →
-- staff_pay_schedule): две половины месяца (F16) или раз в месяц, в т.ч. «до N-го числа
-- следующего месяца». При втором варианте выплата 5 ноября — за октябрь, и по дате это
-- не понять. Поэтому у выплаты теперь хранится месяц, за который она.
--
-- Старые выплаты: месяц даты выплаты — так их и считал код, суммы не меняются.

alter table public.staff_salary_payments
  add column if not exists period_month date;

comment on column public.staff_salary_payments.period_month is
  'Месяц, за который выплата (1-е число). NULL у старых записей = месяц pay_date.';

update public.staff_salary_payments
set period_month = date_trunc('month', pay_date)::date
where period_month is null;
