// Период зарплаты оператора по графику организации.
//
// Раньше период был зашит как «неделя пн–вс»: week_start = понедельник, week_end =
// понедельник + 6 (даже check в БД). Теперь границы считает эта функция, а
// operator_salary_weeks.week_start/week_end хранят начало и конец периода любой длины.
// Для F16 (неделя пн–вс) результат тот же, что и раньше.

import {
  DEFAULT_OPERATOR_PAY_SCHEDULE,
  type OperatorPaySchedule,
} from '@/lib/core/org-settings'

export type OperatorPayPeriod = { start: string; end: string }

function toDays(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number)
  return Math.floor(Date.UTC(y, m - 1, d) / 86_400_000)
}

function fromDays(days: number): string {
  return new Date(days * 86_400_000).toISOString().slice(0, 10)
}

function isIsoDate(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`))
}

function lastDayOfMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

/** ISO-день недели: 1 = пн … 7 = вс. */
function isoWeekday(days: number): number {
  // 1970-01-01 — четверг (4)
  return ((((days + 3) % 7) + 7) % 7) + 1
}

/** Период, в который попадает дата. null — дата некорректна. */
export function operatorPayPeriodFor(
  isoDate: string | null | undefined,
  schedule: OperatorPaySchedule = DEFAULT_OPERATOR_PAY_SCHEDULE,
): OperatorPayPeriod | null {
  const date = String(isoDate || '').slice(0, 10)
  if (!isIsoDate(date)) return null
  const days = toDays(date)
  const [y, m, d] = date.split('-').map(Number)
  const mm = String(m).padStart(2, '0')

  switch (schedule.kind) {
    case 'weekly': {
      const offset = (isoWeekday(days) - schedule.startDay + 7) % 7
      const start = days - offset
      return { start: fromDays(start), end: fromDays(start + 6) }
    }
    case 'biweekly': {
      const anchor = isIsoDate(schedule.anchor) ? toDays(schedule.anchor) : toDays('2026-01-05')
      const index = Math.floor((days - anchor) / 14)
      const start = anchor + index * 14
      return { start: fromDays(start), end: fromDays(start + 13) }
    }
    case 'semimonthly':
      return d <= 15
        ? { start: `${y}-${mm}-01`, end: `${y}-${mm}-15` }
        : { start: `${y}-${mm}-16`, end: `${y}-${mm}-${String(lastDayOfMonth(y, m)).padStart(2, '0')}` }
    case 'monthly':
      return { start: `${y}-${mm}-01`, end: `${y}-${mm}-${String(lastDayOfMonth(y, m)).padStart(2, '0')}` }
    case 'daily':
      return { start: date, end: date }
  }
}

/** Соседний период: -1 — предыдущий, +1 — следующий. */
export function shiftOperatorPayPeriod(
  period: OperatorPayPeriod,
  direction: -1 | 1,
  schedule: OperatorPaySchedule = DEFAULT_OPERATOR_PAY_SCHEDULE,
): OperatorPayPeriod {
  const probe = direction < 0 ? toDays(period.start) - 1 : toDays(period.end) + 1
  return operatorPayPeriodFor(fromDays(probe), schedule) || period
}

/** Число дней периода (неделя — 7). */
export function operatorPayPeriodDays(period: OperatorPayPeriod): number {
  return toDays(period.end) - toDays(period.start) + 1
}
