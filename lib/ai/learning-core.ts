/**
 * Чистая часть обучения ИИ (без сервера и сети): нормы точек, разбор дня,
 * слияние закономерностей. Серверная часть — lib/server/ai-learning.ts.
 */

export const DAY_MS = 86_400_000
export const HISTORY_DAYS = 56
const MAX_FACTS = 30
export const WEEKDAYS = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб']

export type CompanyNorm = {
  id: string
  name: string
  /** Медиана выручки по дню недели (0 = вс), только дни с выручкой. */
  weekday: Array<number | null>
  avgDay: number
  cashShare: number
  nightShare: number
  /** Расходы за 28 дней по статьям, крупные первыми. */
  expenses28: Array<{ category: string; amount: number }>
  daysWithIncome: number
}

export type LearnedFact = {
  id: string
  text: string
  kind: 'rhythm' | 'money' | 'expense' | 'stock' | 'people' | 'risk' | 'other'
  confidence: number
  hits: number
  firstSeen: string
  lastSeen: string
  evidence: string
}

export type BusinessMemory = {
  norms: { companies: CompanyNorm[]; products?: ProductNorm[]; stalled?: string[]; operators?: OperatorNorm[]; computedFor: string } | Record<string, never>
  facts: LearnedFact[]
  lastDay: string | null
  daysLearned: number
}

export type IncomeRow = { date: string; company_id: string; shift: string | null; cash_amount: number | null; kaspi_amount: number | null; card_amount: number | null; online_amount: number | null }
export type ExpenseRow = { date: string; company_id: string; category: string | null; cash_amount: number | null; kaspi_amount: number | null }

