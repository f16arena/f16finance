// Время и деньги «как у организации».
//
// Сайт писался под Казахстан: «сегодня» = UTC+5 (KZ_OFFSET, kzTodayISO, '+05:00' в ~55
// файлах), суммы — «… ₸» (~200 файлов). Новый код берёт пояс и валюту организации
// (OrgSettings.timezone / currency) через эти функции; старый переводится сюда, когда
// появится клиент вне Казахстана. Для F16 результат тот же: Asia/Almaty, KZT.

import { DEFAULT_ORG_CURRENCY, DEFAULT_ORG_TIMEZONE } from '@/lib/core/org-settings'

/** Части даты/времени в поясе организации. */
function zonedParts(timezone: string, at: Date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(at)
  const get = (type: string) => parts.find((p) => p.type === type)?.value || '00'
  return { date: `${get('year')}-${get('month')}-${get('day')}`, hour: Number(get('hour')) % 24, minute: Number(get('minute')) }
}

/** «Сегодня» (YYYY-MM-DD) в поясе организации. */
export function orgTodayISO(timezone: string = DEFAULT_ORG_TIMEZONE, at: Date = new Date()): string {
  return zonedParts(timezone, at).date
}

/** Час (0–23) в поясе организации — для границ смен и рассылок. */
export function orgHour(timezone: string = DEFAULT_ORG_TIMEZONE, at: Date = new Date()): number {
  return zonedParts(timezone, at).hour
}

/** Сдвиг пояса от UTC в минутах на момент `at` (Алматы: +300). */
export function orgUtcOffsetMinutes(timezone: string = DEFAULT_ORG_TIMEZONE, at: Date = new Date()): number {
  const local = zonedParts(timezone, at)
  const [y, m, d] = local.date.split('-').map(Number)
  const asUtc = Date.UTC(y, m - 1, d, local.hour, local.minute)
  const atMinute = Math.floor(at.getTime() / 60_000) * 60_000
  return Math.round((asUtc - atMinute) / 60_000)
}

const CURRENCY_SIGNS: Record<string, string> = {
  KZT: '₸',
  RUB: '₽',
  UZS: 'сум',
  KGS: 'сом',
  TJS: 'сом.',
  AZN: '₼',
  GEL: '₾',
  BYN: 'Br',
  USD: '$',
  EUR: '€',
}

/** Знак валюты; незнакомая — её код. */
export function currencySign(currency: string = DEFAULT_ORG_CURRENCY): string {
  const code = String(currency || DEFAULT_ORG_CURRENCY).toUpperCase()
  return CURRENCY_SIGNS[code] || code
}

/** «12 500 ₸» в валюте организации (целые, как formatMoney). */
export function formatOrgMoney(value: number, currency: string = DEFAULT_ORG_CURRENCY): string {
  return `${Math.round(Number(value || 0)).toLocaleString('ru-RU')} ${currencySign(currency)}`
}
