/**
 * AI tool: налог на упрощёнке за полугодие (форма 910.00).
 * Ставка — из налоговых настроек организации (/tax), как на странице.
 * Capability: tax.view
 */

import type { CopilotTool } from '../../types'
import { scopedCompanyIds, fetchAllPages } from '../../query-helpers'
import { DEFAULT_TAX_RATE, nextTaxPayment, previousTaxHalfYear, taxHalfYearOf } from '@/lib/domain/tax'
import { getOrgSettings } from '@/lib/server/org-settings'

function todayISO(): string {
  // Сегодня по Казахстану: сервер живёт в UTC.
  return new Date(Date.now() + 5 * 3600_000).toISOString().slice(0, 10)
}

const reIso = /^\d{4}-\d{2}-\d{2}$/

function ruDate(iso: string) {
  return new Date(`${iso}T00:00:00`).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })
}

export const getTaxSummaryTool: CopilotTool = {
  name: 'get_tax_summary',
  category: 'analytics',
  description: 'Налог упрощёнки (910.00) за полугодие: оборот, сумма по ставке организации, сроки сдачи и оплаты',
  requiredCapability: 'tax.view',
  severity: 'low',
  params: [
    {
      name: 'period',
      label: 'Период',
      type: 'select',
      required: true,
      description: 'За какой период считаем',
      getOptions: async () => [
        { value: 'nextPayment', label: 'Полугодие ближайшего платежа' },
        { value: 'currentHalf', label: 'Текущее полугодие' },
        { value: 'lastHalf', label: 'Прошлое полугодие' },
        { value: 'currentYear', label: 'Текущий год' },
      ],
    },
    { name: 'from', label: 'С даты', type: 'string', required: false, description: 'Начало периода YYYY-MM-DD (произвольный диапазон, имеет приоритет над period).' },
    { name: 'to', label: 'По дату', type: 'string', required: false, description: 'Конец периода YYYY-MM-DD.' },
  ],
  handler: async (input, ctx) => {
    const period = String(input.period || 'nextPayment')
    const today = todayISO()

    const inFrom = String(input.from || '').trim()
    const inTo = String(input.to || '').trim()
    const hasExact = reIso.test(inFrom) && reIso.test(inTo)

    const half =
      period === 'currentHalf'
        ? taxHalfYearOf(today)
        : period === 'lastHalf'
          ? previousTaxHalfYear(taxHalfYearOf(today))
          : nextTaxPayment(today)
    let from = half.from
    let to = half.to < today ? half.to : today
    let label = half.label
    if (hasExact) {
      from = inFrom
      to = inTo
      label = from === to ? from : `${from} — ${to}`
    } else if (period === 'currentYear') {
      from = `${today.slice(0, 4)}-01-01`
      to = today
      label = `${today.slice(0, 4)} год (два полугодия — два платежа)`
    }

    const tax = (await getOrgSettings(ctx.organizationId || null)).tax
    const rate = tax.rate ?? DEFAULT_TAX_RATE

    // Налоговая база — только по компаниям своей организации.
    const ids = await scopedCompanyIds(ctx)
    const data = await fetchAllPages((rFrom, rTo) => {
      let taxQ = ctx.supabase
        .from('incomes')
        .select('cash_amount, kaspi_amount, card_amount, online_amount')
        .gte('date', from)
        .lte('date', to)
        .order('date', { ascending: true })
        .order('id', { ascending: true })
        .range(rFrom, rTo)
      if (ids) taxQ = taxQ.in('company_id', ids)
      return taxQ
    }).catch(() => [] as any[])

    let revenue = 0
    for (const r of (data || []) as any[]) {
      revenue += Number(r.cash_amount || 0) + Number(r.kaspi_amount || 0) + Number(r.card_amount || 0) + Number(r.online_amount || 0)
    }
    const amount = revenue * (rate / 100)

    const fmt = (n: number) => Math.round(n).toLocaleString('ru-RU') + ' ₸'
    const deadlines =
      hasExact || period === 'currentYear'
        ? ''
        : `\n  Форма 910.00 — до ${ruDate(half.declarationDue)}, оплата — до ${ruDate(half.paymentDue)}`
    const rateNote = tax.rate == null ? ' (ставка не выбрана на странице «Налоги» — взята 2%)' : ''

    return {
      ok: true,
      message: `📊 Налог за ${label}:
  Период: ${from} — ${to}
  Оборот: ${fmt(revenue)}
  Налог ${rate}%${rateNote}: ${fmt(amount)}${deadlines}
  Без учёта фильтров «что входит в оборот» со страницы «Налоги».`,
      data: { revenue, tax: amount, rate, from, to },
    }
  },
}
