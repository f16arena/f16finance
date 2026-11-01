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
  type OperatorIncomeRow,
  type OperatorNorm,
  type ProductNorm,
  type SaleItemRow,
  WEEKDAYS,
  computeNorms,
  computeOperatorNorms,
  computeProductNorms,
  describeDay,
  describeStockAndPeople,
  mergeFacts,
  money,
  num,
  shiftIso,
  weekdayOf,
} from '@/lib/ai/learning-core'

/**
 * Ежедневное «обучение» ИИ на потоке данных организации: деньги, склад и люди.
 *
 * Модель не дообучается — учится память, которую ей подкладывают в каждый разбор:
 *   1. Нормы считаются кодом из журналов за 8 недель: обычная выручка точки по
 *      дню недели (медиана), доля нала/безнала, доля ночи, расходы по статьям.
 *      Пересчитываются каждый день целиком — сами догоняют внесённое задним числом.
 *      Склад: сколько каждого ходового товара уходит в день, на сколько дней хватит
 *      остатка, что перестало продаваться. Люди: обычная выручка оператора за смену.
 *   2. Закономерности ИИ замечает во вчерашнем дне на фоне норм и прошлых
 *      закономерностей. Подтвердилось — уверенность растёт, опровергнуто — падает,
 *      давно не повторялось — медленно гаснет. Ниже порога — забываем.
 *
 * Цифры для ответов ИИ по-прежнему берёт из DATA конкретного разбора; память —
 * только фон «что для этого бизнеса нормально».
 */

