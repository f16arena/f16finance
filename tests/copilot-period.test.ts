import assert from 'node:assert/strict'
import { test } from 'node:test'

import { resolveDateRange, todayISO } from '@/lib/ai/copilot/query-helpers'

test('todayISO — по Казахстану: 20:00 UTC 31 октября у нас уже 1 ноября', () => {
  assert.equal(todayISO(new Date('2026-10-31T20:00:00Z')), '2026-11-01')
  assert.equal(todayISO(new Date('2026-10-31T18:59:00Z')), '2026-10-31')
})

test('«этот месяц» — с 1-го числа, «month» — последние 30 дней, в подписи даты', () => {
  const today = todayISO()
  const first = `${today.slice(0, 8)}01`
  const thisMonth = resolveDateRange({ period: 'this_month' })
  assert.equal(thisMonth.from, first)
  assert.equal(thisMonth.to, today)
  assert.match(thisMonth.label, new RegExp(first))

  const last30 = resolveDateRange({ period: 'month' })
  assert.match(last30.label, /последние 30 дней \(\d{4}-\d{2}-\d{2} — \d{4}-\d{2}-\d{2}\)/)
})

test('«прошлый месяц» — календарный месяц целиком', () => {
  const r = resolveDateRange({ period: 'last_month' })
  assert.equal(r.from!.slice(8), '01')
  const nextDay = new Date(`${r.to}T00:00:00Z`)
  nextDay.setUTCDate(nextDay.getUTCDate() + 1)
  assert.equal(nextDay.toISOString().slice(8, 10), '01')
  assert.equal(nextDay.toISOString().slice(0, 7), todayISO().slice(0, 7))
})

test('точные даты важнее пресета', () => {
  const r = resolveDateRange({ period: 'month', from: '2026-10-01', to: '2026-10-31' })
  assert.deepEqual([r.from, r.to], ['2026-10-01', '2026-10-31'])
})
