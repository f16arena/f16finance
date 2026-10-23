import 'server-only'

import { escapeLikePattern } from '@/lib/core/company-code'

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

/**
 * Что не уходит каскадом вместе с точкой, но без неё теряет смысл.
 *
 * Ставки зарплаты привязаны к КОДУ точки, а не к id: оставшись, они
 * прицепились бы к следующей точке с тем же кодом (так и вышло на пробной
 * «F16 Test»: второй мастер упёрся в «дубликат» и получил чужие ставки).
 * Касса без точек сохраняла рабочий токен.
 * Вызывать до удаления компании — пока связь касс с точкой ещё видна.
 */
export async function collectCompanyLeftovers(supabase: any, company: { id: string; code: string | null }) {
  const { data: links, error } = await supabase
    .from('point_project_companies')
    .select('project_id')
    .eq('company_id', company.id)
  if (error) throw error
  return {
    code: String(company.code || '').trim(),
    projectIds: Array.from(new Set(((links || []) as any[]).map((row) => String(row.project_id || '')).filter(Boolean))),
  }
}

export async function removeCompanyLeftovers(supabase: any, leftovers: { code: string; projectIds: string[] }) {
  if (leftovers.code) {
    const { data: rules, error: rulesError } = await supabase
      .from('operator_salary_rules')
      .select('id')
      .ilike('company_code', escapeLikePattern(leftovers.code))
    if (rulesError) throw rulesError
    const ruleIds = ((rules || []) as any[]).map((row) => row.id)
    if (ruleIds.length) {
      const { error: versionsError } = await supabase.from('operator_salary_rule_versions').delete().in('rule_id', ruleIds)
      if (versionsError) throw versionsError
      const { error: deleteError } = await supabase.from('operator_salary_rules').delete().in('id', ruleIds)
      if (deleteError) throw deleteError
    }
  }

  for (const projectId of leftovers.projectIds) {
    const { count, error: countError } = await supabase
      .from('point_project_companies')
      .select('*', { count: 'exact', head: true })
      .eq('project_id', projectId)
    if (countError) throw countError
    // Касса обслуживала и другие точки — остаётся им.
    if ((count ?? 0) > 0) continue
    const { error: projectError } = await supabase.from('point_projects').delete().eq('id', projectId)
    if (projectError) throw projectError
  }
}

export function describeCompanyHistory(items: CompanyHistoryItem[]) {
  return items
    .map((item) => (item.count < 0 ? `${item.label}: не удалось проверить` : `${item.count.toLocaleString('ru-RU')} ${item.label}`))
    .join(', ')
}

export { companyNameMatches } from '@/lib/core/company-code'
