import 'server-only'

import { addDays } from '@/lib/server/detailing'

/**
 * Оплата мастеров детейлинга (миграция 20261118_detailing_masters).
 *
 * Начислено за месяц = оклад + бонус + процент × (цена − материалы) по машинам,
 * которые мастер выдал в этом месяце. Что из трёх считать — тумблеры в
 * detailing_master_terms. Мастер без строки условий получает условия по
 * умолчанию: только процент, 30%.
 *
 * Считается только здесь — «Мастера», «Деньги» и «Сегодня» берут одну цифру.
 */

export type MasterTerms = {
  salary_on: boolean
  salary_amount: number
  bonus_on: boolean
  bonus_amount: number
  percent_on: boolean
  percent: number
  note: string | null
}

export const DEFAULT_TERMS: MasterTerms = {
  salary_on: false,
  salary_amount: 0,
  bonus_on: false,
  bonus_amount: 0,
  percent_on: true,
  percent: 30,
  note: null,
}

export type MasterPay = {
  id: string
  name: string
  company_id: string
  terms: MasterTerms
  /** Выдано машин за месяц. */
  jobs: number
  /** Сумма (цена − материалы) по выданным машинам. */
  base: number
  salaryPart: number
  bonusPart: number
  percentPart: number
  accrued: number
  paid: number
  due: number
  payouts: Array<{ id: string; amount: number; method: string; paid_on: string }>
}

/** Казахстан живёт в одном поясе, UTC+5: по нему режем «выдано в этом месяце». */
const KZ_OFFSET = '+05:00'

export function monthStart(value: unknown, fallbackToday: string) {
  const s = typeof value === 'string' && /^\d{4}-\d{2}/.test(value) ? value.slice(0, 7) : fallbackToday.slice(0, 7)
  return `${s}-01`
}

export function nextMonth(month: string) {
  const [y, m] = month.split('-').map(Number)
  return m === 12 ? `${y + 1}-01-01` : `${y}-${String(m + 1).padStart(2, '0')}-01`
}

export function isMissingMastersTable(error: any) {
  const code = String(error?.code || '')
  const message = String(error?.message || '')
  return code === '42P01' || code === 'PGRST205' || (/detailing_master_/.test(message) && /does not exist|schema cache/.test(message))
}

function shapeTerms(row: any): MasterTerms {
  if (!row) return { ...DEFAULT_TERMS }
  return {
    salary_on: !!row.salary_on,
    salary_amount: Number(row.salary_amount || 0),
    bonus_on: !!row.bonus_on,
    bonus_amount: Number(row.bonus_amount || 0),
    percent_on: !!row.percent_on,
    percent: Number(row.percent || 0),
    note: row.note ?? null,
  }
}

/**
 * Начисления мастерам точек за месяц. tolerant=true — если таблиц ещё нет
 * (миграция не применена), считаем по условиям по умолчанию без выплат,
 * чтобы «Деньги» и «Сегодня» не падали.
 */
export async function loadMasterPay(
  supabase: any,
  args: { companyIds: string[]; month: string; tolerant?: boolean },
): Promise<{ masters: MasterPay[]; migrationMissing: boolean }> {
  const { companyIds, month } = args
  if (!companyIds.length) return { masters: [], migrationMissing: false }
  const end = nextMonth(month)

  const [assignRes, jobsRes] = await Promise.all([
    supabase
      .from('operator_company_assignments')
      .select('company_id, is_active, operator:operators(id, name, short_name, is_active)')
      .in('company_id', companyIds)
      .eq('is_active', true),
    supabase
      .from('detailing_bookings')
      .select('id, company_id, master_operator_id, price_total, materials_total, issued_at')
      .in('company_id', companyIds)
      .eq('status', 'done')
      .not('master_operator_id', 'is', null)
      .gte('issued_at', `${month}T00:00:00${KZ_OFFSET}`)
      .lt('issued_at', `${end}T00:00:00${KZ_OFFSET}`)
      .limit(1000),
  ])
  if (assignRes.error) throw assignRes.error
  if (jobsRes.error) throw jobsRes.error

  let migrationMissing = false
  let termRows: any[] = []
  let payoutRows: any[] = []
  const [termsRes, payoutsRes] = await Promise.all([
    supabase.from('detailing_master_terms').select('*').in('company_id', companyIds),
    supabase
      .from('detailing_master_payouts')
      .select('id, company_id, operator_id, amount, method, paid_on')
      .in('company_id', companyIds)
      .eq('period_month', month)
      .order('paid_on'),
  ])
  for (const r of [termsRes, payoutsRes]) {
    if (!r.error) continue
    if (args.tolerant && isMissingMastersTable(r.error)) migrationMissing = true
    else throw r.error
  }
  if (!migrationMissing) {
    termRows = termsRes.data || []
    payoutRows = payoutsRes.data || []
  }

  const key = (operatorId: unknown, companyId: unknown) => `${operatorId}:${companyId}`
  const termsBy = new Map(termRows.map((t) => [key(t.operator_id, t.company_id), t]))

  const masters: MasterPay[] = []
  const seen = new Set<string>()
  for (const row of (assignRes.data || []) as any[]) {
    const o = row.operator
    if (!o || o.is_active === false) continue
    const k = key(o.id, row.company_id)
    if (seen.has(k)) continue
    seen.add(k)

    const terms = shapeTerms(termsBy.get(k))
    const jobs = ((jobsRes.data || []) as any[]).filter((b) => String(b.master_operator_id) === String(o.id) && String(b.company_id) === String(row.company_id))
    const base = jobs.reduce((s, b) => s + Math.max(0, Number(b.price_total || 0) - Number(b.materials_total || 0)), 0)
    const salaryPart = terms.salary_on ? terms.salary_amount : 0
    const bonusPart = terms.bonus_on ? terms.bonus_amount : 0
    const percentPart = terms.percent_on ? Math.round((base * terms.percent) / 100) : 0
    const accrued = salaryPart + bonusPart + percentPart
    const payouts = payoutRows
      .filter((p) => key(p.operator_id, p.company_id) === k)
      .map((p) => ({ id: String(p.id), amount: Number(p.amount || 0), method: String(p.method), paid_on: String(p.paid_on) }))
    const paid = payouts.reduce((s, p) => s + p.amount, 0)

    masters.push({
      id: String(o.id),
      name: String(o.short_name || o.name || 'Без имени'),
      company_id: String(row.company_id),
      terms,
      jobs: jobs.length,
      base,
      salaryPart,
      bonusPart,
      percentPart,
      accrued,
      paid,
      due: Math.max(0, accrued - paid),
      payouts,
    })
  }
  masters.sort((a, b) => a.name.localeCompare(b.name, 'ru'))
  return { masters, migrationMissing }
}

/** Расходы-выплаты мастерам за период: их в «Деньгах» показываем строкой «Мастерам», а не в «Расходах». */
export async function loadPayoutExpenseIds(supabase: any, args: { companyIds: string[]; from: string; to: string }) {
  if (!args.companyIds.length) return new Set<string>()
  const { data, error } = await supabase
    .from('detailing_master_payouts')
    .select('expense_id')
    .in('company_id', args.companyIds)
    .gte('paid_on', args.from)
    .lt('paid_on', addDays(args.to, 1))
    .not('expense_id', 'is', null)
    .limit(1000)
  if (error) {
    if (isMissingMastersTable(error)) return new Set<string>()
    throw error
  }
  return new Set(((data || []) as any[]).map((r) => String(r.expense_id)))
}
