import { writeSystemErrorLogSafe } from '@/lib/server/audit'
import { COUNTED_EXPENSE_FILTER } from '@/lib/domain/expense-status'
import {
  addDays,
  detailingContext,
  isIsoDate,
  isMissingDetailingTable,
  json,
} from '@/lib/server/detailing'

/**
 * Деньги детейлинга за период: для дашборда, «Доходов» и «Расходов» ниши.
 *
 * Доход — оплаты за машины (detailing_payments) плюс ручные строки журнала
 * доходов этих точек. Расход — общий журнал расходов. Долги клиентов —
 * выданные машины, оплаченные не полностью, за всё время.
 * Точка: company_id или все детейлинги, доступные сотруднику.
 */

const BOOKING_FIELDS =
  'id, company_id, box_id, start_date, days, status, price_total, materials_total, master_operator_id, ' +
  'customer:customers(id, name, phone), car:customer_cars(plate, model), ' +
  'services:detailing_booking_services(name, price, sort_order), payments:detailing_payments(amount)'

function paidOf(b: any) {
  return ((b.payments || []) as any[]).reduce((s, p) => s + Number(p.amount || 0), 0)
}

function shape(b: any) {
  const paid = paidOf(b)
  return {
    id: b.id,
    company_id: b.company_id,
    box_id: b.box_id,
    start_date: b.start_date,
    days: Number(b.days),
    status: b.status,
    price_total: Number(b.price_total || 0),
    materials_total: Number(b.materials_total || 0),
    master_operator_id: b.master_operator_id,
    customer: b.customer,
    car: b.car,
    services: ((b.services || []) as any[]).sort((x, y) => x.sort_order - y.sort_order).map((s) => ({ name: s.name, price: Number(s.price || 0) })),
    paid,
    due: Math.max(0, Number(b.price_total || 0) - paid),
  }
}

