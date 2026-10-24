import { test } from 'node:test'
import assert from 'node:assert/strict'
import { activeZones, companyZones, sanitizeZones, zoneKeyFromLabel } from '@/lib/core/revenue-zones'

test('без настройки — как раньше: PS-зал получает PS5 + VR, остальные без зон', () => {
  assert.deepEqual(companyZones({ code: 'extra', industry: 'ps_club' }).map((z) => z.key), ['ps5', 'vr'])
  assert.deepEqual(companyZones({ code: 'extra' }).map((z) => z.key), ['ps5', 'vr'])
  assert.deepEqual(companyZones({ code: 'arena', industry: 'club' }), [])
  assert.deepEqual(companyZones({ code: 'X', industry: 'ps_club', revenue_zones: [] }), [])
})

test('выключенная VR пропадает из формы, но остаётся в списке', () => {
  const extra = {
    code: 'extra',
    revenue_zones: [
      { key: 'ps5', label: 'PS5', active: true },
      { key: 'vr', label: 'VR', active: false },
    ],
  }
  assert.deepEqual(activeZones(extra).map((z) => z.key), ['ps5'])
  assert.equal(companyZones(extra).length, 2)
})

test('ключ из названия: латиница, без повторов', () => {
  assert.equal(zoneKeyFromLabel('Бильярд', []), 'bilyard')
  assert.equal(zoneKeyFromLabel('PS 5', []), 'ps_5')
  assert.equal(zoneKeyFromLabel('Бильярд', ['bilyard']), 'bilyard_2')
  assert.equal(zoneKeyFromLabel('???', []), 'zone')
})

test('переименование не меняет ключ, а пропавшая зона не теряется — выключается', () => {
  const existing = [
    { key: 'ps5', label: 'PS5', active: true },
    { key: 'vr', label: 'VR', active: true },
  ]
  const result = sanitizeZones([{ key: 'ps5', label: 'PlayStation 5', active: true }, { label: 'Кальян' }], existing)
  assert.equal(result.ok, true)
  if (!result.ok) return
  assert.deepEqual(result.zones, [
    { key: 'ps5', label: 'PlayStation 5', active: true },
    { key: 'kalyan', label: 'Кальян', active: true },
    { key: 'vr', label: 'VR', active: false },
  ])
})

test('пустое название и дубль — ошибка', () => {
  assert.equal(sanitizeZones([{ label: '  ' }], []).ok, false)
  assert.equal(sanitizeZones([{ label: 'VR' }, { label: 'vr' }], []).ok, false)
  assert.equal(sanitizeZones('nope', []).ok, false)
})
