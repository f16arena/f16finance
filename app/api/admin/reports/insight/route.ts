/**
 * AI-инсайт по текущему срезу страницы /reports.
 * Принимает агрегированные totals (что уже есть на клиенте) → отдаёт короткий
 * комментарий ассистента: что важного, на что обратить внимание.
 *
 * Cheap call — gpt-4o-mini, ~150 tokens out.
 */

import { NextResponse } from 'next/server'
import { requireCapability } from '@/lib/server/capabilities'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { generateAiText } from '@/lib/ai/provider'
import { buildAiBusinessContext } from '@/lib/server/ai-business-context'

export const runtime = 'nodejs'

function fmtMoney(v: number) {
  if (Math.abs(v) >= 1_000_000) return (v / 1_000_000).toFixed(1) + ' млн ₸'
  if (Math.abs(v) >= 1_000) return Math.round(v / 1_000) + 'к ₸'
  return Math.round(v).toLocaleString('ru-RU') + ' ₸'
}

function pct(curr: number, prev: number) {
  if (!prev) return null
  return ((curr - prev) / Math.abs(prev)) * 100
}

interface Body {
  dateFrom?: string
  dateTo?: string
  totals?: {
    incomeTotal?: number
    expenseTotal?: number
    profit?: number
    incomeCash?: number
    incomeKaspi?: number
    incomeOnline?: number
    incomeCard?: number
  }
  totalsPrev?: {
    incomeTotal?: number
    expenseTotal?: number
    profit?: number
  }
  topIncome?: { name: string; value: number }[]
  topExpense?: { name: string; value: number }[]
  cashlessLabel?: string  // "Kaspi" / "Halyk" / "Безналичный"
}

export async function POST(request: Request) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return access.response
  const denied = await requireCapability(access, 'reports.view')
  if (denied) return denied

  const body = (await request.json().catch(() => null)) as Body | null
  if (!body?.totals) return NextResponse.json({ error: 'totals required' }, { status: 400 })

  const t = body.totals
  const tp = body.totalsPrev || {}
  const cashLabel = body.cashlessLabel || 'безналичный'

  const incomeChange = pct(t.incomeTotal || 0, tp.incomeTotal || 0)
  const expenseChange = pct(t.expenseTotal || 0, tp.expenseTotal || 0)
  const profitChange = pct(t.profit || 0, tp.profit || 0)

  const prompt = [
    `Период: ${body.dateFrom} — ${body.dateTo}`,
    `Доход: ${fmtMoney(t.incomeTotal || 0)}${incomeChange !== null ? ` (${incomeChange > 0 ? '+' : ''}${incomeChange.toFixed(0)}% к прошлому периоду)` : ''}`,
    `Расход: ${fmtMoney(t.expenseTotal || 0)}${expenseChange !== null ? ` (${expenseChange > 0 ? '+' : ''}${expenseChange.toFixed(0)}%)` : ''}`,
    `Прибыль: ${fmtMoney(t.profit || 0)}${profitChange !== null ? ` (${profitChange > 0 ? '+' : ''}${profitChange.toFixed(0)}%)` : ''}`,
    `Структура дохода: нал ${fmtMoney(t.incomeCash || 0)}, ${cashLabel} ${fmtMoney(t.incomeKaspi || 0)}, online ${fmtMoney(t.incomeOnline || 0)}, карта ${fmtMoney(t.incomeCard || 0)}`,
    body.topIncome?.length ? `Топ доходов: ${body.topIncome.slice(0, 3).map(i => `${i.name} (${fmtMoney(i.value)})`).join(', ')}` : '',
    body.topExpense?.length ? `Топ расходов: ${body.topExpense.slice(0, 3).map(i => `${i.name} (${fmtMoney(i.value)})`).join(', ')}` : '',
    '',
    'Напиши КОРОТКИЙ вывод про этот период — максимум 4 строки:',
    '— главное: прибыль выросла или упала и из-за чего (доход или расход), с цифрами выше',
    '— что необычно: резкий рост статьи расхода, перекос в одну статью дохода, сдвиг нал/безнал',
    '— одно действие, если есть что улучшить; если всё ровно — так и скажи',
    'Стиль: прямо, без воды, 1–2 эмодзи по смыслу. Не начинай с приветствия — сразу с эмодзи и сути.',
  ].filter(Boolean).join('\n')

  const model = process.env.OPENAI_MODEL || 'gpt-4o-mini'
  try {
    const result = await generateAiText({
      model,
      // Reasoning-модели (gpt-5*) тратят бюджет на внутреннее размышление:
      // с лимитом 250 на сам ответ ничего не оставалось → «OpenAI не вернул текст».
      maxTokens: model.startsWith('gpt-5') ? 2500 : 300,
      messages: [
        { role: 'system', content: ['Ты — финансовый помощник владельца малого бизнеса в Казахстане. Смотришь на итоги периода и говоришь главное — коротко, по-русски, цифрами из данных.', await buildAiBusinessContext(access.activeOrganization?.id || null)].filter(Boolean).join('\n\n') },
        { role: 'user', content: prompt },
      ],
    })
    return NextResponse.json({ text: result.text, tokens: result.usage?.total_tokens || null })
  } catch (e: any) {
    return NextResponse.json({ error: e?.message || 'AI failed' }, { status: 500 })
  }
}
