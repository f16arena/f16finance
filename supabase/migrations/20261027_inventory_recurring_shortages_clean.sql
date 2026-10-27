-- Алерт «регулярная недостача» подсвечивал «воровство» по ложным данным:
--   * архивные ревизии (archived_at) и откатанные акты аудита (status = 'cancelled')
--     — их результат отменён, недостачи нет;
--   * позиции, обнулённые галочкой «Обнулить непосчитанное» — их просто не
--     посчитали, товар физически был и потом продавался.
-- Остальное как было.

create or replace function public.inventory_recurring_shortages(p_days integer default 30, p_min_count integer default 3)
returns table(item_id uuid, item_name text, item_barcode text, shortage_count integer, total_shortage_qty numeric, total_shortage_cost numeric, last_shortage_at timestamp with time zone, affected_locations text[])
language sql
stable
set search_path to 'public', 'pg_temp'
as $function$
  with shortages as (
    select
      isi.item_id,
      isi.delta_qty,
      isi.expected_qty,
      isi.actual_qty,
      ist.counted_at,
      ist.location_id,
      il.name as location_name
    from public.inventory_stocktake_items isi
    join public.inventory_stocktakes ist on ist.id = isi.stocktake_id
    left join public.inventory_locations il on il.id = ist.location_id
    where ist.counted_at >= current_date - p_days
      and isi.delta_qty < 0
      and ist.archived_at is null
      and coalesce(isi.comment, '') not like 'Полная ревизия: не посчитано%'
      and not exists (
        select 1 from public.inventory_audit_acts a
        where a.stocktake_id = ist.id and a.status = 'cancelled'
      )
  )
  select
    s.item_id,
    coalesce(ii.name, '?') as item_name,
    coalesce(ii.barcode, '') as item_barcode,
    count(*)::integer as shortage_count,
    sum(abs(s.delta_qty))::numeric as total_shortage_qty,
    sum(abs(s.delta_qty) * coalesce(ii.default_purchase_price, 0))::numeric as total_shortage_cost,
    max(s.counted_at::timestamptz) as last_shortage_at,
    array_agg(distinct s.location_name order by s.location_name) as affected_locations
  from shortages s
  left join public.inventory_items ii on ii.id = s.item_id
  group by s.item_id, ii.name, ii.barcode, ii.default_purchase_price
  having count(*) >= p_min_count
  order by count(*) desc, sum(abs(s.delta_qty)) desc;
$function$;
