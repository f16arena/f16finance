import { test } from 'node:test'
import assert from 'node:assert/strict'

import { getAllCapabilityIds } from '../lib/core/capabilities'
import { NICHE_ROLES, isNicheRole, nicheRoleCapabilities } from '../lib/core/niche-roles'

test('роли ниши: каждое право есть в каталоге — опечатка не даст молча пустое право', () => {
  const catalog = new Set(getAllCapabilityIds())
  for (const role of Object.values(NICHE_ROLES)) {
    for (const cap of role.capabilities) assert.ok(catalog.has(cap), `${role.code}: нет в каталоге «${cap}»`)
  }
})

test('администратор детейлинга не видит деньги, мастеров и не правит прайс', () => {
  const caps = new Set(nicheRoleCapabilities('detailing_admin'))
  for (const hidden of ['detailing-money.view', 'detailing-masters.view', 'detailing-masters.pay', 'income.view', 'expenses.view', 'detailing-prices.edit', 'staff.view']) {
    assert.equal(caps.has(hidden), false, hidden)
  }
  for (const needed of ['detailing-bookings.create', 'detailing-bookings.take_payment', 'detailing-clients.edit', 'expenses.create', 'dashboard.view']) {
    assert.equal(caps.has(needed), true, needed)
  }
})

test('бухгалтер детейлинга видит деньги, но не меняет записи и не платит мастерам', () => {
  const caps = new Set(nicheRoleCapabilities('detailing_accountant'))
  for (const needed of ['detailing-money.view', 'income.view', 'expenses.view', 'expenses.create', 'detailing-bookings.view']) {
    assert.equal(caps.has(needed), true, needed)
  }
  for (const hidden of ['detailing-bookings.create', 'detailing-bookings.take_payment', 'detailing-masters.pay', 'detailing-masters.edit', 'detailing-prices.edit']) {
    assert.equal(caps.has(hidden), false, hidden)
  }
})

test('обычные роли не считаются ролями ниши', () => {
  for (const role of ['owner', 'manager', 'other', '', null, undefined]) {
    assert.equal(isNicheRole(role), false)
    assert.equal(nicheRoleCapabilities(role), null)
  }
})

test('мастер со своим входом видит только свой экран', () => {
  assert.deepEqual(nicheRoleCapabilities('detailing_master'), ['detailing-my.view'])
  assert.equal(NICHE_ROLES.detailing_master.home, '/detailing/my')
})
