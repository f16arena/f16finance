/**
 * Подписи должностей — одни и те же на всех страницах (доступ, HR, найм,
 * структура, мастер точки).
 *
 * Должности бывают двух видов:
 *   • в офисе — роль портала (positions): какие страницы и действия доступны;
 *   • на точке — point_positions: права в кассе (базовая роль) и доплата за смену.
 */

export type PositionKind = 'office' | 'point'

export const POSITION_KIND_LABELS: Record<PositionKind, { title: string; hint: string }> = {
  office: { title: 'В офисе', hint: 'доступ к страницам портала' },
  point: { title: 'На точке', hint: 'права в кассе и доплата за смену' },
}

/** Встроенные роли портала — хранятся латиницей, показываются по-русски. */
export const OFFICE_BUILTIN_LABELS: Record<string, string> = {
  owner: 'Владелец',
  manager: 'Руководитель',
  marketer: 'Маркетолог',
  other: 'Прочие',
}

export function officePositionLabel(name: string | null | undefined, label?: string | null) {
  if (label?.trim()) return label.trim()
  const key = String(name || '').trim()
  if (!key) return 'нет должности'
  return OFFICE_BUILTIN_LABELS[key] ?? key.replace(/_/g, ' ')
}

/** Базовые роли на точке — от них права в кассе. */
export type PointBaseRole = 'operator' | 'senior_operator' | 'senior_cashier'

export const POINT_BASE_ROLE_LABELS: Record<PointBaseRole, string> = {
  operator: 'Оператор',
  senior_operator: 'Старший оператор',
  senior_cashier: 'Старший кассир',
}

export function peopleCountLabel(count: number) {
  const mod10 = count % 10
  const mod100 = count % 100
  const word = mod10 === 1 && mod100 !== 11 ? 'человек' : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? 'человека' : 'человек'
  return count === 0 ? 'никого' : `${count} ${word}`
}
