import 'server-only'

import { addDaysISO, mondayOfISO } from '@/lib/core/date'
import { escapeHtml, formatMoney } from '@/lib/core/format'
import { isDefaultOperatorPaySchedule, type OperatorPaySchedule } from '@/lib/core/org-settings'
import { operatorPayPeriodFor } from '@/lib/domain/operator-pay-period'
import { calculateOperatorSalarySummary, isDebtDeductedFromSalary } from '@/lib/domain/salary'
import { findOperatorByKey, listOperatorSalaryData, listSalaryReferenceData } from '@/lib/server/repositories/salary'
import type { AdminSupabaseClient } from '@/lib/server/supabase'
import { sendTelegram } from '@/lib/server/telegram'

export async function getOperatorSalarySnapshot(
  supabase: AdminSupabaseClient,
  params: {
    operatorId: string
    dateFrom: string
    dateTo: string
    weekStart?: string
    companyCode?: string
    /** График организации; без него — неделя пн–вс, как раньше. */
    schedule?: OperatorPaySchedule
  },
) {
  const period = params.schedule ? operatorPayPeriodFor(params.weekStart || params.dateFrom, params.schedule) : null
  const normalizedWeekStart = period?.start || mondayOfISO(params.weekStart || params.dateFrom)
  const isWeek = !params.schedule || isDefaultOperatorPaySchedule(params.schedule)
  const [reference, payload, operatorRow] = await Promise.all([
    listSalaryReferenceData(supabase),
    listOperatorSalaryData(supabase, {
      operatorId: params.operatorId,
      dateFrom: params.dateFrom,
      dateTo: params.dateTo,
      weekStart: normalizedWeekStart,
      debtsByDate: !isWeek,
      companyCode: params.companyCode,
    }),
    findOperatorByKey(supabase, params.operatorId),
  ])
  const profile = Array.isArray((operatorRow as any)?.operator_profiles)
    ? (operatorRow as any).operator_profiles[0]
    : (operatorRow as any)?.operator_profiles

  const summary = calculateOperatorSalarySummary({
    operatorId: params.operatorId,
    operator: operatorRow
      ? {
          id: params.operatorId,
          name: (operatorRow as any).name || 'Оператор',
          short_name: (operatorRow as any).short_name || null,
          hire_date: profile?.hire_date || null,
        }
      : null,
    companies: reference.companies,
    rules: reference.rules,
    seniorityTiers: reference.seniorityTiers,
    assignments: reference.assignments,
    incomes: payload.incomes,
    adjustments: payload.adjustments,
    debts: payload.debts,
    options: params.companyCode ? { companyCodes: [params.companyCode] } : undefined,
  })

  // Построчно для квитка: каждое начисление и удержание со своей причиной.
  // Те же строки и те же условия, что берёт расчёт выше.
  const companyName = new Map(reference.companies.map((c: any) => [String(c.id), String(c.name || c.code || '')]))
  const lines: SalaryStatementLine[] = []
  for (const adj of payload.adjustments) {
    if (String(adj.operator_id) !== params.operatorId) continue
    if (adj.status && adj.status !== 'active') continue
    const amount = Number(adj.amount || 0)
    if (!(amount > 0)) continue
    const kind = adj.kind === 'bonus' || adj.kind === 'advance' || adj.kind === 'debt' ? adj.kind : 'fine'
    lines.push({
      kind,
      amount,
      date: adj.date ? String(adj.date) : null,
      comment: adj.comment ? String(adj.comment).trim() : null,
      place: adj.company_id ? companyName.get(String(adj.company_id)) || null : null,
    })
  }
  for (const debt of payload.debts) {
    if (String(debt.operator_id) !== params.operatorId) continue
    if (!isDebtDeductedFromSalary(debt)) continue
    const amount = Number(debt.amount || 0)
    if (!(amount > 0)) continue
    lines.push({
      kind: 'shop_debt',
      amount,
      date: debt.date ? String(debt.date) : null,
      comment: debt.comment ? String(debt.comment).trim() : null,
      place: debt.company_id ? companyName.get(String(debt.company_id)) || null : null,
    })
  }
  lines.sort((a, b) => String(a.date || '').localeCompare(String(b.date || '')))

  return {
    ...summary,
    lines,
    weekStart: normalizedWeekStart,
    weekEnd: period?.end || addDaysISO(normalizedWeekStart, 6),
    /** Неделя пн–вс (F16) или другой период по графику — для подписи в сообщении. */
    isWeek,
  }
}

