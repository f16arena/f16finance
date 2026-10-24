import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ALL_POINT_PERMISSIONS, assignmentPointPermissions, sanitizePointPermissions } from '@/lib/core/point-permissions'

test('без должности — как раньше: старший всё, оператор ничего', () => {
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'senior_cashier' }), ALL_POINT_PERMISSIONS)
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'senior_operator' }), ALL_POINT_PERMISSIONS)
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'operator' }), [])
})

test('галочки должности важнее роли', () => {
  const adminHall = { permissions: ['schedule_requests'], base_role: 'operator', is_active: true }
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'operator', position: adminHall }), ['schedule_requests'])
  const barista = { permissions: [], base_role: 'senior_cashier', is_active: true }
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'senior_cashier', position: barista }), [])
})

test('должность без списка — права по её роли; в архиве — по роли назначения', () => {
  assert.deepEqual(assignmentPointPermissions({ position: { permissions: null, base_role: 'senior_operator' } }), ALL_POINT_PERMISSIONS)
  const archived = { permissions: ['point_tasks'], base_role: 'operator', is_active: false }
  assert.deepEqual(assignmentPointPermissions({ role_in_company: 'operator', position: archived }), [])
})

test('чужие ключи отбрасываются', () => {
  assert.deepEqual(sanitizePointPermissions(['point_tasks', 'delete_everything', 'schedule_requests']), ['schedule_requests', 'point_tasks'])
  assert.deepEqual(sanitizePointPermissions('point_tasks'), [])
})
