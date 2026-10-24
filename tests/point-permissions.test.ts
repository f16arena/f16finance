import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ALL_LEAD_PERMISSIONS,
  DEFAULT_KASSA_PERMISSIONS,
  assignmentPointPermissions,
  hasLeadPermission,
  sanitizePointPermissions,
} from '@/lib/core/point-permissions'

test('без должности — как было: касса всем, старшему ещё и права старшего', () => {
  const senior = assignmentPointPermissions({ role_in_company: 'senior_cashier' })
  assert.deepEqual(senior, [...ALL_LEAD_PERMISSIONS, ...DEFAULT_KASSA_PERMISSIONS])
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'operator' }), DEFAULT_KASSA_PERMISSIONS)
  assert.equal(hasLeadPermission(assignmentPointPermissions({ role_in_company: 'operator' })), false)
})

test('чужие долги по умолчанию не удаляет никто — это право выдают отдельно', () => {
  assert.equal(DEFAULT_KASSA_PERMISSIONS.includes('kassa_delete_debts' as any), false)
  assert.equal(assignmentPointPermissions({ role_in_company: 'senior_operator' }).includes('kassa_delete_debts'), false)
})

test('галочки должности важнее роли', () => {
  const adminHall = { permissions: ['schedule_requests'], base_role: 'operator', is_active: true }
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'operator', position: adminHall }), ['schedule_requests'])
  const barista = { permissions: [], base_role: 'senior_cashier', is_active: true }
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'senior_cashier', position: barista }), [])
})

test('должность без списка — права по её роли; в архиве — по роли назначения', () => {
  assert.equal(hasLeadPermission(assignmentPointPermissions({ position: { permissions: null, base_role: 'senior_operator' } })), true)
  const archived = { permissions: ['point_tasks'], base_role: 'operator', is_active: false }
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'operator', position: archived }), DEFAULT_KASSA_PERMISSIONS)
})

test('чужие ключи отбрасываются', () => {
  assert.deepEqual(sanitizePointPermissions(['point_tasks', 'delete_everything', 'kassa_refunds']), ['point_tasks', 'kassa_refunds'])
  assert.deepEqual(sanitizePointPermissions('point_tasks'), [])
})
