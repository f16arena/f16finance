import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeOrgSettings, normalizeStaffPaySchedule } from '@/lib/core/org-settings'
import {
  buildStaffSalarySummary,
  calcStaffToPay,
  getSalarySlotRange,
  getStaffPayPeriod,
  getStaffPaymentAdjustmentPeriod,
  staffPaymentPeriodMonth,
} from '@/lib/domain/staff-salary-slot'

const staff = { id: 's1', full_name: 'Айгуль', monthly_salary: 300_000 }
const monthly = { kind: 'monthly' as const, payDay: null }
const by10 = { kind: 'monthly' as const, payDay: 10 }

// ─── Пустые настройки = две половины месяца, как у F16 ─────────────────────

test('без настроек график — две половины месяца', () => {
  assert.deepEqual(normalizeOrgSettings({}).staffPaySchedule, { kind: 'semimonthly' })
  assert.deepEqual(normalizeOrgSettings(null).staffPaySchedule, { kind: 'semimonthly' })
  assert.deepEqual(normalizeStaffPaySchedule({ kind: 'weird' }), { kind: 'semimonthly' })
})

test('день выплаты вне 1–28 не принимается', () => {
  assert.deepEqual(normalizeStaffPaySchedule({ kind: 'monthly', payDay: 31 }), { kind: 'monthly', payDay: null })
  assert.deepEqual(normalizeStaffPaySchedule({ kind: 'monthly', payDay: 10 }), { kind: 'monthly', payDay: 10 })
})

test('две половины считают так же, как раньше', () => {
  for (const day of ['2026-10-01', '2026-10-15', '2026-10-16', '2026-10-31', '2026-02-28']) {
    const period = getStaffPayPeriod(day)
    const slot = Number(day.slice(8)) <= 15 ? 'first' : 'second'
    const legacy = getSalarySlotRange(day, slot)
    assert.deepEqual({ from: period?.from, to: period?.to, slot: period?.slot }, { ...legacy, slot })
    assert.equal(calcStaffToPay(staff, [], [], period).half, 150_000)
  }
})

// ─── Раз в месяц ───────────────────────────────────────────────────────────

test('раз в месяц в конце: период — весь текущий месяц, оклад целиком', () => {
  const period = getStaffPayPeriod('2026-10-20', monthly)
  assert.deepEqual(period, { from: '2026-10-01', to: '2026-10-31', slot: 'first', month: '2026-10', share: 1 })
  assert.equal(calcStaffToPay(staff, [], [], period).half, 300_000)
})

test('до 10-го: 5 ноября ещё платим за октябрь, 11 ноября копится ноябрь', () => {
  assert.equal(getStaffPayPeriod('2026-11-05', by10)?.month, '2026-10')
  assert.equal(getStaffPayPeriod('2026-11-10', by10)?.month, '2026-10')
  assert.equal(getStaffPayPeriod('2026-11-11', by10)?.month, '2026-11')
  assert.equal(getStaffPayPeriod('2026-01-03', by10)?.month, '2025-12')
})

test('выплата 5 ноября без period_month относится к октябрю', () => {
  assert.equal(staffPaymentPeriodMonth({ pay_date: '2026-11-05' }, by10), '2026-10')
  assert.equal(staffPaymentPeriodMonth({ pay_date: '2026-11-05', period_month: '2026-11-01' }, by10), '2026-11')
  // Две половины — как раньше, по месяцу даты
  assert.equal(staffPaymentPeriodMonth({ pay_date: '2026-11-05' }), '2026-11')
})

test('окно удержаний за октябрь при выплате 5 ноября — с 1 октября', () => {
  const period = getStaffPaymentAdjustmentPeriod('2026-11-05', 'first', null, by10)
  assert.equal(period?.from, '2026-10-01')
  assert.equal(period?.to, '2026-11-05')
  assert.equal(period?.share, 1)
})

test('уволенному при месячном графике — по день ухода', () => {
  const period = getStaffPayPeriod('2026-09-20', monthly)
  const calc = calcStaffToPay({ ...staff, dismissal_date: '2026-09-10' }, [], [], period)
  assert.equal(calc.half, 100_000) // 300 000 × 10 / 30
})

test('месяц закрыт одной выплатой при месячном графике', () => {
  const summary = buildStaffSalarySummary({
    staff: [staff],
    adjustments: [],
    payments: [{ id: 'p1', staff_id: 's1', pay_date: '2026-11-05', slot: 'first', amount: 300_000 }],
    today: '2026-11-07',
    schedule: by10,
  })
  assert.equal(summary.period?.month, '2026-10')
  assert.equal(summary.rows[0].month_closed, true)
  assert.equal(summary.rows[0].paid_this_month, 300_000)
})

test('две половины: одна выплата месяц не закрывает (как раньше)', () => {
  const summary = buildStaffSalarySummary({
    staff: [staff],
    adjustments: [],
    payments: [{ id: 'p1', staff_id: 's1', pay_date: '2026-10-01', slot: 'first', amount: 150_000 }],
    today: '2026-10-20',
  })
  assert.equal(summary.rows[0].month_closed, false)
  assert.deepEqual(summary.rows[0].paid_slots, ['first'])
})
