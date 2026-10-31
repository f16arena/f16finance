import assert from 'node:assert/strict'
import { test } from 'node:test'

import { computeNorms, describeDay, mergeFacts, type LearnedFact } from '@/lib/ai/learning-core'

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
