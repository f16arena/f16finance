/**
 * Точка-«экстра» — отдельная касса, которую финансовые отчёты по умолчанию
 * не складывают в общие итоги (переключатель «включить в итоги»).
 *
 * Признак — настройка точки `companies.exclude_from_totals`
 * (миграция 20261023_companies_exclude_from_totals). Пока запрос её не выбрал,
 * действует прежнее правило F16: код `extra` или точное имя `F16 Extra`.
 * Касса (арена, отчёт смены) флаг намеренно не выбирает: там «Extra» значит
 * «доход зала — одной суммой при закрытии смены», а не «вне итогов».
 * Раньше /reports искал подстроку «extra» в названии — у SaaS-клиента точка
 * «Extra Store» молча выпадала из выручки.
 */
export function isExtraCompany(company: {
  code?: string | null
  name?: string | null
  exclude_from_totals?: boolean | null
}): boolean {
  if (typeof company.exclude_from_totals === 'boolean') return company.exclude_from_totals
  return String(company.code || '').toLowerCase() === 'extra' || company.name === 'F16 Extra'
}
