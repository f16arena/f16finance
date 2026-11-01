/**
 * AI tool: посмотреть расходы за период с разбивкой по категориям.
 * Capability: expenses.view
 */

import type { CopilotTool } from '../../types'
import { companyOptions, scopedCompanyIds, fetchAllPages, resolveDateRange, PERIOD_OPTIONS } from '../../query-helpers'
import { COUNTED_EXPENSE_FILTER } from '@/lib/domain/expense-status'

export const queryExpensesTool: CopilotTool = {
  name: 'query_expenses',
  category: 'analytics',
  description: 'Посчитать расходы за период с разбивкой по категориям',
  requiredCapability: 'expenses.view',
  severity: 'low',
  params: [
    {
      name: 'period',
      label: 'Период',
      type: 'select',
      required: false,
      description: 'Готовый период. ИЛИ используй точные даты from/to для конкретного диапазона.',
      getOptions: async () => PERIOD_OPTIONS,
    },
    { name: 'from', label: 'С даты', type: 'string', required: false, description: 'Начало периода YYYY-MM-DD (для произвольного диапазона, напр. "за 15-21 июня").' },
    { name: 'to', label: 'По дату', type: 'string', required: false, description: 'Конец периода YYYY-MM-DD.' },
    {
      name: 'company_id',
      label: 'Точка',
      type: 'select',
      required: false,
      description: 'Фильтр по точке',
      getOptions: async (ctx) => companyOptions(ctx, { allLabel: '📍 Все точки' }),
    },
  ],
  handler: async (input, ctx) => {
    const companyId = String(input.company_id || '')
    // Приоритет — точные даты from/to (для запросов вида «за 15-21 июня»).
    const range = resolveDateRange(input)
    const from = range.from as string
    const to = range.to as string

    // Скоуп организации считаем всегда: указанная точка не должна его отменять,
    // иначе чужой company_id отдаёт расходы другого клиента.
    const ids = await scopedCompanyIds(ctx)
    if (companyId && ids && !ids.includes(companyId)) {
      return { ok: false, message: 'Точка не найдена.' }
    }
    let rows: any[]
    try {
      rows = await fetchAllPages((rFrom, rTo) => {
        let query = ctx.supabase
          .from('expenses')
          .select('cash_amount, kaspi_amount, category')
          .gte('date', from)
          .lte('date', to)
          .or(COUNTED_EXPENSE_FILTER)
          .order('date', { ascending: true })
          .order('id', { ascending: true })
          .range(rFrom, rTo)
        if (companyId) query = query.eq('company_id', companyId)
        else if (ids) query = query.in('company_id', ids)
        return query
      })
    } catch (e: any) {
      return { ok: false, message: `Ошибка: ${e?.message || 'unknown'}` }
    }
    let total = 0
    const byCategory = new Map<string, number>()
    for (const r of rows) {
      const sum = Number(r.cash_amount || 0) + Number(r.kaspi_amount || 0)
      total += sum
      const cat = String(r.category || 'Без категории')
      byCategory.set(cat, (byCategory.get(cat) || 0) + sum)
    }

    const sorted = Array.from(byCategory.entries()).sort((a, b) => b[1] - a[1])
    const top = sorted.slice(0, 8)
    const fmt = (n: number) => Math.round(n).toLocaleString('ru-RU') + ' ₸'

    const lines = [`💸 Расходы за ${range.label}:`, `Итого: ${fmt(total)}`]
    if (!rows.length) lines.push('За этот период расходов не внесено.')
    else lines.push('', 'Топ категорий:')
    for (const [cat, sum] of top) {
      const pct = total > 0 ? ((sum / total) * 100).toFixed(1) : '0'
      lines.push(`  • ${cat}: ${fmt(sum)} (${pct}%)`)
    }
    if (sorted.length > top.length) lines.push(`  ... и ещё ${sorted.length - top.length}`)

    return { ok: true, message: lines.join('\n'), data: { from, to, total, count: rows.length, byCategory: Object.fromEntries(sorted) } }
  },
}
