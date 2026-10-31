import 'server-only'

import { generateAiText } from '@/lib/ai/provider'
import { COUNTED_EXPENSE_FILTER } from '@/lib/domain/expense-status'
import { fetchAllRows } from '@/lib/server/fetch-all-rows'
import {
  type BusinessMemory,
  type CompanyNorm,
  type ExpenseRow,
  HISTORY_DAYS,
  type IncomeRow,
  type LearnedFact,
  WEEKDAYS,
  computeNorms,
  describeDay,
  mergeFacts,
  money,
  num,
  shiftIso,
  weekdayOf,
} from '@/lib/ai/learning-core'

/**
 * Ежедневное «обучение» ИИ на потоке данных организации.
 *
 * Модель не дообучается — учится память, которую ей подкладывают в каждый разбор:
 *   1. Нормы считаются кодом из журналов за 8 недель: обычная выручка точки по
 *      дню недели (медиана), доля нала/безнала, доля ночи, расходы по статьям.
 *      Пересчитываются каждый день целиком — сами догоняют внесённое задним числом.
 *   2. Закономерности ИИ замечает во вчерашнем дне на фоне норм и прошлых
 *      закономерностей. Подтвердилось — уверенность растёт, опровергнуто — падает,
 *      давно не повторялось — медленно гаснет. Ниже порога — забываем.
 *
 * Цифры для ответов ИИ по-прежнему берёт из DATA конкретного разбора; память —
 * только фон «что для этого бизнеса нормально».
 */

const LEARN_PROMPT = [
  'Ты — аналитик, который ведёт «память» о конкретном бизнесе: что для него нормально и какие закономерности повторяются.',
  'Тебе дают: нормы точек (посчитаны кодом за 8 недель), вчерашний день по точкам, отклонения от норм и уже известные закономерности с id и уверенностью.',
  '',
  'Задача:',
  '1. confirm — id известных закономерностей, которые вчерашний день ПОДТВЕРДИЛ (было видно в данных).',
  '2. refute — id тех, которые вчерашний день прямо ОПРОВЕРГ.',
  '3. add — 0–3 НОВЫЕ закономерности, только если они видны в данных и полезны владельцу на будущее: ритм (какие дни/смены сильные), деньги (нал/безнал, средний уровень), расходы (повторяющиеся траты), риски (точка регулярно не сдаёт отчёт, выручка проседает).',
  '',
  'Правила:',
  '- Один день — не закономерность. Новую добавляй, только если она опирается на нормы (несколько недель) или повторяет то, что уже было в отклонениях. Иначе не добавляй ничего.',
  '- Каждая закономерность — одно короткое предложение с точкой и числом из данных, например «Арена: суббота — сильнейший день, обычно около 900 000 ₸».',
  '- Не дублируй известные (переформулировка — тоже дубль, тогда confirm).',
  '- Не выдумывай причин, которых нет в данных. Не давай советов — это память, не рекомендации.',
  '',
  'Верни СТРОГО JSON без markdown: {"confirm":["id"],"refute":["id"],"add":[{"text":"...","kind":"rhythm|money|expense|risk|other","evidence":"на каких числах"}]}',
].join('\n')

