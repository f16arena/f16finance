import { test } from 'node:test'
import assert from 'node:assert/strict'

import { durationLabel, hourRange, normalizeDuration, slotEndTime, slotTimeLabel, slotsOverlap } from '../lib/core/detailing-slots'

const day = (start_date: string, days = 1) => ({ start_date, days })
const hours = (start_date: string, start_time: string, duration_minutes: number) => ({ start_date, days: 1, start_time, duration_minutes })

test('запись без длительности держит бокс весь день', () => {
  assert.equal(hourRange({ start_date: '2026-11-19', days: 1, start_time: '10:00' }), null)
  assert.equal(slotsOverlap(day('2026-11-19'), hours('2026-11-19', '10:00', 60)), true)
  assert.equal(slotsOverlap({ start_date: '2026-11-19', days: 1, start_time: '10:00' }, hours('2026-11-19', '15:00', 60)), true)
})

test('две быстрые работы в один день не мешают друг другу, если часы не пересекаются', () => {
  assert.equal(slotsOverlap(hours('2026-11-19', '10:00', 60), hours('2026-11-19', '11:00', 60)), false)
  assert.equal(slotsOverlap(hours('2026-11-19', '10:00', 90), hours('2026-11-19', '11:00', 60)), true)
  assert.equal(slotsOverlap(hours('2026-11-19', '10:00', 60), hours('2026-11-19', '10:30', 30)), true)
})

test('разные дни не пересекаются, многодневная работа закрывает все свои дни', () => {
  assert.equal(slotsOverlap(hours('2026-11-19', '10:00', 60), hours('2026-11-20', '10:00', 60)), false)
  assert.equal(slotsOverlap(day('2026-11-18', 3), hours('2026-11-20', '10:00', 60)), true)
  assert.equal(slotsOverlap(day('2026-11-18', 2), hours('2026-11-20', '10:00', 60)), false)
})

test('длительность у многодневной записи не считается', () => {
  assert.equal(hourRange({ start_date: '2026-11-19', days: 2, start_time: '10:00', duration_minutes: 60 }), null)
})

test('подписи времени и длительности', () => {
  assert.equal(slotEndTime(hours('2026-11-19', '15:30', 90)), '17:00')
  assert.equal(slotTimeLabel(hours('2026-11-19', '9:00', 30)), '09:00–09:30')
  assert.equal(slotTimeLabel({ start_date: '2026-11-19', days: 1, start_time: '15:00:00' }), '15:00')
  assert.equal(slotTimeLabel(day('2026-11-19')), '')
  assert.equal(durationLabel(30), '30 мин')
  assert.equal(durationLabel(60), '1 ч')
  assert.equal(durationLabel(90), '1,5 ч')
})

test('мусор в длительности — это «весь день»', () => {
  assert.equal(normalizeDuration(null), null)
  assert.equal(normalizeDuration(0), null)
  assert.equal(normalizeDuration('abc'), null)
  assert.equal(normalizeDuration(5000), null)
  assert.equal(normalizeDuration('120'), 120)
})