const LEARN_PROMPT = [
  'Ты — аналитик, который ведёт «память» о конкретном бизнесе: что для него нормально и какие закономерности повторяются.',
  'Тебе дают: нормы точек (посчитаны кодом за 8 недель), нормы товаров (сколько уходит в день) и операторов (обычная выручка за смену), вчерашний день, отклонения от норм и уже известные закономерности с id и уверенностью.',
  '',
  'Задача:',
  '1. confirm — id известных закономерностей, которые вчерашний день ПОДТВЕРДИЛ (было видно в данных).',
  '2. refute — id тех, которые вчерашний день прямо ОПРОВЕРГ.',
  '3. add — 0–3 НОВЫЕ закономерности, только если они видны в данных и полезны владельцу на будущее: ритм (какие дни/смены сильные), деньги (нал/безнал, средний уровень), расходы (повторяющиеся траты), склад (какие товары ходовые, что часто кончается, что встало), люди (кто стабильно сильнее или слабее в смене), риски (точка регулярно не сдаёт отчёт, выручка проседает).',
  '',
  'Правила:',
  '- Один день — не закономерность. Новую добавляй, только если она опирается на нормы (несколько недель) или повторяет то, что уже было в отклонениях. Иначе не добавляй ничего.',
  '- Каждая закономерность — одно короткое предложение с точкой, товаром или человеком и числом из данных, например «Арена: суббота — сильнейший день, обычно около 900 000 ₸» или «Coca-Cola 0,5 уходит ~20 шт в день, к пятнице часто кончается».',
  '- Про людей — только наблюдения из цифр, без оценок личности.',
  '- Не дублируй известные (переформулировка — тоже дубль, тогда confirm).',
  '- Не выдумывай причин, которых нет в данных. Не давай советов — это память, не рекомендации.',
  '',
  'Верни СТРОГО JSON без markdown: {"confirm":["id"],"refute":["id"],"add":[{"text":"...","kind":"rhythm|money|expense|stock|people|risk|other","evidence":"на каких числах"}]}',
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

  const [incomes, expenses, memRes, sales, stock] = await Promise.all([
    fetchAllRows<OperatorIncomeRow>((a, b) =>
      supabase.from('incomes').select('date, company_id, shift, shift_id, operator_id, cash_amount, kaspi_amount, card_amount, online_amount').in('company_id', ids).gte('date', from).lte('date', day).order('id').range(a, b),
    ),
    fetchAllRows<ExpenseRow>((a, b) =>
      supabase.from('expenses').select('date, company_id, category, cash_amount, kaspi_amount').in('company_id', ids).gte('date', from).lte('date', day).or(COUNTED_EXPENSE_FILTER).order('id').range(a, b),
    ),
    supabase.from('ai_business_memory').select('facts, last_day, days_learned').eq('organization_id', orgId).maybeSingle(),
    // Склад — вспомогательный: не загрузился, учимся на деньгах без него.
    loadSaleItems(supabase, ids, from, day).catch((e) => {
      console.error('[ai-learning] sales', orgId, e?.message || e)
      return [] as SaleItemRow[]
    }),
    loadStock(supabase, ids).catch(() => null),
  ])
  if (memRes.error) throw memRes.error
  // Этот день уже выучен — повторный запуск ничего не делает.
  if (memRes.data?.last_day === day) return null

  const prevFacts = (Array.isArray(memRes.data?.facts) ? memRes.data.facts : []) as LearnedFact[]
  const norms = computeNorms(companies, incomes, expenses, day)
  // Отклонения меряем от норм БЕЗ самого дня, иначе он тянет норму к себе.
  const before = computeNorms(companies, incomes.filter((r) => r.date < day), expenses.filter((r) => r.date < day), shiftIso(day, -1))
  const { lines, anomalies } = describeDay(before, incomes, expenses, day)

  const operatorNames = await loadOperatorNames(supabase, incomes)
  const { products, stalled } = computeProductNorms(sales, day, stock)
  const operators = computeOperatorNorms(incomes, operatorNames, day)
  const productsBefore = computeProductNorms(sales.filter((r) => r.date < day), shiftIso(day, -1), stock).products
  const operatorsBefore = computeOperatorNorms(incomes.filter((r) => r.date < day), operatorNames, shiftIso(day, -1))
  const sp = describeStockAndPeople(productsBefore, operatorsBefore, sales, incomes, day)
  // Остаток «на сколько хватит» считаем по свежей норме (с учётом вчерашних продаж).
  lines.push(...sp.lines)
  anomalies.push(...sp.anomalies.filter((a) => !a.includes('хватит примерно')))
  anomalies.push(...describeStockAndPeople(products, [], [], [], day).anomalies)

  let facts = prevFacts
  let added = 0
  // Пустой день без истории учить нечему.
  if (incomes.length || expenses.length || sales.length) {
    const normText = norms
      .map((n) => `${n.name}: средний день ${money(n.avgDay)}; по дням недели ${n.weekday.map((v, i) => `${WEEKDAYS[i]} ${v ? money(v) : '—'}`).join(', ')}; нал ${Math.round(n.cashShare * 100)}%, ночь ${Math.round(n.nightShare * 100)}%; расходы за 28 дней: ${n.expenses28.map((e) => `${e.category} ${money(e.amount)}`).join(', ') || 'нет'}`)
      .join('\n')
    const productText = products.length
      ? products.map((p) => `${p.name}: ${p.perDay} шт/день, продавался ${p.daysSold28} из 28 дн., выручка ${money(p.revenue28)}${p.stock != null ? `, остаток ${Math.round(p.stock)}` : ''}`).join('\n')
      : 'нет продаж товаров'
    const operatorText = operators.length
      ? operators.map((o) => `${o.name}: обычная смена ${money(o.perShift)}, смен за 28 дн. ${o.shifts28}`).join('\n')
      : 'нет данных по операторам'
    const known = prevFacts.map((f) => `${f.id} (уверенность ${Math.round(f.confidence * 100)}%): ${f.text}`).join('\n') || 'пока нет'
    const res = await generateAiText({
      messages: [
        { role: 'system', content: LEARN_PROMPT },
        { role: 'user', content: `День: ${day} (${WEEKDAYS[weekdayOf(day)]}).\n\nНОРМЫ:\n${normText}\n\nТОВАРЫ (28 дней):\n${productText}${stalled.length ? `\nПерестали продаваться: ${stalled.join(', ')}` : ''}\n\nОПЕРАТОРЫ (28 дней):\n${operatorText}\n\nВЧЕРА:\n${lines.join('\n')}\n\nОТКЛОНЕНИЯ:\n${anomalies.join('\n') || 'нет'}\n\nИЗВЕСТНЫЕ ЗАКОНОМЕРНОСТИ:\n${known}` },
      ],
      temperature: 0.2,
      maxTokens: 1500,
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
    norms: { companies: norms, products, stalled, operators, computedFor: day },
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
  const [{ data, error }, told] = await Promise.all([
    supabase.from('ai_business_memory').select('norms, facts, last_day, days_learned').eq('organization_id', orgId).maybeSingle(),
    loadToldFacts(supabase, orgId),
  ])
  const toldBlock = told.length
    ? `ВЛАДЕЛЕЦ И КОМАНДА ПРОСИЛИ ЗАПОМНИТЬ (правила и особенности этого бизнеса — учитывай в ответах):\n${told.map((t) => `- ${t}`).join('\n')}`
    : ''
  // Таблицы ещё нет (миграция не применена) — ИИ работает как раньше.
  if (error || !data) return toldBlock
  const companies = ((data.norms as any)?.companies || []) as CompanyNorm[]
  const products = ((data.norms as any)?.products || []) as ProductNorm[]
  const stalled = ((data.norms as any)?.stalled || []) as string[]
  const operators = ((data.norms as any)?.operators || []) as OperatorNorm[]
  const facts = ((data.facts || []) as LearnedFact[]).filter((f) => f.confidence >= 0.5).slice(0, 15)
  if (!companies.length && !facts.length && !products.length) return toldBlock
  const normLines = companies
    .filter((n) => n.avgDay > 0)
    .map((n) => {
      const days = n.weekday.map((v, i) => (v ? `${WEEKDAYS[i]} ${money(v)}` : '')).filter(Boolean).join(', ')
      return `- ${n.name}: обычный день ${money(n.avgDay)}${days ? ` (${days})` : ''}, нал ${Math.round(n.cashShare * 100)}%, ночь ${Math.round(n.nightShare * 100)}%`
    })
  const productLines = products.slice(0, 15).map((p) => {
    const left = p.stock != null && p.perDay > 0 ? `, остаток ${Math.round(p.stock)} — на ~${Math.round(p.stock / p.perDay)} дн.` : ''
    return `- ${p.name}: уходит ${p.perDay} шт в день${left}`
  })
  const operatorLines = operators.slice(0, 12).map((o) => `- ${o.name}: обычная смена ${money(o.perShift)} (${o.shifts28} смен за 4 недели)`)
  const factLines = facts.map((f) => `- ${f.text} (подтверждалось ${f.hits} раз)`)
  return [
    `ПАМЯТЬ О БИЗНЕСЕ (выучено из данных за ${data.days_learned} дн., на ${data.last_day}; фон для сравнения, не данные периода — если расходится с DATA, верь DATA):`,
    normLines.length ? `Нормы точек:\n${normLines.join('\n')}` : '',
    productLines.length ? `Ходовые товары (остаток — на ${data.last_day}):\n${productLines.join('\n')}` : '',
    stalled.length ? `Перестали продаваться: ${stalled.join(', ')}` : '',
    operatorLines.length ? `Операторы:\n${operatorLines.join('\n')}` : '',
    factLines.length ? `Закономерности:\n${factLines.join('\n')}` : '',
    'Сравнивай цифры периода с этими нормами и говори «выше/ниже обычного», когда это важно.',
  ]
    .filter(Boolean)
    .join('\n') + (toldBlock ? `\n\n${toldBlock}` : '')
}

/** Позиции продаж точек за период, без возвращённых чеков. */
async function loadSaleItems(supabase: any, companyIds: string[], from: string, to: string): Promise<SaleItemRow[]> {
  const rows = await fetchAllRows<any>(
    (a, b) =>
      supabase
        .from('point_sale_items')
        .select('id, item_id, quantity, total_price, item:item_id(name), sale:sale_id!inner(sale_date, company_id, refunded_at)')
        .in('sale.company_id', companyIds)
        .gte('sale.sale_date', from)
        .lte('sale.sale_date', to)
        .is('sale.refunded_at', null)
        .order('id')
        .range(a, b),
    { maxPages: 200 },
  )
  return rows.map((r) => ({
    date: String(r.sale?.sale_date || ''),
    company_id: String(r.sale?.company_id || ''),
    item_id: String(r.item_id),
    name: String(r.item?.name || 'Товар'),
    quantity: num(r.quantity),
    total: num(r.total_price),
  }))
}

/** Текущий остаток по товарам на всех локациях точек (склад + витрина). */
async function loadStock(supabase: any, companyIds: string[]): Promise<Map<string, number>> {
  const { data: locs, error } = await supabase.from('inventory_locations').select('id').in('company_id', companyIds)
  if (error) throw error
  const locIds = ((locs || []) as any[]).map((l) => String(l.id))
  const stock = new Map<string, number>()
  if (!locIds.length) return stock
  const balances = await fetchAllRows<any>((a, b) =>
    supabase.from('inventory_balances').select('item_id, location_id, quantity').in('location_id', locIds).order('item_id').order('location_id').range(a, b),
  )
  for (const b of balances) stock.set(String(b.item_id), (stock.get(String(b.item_id)) || 0) + num(b.quantity))
  return stock
}

async function loadOperatorNames(supabase: any, incomes: OperatorIncomeRow[]): Promise<Map<string, string>> {
  const ids = [...new Set(incomes.map((r) => r.operator_id).filter(Boolean))] as string[]
  const names = new Map<string, string>()
  if (!ids.length) return names
  const { data } = await supabase.from('operators').select('id, name, short_name').in('id', ids)
  for (const o of (data || []) as any[]) names.set(String(o.id), String(o.short_name || o.name || 'Оператор'))
  return names
}

/** Факты, которые люди сами попросили копилот запомнить (ai_memory), — свежие первыми. */
async function loadToldFacts(supabase: any, orgId: string): Promise<string[]> {
  const { data, error } = await supabase
    .from('ai_memory')
    .select('value')
    .eq('organization_id', orgId)
    .order('created_at', { ascending: false })
    .limit(20)
  if (error || !data) return []
  return (data as Array<{ value: unknown }>).map((r) => String(r.value || '').trim().slice(0, 300)).filter(Boolean)
}

export { computeNorms, describeDay, mergeFacts, type LearnedFact, type CompanyNorm, type BusinessMemory }
