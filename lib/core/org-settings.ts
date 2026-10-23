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

export type OrgSettings = {
  staffPaySchedule: StaffPaySchedule
}

export const DEFAULT_STAFF_PAY_SCHEDULE: StaffPaySchedule = { kind: 'semimonthly' }

export const DEFAULT_ORG_SETTINGS: OrgSettings = {
  staffPaySchedule: DEFAULT_STAFF_PAY_SCHEDULE,
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
