import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pointProfile } from '@/lib/core/point-profile'

test('точки F16 ведут себя как раньше — и по нише, и без неё (по коду)', () => {
  for (const industry of [null, 'club']) {
    const arena = pointProfile({ code: 'arena', industry })
    assert.equal(arena.incomeZone, 'pc')
    assert.equal(arena.showOnline, true)
    assert.equal(arena.hasNightShift, true)
  }
  for (const industry of [null, 'shop']) {
    const ramen = pointProfile({ code: 'ramen', industry })
    assert.equal(ramen.incomeZone, 'ramen')
    assert.equal(ramen.seniorLabel, 'Старший кассир')
  }
  for (const industry of [null, 'ps_club']) {
    const extra = pointProfile({ code: 'extra', industry })
    assert.equal(extra.splitPsVr, true)
    assert.equal(extra.hasNightShift, false)
    assert.equal(extra.incomeZone, 'extra')
  }
})

test('чужой код с нишей получает поведение ниши, а не «ничего»', () => {
  assert.equal(pointProfile({ code: 'CASTLE', industry: 'club' }).showOnline, true)
  assert.equal(pointProfile({ code: 'COFFEE', industry: 'food' }).seniorLabel, 'Старший кассир')
  assert.equal(pointProfile({ code: 'PSZONE', industry: 'ps_club' }).splitPsVr, true)
})

test('ниша важнее старого кода; без ниши и кода — обычная точка', () => {
  assert.equal(pointProfile({ code: 'arena', industry: 'food' }).incomeZone, 'ramen')
  const plain = pointProfile({ code: 'ZDANIE', industry: null })
  assert.equal(plain.kind, 'other')
  assert.equal(plain.hasNightShift, true)
  assert.equal(plain.splitPsVr, false)
  assert.equal(pointProfile({ code: null, industry: 'nonsense' }).kind, 'other')
})
