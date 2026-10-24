// Настройки организации — то, что раньше было зашито в код под F16.
//
// Хранятся в organizations.settings (jsonb). Пустое поле = значение F16, поэтому у
// F16 и у всех, кто ничего не менял, поведение прежнее. Новый клиент выставляет своё
// на /settings. Здесь только чистая логика: разбор, значения по умолчанию, подписи.

import { DEFAULT_HOLIDAY_SETTINGS, normalizeHolidaySettings, type HolidaySettings } from './holidays'

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
  /**
   * Часовой пояс (IANA) и валюта (ISO 4217) организации. Ключи settings.timezone /
   * settings.currency уже пишет панель /platform. Код сайта пока считает по
   * Казахстану (UTC+5, ₸) — новые места берут значения отсюда через
   * lib/core/org-locale.ts; старые переводятся, когда появится клиент вне KZ.
   */
  timezone: string
  currency: string
  /**
   * Свои названия встроенных должностей: { manager: 'Управляющий' }. Внутреннее
   * имя (manager) остаётся — на нём права; меняется только подпись везде.
   */
  positionLabels: Record<string, string>
  /** Налог (упрощёнка 910): ставка маслихата, реквизиты, что входит в оборот. */
  tax: OrgTaxSettings
  /** Поздравления команды с праздниками (lib/core/holidays.ts). */
  holidays: HolidaySettings
}

export type TaxableChannels = { cash: boolean; kaspi: boolean; online: boolean; card: boolean }

export type OrgTaxSettings = {
  /** Ставка маслихата 2–6 %; null — ещё не выбрана (страница просит выбрать). */
  rate: number | null
  companyFullName: string
  bin: string
  oked: string
  vatPayer: boolean
  /** Точки «не в общих итогах» (F16 Extra) включать в оборот. */
  includeExtra: boolean
  /** По точке: какие способы оплаты входят в оборот. Нет точки — всё входит. */
  taxableFilter: Record<string, TaxableChannels>
}

export const DEFAULT_TAX_SETTINGS: OrgTaxSettings = {
  rate: null,
  companyFullName: '',
  bin: '',
  oked: '',
  vatPayer: false,
  includeExtra: false,
  taxableFilter: {},
}

export function normalizeTaxSettings(raw: unknown): OrgTaxSettings {
  const v = (raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}) as Record<string, unknown>
  const rate = Number(v.rate)
  const filter: Record<string, TaxableChannels> = {}
  const rawFilter = v.taxableFilter && typeof v.taxableFilter === 'object' ? (v.taxableFilter as Record<string, any>) : {}
  for (const [companyId, ch] of Object.entries(rawFilter)) {
    if (!/^[0-9a-f-]{36}$/i.test(companyId) || !ch || typeof ch !== 'object') continue
    filter[companyId] = { cash: ch.cash !== false, kaspi: ch.kaspi !== false, online: ch.online !== false, card: ch.card !== false }
  }
  return {
    rate: Number.isFinite(rate) && rate >= 2 && rate <= 6 ? Math.round(rate * 10) / 10 : null,
    companyFullName: String(v.companyFullName || '').trim().slice(0, 120),
    bin: String(v.bin || '').replace(/\D/g, '').slice(0, 12),
    oked: String(v.oked || '').replace(/\D/g, '').slice(0, 5),
    vatPayer: v.vatPayer === true,
    includeExtra: v.includeExtra === true,
    taxableFilter: filter,
  }
}

export const DEFAULT_ORG_TIMEZONE = 'Asia/Almaty'
export const DEFAULT_ORG_CURRENCY = 'KZT'

export const DEFAULT_STAFF_PAY_SCHEDULE: StaffPaySchedule = { kind: 'semimonthly' }
export const DEFAULT_OPERATOR_PAY_SCHEDULE: OperatorPaySchedule = { kind: 'weekly', startDay: 1 }
/** Якорь двухнедельного периода по умолчанию — понедельник. */
export const DEFAULT_BIWEEKLY_ANCHOR = '2026-01-05'

export const DEFAULT_ORG_SETTINGS: OrgSettings = {
  staffPaySchedule: DEFAULT_STAFF_PAY_SCHEDULE,
  operatorPaySchedule: DEFAULT_OPERATOR_PAY_SCHEDULE,
  timezone: DEFAULT_ORG_TIMEZONE,
  currency: DEFAULT_ORG_CURRENCY,
  positionLabels: {},
  tax: DEFAULT_TAX_SETTINGS,
  holidays: DEFAULT_HOLIDAY_SETTINGS,
}

/** Встроенные должности, чью подпись организация может поменять. */
export const RENAMABLE_BUILTIN_POSITIONS = ['owner', 'manager', 'marketer'] as const

export function normalizePositionLabels(raw: unknown): Record<string, string> {
  const value = (raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}) as Record<string, unknown>
  const out: Record<string, string> = {}
  for (const key of RENAMABLE_BUILTIN_POSITIONS) {
    const label = String(value[key] ?? '').trim().replace(/\s+/g, ' ').slice(0, 40)
    if (label) out[key] = label
  }
  return out
}

/** Часовой пояс, который понимает Intl; иначе — Алматы. */
export function normalizeOrgTimezone(raw: unknown): string {
  const value = String(raw || '').trim()
  if (!value) return DEFAULT_ORG_TIMEZONE
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value })
    return value
  } catch {
    return DEFAULT_ORG_TIMEZONE
  }
}

/** Трёхбуквенный код валюты; иначе — тенге. */
export function normalizeOrgCurrency(raw: unknown): string {
  const value = String(raw || '').trim().toUpperCase()
  return /^[A-Z]{3}$/.test(value) ? value : DEFAULT_ORG_CURRENCY
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
    timezone: normalizeOrgTimezone(value.timezone),
    currency: normalizeOrgCurrency(value.currency),
    positionLabels: normalizePositionLabels(value.position_labels),
    tax: normalizeTaxSettings(value.tax),
    holidays: normalizeHolidaySettings(value.holidays),
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