export const num = (v: unknown) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}
export const shiftIso = (iso: string, days: number) => new Date(Date.parse(`${iso}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10)
export const weekdayOf = (iso: string) => new Date(`${iso}T00:00:00Z`).getUTCDay()
export const money = (v: number) => `${Math.round(v).toLocaleString('ru-RU')} ₸`
export const incomeTotal = (r: IncomeRow) => num(r.cash_amount) + num(r.kaspi_amount) + num(r.card_amount) + num(r.online_amount)
export const expenseTotal = (r: ExpenseRow) => num(r.cash_amount) + num(r.kaspi_amount)

function median(values: number[]) {
  if (!values.length) return null
  const s = [...values].sort((a, b) => a - b)
  const m = Math.floor(s.length / 2)
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}

/** Нормы точек по журналам за HISTORY_DAYS дней до `day` включительно. */
export function computeNorms(companies: Array<{ id: string; name: string }>, incomes: IncomeRow[], expenses: ExpenseRow[], day: string): CompanyNorm[] {
  const from28 = shiftIso(day, -27)
  return companies.map((c) => {
    const perDay = new Map<string, number>()
    let cash = 0
    let night = 0
    let total = 0
    for (const r of incomes) {
      if (r.company_id !== c.id) continue
      const t = incomeTotal(r)
      perDay.set(r.date, (perDay.get(r.date) || 0) + t)
      if (r.date >= from28) {
        total += t
        cash += num(r.cash_amount)
        if (r.shift === 'night') night += t
      }
    }
    const byWeekday: number[][] = [[], [], [], [], [], [], []]
    for (const [d, v] of perDay) if (v > 0) byWeekday[weekdayOf(d)].push(v)
    const days28 = [...perDay].filter(([d, v]) => d >= from28 && v > 0)
    const exp = new Map<string, number>()
    for (const r of expenses) {
      if (r.company_id !== c.id || r.date < from28) continue
      const key = String(r.category || 'Без статьи')
      exp.set(key, (exp.get(key) || 0) + expenseTotal(r))
    }
    return {
      id: c.id,
      name: c.name,
      // Меньше трёх наблюдений — нормы ещё нет.
      weekday: byWeekday.map((v) => (v.length >= 3 ? Math.round(median(v)!) : null)),
      avgDay: days28.length ? Math.round(days28.reduce((s, [, v]) => s + v, 0) / days28.length) : 0,
      cashShare: total ? Math.round((cash / total) * 100) / 100 : 0,
      nightShare: total ? Math.round((night / total) * 100) / 100 : 0,
      expenses28: [...exp].map(([category, amount]) => ({ category, amount: Math.round(amount) })).sort((a, b) => b.amount - a.amount).slice(0, 8),
      daysWithIncome: perDay.size,
    }
  })
}

/** Вчерашний день точек и отклонения от норм — кодом, без ИИ. */
export function describeDay(norms: CompanyNorm[], incomes: IncomeRow[], expenses: ExpenseRow[], day: string) {
  const wd = weekdayOf(day)
  const lines: string[] = []
  const anomalies: string[] = []
  for (const n of norms) {
    const rows = incomes.filter((r) => r.company_id === n.id && r.date === day)
    const revenue = rows.reduce((s, r) => s + incomeTotal(r), 0)
    const cash = rows.reduce((s, r) => s + num(r.cash_amount), 0)
    const nightRev = rows.filter((r) => r.shift === 'night').reduce((s, r) => s + incomeTotal(r), 0)
    const exp = expenses.filter((r) => r.company_id === n.id && r.date === day)
    const expByCat = new Map<string, number>()
    for (const r of exp) expByCat.set(String(r.category || 'Без статьи'), (expByCat.get(String(r.category || 'Без статьи')) || 0) + expenseTotal(r))
    const norm = n.weekday[wd]
    lines.push(
      `${n.name}: выручка ${money(revenue)}${norm ? ` (обычный ${WEEKDAYS[wd]} ${money(norm)})` : ''}` +
        (revenue ? `, нал ${Math.round((cash / revenue) * 100)}% (обычно ${Math.round(n.cashShare * 100)}%), ночь ${Math.round((nightRev / revenue) * 100)}%` : '') +
        (expByCat.size ? `; расходы: ${[...expByCat].map(([k, v]) => `${k} ${money(v)}`).join(', ')}` : ''),
    )
    if (norm && revenue === 0 && n.avgDay > 0) anomalies.push(`${n.name}: нет выручки за день — отчёт не внесён или точка не работала`)
    else if (norm && revenue > 0) {
      const dev = (revenue - norm) / norm
      if (Math.abs(dev) >= 0.25) anomalies.push(`${n.name}: выручка ${dev > 0 ? 'выше' : 'ниже'} обычного ${WEEKDAYS[wd]} на ${Math.round(Math.abs(dev) * 100)}% (${money(revenue)} против ${money(norm)})`)
    }
    for (const [cat, v] of expByCat) {
      const usual = n.expenses28.find((e) => e.category === cat)?.amount || 0
      // Разовая трата больше недельной нормы статьи — заметно.
      if (v >= 20_000 && v > usual / 4) anomalies.push(`${n.name}: расход «${cat}» ${money(v)} за день, за 28 дней по статье ${money(usual)}`)
    }
  }
  return { lines, anomalies }
}

/** Слияние ответа ИИ с прошлой памятью: подтверждения, опровержения, затухание. */
export function mergeFacts(
  prev: LearnedFact[],
  answer: { confirm?: string[]; refute?: string[]; add?: Array<{ text: string; kind?: string; evidence?: string }> },
  day: string,
): LearnedFact[] {
  const confirm = new Set(answer.confirm || [])
  const refute = new Set(answer.refute || [])
  const kinds = ['rhythm', 'money', 'expense', 'stock', 'people', 'risk', 'other'] as const
  const next = prev.map((f) => {
    if (confirm.has(f.id)) return { ...f, confidence: Math.min(1, f.confidence + 0.15), hits: f.hits + 1, lastSeen: day }
    if (refute.has(f.id)) return { ...f, confidence: f.confidence - 0.3 }
    // Не упоминалось — гаснет медленно: за месяц без подтверждений уходит.
    return { ...f, confidence: f.confidence - 0.02 }
  })
  const known = new Set(next.map((f) => f.text.toLowerCase()))
  let seq = 0
  for (const a of answer.add || []) {
    const text = String(a.text || '').trim().slice(0, 240)
    if (!text || known.has(text.toLowerCase())) continue
    known.add(text.toLowerCase())
    seq += 1
    next.push({
      id: `f${day.replace(/-/g, '')}${seq}`,
      text,
      kind: (kinds as readonly string[]).includes(String(a.kind)) ? (a.kind as LearnedFact['kind']) : 'other',
      confidence: 0.4,
      hits: 1,
      firstSeen: day,
      lastSeen: day,
      evidence: String(a.evidence || '').slice(0, 240),
    })
  }
  return next
    .filter((f) => f.confidence >= 0.2)
    .sort((a, b) => b.confidence - a.confidence)
    .slice(0, MAX_FACTS)
}

// ─── Склад и люди ─────────────────────────────────────────────────────────

export type SaleItemRow = { date: string; company_id: string; item_id: string; name: string; quantity: number; total: number }
export type OperatorIncomeRow = IncomeRow & { operator_id: string | null; shift_id: string | null }

export type ProductNorm = {
  id: string
  name: string
  /** Штук в день в среднем за 28 дней (дни без продаж тоже считаются). */
  perDay: number
  revenue28: number
  daysSold28: number
  /** Остаток сейчас (склад + витрина), null — неизвестен. */
  stock: number | null
}

export type OperatorNorm = {
  id: string
  name: string
  /** Медиана выручки за смену за 28 дней. */
  perShift: number
  shifts28: number
  revenue28: number
}

const TOP_PRODUCTS = 25
const TOP_OPERATORS = 20

/** Ходовые товары за 28 дней + то, что продавалось раньше и встало за последние 14 дней. */
export function computeProductNorms(sales: SaleItemRow[], day: string, stock: Map<string, number> | null = null) {
  const from28 = shiftIso(day, -27)
  const from14 = shiftIso(day, -13)
  const agg = new Map<string, { name: string; qty: number; revenue: number; days: Set<string>; before: number; last14: number }>()
  for (const r of sales) {
    if (r.date > day) continue
    const cur = agg.get(r.item_id) || { name: r.name, qty: 0, revenue: 0, days: new Set<string>(), before: 0, last14: 0 }
    if (r.date >= from28) {
      cur.qty += num(r.quantity)
      cur.revenue += num(r.total)
      cur.days.add(r.date)
    } else cur.before += num(r.quantity)
    if (r.date >= from14) cur.last14 += num(r.quantity)
    agg.set(r.item_id, cur)
  }
  const products: ProductNorm[] = [...agg]
    .filter(([, a]) => a.qty > 0)
    .map(([id, a]) => ({
      id,
      name: a.name,
      perDay: Math.round((a.qty / 28) * 10) / 10,
      revenue28: Math.round(a.revenue),
      daysSold28: a.days.size,
      stock: stock ? (stock.get(id) ?? 0) : null,
    }))
    .sort((a, b) => b.revenue28 - a.revenue28)
    .slice(0, TOP_PRODUCTS)
  // Продавался в прошлые 4 недели заметно, а последние 2 недели — ни штуки.
  const stalled = [...agg]
    .filter(([, a]) => a.before >= 10 && a.last14 === 0)
    .sort((a, b) => b[1].before - a[1].before)
    .slice(0, 5)
    .map(([, a]) => `${a.name} (было ${Math.round(a.before)} шт за прошлые недели)`)
  return { products, stalled }
}

const shiftKey = (r: OperatorIncomeRow) => r.shift_id || `${r.date}:${r.company_id}:${r.shift || ''}`

/** Обычная выручка каждого оператора за смену (медиана) за 28 дней. */
export function computeOperatorNorms(incomes: OperatorIncomeRow[], names: Map<string, string>, day: string): OperatorNorm[] {
  const from28 = shiftIso(day, -27)
  const byOp = new Map<string, Map<string, number>>()
  for (const r of incomes) {
    if (!r.operator_id || r.date < from28 || r.date > day) continue
    const shifts = byOp.get(r.operator_id) || new Map<string, number>()
    shifts.set(shiftKey(r), (shifts.get(shiftKey(r)) || 0) + incomeTotal(r))
    byOp.set(r.operator_id, shifts)
  }
  return [...byOp]
    .map(([id, shifts]) => {
      const values = [...shifts.values()].filter((v) => v > 0)
      return {
        id,
        name: names.get(id) || 'Оператор',
        perShift: Math.round(median(values) || 0),
        shifts28: values.length,
        revenue28: Math.round(values.reduce((s, v) => s + v, 0)),
      }
    })
    .filter((o) => o.shifts28 > 0)
    .sort((a, b) => b.revenue28 - a.revenue28)
    .slice(0, TOP_OPERATORS)
}

/** Вчера по товарам и людям против их норм (нормы — без самого дня). */
export function describeStockAndPeople(
  products: ProductNorm[],
  operators: OperatorNorm[],
  sales: SaleItemRow[],
  incomes: OperatorIncomeRow[],
  day: string,
) {
  const lines: string[] = []
  const anomalies: string[] = []

  const soldToday = new Map<string, number>()
  for (const r of sales) if (r.date === day) soldToday.set(r.item_id, (soldToday.get(r.item_id) || 0) + num(r.quantity))
  const top = products.slice(0, 10).map((p) => `${p.name} ${Math.round(soldToday.get(p.id) || 0)} шт (обычно ${p.perDay}/день)`)
  if (top.length) lines.push(`Товары вчера: ${top.join(', ')}`)
  for (const p of products) {
    const q = soldToday.get(p.id) || 0
    if (p.perDay >= 1 && q >= 5 && q >= p.perDay * 3) anomalies.push(`${p.name}: продано ${Math.round(q)} шт за день, обычно ${p.perDay} в день`)
    if (p.stock != null && p.perDay >= 1) {
      const daysLeft = p.stock / p.perDay
      if (daysLeft < 3) anomalies.push(`${p.name}: осталось ${Math.max(0, Math.round(p.stock))} шт — хватит примерно на ${Math.max(0, Math.round(daysLeft * 10) / 10)} дн. (уходит ${p.perDay} в день)`)
    }
  }

  const todayShifts = new Map<string, Map<string, number>>()
  for (const r of incomes) {
    if (r.date !== day || !r.operator_id) continue
    const m = todayShifts.get(r.operator_id) || new Map<string, number>()
    m.set(shiftKey(r), (m.get(shiftKey(r)) || 0) + incomeTotal(r))
    todayShifts.set(r.operator_id, m)
  }
  const opLines: string[] = []
  for (const [opId, shifts] of todayShifts) {
    const norm = operators.find((o) => o.id === opId)
    const rev = [...shifts.values()].reduce((s, v) => s + v, 0)
    const perShift = shifts.size ? rev / shifts.size : 0
    opLines.push(`${norm?.name || 'Оператор'} ${money(perShift)} за смену${norm ? ` (обычно ${money(norm.perShift)})` : ''}`)
    // Меньше четырёх смен в истории — нормы ещё нет.
    if (norm && norm.shifts28 >= 4 && norm.perShift > 0) {
      const dev = (perShift - norm.perShift) / norm.perShift
      if (Math.abs(dev) >= 0.3) anomalies.push(`${norm.name}: смена ${money(perShift)} — ${dev > 0 ? 'выше' : 'ниже'} его обычной на ${Math.round(Math.abs(dev) * 100)}% (${money(norm.perShift)})`)
    }
  }
  if (opLines.length) lines.push(`Смены вчера: ${opLines.join(', ')}`)
  return { lines, anomalies }
}
