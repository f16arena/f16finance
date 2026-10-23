import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mondayOfISO, addDaysISO } from '@/lib/core/date'
import { normalizeOrgSettings, normalizeOperatorPaySchedule } from '@/lib/core/org-settings'
import { operatorPayPeriodFor, shiftOperatorPayPeriod } from '@/lib/domain/operator-pay-period'

test('без настроек — неделя пн–вс, как раньше', () => {
  assert.deepEqual(normalizeOrgSettings({}).operatorPaySchedule, { kind: 'weekly', startDay: 1 })
  // Каждый день трёх месяцев: начало = понедельник, конец = понедельник + 6
  for (let i = 0; i < 100; i += 1) {
    const day = addDaysISO('2026-09-01', i)
    const period = operatorPayPeriodFor(day)
    const monday = mondayOfISO(day)
    assert.deepEqual(period, { start: monday, end: addDaysISO(monday, 6) }, day)
  }
})

test('неделя со среды', () => {
  const schedule = normalizeOperatorPaySchedule({ kind: 'weekly', startDay: 3 })
  assert.deepEqual(operatorPayPeriodFor('2026-10-23', schedule), { start: '2026-10-21', end: '2026-10-27' }) // пт
  assert.deepEqual(operatorPayPeriodFor('2026-10-21', schedule), { start: '2026-10-21', end: '2026-10-27' }) // ср
  assert.deepEqual(operatorPayPeriodFor('2026-10-20', schedule), { start: '2026-10-14', end: '2026-10-20' }) // вт
})

test('две недели от якоря, в т.ч. до якоря', () => {
  const schedule = { kind: 'biweekly' as const, anchor: '2026-10-05' }
  assert.deepEqual(operatorPayPeriodFor('2026-10-05', schedule), { start: '2026-10-05', end: '2026-10-18' })
  assert.deepEqual(operatorPayPeriodFor('2026-10-18', schedule), { start: '2026-10-05', end: '2026-10-18' })
  assert.deepEqual(operatorPayPeriodFor('2026-10-19', schedule), { start: '2026-10-19', end: '2026-11-01' })
  assert.deepEqual(operatorPayPeriodFor('2026-10-04', schedule), { start: '2026-09-21', end: '2026-10-04' })
})

test('половины месяца, месяц, день', () => {
  assert.deepEqual(operatorPayPeriodFor('2026-02-20', { kind: 'semimonthly' }), { start: '2026-02-16', end: '2026-02-28' })
  assert.deepEqual(operatorPayPeriodFor('2026-02-15', { kind: 'semimonthly' }), { start: '2026-02-01', end: '2026-02-15' })
  assert.deepEqual(operatorPayPeriodFor('2026-10-23', { kind: 'monthly' }), { start: '2026-10-01', end: '2026-10-31' })
  assert.deepEqual(operatorPayPeriodFor('2026-10-23', { kind: 'daily' }), { start: '2026-10-23', end: '2026-10-23' })
})

test('соседние периоды', () => {
  const week = operatorPayPeriodFor('2026-10-23')!
  assert.deepEqual(shiftOperatorPayPeriod(week, -1), { start: '2026-10-12', end: '2026-10-18' })
  assert.deepEqual(shiftOperatorPayPeriod(week, 1), { start: '2026-10-26', end: '2026-11-01' })
  const half = operatorPayPeriodFor('2026-10-10', { kind: 'semimonthly' })!
  assert.deepEqual(shiftOperatorPayPeriod(half, -1, { kind: 'semimonthly' }), { start: '2026-09-16', end: '2026-09-30' })
})

test('мусор и неверные даты', () => {
  assert.equal(operatorPayPeriodFor('oops'), null)
  assert.equal(operatorPayPeriodFor(null), null)
  assert.deepEqual(normalizeOperatorPaySchedule({ kind: 'weekly', startDay: 9 }), { kind: 'weekly', startDay: 1 })
})
