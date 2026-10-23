import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pointRuleActiveOn, resolveShiftOverrides, type PointRuleRow } from '@/lib/domain/point-rules'

const rule = (id: string, base: number, from: string | null, to: string | null): PointRuleRow => ({
  id,
  company_id: 'c1',
  scope: 'salary',
  event: 'salary.shift.computed',
  name: id,
  description: null,
  priority: 100,
  is_active: true,
  stop_processing: false,
  conditions: [{ field: 'shift.type', operator: 'eq', value: 'day' }],
  actions: [{ type: 'set_base_per_shift', amount: base }],
  effective_from: from,
  effective_to: to,
})

// Правка 29.09: старая строка закрыта 28.09, новая — с 29.09
const rules = [rule('old', 5000, null, '2026-09-28'), rule('new', 8000, '2026-09-29', null)]
const base = (date?: string) => resolveShiftOverrides({ rules, companyId: 'c1', shiftType: 'day', turnover: 0, date }).basePerShift

test('выплаченная неделя до правки считается по старому варианту', () => {
  assert.equal(base('2026-09-22'), 5000)
  assert.equal(base('2026-09-28'), 5000)
})

test('с понедельника правки — новый вариант', () => {
  assert.equal(base('2026-09-29'), 8000)
  assert.equal(base('2026-10-05'), 8000)
})

test('без даты берутся только действующие строки', () => {
  assert.equal(base(undefined), 8000)
})

test('строки до версионирования (без дат) действуют всегда', () => {
  assert.equal(pointRuleActiveOn({ effective_from: null, effective_to: null }, '2020-01-01'), true)
  assert.equal(pointRuleActiveOn({}, undefined), true)
})
