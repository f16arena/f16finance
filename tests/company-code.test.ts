import { test } from 'node:test'
import assert from 'node:assert/strict'
import { companyCodeBase, companyNameMatches, pickFreeCompanyCode, transliterate } from '@/lib/core/company-code'

test('код из кириллицы и казахских букв — латиница без пробелов', () => {
  assert.equal(companyCodeBase('Кофейня на Абая'), 'KOFEYNYANA')
  assert.equal(companyCodeBase('Қазақ Ұлы'), 'KAZAKULY')
  assert.equal(companyCodeBase('F16 Arena'), 'F16ARENA')
  assert.equal(companyCodeBase('★★★'), 'POINT')
})

test('занятый код — берём следующий номер, регистр не важен', () => {
  assert.equal(pickFreeCompanyCode('ARENA', [], 'X'), 'ARENA')
  assert.equal(pickFreeCompanyCode('ARENA', ['arena'], 'X'), 'ARENA2')
  assert.equal(pickFreeCompanyCode('ARENA', ['ARENA', 'arena2', 'ARENA3'], 'X'), 'ARENA4')
  const all = ['ARENA', ...Array.from({ length: 8 }, (_, i) => `ARENA${i + 2}`)]
  assert.equal(pickFreeCompanyCode('ARENA', all, 'K7Q'), 'ARENAK7Q')
})

test('логин из имени', () => {
  assert.equal(transliterate('Айгуль').toLowerCase(), 'aygul')
})

test('удаление — только с точно набранным названием', () => {
  assert.equal(companyNameMatches('F16 Test', 'f16  test '), true)
  assert.equal(companyNameMatches('F16 Test', 'F16'), false)
  assert.equal(companyNameMatches('F16 Test', ''), false)
  assert.equal(companyNameMatches('F16 Test', undefined), false)
})