export async function GET(request: Request) {
  try {
    const ctx = await detailingContext(request, ['detailing-bookings.view', 'income.view', 'expenses.view'])
    if ('response' in ctx) return ctx.response
    const url = new URL(request.url)
    const requested = String(url.searchParams.get('company_id') || '').trim()
    const companies = requested ? ctx.companies.filter((c) => c.id === requested) : ctx.companies
    if (!companies.length) return json({ ok: true, data: { companies: ctx.companies, empty: true } })
    const ids = companies.map((c) => c.id)

    const today = isIsoDate(url.searchParams.get('today')) ? String(url.searchParams.get('today')) : new Date().toISOString().slice(0, 10)
    const from = isIsoDate(url.searchParams.get('from')) ? String(url.searchParams.get('from')) : `${today.slice(0, 7)}-01`
    const to = isIsoDate(url.searchParams.get('to')) ? String(url.searchParams.get('to')) : today
    const toExcl = addDays(to, 1)

    const [payRes, incRes, expRes, rangeRes, debtRes, nowRes, boxRes, mastersRes] = await Promise.all([
      ctx.supabase
        .from('detailing_payments')
        .select('id, booking_id, company_id, paid_on, amount, method, kind, booking:detailing_bookings(' + BOOKING_FIELDS + ')')
        .in('company_id', ids)
        .gte('paid_on', from)
        .lt('paid_on', toExcl)
        .order('paid_on', { ascending: false })
        .limit(1000),
      // Ручные доходы этих точек (не из записи): ароматизаторы, сертификаты.
      ctx.supabase
        .from('incomes')
        .select('id, date, company_id, cash_amount, kaspi_amount, card_amount, online_amount, comment, source')
        .in('company_id', ids)
        .gte('date', from)
        .lt('date', toExcl)
        .or('source.is.null,source.neq.detailing')
        .limit(1000),
      ctx.supabase
        .from('expenses')
        .select('id, date, company_id, category, cash_amount, kaspi_amount, comment, status')
        .in('company_id', ids)
        .gte('date', from)
        .lt('date', toExcl)
        .or(COUNTED_EXPENSE_FILTER)
        .order('date', { ascending: false })
        .limit(1000),
      // Записи, начавшиеся в периоде: средний чек, услуги, загрузка, материалы.
      ctx.supabase
        .from('detailing_bookings')
        .select(BOOKING_FIELDS)
        .in('company_id', ids)
        .gte('start_date', addDays(from, -60))
        .lt('start_date', toExcl)
        .not('status', 'in', '(cancelled,requested)')
        .limit(1000),
      ctx.supabase
        .from('detailing_bookings')
        .select(BOOKING_FIELDS)
        .in('company_id', ids)
        .eq('status', 'done')
        .limit(1000),
      // Что происходит сейчас: машины в боксах, кто приедет и кого выдавать сегодня, заявки.
      ctx.supabase
        .from('detailing_bookings')
        .select(BOOKING_FIELDS)
        .in('company_id', ids)
        .gte('start_date', addDays(today, -60))
        .lte('start_date', addDays(today, 7))
        .not('status', 'in', '(cancelled)')
        .limit(1000),
      ctx.supabase.from('detailing_boxes').select('id, name, company_id').in('company_id', ids).eq('is_active', true),
      ctx.supabase.from('operator_company_assignments').select('operator:operators(id, name, short_name)').in('company_id', ids),
    ])
    for (const r of [payRes, incRes, expRes, rangeRes, debtRes, nowRes, boxRes, mastersRes]) if (r.error) throw r.error

    const masterName = new Map<string, string>()
    for (const row of (mastersRes.data || []) as any[]) {
      if (row.operator) masterName.set(String(row.operator.id), String(row.operator.short_name || row.operator.name || ''))
    }

    const payments = ((payRes.data || []) as any[]).map((p) => {
      const b = p.booking ? shape(p.booking) : null
      return {
        id: p.id,
        paid_on: p.paid_on,
        amount: Number(p.amount || 0),
        method: p.method as 'cash' | 'kaspi' | 'card',
        kind: p.kind as 'prepayment' | 'payment',
        booking: b ? { ...b, master: b.master_operator_id ? masterName.get(String(b.master_operator_id)) || null : null } : null,
      }
    })

    const otherIncome = ((incRes.data || []) as any[]).map((r) => ({
      id: r.id,
      date: r.date,
      comment: r.comment,
      cash: Number(r.cash_amount || 0),
      kaspi: Number(r.kaspi_amount || 0) + Number(r.online_amount || 0),
      card: Number(r.card_amount || 0),
    }))

    const expenses = ((expRes.data || []) as any[]).map((e) => ({
      id: e.id,
      date: e.date,
      category: e.category || 'Без категории',
      comment: e.comment,
      cash: Number(e.cash_amount || 0),
      cashless: Number(e.kaspi_amount || 0),
      amount: Number(e.cash_amount || 0) + Number(e.kaspi_amount || 0),
    }))

    // Записи, которые пересекают период: загрузка боксов и материалы по дням работы.
    const inRange = ((rangeRes.data || []) as any[])
      .map(shape)
      .filter((b) => addDays(b.start_date, b.days) > from)
    const startedInRange = inRange.filter((b) => b.start_date >= from)

    const periodDays = Math.max(1, Math.round((Date.parse(`${toExcl}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000))
    let boxDaysBusy = 0
    for (const b of inRange) {
      if (b.status === 'no_show') continue
      const start = b.start_date > from ? b.start_date : from
      const end = addDays(b.start_date, b.days) < toExcl ? addDays(b.start_date, b.days) : toExcl
      boxDaysBusy += Math.max(0, Math.round((Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86_400_000))
    }
    const boxes = (boxRes.data || []) as any[]

    const debts = ((debtRes.data || []) as any[])
      .map(shape)
      .filter((b) => b.due > 0.009)
      .sort((a, b) => b.due - a.due)

    const now = ((nowRes.data || []) as any[]).map(shape).map((b) => ({ ...b, master: b.master_operator_id ? masterName.get(String(b.master_operator_id)) || null : null }))
    const lastDay = (b: { start_date: string; days: number }) => addDays(b.start_date, b.days - 1)
    const live = {
      inBox: now.filter((b) => b.status === 'in_box'),
      arriving: now.filter((b) => b.status === 'booked' && b.start_date === today),
      handOver: now.filter((b) => (b.status === 'in_box' || b.status === 'booked') && lastDay(b) === today),
      overdue: now.filter((b) => b.status === 'in_box' && lastDay(b) < today),
      requests: now.filter((b) => b.status === 'requested'),
      upcoming: now.filter((b) => b.status === 'booked' && b.start_date > today).sort((a, b) => a.start_date.localeCompare(b.start_date)).slice(0, 8),
    }

    return json({
      ok: true,
      data: {
        companies: ctx.companies,
        scope: companies.map((c) => c.id),
        from,
        to,
        today,
        payments,
        otherIncome,
        expenses,
        bookings: startedInRange.map((b) => ({ ...b, master: b.master_operator_id ? masterName.get(String(b.master_operator_id)) || null : null })),
        materialsUsed: startedInRange.filter((b) => b.status === 'done' || b.status === 'in_box').reduce((s, b) => s + b.materials_total, 0),
        load: { boxDaysBusy, boxDaysTotal: boxes.length * periodDays, boxes: boxes.length },
        debts,
        live,
      },
    })
  } catch (error: any) {
    if (isMissingDetailingTable(error)) return json({ error: 'Детейлинг ещё не включён в базе — нужна миграция 20261029_detailing_core' }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-money:get', message: error?.message || 'Detailing money GET error' })
    return json({ error: 'Не удалось загрузить цифры' }, 500)
  }
}
