/**
 * Занятость бокса. Запись держит бокс целыми днями — кроме «быстрой» работы:
 * один день, назначено время и длительность. Такая запись занимает только свои
 * часы, и в тот же бокс в тот же день можно поставить ещё машину.
 *
 * Логика общая для сервера (проверка при сохранении) и экрана (подсказка
 * «занят»), чтобы они не расходились.
 */

export type Slot = {
  start_date: string
  days: number
  start_time?: string | null
  duration_minutes?: number | null
}

export const MIN_DURATION = 15
export const MAX_DURATION = 720

/** Длительности, которые предлагаем кнопками. */
export const DURATION_CHOICES = [30, 60, 90, 120, 180, 240]

function shiftDate(isoDate: string, days: number) {
  const d = new Date(`${isoDate}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function timeToMinutes(time: string | null | undefined): number | null {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(time || ''))
  if (!m) return null
  const value = Number(m[1]) * 60 + Number(m[2])
  return value < 24 * 60 ? value : null
}

export function minutesToTime(total: number) {
  const capped = Math.min(24 * 60, Math.max(0, Math.round(total)))
  return `${String(Math.floor(capped / 60)).padStart(2, '0')}:${String(capped % 60).padStart(2, '0')}`
}

/** Длительность из запроса: число минут в разумных пределах, иначе null (весь день). */
export function normalizeDuration(value: unknown): number | null {
  const n = Math.round(Number(value))
  return Number.isFinite(n) && n >= MIN_DURATION && n <= MAX_DURATION ? n : null
}

/** Часы быстрой работы в минутах от полуночи; null — запись держит бокс весь день. */
export function hourRange(slot: Slot): { from: number; to: number } | null {
  if (Number(slot.days) !== 1) return null
  const from = timeToMinutes(slot.start_time)
  const duration = normalizeDuration(slot.duration_minutes)
  if (from === null || duration === null) return null
  return { from, to: from + duration }
}

/** «16:30» — когда быстрая работа закончится; null у записи на весь день. */
export function slotEndTime(slot: Slot): string | null {
  const range = hourRange(slot)
  return range ? minutesToTime(range.to) : null
}

/** «10:00–11:30» для быстрой работы, «10:00» для записи со временем, '' без времени. */
export function slotTimeLabel(slot: Slot): string {
  const end = slotEndTime(slot)
  const start = timeToMinutes(slot.start_time)
  if (start === null) return ''
  return end ? `${minutesToTime(start)}–${end}` : minutesToTime(start)
}

/** Мешают ли две записи друг другу в одном боксе. */
export function slotsOverlap(a: Slot, b: Slot): boolean {
  const aEnd = shiftDate(a.start_date, Math.max(1, Number(a.days) || 1)) // не включительно
  const bEnd = shiftDate(b.start_date, Math.max(1, Number(b.days) || 1))
  if (!(a.start_date < bEnd && b.start_date < aEnd)) return false
  const ra = hourRange(a)
  const rb = hourRange(b)
  // Хотя бы одна стоит весь день — бокс занят.
  if (!ra || !rb) return true
  return ra.from < rb.to && rb.from < ra.to
}

/** «30 мин», «1 ч», «1,5 ч». */
export function durationLabel(minutes: number) {
  if (minutes < 60) return `${minutes} мин`
  const hours = minutes / 60
  return `${Number.isInteger(hours) ? hours : String(Math.round(hours * 10) / 10).replace('.', ',')} ч`
}
