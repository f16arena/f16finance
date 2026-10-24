-- Права кассы у должностей на точке (lib/core/point-permissions.ts):
--   kassa_refunds, kassa_big_discount, kassa_free_price, kassa_correct_payment,
--   kassa_delete_debts.
-- До галочек касса разрешала всё всем — поэтому всем уже заведённым должностям
-- права кассы добавляются: у людей ничего не пропадает. Кроме kassa_delete_debts:
-- чужой долг оператора удалить было нельзя, это право владелец выдаёт сам.
-- Снять право — убрать галочку на странице «Должности и доступ».
update public.point_positions
set permissions = (
  select array_agg(distinct p order by p)
  from unnest(
    permissions || array['kassa_refunds', 'kassa_big_discount', 'kassa_free_price', 'kassa_correct_payment']
  ) as p
),
updated_at = now()
where permissions is not null;
