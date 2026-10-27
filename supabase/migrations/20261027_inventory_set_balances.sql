-- «Установить остаток» (склад, витрина, Excel-импорт) писал абсолютное число
-- upsert'ом поверх строки, а движение считал по остатку, прочитанному ДО этого.
-- Продажа или долг между чтением и записью терялись: списание затиралось,
-- в истории движений расходилось с балансом.
--
-- Здесь всё одним шагом: строка блокируется (как в inventory_apply_balance_delta),
-- новое значение и дельта-движение пишутся в той же транзакции.
-- Строки блокируем в порядке item_id — две параллельные пачки не встанут в deadlock.

create or replace function public.inventory_set_balances(
  p_location_id uuid,
  p_items jsonb,                 -- [{item_id, quantity, unit_cost?}]
  p_movement_type text,
  p_reference_type text,
  p_comment text,
  p_actor_user_id uuid default null,
  p_comment_with_change boolean default false  -- дописать «было → стало» в комментарий
)
returns table (item_id uuid, before_qty numeric, after_qty numeric)
language plpgsql
set search_path to 'public'
as $$
#variable_conflict use_column
declare
  v_row record;
  v_before numeric;
  v_delta numeric;
begin
  for v_row in
    select (x->>'item_id')::uuid as item_id,
           round((x->>'quantity')::numeric, 3) as quantity,
           nullif(x->>'unit_cost', '')::numeric as unit_cost
    from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) x
    order by (x->>'item_id')::uuid
  loop
    if v_row.quantity is null or v_row.quantity < 0 then
      raise exception 'inventory-set-quantity-invalid';
    end if;

    insert into public.inventory_balances (location_id, item_id, quantity)
    values (p_location_id, v_row.item_id, 0)
    on conflict (location_id, item_id) do nothing;

    select b.quantity into v_before
    from public.inventory_balances b
    where b.location_id = p_location_id and b.item_id = v_row.item_id
    for update;

    v_before := coalesce(v_before, 0);
    v_delta := v_row.quantity - v_before;

    if abs(v_delta) > 0.0005 then
      update public.inventory_balances b
      set quantity = v_row.quantity,
          updated_at = timezone('utc', now())
      where b.location_id = p_location_id and b.item_id = v_row.item_id;

      insert into public.inventory_movements (
        item_id, movement_type, quantity, unit_cost,
        from_location_id, to_location_id,
        reference_type, reference_id, comment, actor_user_id, created_at
      ) values (
        v_row.item_id, p_movement_type, abs(v_delta), v_row.unit_cost,
        case when v_delta < 0 then p_location_id end,
        case when v_delta > 0 then p_location_id end,
        p_reference_type, null,
        case when p_comment_with_change
          then coalesce(p_comment, '') || ': ' || v_before::text || ' → ' || v_row.quantity::text
          else p_comment end,
        p_actor_user_id, timezone('utc', now())
      );
    end if;

    inventory_set_balances.item_id := v_row.item_id;
    inventory_set_balances.before_qty := v_before;
    inventory_set_balances.after_qty := v_row.quantity;
    return next;
  end loop;
end;
$$;

revoke all on function public.inventory_set_balances(uuid, jsonb, text, text, text, uuid, boolean) from public, anon, authenticated;
grant execute on function public.inventory_set_balances(uuid, jsonb, text, text, text, uuid, boolean) to service_role;
