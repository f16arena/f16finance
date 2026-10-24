-- Зоны выручки точки (PS5, VR, бильярд…): список [{key, label, active}].
-- null — как раньше: PS-зал получает PS5 + VR, остальные вносят одной суммой.
-- Ключ зоны пишется в incomes.zone; выключенная зона пропадает из формы
-- прихода, но её история остаётся в отчётах (lib/core/revenue-zones.ts).
alter table public.companies add column if not exists revenue_zones jsonb;

comment on column public.companies.revenue_zones is
  'Зоны выручки точки [{key,label,active}]; null — по нише (lib/core/revenue-zones.ts)';

-- F16 Extra: VR-зону закрыли (последняя выручка 11.01.2026) — в форме только PS5.
update public.companies
set revenue_zones = '[{"key":"ps5","label":"PS5","active":true},{"key":"vr","label":"VR","active":false}]'::jsonb
where code = 'extra' and revenue_zones is null;
