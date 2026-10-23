import { test } from 'node:test'
import assert from 'node:assert/strict'
import { calculateOperatorSalarySummary, type SalaryOperatorCompanyAssignment } from '@/lib/domain/salary'

const companies = [{ id: 'c1', code: 'CAFE', name: 'Кофейня' }]
const rules = [
  {
    company_code: 'CAFE',
    shift_type: 'day' as const,
    base_per_shift: 8000,
    senior_operator_bonus: 2000,
    senior_cashier_bonus: 1000,
    threshold1_turnover: null,
    threshold1_bonus: null,
    threshold2_turnover: null,
    threshold2_bonus: null,
  },
]
const incomes = [
  { date: '2026-10-20', company_id: 'c1', shift: 'day' as const, cash_amount: 50000, kaspi_amount: 0, card_amount: 0, operator_id: 'op1' },
  { date: '2026-10-21', company_id: 'c1', shift: 'day' as const, cash_amount: 50000, kaspi_amount: 0, card_amount: 0, operator_id: 'op1' },
]

function summary(assignment: Partial<SalaryOperatorCompanyAssignment>) {
  return calculateOperatorSalarySummary({
    operatorId: 'op1',
    companies,
    rules,
    assignments: [{ operator_id: 'op1', company_id: 'c1', role_in_company: 'operator', is_active: true, ...assignment }],
    incomes,
    adjustments: [],
    debts: [],
  })
}

test('без должности — бонус по базовой роли, как раньше', () => {
  assert.equal(summary({ role_in_company: 'operator' }).roleBonuses, 0)
  assert.equal(summary({ role_in_company: 'senior_operator' }).roleBonuses, 4000)
})

test('своя должность платит свою доплату за смену', () => {
  const s = summary({ role_in_company: 'operator', position_bonus: 1500 })
  assert.equal(s.roleBonuses, 3000)
  assert.equal(s.baseSalary, 16000)
})

test('доплата должности заменяет бонус старшего, а не складывается с ним', () => {
  assert.equal(summary({ role_in_company: 'senior_operator', position_bonus: 500 }).roleBonuses, 1000)
  assert.equal(summary({ role_in_company: 'senior_operator', position_bonus: 0 }).roleBonuses, 0)
})
