-- Детейлинг: быстрые работы по часам. Мойка или химчистка занимает бокс на
-- пару часов, а не на весь день — в тот же бокс в тот же день можно поставить
-- ещё машину.
--
-- duration_minutes у записи: сколько минут она держит бокс, начиная со
-- start_time. Пусто — запись держит бокс целыми днями, как раньше.
-- duration_minutes у услуги: подсказка для новой записи.

alter table public.detailing_bookings
  add column if not exists duration_minutes integer
    check (duration_minutes is null or duration_minutes between 15 and 720);

alter table public.detailing_services
  add column if not exists duration_minutes integer
    check (duration_minutes is null or duration_minutes between 15 and 720);

comment on column public.detailing_bookings.duration_minutes is 'Сколько минут запись держит бокс с start_time; null — целыми днями';
comment on column public.detailing_services.duration_minutes is 'Быстрая услуга: сколько минут занимает; null — занимает дни (days)';

notify pgrst, 'reload schema';
