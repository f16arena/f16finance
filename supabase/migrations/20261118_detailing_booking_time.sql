-- Детейлинг: время записи. К какому часу клиент привезёт машину.
-- Пусто — время не назначали (старые записи и «в течение дня»).

alter table public.detailing_bookings
  add column if not exists start_time time;

comment on column public.detailing_bookings.start_time is 'К какому времени приедет машина; null — время не назначено';

notify pgrst, 'reload schema';
