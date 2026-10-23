// Настройки организации — то, что раньше было зашито в код под F16.
//
// Хранятся в organizations.settings (jsonb). Пустое поле = значение F16, поэтому у
// F16 и у всех, кто ничего не менял, поведение прежнее. Новый клиент выставляет своё
// на /settings. Здесь только чистая логика: разбор, значения по умолчанию, подписи.

/**
 * График выплаты оклада админ-составу.
 *
 * - semimonthly — две выплаты: за 1–15 и за 16–конец, по половине оклада (F16).
 * - monthly — одна выплата за календарный месяц. payDay = null: в конце того же
 *   месяца; payDay = N: до N-го числа следующего месяца («за октябрь — до 10 ноября»).
 */
export type StaffPaySchedule =
  | { kind: 'semimonthly' }
  | { kind: 'monthly'; payDay: number | null }

/**
 * Период зарплаты операторов (по сменам).
 *
 * - weekly — неделя с дня startDay (1 = пн … 7 = вс). F16: неделя пн–вс.
 * - biweekly — две недели от якорной даты anchor (её день недели — начало периода).
 * - semimonthly — 1–15 и 16–конец месяца.
 * - monthly — календарный месяц.
 * - daily — каждый день отдельно.
 */
export type OperatorPaySchedule =
  | { kind: 'weekly'; startDay: number }
  | { kind: 'biweekly'; anchor: string }
  | { kind: 'semimonthly' }
  | { kind: 'monthly' }
  | { kind: 'daily' }

export type OrgSettings = {
  staffPaySchedule: StaffPaySchedule
  operatorPaySchedule: OperatorPaySchedule
}

export const DEFAULT_STAFF_PAY_SCHEDULE: StaffPaySchedule = { kind: 'semimonthly' }
export const DEFAULT_OPERATOR_PAY_SCHEDULE: OperatorPaySchedule = { kind: 'weekly', startDay: 1 }
/** Якорь двухнедельного периода по умолчанию — понедельник. */
export const DEFAULT_BIWEEKLY_ANCHOR = '2026-01-05'

export const DEFAULT_ORG_SETTINGS: OrgSettings = {
  staffPaySchedule: DEFAULT_STAFF_PAY_SCHEDULE,
  operatorPaySchedule: DEFAULT_OPERATOR_PAY_SCHEDULE,
}

/** Неделя пн–вс — как было до настроек. Для неё работают старые пути (week_start = понедельник). */
export function isDefaultOperatorPaySchedule(schedule: OperatorPaySchedule): boolean {
  return schedule.kind === 'weekly' && schedule.startDay === 1
}

export function normalizeOperatorPaySchedule(raw: unknown): OperatorPaySchedule {
  const value = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  if (value.kind === 'weekly') {
    const day = Number(value.startDay)
    return { kind: 'weekly', startDay: Number.isInteger(day) && day >= 1 && day <= 7 ? day : 1 }
  }
  if (value.kind === 'biweekly') {
    const anchor = String(value.anchor || '')
    return { kind: 'biweekly', anchor: /^\d{4}-\d{2}-\d{2}$/.test(anchor) ? anchor : DEFAULT_BIWEEKLY_ANCHOR }
  }
  if (value.kind === 'semimonthly' || value.kind === 'monthly' || value.kind === 'daily') return { kind: value.kind }
  return DEFAULT_OPERATOR_PAY_SCHEDULE
}

export function serializeOperatorPaySchedule(schedule: OperatorPaySchedule): Record<string, unknown> {
  return { ...schedule }
}

const WEEKDAY_FROM = ['', 'понедельника', 'вторника', 'среды', 'четверга', 'пятницы', 'субботы', 'воскресенья']

export function describeOperatorPaySchedule(schedule: OperatorPaySchedule): string {
  switch (schedule.kind) {
    case 'weekly':
      return schedule.startDay === 1 ? 'Каждую неделю, пн–вс' : `Каждую неделю, с ${WEEKDAY_FROM[schedule.startDay]}`
    case 'biweekly':
      return 'Раз в две недели'
    case 'semimonthly':
      return 'Два раза в месяц: 1–15 и 16–конец'
    case 'monthly':
      return 'Раз в месяц'
    case 'daily':
      return 'Каждый день'
  }
}

/** День выплаты «до N-го»: 1–28, чтобы число было в каждом месяце. */
export const MAX_STAFF_PAY_DAY = 28

export function normalizeStaffPaySchedule(raw: unknown): StaffPaySchedule {
  const value = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  if (value.kind === 'monthly') {
    const day = Number(value.payDay)
    const payDay = Number.isInteger(day) && day >= 1 && day <= MAX_STAFF_PAY_DAY ? day : null
    return { kind: 'monthly', payDay }
  }
  return DEFAULT_STAFF_PAY_SCHEDULE
}

/** organizations.settings → настройки с подставленными значениями F16. */
export function normalizeOrgSettings(raw: unknown): OrgSettings {
  const value = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>
  return {
    staffPaySchedule: normalizeStaffPaySchedule(value.staff_pay_schedule),
    operatorPaySchedule: normalizeOperatorPaySchedule(value.operator_pay_schedule),
  }
}

/** Обратно в jsonb: ключи в snake_case, как остальные поля organizations.settings. */
export function serializeStaffPaySchedule(schedule: StaffPaySchedule): Record<string, unknown> {
  return schedule.kind === 'monthly'
    ? { kind: 'monthly', payDay: schedule.payDay }
    : { kind: 'semimonthly' }
}

export function describeStaffPaySchedule(schedule: StaffPaySchedule): string {
  if (schedule.kind === 'semimonthly') return 'Два раза в месяц: за 1–15 и за 16–конец, по половине оклада'
  if (schedule.payDay == null) return 'Раз в месяц, в конце месяца'
  return `Раз в месяц: за прошлый месяц до ${schedule.payDay}-го числа`
}
