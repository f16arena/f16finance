/**
 * Как ведёт себя точка — по её нише (companies.industry), а не по коду.
 *
 * Раньше поведение решали коды точек F16: arena — клуб с онлайн-оплатой,
 * ramen — касса со «старшим кассиром», extra — зал PS5 + VR без ночной смены.
 * У клиента с кодом CASTLE или COFFEE ничего из этого не включалось. Код остаётся
 * только запасным вариантом для точки, у которой ниша не выбрана.
 */

import { INDUSTRY_CODES, type IndustryCode } from './industries'

/** Зона дохода в incomes.zone — по ней отчёты делят выручку. */
export type IncomeZone = 'pc' | 'ramen' | 'extra' | 'other'

export type PointProfile = {
  kind: IndustryCode
  incomeZone: IncomeZone
  /** Приход вносится двумя строками: PS5 и VR. */
  splitPsVr: boolean
  /** Поле «онлайн» при вводе выручки (оплата бронирований через сайт). */
  showOnline: boolean
  /** В графике есть ночная смена. */
  hasNightShift: boolean
  /** Как зовут старшего на этой точке. */
  seniorLabel: 'Старший оператор' | 'Старший кассир'
}

const LEGACY_KIND_BY_CODE: Record<string, IndustryCode> = {
  arena: 'club',
  ramen: 'shop',
  extra: 'ps_club',
}

export function pointKind(company: { code?: string | null; industry?: string | null } | null | undefined): IndustryCode {
  const industry = String(company?.industry || '').trim()
  if ((INDUSTRY_CODES as string[]).includes(industry)) return industry as IndustryCode
  return LEGACY_KIND_BY_CODE[String(company?.code || '').trim().toLowerCase()] || 'other'
}

export function pointProfile(company: { code?: string | null; industry?: string | null } | null | undefined): PointProfile {
  const kind = pointKind(company)
  switch (kind) {
    case 'club':
      return { kind, incomeZone: 'pc', splitPsVr: false, showOnline: true, hasNightShift: true, seniorLabel: 'Старший оператор' }
    case 'ps_club':
      return { kind, incomeZone: 'extra', splitPsVr: true, showOnline: false, hasNightShift: false, seniorLabel: 'Старший оператор' }
    case 'shop':
    case 'food':
      return { kind, incomeZone: 'ramen', splitPsVr: false, showOnline: false, hasNightShift: true, seniorLabel: 'Старший кассир' }
    default:
      return { kind, incomeZone: 'other', splitPsVr: false, showOnline: false, hasNightShift: true, seniorLabel: 'Старший оператор' }
  }
}
