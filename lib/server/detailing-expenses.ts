import 'server-only'

/**
 * Расходы детейлинга. Владелец здесь сам закупщик и бухгалтер, поэтому расход
 * пишется в два нажатия: сумма и на что. Клубный мастер расходов (чек, поставщик,
 * комментарий от 20 знаков) защищает владельца от чужих трат — тут он лишний.
 *
 * Строка ложится в тот же журнал `expenses`, статьи — общие статьи организации.
 */

/** Статьи, которые предлагаются сразу. Нужная создаётся при первом расходе. */
export const DETAILING_EXPENSE_CATEGORIES = ['Химия', 'Плёнка', 'Материалы', 'Аренда', 'Свет, вода', 'Зарплата', 'Реклама', 'Другое']

async function ownCategories(supabase: any, orgId: string) {
  const { data, error } = await supabase.from('expense_categories').select('name').eq('organization_id', orgId).order('name').limit(300)
  if (error) throw error
  return ((data || []) as any[]).map((r) => String(r.name || '')).filter(Boolean)
}

/** Имя статьи организации; нет такой — заводим. Возвращает имя, как оно хранится в expenses.category. */
export async function ensureExpenseCategory(supabase: any, orgId: string, wanted: string) {
  const name = String(wanted || '').trim().slice(0, 60) || 'Другое'
  const found = (await ownCategories(supabase, orgId)).find((n) => n.toLowerCase() === name.toLowerCase())
  if (found) return found
  const { error } = await supabase.from('expense_categories').insert({ name, organization_id: orgId, accounting_group: 'operating' })
  if (error) throw error
  return name
}

/** Статьи для кнопок в окне расхода: стандартные, потом свои, которых среди стандартных нет. */
export async function listExpenseCategories(supabase: any, orgId: string) {
  const own = await ownCategories(supabase, orgId)
  const standard = new Set(DETAILING_EXPENSE_CATEGORIES.map((n) => n.toLowerCase()))
  return [...DETAILING_EXPENSE_CATEGORIES, ...own.filter((n) => !standard.has(n.toLowerCase()))]
}
