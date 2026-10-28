-- Кем поставлена смена: ключ открытого API или человек (null). API меняет и
-- удаляет только смены своего ключа; ручные смены менеджера для него закрыты.
-- Правка смены на сайте или в Telegram-боте обнуляет метку — смена становится ручной.
alter table public.shifts
  add column if not exists api_key_id uuid null references public.api_keys(id) on delete set null;
