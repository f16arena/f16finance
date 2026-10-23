import 'server-only'

/**
 * История точки, из-за которой её нельзя удалять.
 *
 * У companies 55 внешних ключей с ON DELETE CASCADE: удаление точки молча
 * стирает доходы, продажи, возвраты, брони, клиентов, сессии арены. Расходы
 * ключа нет вовсе — они остались бы «ничьими». Поэтому удаляется только
 * пустая точка, а всё, у чего есть прошлое, — нет.
 */
const HISTORY_TABLES: Array<{ table: string; label: string }> = [
  { table: 'incomes', label: 'доходов' },
  { table: 'expenses', label: 'расходов' },
  { table: 'point_sales', label: 'продаж' },
  { table: 'point_returns', label: 'возвратов' },
  { table: 'point_shifts', label: 'смен кассы' },
  { table: 'operator_shifts', label: 'смен операторов' },
  { table: 'shifts', label: 'смен в графике' },
  { table: 'debts', label: 'долгов' },
  { table: 'point_debt_items', label: 'долгов точки' },
  { table: 'arena_sessions', label: 'игровых сессий' },
  { table: 'client_bookings', label: 'броней' },
  { table: 'customers', label: 'клиентов' },
  { table: 'inventory_items', label: 'товаров' },
]

export type CompanyHistoryItem = { label: string; count: number }

export async function countCompanyHistory(supabase: any, companyId: string) {
  const results = await Promise.all(
    HISTORY_TABLES.map(async ({ table, label }) => {
      const { count, error } = await supabase.from(table).select('*', { count: 'exact', head: true }).eq('company_id', companyId)
      // Не смогли посчитать — считаем, что история есть: лучше не дать удалить, чем стереть.
      if (error) return { label, count: -1 }
      return { label, count: count ?? 0 }
    }),
  )
  const items: CompanyHistoryItem[] = results.filter((item) => item.count !== 0)
  return { items, hasHistory: items.length > 0 }
}

export function describeCompanyHistory(items: CompanyHistoryItem[]) {
  return items
    .map((item) => (item.count < 0 ? `${item.label}: не удалось проверить` : `${item.count.toLocaleString('ru-RU')} ${item.label}`))
    .join(', ')
}

export { companyNameMatches } from '@/lib/core/company-code'
