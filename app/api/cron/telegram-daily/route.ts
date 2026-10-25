import { NextResponse } from 'next/server'
import { splitIncomeKaspiByCalendarDay, type ReportIncomeCalendarRow } from '@/lib/reports/income-calendar-kaspi'
import { requiredEnv } from '@/lib/server/env'
import { listOrgReportTargets } from '@/lib/server/report-targets'
import { createAdminSupabaseClient } from '@/lib/server/supabase'
import { escapeTelegramHtml } from '@/lib/telegram/message-kit'
import { sendTelegramMessage } from '@/lib/telegram/send'
import { COUNTED_EXPENSE_FILTER } from '@/lib/domain/expense-status'
import { resolveFinancialGroup, type FinancialGroup } from '@/lib/core/financial-groups'

export const runtime = 'nodejs'

const KZ_OFFSET = 5 * 3600_000

function yesterdayKZISO() {
  const now = new Date(Date.now() + KZ_OFFSET)
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - 1))
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`
}

function safeNum(v: number | null | undefined) { return Number(v || 0) }

function shiftDateISO(iso: string, days: number) {
  const [y, m, d] = iso.split('-').map(Number)
  const t = new Date(Date.UTC(y, (m || 1) - 1, d || 1) + days * 86_400_000)
  return `${t.getUTCFullYear()}-${String(t.getUTCMonth() + 1).padStart(2, '0')}-${String(t.getUTCDate()).padStart(2, '0')}`
}

function fmtMoney(v: number) {
  const abs = Math.abs(v)
  const sign = v < 0 ? '-' : ''
  if (abs >= 1_000_000) return sign + (abs / 1_000_000).toFixed(1) + ' млн ₸'
  if (abs >= 1_000) return sign + Math.round(abs / 1_000) + ' тыс ₸'
  return Math.round(v).toLocaleString('ru-RU') + ' ₸'
}

// PostgREST режет ответ до 1000 строк — забираем постранично.
const PAGE = 1000
async function fetchAllPages(build: (from: number, to: number) => any): Promise<any[]> {
  const out: any[] = []
  for (let from = 0; ; from += PAGE) {
    const { data, error } = await build(from, from + PAGE - 1)
    if (error) throw error
    const rows = data || []
    out.push(...rows)
    if (rows.length < PAGE) break
  }
  return out
}

function fmtExact(v: number) {
  return Math.round(v).toLocaleString('ru-RU') + ' ₸'
}

// Платежи, которые закрывают период, а не тратятся за день: зарплата за прошлую
// неделю, аренда за месяц, налоги, покупка оборудования. В дневной прибыли они
// дают «минус» в день оплаты и завышение в остальные дни — показываем отдельно.
const PERIOD_GROUPS = new Set<FinancialGroup>([
  'payroll',
  'payroll_advance',
  'payroll_tax',
  'income_tax',
  'depreciation',
  'financial_expenses',
  'capex',
  'profit_distribution',
])

function isPeriodPayment(category: string | null, explicitGroup: string | null) {
  if (String(category || '').toLowerCase().includes('аренд')) return true
  return PERIOD_GROUPS.has(resolveFinancialGroup(category, explicitGroup))
}

// Собирает текст дневного отчёта по заданному скоупу компаний.
// companyIds === null → без фильтра (env-фолбэк single-tenant F16).
async function buildDailyReport(
  supabase: ReturnType<typeof createAdminSupabaseClient>,
  date: string,
  companyIds: string[] | null,
  organizationId: string | null,
): Promise<{ messageText: string; totalIncome: number; totalExpense: number; profit: number }> {
  // Безнал ночной смены после 00:00 — по календарным суткам (решение владельца
  // 17.09.2026), как /reports: берём и вчерашнюю ночь, потом режем по дате.
  let incQ = supabase
    .from('incomes')
    .select('id, date, shift, zone, comment, cash_amount, kaspi_amount, kaspi_before_midnight, online_amount, card_amount, operator_id, company_id, companies(name, code)')
    .gte('date', shiftDateISO(date, -1))
    .lte('date', date)
  let expQ = supabase.from('expenses').select('cash_amount, kaspi_amount, category, company_id').eq('date', date).or(COUNTED_EXPENSE_FILTER)
  if (companyIds) {
    incQ = incQ.in('company_id', companyIds)
    expQ = expQ.in('company_id', companyIds)
  }
  // Справочник статей — только своей организации (как в ОПиУ и /reports)
  let catQ = supabase.from('expense_categories').select('name, accounting_group')
  if (organizationId) catQ = catQ.eq('organization_id', organizationId)
  const [incomesRaw, expensesRes, categoriesRes] = await Promise.all([incQ, expQ, catQ])
  const categoryGroups = new Map<string, string | null>()
  for (const row of (categoriesRes.data ?? []) as Array<{ name: string | null; accounting_group: string | null }>) {
    const key = String(row.name || '').trim().toLowerCase()
    if (key) categoryGroups.set(key, row.accounting_group ?? null)
  }

  // Сверка с чеками: смена закрывается суммами, которые ввёл оператор, а касса
  // знает продажи по чекам. Сравниваем по дате смены, без разбивки ночного безнала.
  const shiftByCompany = new Map<string, number>()
  for (const row of (incomesRaw.data ?? []) as any[]) {
    if (row.date !== date || !row.company_id) continue
    const t = safeNum(row.cash_amount) + safeNum(row.kaspi_amount) + safeNum(row.online_amount) + safeNum(row.card_amount)
    shiftByCompany.set(row.company_id, (shiftByCompany.get(row.company_id) || 0) + t)
  }
  const salesRows = await fetchAllPages((from, to) => {
    let q = supabase
      .from('point_sales')
      .select('id, company_id, total_amount')
      .eq('sale_date', date)
      .is('refunded_at', null)
      .order('id', { ascending: true })
      .range(from, to)
    if (companyIds) q = q.in('company_id', companyIds)
    return q
  }).catch(() => [] as any[])
  const checksByCompany = new Map<string, number>()
  for (const row of salesRows) {
    if (!row.company_id) continue
    checksByCompany.set(row.company_id, (checksByCompany.get(row.company_id) || 0) + safeNum(row.total_amount))
  }
  const incomesRes = {
    ...incomesRaw,
    data: (splitIncomeKaspiByCalendarDay((incomesRaw.data ?? []) as unknown as ReportIncomeCalendarRow[]) as any[]).filter(
      (r) => r.date === date,
    ),
  }

  // Fetch operator names for operators that worked today
  const operatorIds = [...new Set(
    (incomesRes.data ?? []).map((r: any) => r.operator_id).filter(Boolean)
  )]
  const operatorNames = new Map<string, string>()
  if (operatorIds.length > 0) {
    const { data: ops } = await supabase
      .from('operators')
      .select('id, name, short_name, operator_profiles(full_name)')
      .in('id', operatorIds)
    for (const op of ops ?? []) {
      const profile = Array.isArray((op as any).operator_profiles)
        ? (op as any).operator_profiles[0]
        : null
      const displayName = profile?.full_name || (op as any).short_name || op.name || 'Оператор'
      operatorNames.set(op.id, displayName)
    }
  }

  let totalIncome = 0
  let totalExpense = 0
  const opRevenue = new Map<string, number>()
  const companyRevenue = new Map<string, { name: string; total: number }>()
  const catMap = new Map<string, number>()

  for (const row of (incomesRes.data ?? []) as any[]) {
    const total = safeNum(row.cash_amount) + safeNum(row.kaspi_amount) + safeNum(row.online_amount) + safeNum(row.card_amount)
    totalIncome += total
    if (row.operator_id) opRevenue.set(row.operator_id, (opRevenue.get(row.operator_id) || 0) + total)
    if (row.company_id) {
      const compName = row.companies?.name || row.company_id
      const existing = companyRevenue.get(row.company_id)
      companyRevenue.set(row.company_id, { name: compName, total: (existing?.total || 0) + total })
    }
  }
  let periodExpense = 0
  const periodCats = new Set<string>()
  for (const row of (expensesRes.data ?? []) as any[]) {
    const total = safeNum(row.cash_amount) + safeNum(row.kaspi_amount)
    totalExpense += total
    const cat = row.category || 'Прочее'
    catMap.set(cat, (catMap.get(cat) || 0) + total)
    const key = String(row.category || '').trim().toLowerCase()
    if (total > 0 && isPeriodPayment(row.category, categoryGroups.get(key) ?? null)) {
      periodExpense += total
      periodCats.add(cat)
    }
  }

  const profit = totalIncome - totalExpense
  const margin = totalIncome > 0 ? (profit / totalIncome) * 100 : 0
  const marginEmoji = margin >= 20 ? '🟢' : margin >= 10 ? '🟡' : '🔴'
  const sign = profit >= 0 ? '+' : ''
  const topCats = Array.from(catMap.entries()).sort((a, b) => b[1] - a[1]).slice(0, 3)

  // 30-day rolling average for anomaly detection
  const thirtyDaysAgo = (() => {
    const d = new Date(Date.now() + KZ_OFFSET)
    const past = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() - 31))
    return `${past.getUTCFullYear()}-${String(past.getUTCMonth() + 1).padStart(2, '0')}-${String(past.getUTCDate()).padStart(2, '0')}`
  })()
  // 30 дней доходов может быть >1000 строк — постранично, иначе средняя занижается.
  const avgRows = await fetchAllPages((from, to) => {
    let q = supabase
      .from('incomes')
      .select('id, date, shift, zone, comment, cash_amount, kaspi_amount, kaspi_before_midnight, online_amount, card_amount, company_id')
      .gte('date', shiftDateISO(thirtyDaysAgo, -1))
      .lt('date', date)
      .order('date', { ascending: true })
      .order('id', { ascending: true })
      .range(from, to)
    if (companyIds) q = q.in('company_id', companyIds)
    return q
  }).catch(() => [] as any[])

  const dayTotals = new Map<string, number>()
  const avgCalendarRows = (splitIncomeKaspiByCalendarDay((avgRows ?? []) as ReportIncomeCalendarRow[]) as any[]).filter(
    (r) => r.date >= thirtyDaysAgo && r.date < date,
  )
  for (const row of avgCalendarRows) {
    const t = safeNum(row.cash_amount) + safeNum(row.kaspi_amount) + safeNum(row.online_amount) + safeNum(row.card_amount)
    dayTotals.set(row.date, (dayTotals.get(row.date) || 0) + t)
  }
  const dayValues = Array.from(dayTotals.values()).filter(v => v > 0)
  const avgDailyIncome = dayValues.length > 0 ? dayValues.reduce((a, b) => a + b, 0) / dayValues.length : 0
  const dropPercent = avgDailyIncome > 0 ? ((avgDailyIncome - totalIncome) / avgDailyIncome) * 100 : 0
  const isAnomaly = avgDailyIncome > 0 && dropPercent > 30 && totalIncome < avgDailyIncome
  const isSurge = avgDailyIncome > 0 && totalIncome > avgDailyIncome * 1.3

  const lines = [
    `<b>☀️ Итоги дня</b>`,
    `<i>📅 ${date}</i>`,
    '',
    `💰 Выручка: <b>${fmtMoney(totalIncome)}</b>`,
    `📉 Расходы: <b>${fmtMoney(totalExpense)}</b>`,
  ]
  if (periodExpense > 0) {
    const names = Array.from(periodCats).slice(0, 3).map(escapeTelegramHtml).join(', ')
    lines.push(`<i>   из них ${fmtMoney(periodExpense)} — платежи за период (${names}), не расходы дня</i>`)
  }
  lines.push(
    `💼 Прибыль: <b>${sign}${fmtMoney(profit)}</b>`,
    `${marginEmoji} Маржа: <b>${margin.toFixed(1)}%</b>`,
  )
  if (periodExpense > 0) {
    const dayProfit = profit + periodExpense
    lines.push(`<i>   без платежей за период: ${dayProfit >= 0 ? '+' : ''}${fmtMoney(dayProfit)}</i>`)
  }

  if (avgDailyIncome > 0) {
    lines.push(`📊 Средняя выручка (30д): <b>${fmtMoney(avgDailyIncome)}</b>`)
  }

  // Только точки, где продают через кассу Orda; у клуба на SENET чеков здесь нет
  const reconcile = Array.from(checksByCompany.entries()).filter(([, checks]) => checks > 0)
  if (reconcile.length > 0) {
    lines.push('', '<b>Сверка с чеками кассы</b>')
    for (const [companyId, checks] of reconcile) {
      const shifts = shiftByCompany.get(companyId) || 0
      const diff = shifts - checks
      const name = escapeTelegramHtml(companyRevenue.get(companyId)?.name || 'Точка')
      const mark = Math.abs(diff) < 1 ? '✅' : Math.abs(diff) > checks * 0.02 ? '⚠️' : '▫️'
      const diffText = Math.abs(diff) < 1 ? 'сходится' : `${diff > 0 ? '+' : '−'}${fmtExact(Math.abs(diff))}`
      lines.push(`  ${mark} ${name}: чеки ${fmtExact(checks)}, закрыто сменами ${fmtExact(shifts)} (${diffText})`)
    }
  }

  // Per-company breakdown
  const sortedCompanies = Array.from(companyRevenue.values()).sort((a, b) => b.total - a.total)
  if (sortedCompanies.length > 1) {
    lines.push('', '<b>По точкам</b>')
    for (const { name, total } of sortedCompanies) {
      const pct = totalIncome > 0 ? Math.round((total / totalIncome) * 100) : 0
      lines.push(`  🏢 ${escapeTelegramHtml(name)}: <b>${fmtMoney(total)}</b> (${pct}%)`)
    }
  }

  // Operator breakdown
  const sortedOps = Array.from(opRevenue.entries()).sort((a, b) => b[1] - a[1])
  if (sortedOps.length > 0) {
    lines.push('', `<b>Операторы (${sortedOps.length})</b>`)
    for (const [opId, rev] of sortedOps) {
      const name = operatorNames.get(opId) || 'Оператор'
      lines.push(`  👤 ${escapeTelegramHtml(name)}: <b>${fmtMoney(rev)}</b>`)
    }
  }

  if (isAnomaly) {
    lines.push('')
    lines.push(`⚠️ <b>АНОМАЛИЯ:</b> выручка на ${dropPercent.toFixed(0)}% ниже среднего!`)
  }
  if (isSurge) {
    lines.push('')
    lines.push(`🚀 <b>Рекорд дня:</b> выручка на ${((totalIncome / avgDailyIncome - 1) * 100).toFixed(0)}% выше нормы!`)
  }

  if (topCats.length > 0) {
    lines.push('', '<b>Топ расходов</b>')
    for (const [cat, val] of topCats) lines.push(`  ▸ ${escapeTelegramHtml(cat)}: ${fmtMoney(val)}`)
  }

  const messageText = lines.join('\n')
  return { messageText, totalIncome, totalExpense, profit }
}

export async function GET(req: Request) {
  const auth = req.headers.get('authorization') || ''
  const cronSecret = requiredEnv('CRON_SECRET')
  if (auth !== `Bearer ${cronSecret}`) {
    return NextResponse.json({ ok: false, error: 'unauthorized' }, { status: 401 })
  }

  const supabase = createAdminSupabaseClient()
  const date = yesterdayKZISO()

  // Изоляция: каждой организации с настроенным telegram_owner_chat_id — только её
  // точки в её чат. Если per-org целей нет — прежнее env-поведение (F16 single-tenant).
  const orgTargets = await listOrgReportTargets()
  if (orgTargets.length > 0) {
    const results = []
    for (const t of orgTargets) {
      const report = await buildDailyReport(supabase, date, t.companyIds, t.organizationId)
      await sendTelegramMessage(t.chatId, report.messageText).then(() => null).catch(() => null)
      results.push({ org: t.organizationId, totalIncome: report.totalIncome, profit: report.profit })
    }
    return NextResponse.json({ ok: true, perOrg: true, date, results })
  }

  // Env-фолбэк (F16): скоуп по TELEGRAM_OWNER_ORG_ID (если задан), рассылка в общий
  // отчётный чат + владельцу — как было раньше.
  const chatId = process.env.TELEGRAM_CHAT_ID
  if (!chatId) return NextResponse.json({ ok: false, error: 'TELEGRAM_CHAT_ID not set' })

  const ownerOrgId = process.env.TELEGRAM_OWNER_ORG_ID || null
  let ownerCompanyIds: string[] | null = null
  if (ownerOrgId) {
    const { data: cos } = await supabase.from('companies').select('id').eq('organization_id', ownerOrgId)
    ownerCompanyIds = (cos || []).map((c: any) => String(c.id))
  }

  const report = await buildDailyReport(supabase, date, ownerCompanyIds, ownerOrgId)
  const recipients = [chatId, process.env.TELEGRAM_OWNER_CHAT_ID].filter(Boolean) as string[]
  const uniqueRecipients = [...new Set(recipients)]
  await Promise.all(
    uniqueRecipients.map((id) => sendTelegramMessage(id, report.messageText).then(() => null).catch(() => null)),
  )

  return NextResponse.json({
    ok: true,
    perOrg: false,
    date,
    totalIncome: report.totalIncome,
    totalExpense: report.totalExpense,
    profit: report.profit,
    recipients: uniqueRecipients.length,
  })
}
