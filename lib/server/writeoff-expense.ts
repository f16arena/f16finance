import 'server-only'

import { kzTodayISO } from '@/lib/server/forecast-inputs'

type AnySupabase = any

/**
 * Автозапись убытка в расходы после списания: сумма по себестоимости, категория
 * «Списание товаров» (или своя категория со «списан» в названии), компания
 * точки списания. При отмене списания расход удаляется по маркеру
 * [auto-writeoff:id] в комментарии. Общая для сайта и копилота.
 */
export async function createWriteoffAutoExpense(
  supabase: AnySupabase,
  writeoffId: string,
  scope: { organizationId: string | null; isSuperAdmin: boolean },
) {
  if (!writeoffId) return
  const { data: woRow } = await supabase
    .from('inventory_writeoffs')
    .select('total_amount, written_at, reason, location:location_id(company_id)')
    .eq('id', writeoffId)
    .maybeSingle()
  const loc = Array.isArray((woRow as any)?.location) ? (woRow as any).location[0] : (woRow as any)?.location
  const total = Number((woRow as any)?.total_amount || 0)
  if (!woRow || !loc?.company_id || total <= 0) return

  const writtenDate = String((woRow as any).written_at || '').slice(0, 10)
  let categoryName = 'Списание товаров'
  let catQuery = supabase.from('expense_categories').select('name').ilike('name', '%списан%').limit(1)
  // NEVER-pattern: не-супер без орг → только глобальные категории (org IS NULL),
  // а не справочник чужого арендатора.
  if (scope.organizationId) catQuery = catQuery.or(`organization_id.is.null,organization_id.eq.${scope.organizationId}`)
  else if (!scope.isSuperAdmin) catQuery = catQuery.is('organization_id', null)
  const { data: catRows } = await catQuery
  if (catRows?.[0]?.name) categoryName = String(catRows[0].name)

  const { error } = await supabase.from('expenses').insert([{
    // Фолбэк «сегодня» — по Казахстану (сервер в UTC)
    date: writtenDate || kzTodayISO(),
    company_id: loc.company_id,
    operator_id: null,
    category: categoryName,
    // Неденежный убыток: кладём в безнал, чтобы не искажать сверку
    // физической наличной кассы (реальных денег из ящика не уходило)
    cash_amount: 0,
    kaspi_amount: total,
    comment: `Автоматически: списание со склада по себестоимости. Причина: ${(woRow as any).reason || '—'} [auto-writeoff:${writeoffId}]`,
    status: 'confirmed',
  }])
  if (error) throw error
}