export type SalaryStatementLine = {
  /** debt — долг, внесённый вручную; shop_debt — взял в долг на кассе. */
  kind: 'bonus' | 'fine' | 'advance' | 'debt' | 'shop_debt'
  amount: number
  date: string | null
  comment: string | null
  place: string | null
}

const STATEMENT_SECTIONS: Array<{ kind: SalaryStatementLine['kind']; title: string; sign: '+' | '−'; deduction: boolean }> = [
  { kind: 'bonus', title: '🎁 Премии', sign: '+', deduction: false },
  { kind: 'fine', title: '➖ Штрафы', sign: '−', deduction: true },
  { kind: 'debt', title: '🧾 Долги', sign: '−', deduction: true },
  { kind: 'shop_debt', title: '🛒 Взято в долг на кассе', sign: '−', deduction: true },
  { kind: 'advance', title: '💸 Авансы', sign: '−', deduction: true },
]

function shortRuDate(iso: string | null) {
  if (!iso || !/^\d{4}-\d{2}-\d{2}/.test(iso)) return ''
  return `${iso.slice(8, 10)}.${iso.slice(5, 7)}`
}

/** Строка квитка: сумма, дата и точка, за что. Позиции кассы («Товар x1 = 800 ₸») — списком. */
function statementLineText(line: SalaryStatementLine) {
  const head = [shortRuDate(line.date), line.place].filter(Boolean).join(' · ')
  const text = `• <b>${formatMoney(line.amount)}</b>${head ? ` <i>(${escapeHtml(head)})</i>` : ''}`
  const parts = (line.comment || '').split('\n').map((p) => p.trim()).filter(Boolean)
  if (!parts.length) return `${text}${line.kind === 'shop_debt' ? '' : ' — <i>без комментария</i>'}\n`
  if (parts.length === 1) return `${text} — ${escapeHtml(parts[0])}\n`
  // Telegram режет сообщение на 4096 символах — длинный чек кассы сокращаем.
  const shown = parts.length > 10 ? parts.slice(0, 9) : parts
  const more = parts.length - shown.length
  return `${text}\n${shown.map((p) => `   ${escapeHtml(p)}`).join('\n')}${more > 0 ? `\n   <i>…и ещё ${more}</i>` : ''}\n`
}

