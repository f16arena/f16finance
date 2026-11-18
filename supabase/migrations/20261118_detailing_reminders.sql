-- Детейлинг: напоминания «пора обновить».
--
-- У услуги — через сколько месяцев клиенту стоит напомнить (керамика — 12).
-- 0 или пусто — не напоминать. У строки услуги в записи — когда клиенту уже
-- написали, чтобы он не висел в списке «пора напомнить» вечно.

alter table public.detailing_services
  add column if not exists remind_months integer check (remind_months is null or remind_months between 0 and 60);

alter table public.detailing_booking_services
  add column if not exists reminded_at timestamptz;

comment on column public.detailing_services.remind_months is 'Через сколько месяцев после работы напомнить клиенту; null/0 — не напоминать';
comment on column public.detailing_booking_services.reminded_at is 'Когда клиенту написали «пора обновить» по этой работе';

notify pgrst, 'reload schema';
