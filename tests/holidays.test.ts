import { test } from 'node:test'
import assert from 'node:assert/strict'
import { holidaysOn, normalizeHolidaySettings } from '@/lib/core/holidays'

test('без настроек — как раньше: Наурыз 21 марта со стандартным текстом', () => {
  const list = holidaysOn(normalizeHolidaySettings(null), 3, 21)
  assert.equal(list.length, 1)
  assert.match(list[0].greeting, /Наурыз құтты болсын/)
  assert.deepEqual(holidaysOn(normalizeHolidaySettings(undefined), 4, 2), [])
})

test('выключенный праздник, свой текст и своя дата', () => {
  const settings = normalizeHolidaySettings({
    disabled: ['first_president_day', 'unknown_key'],
    greetings: { nauryz: 'Свой текст, {name}' },
    custom: [{ month: 2, day: 29, name: 'День рождения клуба' }, { month: 2, day: 30, name: 'нет такой даты' }],
  })
  assert.deepEqual(settings.disabled, ['first_president_day'])
  assert.deepEqual(holidaysOn(settings, 12, 1), [])
  assert.equal(holidaysOn(settings, 3, 21)[0].greeting, 'Свой текст, {name}')
  assert.equal(settings.custom.length, 1)
  assert.equal(holidaysOn(settings, 2, 29)[0].name, 'День рождения клуба')
})

test('рассылка выключена — никаких поздравлений', () => {
  assert.deepEqual(holidaysOn(normalizeHolidaySettings({ enabled: false }), 1, 1), [])
})
