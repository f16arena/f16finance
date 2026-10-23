-- «Варианты по выручке» (point_rules) получают срок действия.
--
-- Раньше правка варианта меняла строку на месте, и зарплата пересчитывалась
-- за все недели, включая выплаченные. Теперь правка закрывает старую строку
-- (effective_to = воскресенье прошлой недели) и создаёт новую с понедельника
-- текущей недели. Расчёт смены берёт только строки, действовавшие в её дату.
--
-- Старые строки: обе даты пустые = действовали всегда, как и раньше.

alter table public.point_rules
  add column if not exists effective_from date,
  add column if not exists effective_to date,
  add column if not exists replaced_by uuid references public.point_rules(id) on delete set null;

comment on column public.point_rules.effective_from is
  'С какой даты действует строка правила. NULL — с самого начала.';
comment on column public.point_rules.effective_to is
  'По какую дату включительно действует строка. NULL — действует сейчас. Закрытые строки в списке правил не показываются, но считают прошлые недели.';
comment on column public.point_rules.replaced_by is
  'Строка, которая заменила эту при правке.';

create index if not exists idx_point_rules_current
  on public.point_rules(scope, event, priority)
  where effective_to is null;
