import { test } from 'node:test'
import assert from 'node:assert/strict'
import { formatMoney } from '@/lib/core/format'
import { normalizeOrgSettings } from '@/lib/core/org-settings'
import { currencySign, formatOrgMoney, orgHour, orgTodayISO, orgUtcOffsetMinutes } from '@/lib/core/org-locale'

test('без настроек — Алматы и тенге', () => {
  const settings = normalizeOrgSettings({})
  assert.equal(settings.timezone, 'Asia/Almaty')
  assert.equal(settings.currency, 'KZT')
})

test('мусор в настройках не ломает', () => {
  const settings = normalizeOrgSettings({ timezone: 'Mars/Olympus', currency: 'тенге' })
  assert.equal(settings.timezone, 'Asia/Almaty')
  assert.equal(settings.currency, 'KZT')
  assert.equal(normalizeOrgSettings({ timezone: 'Asia/Qyzylorda', currency: 'kzt' }).currency, 'KZT')
})

test('«сегодня» по Алматы: 20:30 UTC — уже следующий день', () => {
  const at = new Date('2026-10-23T20:30:00Z')
  assert.equal(orgTodayISO('Asia/Almaty', at), '2026-10-24')
  assert.equal(orgHour('Asia/Almaty', at), 1)
  assert.equal(orgUtcOffsetMinutes('Asia/Almaty', at), 300)
  assert.equal(orgTodayISO('Europe/Moscow', at), '2026-10-23')
  assert.equal(orgUtcOffsetMinutes('Europe/Moscow', at), 180)
})

test('деньги в тенге — как formatMoney', () => {
  assert.equal(formatOrgMoney(12500.4), formatMoney(12500.4))
  assert.equal(currencySign('RUB'), '₽')
  assert.equal(currencySign('XYZ'), 'XYZ')
})