/** Один шаг обучения организации за `day`. Возвращает обновлённую память. */
export async function learnOrganizationDay(supabase: any, orgId: string, day: string): Promise<{ memory: BusinessMemory; added: number; anomalies: number } | null> {
  const { data: companiesRaw, error: cErr } = await supabase
    .from('companies')
    .select('id, name')
    .eq('organization_id', orgId)
    .is('archived_at', null)
    .order('name')
  if (cErr) throw cErr
  const companies = ((companiesRaw || []) as any[]).map((c) => ({ id: String(c.id), name: String(c.name || 'Точка') }))
  if (!companies.length) return null
  const ids = companies.map((c) => c.id)
  const from = shiftIso(day, -(HISTORY_DAYS - 1))

  const [incomes, expenses, memRes] = await Promise.all([
    fetchAllRows<IncomeRow>((a, b) =>
      supabase.from('incomes').select('date, company_id, shift, cash_amount, kaspi_amount, card_amount, online_amount').in('company_id', ids).gte('date', from).lte('date', day).order('id').range(a, b),
    ),
    fetchAllRows<ExpenseRow>((a, b) =>
      supabase.from('expenses').select('date, company_id, category, cash_amount, kaspi_amount').in('company_id', ids).gte('date', from).lte('date', day).or(COUNTED_EXPENSE_FILTER).order('id').range(a, b),
    ),
    supabase.from('ai_business_memory').select('facts, last_day, days_learned').eq('organization_id', orgId).maybeSingle(),
  ])
  if (memRes.error) throw memRes.error
  // Этот день уже выучен — повторный запуск ничего не делает.
  if (memRes.data?.last_day === day) return null

  const prevFacts = (Array.isArray(memRes.data?.facts) ? memRes.data.facts : []) as LearnedFact[]
  const norms = computeNorms(companies, incomes, expenses, day)
  // Отклонения меряем от норм БЕЗ самого дня, иначе он тянет норму к себе.
  const before = computeNorms(companies, incomes.filter((r) => r.date < day), expenses.filter((r) => r.date < day), shiftIso(day, -1))
  const { lines, anomalies } = describeDay(before, incomes, expenses, day)

  let facts = prevFacts
  let added = 0
  // Пустой день без истории учить нечему.
  if (incomes.length || expenses.length) {
    const normText = norms
      .map((n) => `${n.name}: средний день ${money(n.avgDay)}; по дням недели ${n.weekday.map((v, i) => `${WEEKDAYS[i]} ${v ? money(v) : '—'}`).join(', ')}; нал ${Math.round(n.cashShare * 100)}%, ночь ${Math.round(n.nightShare * 100)}%; расходы за 28 дней: ${n.expenses28.map((e) => `${e.category} ${money(e.amount)}`).join(', ') || 'нет'}`)
      .join('\n')
    const known = prevFacts.map((f) => `${f.id} (уверенность ${Math.round(f.confidence * 100)}%): ${f.text}`).join('\n') || 'пока нет'
    const res = await generateAiText({
      messages: [
        { role: 'system', content: LEARN_PROMPT },
        { role: 'user', content: `День: ${day} (${WEEKDAYS[weekdayOf(day)]}).\n\nНОРМЫ:\n${normText}\n\nВЧЕРА:\n${lines.join('\n')}\n\nОТКЛОНЕНИЯ:\n${anomalies.join('\n') || 'нет'}\n\nИЗВЕСТНЫЕ ЗАКОНОМЕРНОСТИ:\n${known}` },
      ],
      temperature: 0.2,
      maxTokens: 1200,
    })
    const raw = res.text.replace(/^```(?:json)?\s*|\s*```$/g, '')
    let answer: any = {}
    try {
      answer = JSON.parse(raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
    } catch {
      answer = {}
    }
    facts = mergeFacts(prevFacts, answer, day)
    added = facts.filter((f) => f.firstSeen === day).length
  }

  const memory: BusinessMemory = {
    norms: { companies: norms, computedFor: day },
    facts,
    lastDay: day,
    daysLearned: num(memRes.data?.days_learned) + 1,
  }
  const { error: upErr } = await supabase.from('ai_business_memory').upsert(
    { organization_id: orgId, norms: memory.norms, facts, last_day: day, days_learned: memory.daysLearned, updated_at: new Date().toISOString() },
    { onConflict: 'organization_id' },
  )
  if (upErr) throw upErr
  return { memory, added, anomalies: anomalies.length }
}

/** Блок памяти для системного промпта любого ИИ-разбора. Пусто — пока не учились. */
export async function loadAiMemoryBlock(supabase: any, orgId: string): Promise<string> {
  const { data, error } = await supabase.from('ai_business_memory').select('norms, facts, last_day, days_learned').eq('organization_id', orgId).maybeSingle()
  // Таблицы ещё нет (миграция не применена) — ИИ работает как раньше.
  if (error || !data) return ''
  const companies = ((data.norms as any)?.companies || []) as CompanyNorm[]
  const facts = ((data.facts || []) as LearnedFact[]).filter((f) => f.confidence >= 0.5).slice(0, 12)
  if (!companies.length && !facts.length) return ''
  const normLines = companies
    .filter((n) => n.avgDay > 0)
    .map((n) => {
      const days = n.weekday.map((v, i) => (v ? `${WEEKDAYS[i]} ${money(v)}` : '')).filter(Boolean).join(', ')
      return `- ${n.name}: обычный день ${money(n.avgDay)}${days ? ` (${days})` : ''}, нал ${Math.round(n.cashShare * 100)}%, ночь ${Math.round(n.nightShare * 100)}%`
    })
  const factLines = facts.map((f) => `- ${f.text} (подтверждалось ${f.hits} раз)`)
  return [
    `ПАМЯТЬ О БИЗНЕСЕ (выучено из данных за ${data.days_learned} дн., на ${data.last_day}; фон для сравнения, не данные периода — если расходится с DATA, верь DATA):`,
    normLines.length ? `Нормы точек:\n${normLines.join('\n')}` : '',
    factLines.length ? `Закономерности:\n${factLines.join('\n')}` : '',
    'Сравнивай цифры периода с этими нормами и говори «выше/ниже обычного», когда это важно.',
  ]
    .filter(Boolean)
    .join('\n')
}

export { computeNorms, describeDay, mergeFacts, type LearnedFact, type CompanyNorm, type BusinessMemory }
