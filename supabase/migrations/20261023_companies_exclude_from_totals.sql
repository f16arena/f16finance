-- «F16 Extra» был зашит в код: точку не складывали в общие итоги по коду `extra` или имени
-- «F16 Extra» (~30 мест). Теперь это настройка точки — любая организация может отметить
-- отдельную кассу, которая в итогах сети только по галочке «включить».
--
-- F16: флаг включается у точки с кодом extra — поведение отчётов не меняется.

alter table public.companies
  add column if not exists exclude_from_totals boolean not null default false;

comment on column public.companies.exclude_from_totals is
  'Не складывать в общие итоги отчётов по умолчанию (галочка «включить» на страницах). Было: код extra / «F16 Extra».';

update public.companies
set exclude_from_totals = true
where (lower(code) = 'extra' or name = 'F16 Extra')
  and exclude_from_totals = false;