export function buildSalaryTelegramMessage(params: {
  operatorName: string
  dateFrom: string
  dateTo: string
  weekStart: string
  weekEnd: string
  summary: Awaited<ReturnType<typeof getOperatorSalarySnapshot>>
  lastItem?: {
    name: string
    qty: number
    total: number
    pointName?: string | null
    companyName?: string | null
  } | null
}): string {
  const { operatorName, dateFrom, dateTo, weekStart, weekEnd, summary, lastItem } = params

  let text = `<b>💰 Зарплата · сводка</b>\n\n`
  text += `👤 <b>${escapeHtml(operatorName)}</b>\n`
  text += `📆 Период: <code>${escapeHtml(`${dateFrom} — ${dateTo}`)}</code>\n`
  text += `🗓 ${summary.isWeek ? 'Неделя (пн-вс)' : 'Период зарплаты'}: <code>${escapeHtml(`${weekStart} — ${weekEnd}`)}</code>\n\n`

  if (lastItem?.name) {
    text += `🛒 Сегодня в долг: <b>${escapeHtml(lastItem.name)}</b> x${lastItem.qty} = <b>${formatMoney(lastItem.total)}</b>\n`
    if (lastItem.pointName || lastItem.companyName) {
      text += `📍 Где: <b>${escapeHtml(lastItem.pointName || lastItem.companyName || '')}</b>\n`
    }
    text += '\n'
  }

  // Квиток: начислено → удержано (у каждой строки своя причина) → к выплате.
  text += `<b>Начислено</b>\n`
  text += `📌 Смен: <b>${summary.shifts}</b> · база <b>${formatMoney(summary.baseSalary)}</b>\n`
  if (summary.seniorityBonuses > 0) text += `📈 За стаж: <b>${formatMoney(summary.seniorityBonuses)}</b>\n`
  if (summary.autoBonuses > 0) text += `✅ Бонусы за выручку: <b>${formatMoney(summary.autoBonuses)}</b>\n`
  if (summary.roleBonuses > 0) text += `⭐ Надбавка за роль: <b>${formatMoney(summary.roleBonuses)}</b>\n`

  const lines = summary.lines || []
  let deductionsHeader = false
  for (const section of STATEMENT_SECTIONS) {
    const rows = lines.filter((l) => l.kind === section.kind)
    if (!rows.length) continue
    if (section.deduction && !deductionsHeader) {
      text += `\n<b>Удержано</b>\n`
      deductionsHeader = true
    }
    const total = rows.reduce((sum, l) => sum + l.amount, 0)
    text += `\n${section.title}: <b>${section.sign}${formatMoney(total)}</b>\n`
    for (const row of rows) text += statementLineText(row)
  }

  text += `\n━━━━━━━━━━━━\n`
  text += `Начислено: <b>${formatMoney(summary.totalAccrued)}</b>\n`
  text += `Удержано: <b>${formatMoney(summary.totalDeductions)}</b>\n`
  text += `\n💰 <b>К выплате: ${formatMoney(summary.remainingAmount)}</b>`

  return text
}

export function buildPointDebtTelegramMessage(params: {
  debtorName: string
  weekStart: string
  weekDebtTotal: number
  lastItem: {
    name: string
    qty: number
    total: number
    pointName?: string | null
    companyName?: string | null
    comment?: string | null
  }
}) {
  const place = [params.lastItem.pointName, params.lastItem.companyName].filter(Boolean).join(' · ')
  let text = `<b>🧾 Новый долг</b>\n\n`
  text += `👤 ${escapeHtml(params.debtorName)}\n`
  text += `🛒 ${escapeHtml(params.lastItem.name)} × ${params.lastItem.qty}\n`
  text += `💵 Сумма позиции: <b>${formatMoney(params.lastItem.total)}</b>\n`
  if (place) text += `📍 ${escapeHtml(place)}\n`
  text += `🗓 Неделя: <code>${escapeHtml(params.weekStart)}</code>\n`
  if (params.lastItem.comment?.trim()) {
    text += `💬 ${escapeHtml(params.lastItem.comment.trim())}\n`
  }
  text += `\n📌 Долг за неделю: <b>${formatMoney(params.weekDebtTotal)}</b>`
  return text
}

export async function sendOperatorDebtTelegramSnapshot(
  supabase: AdminSupabaseClient,
  params: {
    operatorId: string
    operatorName: string
    operatorChatId: string | null
    weekStart: string
    lastItem: {
      name: string
      qty: number
      total: number
      pointName?: string | null
      companyName?: string | null
      comment?: string | null
    }
  },
) {
  if (!params.operatorChatId) {
    return { sent: false as const, reason: 'telegram-missing' }
  }

  const { data: debtRows, error: debtError } = await supabase
    .from('debts')
    .select('amount')
    .eq('operator_id', params.operatorId)
    .eq('week_start', params.weekStart)
    .eq('status', 'active')
  if (debtError) throw debtError
  const weekDebtTotal = (debtRows || []).reduce((sum: number, row: any) => sum + Number(row.amount || 0), 0)

  const text = buildPointDebtTelegramMessage({
    debtorName: params.operatorName,
    weekStart: params.weekStart,
    weekDebtTotal,
    lastItem: params.lastItem,
  })

  await sendTelegram(text, params.operatorChatId)
  return { sent: true as const }
}
