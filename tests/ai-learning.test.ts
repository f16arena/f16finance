import assert from 'node:assert/strict'
import { test } from 'node:test'

import { computeNorms, computeOperatorNorms, computeProductNorms, describeDay, describeStockAndPeople, mergeFacts, type LearnedFact, type OperatorIncomeRow, type SaleItemRow } from '@/lib/ai/learning-core'

const co = [{ id: 'a', name: 'Арена' }]
const inc = (date: string, cash: number, kaspi = 0, shift = 'day') => ({ date, company_id: 'a', shift, cash_amount: cash, kaspi_amount: kaspi, card_amount: 0, online_amount: 0 })

// Субботы октября 2026: 3, 10, 17, 24, 31.
const saturdays = ['2026-10-03', '2026-10-10', '2026-10-17', '2026-10-24']

test('норма дня недели — медиана, нужна минимум за три недели', () => {
  const rows = [inc('2026-10-03', 100_000), inc('2026-10-10', 300_000), inc('2026-10-17', 200_000)]
  const [n] = computeNorms(co, rows, [], '2026-10-30')
  assert.equal(n.weekday[6], 200_000)
  // Две пятницы — ещё не норма.
  const [m] = computeNorms(co, [inc('2026-10-23', 1), inc('2026-10-16', 1)], [], '2026-10-30')
  assert.equal(m.weekday[5], null)
})

test('доля нала и ночи — за последние 28 дней', () => {
  const rows = [inc('2026-10-30', 60_000, 40_000, 'night'), inc('2026-10-29', 0, 100_000, 'day'), inc('2026-09-01', 999_999)]
  const [n] = computeNorms(co, rows, [], '2026-10-30')
  assert.equal(n.cashShare, 0.3)
  assert.equal(n.nightShare, 0.5)
})

test('отклонение от обычной субботы попадает в аномалии', () => {
  const history = saturdays.map((d) => inc(d, 200_000))
  const norms = computeNorms(co, history, [], '2026-10-30')
  const { anomalies } = describeDay(norms, [...history, inc('2026-10-31', 100_000)], [], '2026-10-31')
  assert.equal(anomalies.length, 1)
  assert.match(anomalies[0], /ниже обычного сб на 50%/)
})

test('нет выручки в день, когда обычно есть, — это аномалия', () => {
  const history = saturdays.map((d) => inc(d, 200_000))
  const norms = computeNorms(co, history, [], '2026-10-30')
  const { anomalies } = describeDay(norms, history, [], '2026-10-31')
  assert.match(anomalies[0], /нет выручки/)
})

const fact = (id: string, confidence: number, text = id): LearnedFact => ({ id, text, kind: 'rhythm', confidence, hits: 1, firstSeen: '2026-10-01', lastSeen: '2026-10-01', evidence: '' })

test('подтверждение поднимает уверенность, опровержение роняет, молчание гасит', () => {
  const out = mergeFacts([fact('x', 0.5), fact('y', 0.5), fact('z', 0.5)], { confirm: ['x'], refute: ['y'] }, '2026-10-31')
  const by = Object.fromEntries(out.map((f) => [f.id, f]))
  assert.equal(by.x.confidence, 0.65)
  assert.equal(by.x.hits, 2)
  assert.equal(by.x.lastSeen, '2026-10-31')
  assert.equal(by.y.confidence, 0.2)
  assert.equal(by.z.confidence, 0.48)
})

test('ниже порога — забываем; дубли новой закономерности не добавляем', () => {
  const out = mergeFacts([fact('old', 0.21, 'Суббота сильная')], { add: [{ text: 'суббота сильная' }, { text: 'Ночь даёт 60% выручки', kind: 'money' }] }, '2026-10-31')
  assert.deepEqual(out.map((f) => f.text), ['Ночь даёт 60% выручки'])
  assert.equal(out[0].confidence, 0.4)
  assert.equal(out[0].kind, 'money')
})

const sale = (date: string, qty: number, item = 'cola', name = 'Coca-Cola 0,5'): SaleItemRow => ({ date, company_id: 'c1', item_id: item, name, quantity: qty, total: qty * 500 })
const days = (n: number, end = '2026-10-30') => Array.from({ length: n }, (_, i) => new Date(Date.parse(`${end}T00:00:00Z`) - i * 86_400_000).toISOString().slice(0, 10))

test('товары: штук в день за 28 дней и на сколько хватит остатка', () => {
  const rows = days(28).map((d) => sale(d, 20))
  const { products } = computeProductNorms(rows, '2026-10-30', new Map([['cola', 30]]))
  assert.equal(products[0].perDay, 20)
  assert.equal(products[0].stock, 30)
  const { anomalies } = describeStockAndPeople(products, [], [], [], '2026-10-31')
  assert.ok(anomalies.some((a) => a.includes('Coca-Cola') && a.includes('1.5 дн')))
})

test('товары: продавался раньше, последние 2 недели — ни штуки', () => {
  const rows = days(20, '2026-10-10').map((d) => sale(d, 2, 'chips', 'Lays'))
  const { stalled } = computeProductNorms(rows, '2026-10-30')
  assert.ok(stalled[0]?.startsWith('Lays'))
})

test('товары: всплеск продаж втрое выше обычного', () => {
  const rows = days(28).map((d) => sale(d, 3))
  const { products } = computeProductNorms(rows, '2026-10-30')
  const { anomalies } = describeStockAndPeople(products, [], [sale('2026-10-31', 12)], [], '2026-10-31')
  assert.ok(anomalies.some((a) => a.includes('продано 12 шт')))
})

const opInc = (date: string, op: string, total: number): OperatorIncomeRow => ({ date, company_id: 'c1', shift: 'day', shift_id: null, operator_id: op, cash_amount: total, kaspi_amount: 0, card_amount: 0, online_amount: 0 })

test('операторы: обычная смена — медиана, просадка на 30%+ заметна', () => {
  const hist = days(8).map((d) => opInc(d, 'o1', 100_000))
  const norms = computeOperatorNorms(hist, new Map([['o1', 'Айгерим']]), '2026-10-30')
  assert.equal(norms[0].perShift, 100_000)
  assert.equal(norms[0].shifts28, 8)
  const { anomalies } = describeStockAndPeople([], norms, [], [opInc('2026-10-31', 'o1', 60_000)], '2026-10-31')
  assert.ok(anomalies.some((a) => a.includes('Айгерим') && a.includes('ниже') && a.includes('40%')))
})
