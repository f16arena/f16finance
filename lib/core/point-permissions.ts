/**
 * Права человека на точке — галочки у должности (point_positions.permissions).
 *
 * Два набора:
 *   • старший — кабинет старшего на сайте и в приложении (заявки по графику,
 *     задачи точки, уведомления);
 *   • касса — что можно в кассе (возвраты, большая скидка, свободная цена,
 *     исправление оплаты, удаление чужих долгов). Проверяет сервер (/api/point/*).
 *
 * До галочек касса разрешала всё всем. Поэтому без должности (или у
 * должности без списка) права кассы есть у любого оператора, а права
 * старшего — только у старшего. Снять право — убрать галочку у должности.
 */

export const LEAD_PERMISSIONS = [
  {
    key: 'schedule_requests',
    label: 'Разбирать заявки на замену в графике',
    hint: 'Видит заявки операторов своей точки и предлагает: оставить, снять, заменить.',
  },
  {
    key: 'point_tasks',
    label: 'Вести задачи точки',
    hint: 'Берёт задачи точки в работу, отмечает выполненными, пишет комментарии.',
  },
  {
    key: 'shift_request_alerts',
    label: 'Получать уведомления о заявках на смену',
    hint: 'Telegram, когда оператор точки просит поменять смену.',
  },
] as const

/** Скидка больше этого процента — отдельное право. */
export const BIG_DISCOUNT_PERCENT = 10

export const KASSA_PERMISSIONS = [
  { key: 'kassa_refunds', label: 'Делать возвраты', hint: 'Вернуть деньги покупателю за товар.' },
  {
    key: 'kassa_big_discount',
    label: `Давать скидку больше ${BIG_DISCOUNT_PERCENT}%`,
    hint: 'В продаже и на сеанс в арене. Скидка бонусами лояльности сюда не считается.',
  },
  { key: 'kassa_free_price', label: 'Продавать универсальный товар', hint: 'Название и цену вводит кассир — например, товар до оприходования. Услуги из справочника продаются и без этого права.' },
  { key: 'kassa_correct_payment', label: 'Исправлять оплату в чеке', hint: 'Поменять наличные ↔ безнал в уже пробитом чеке.' },
  {
    key: 'kassa_delete_debts',
    label: 'Удалять чужие долги',
    hint: 'Свой долг или только что записанный (15 минут) можно удалить всегда.',
  },
] as const

export const POINT_PERMISSIONS = [...LEAD_PERMISSIONS, ...KASSA_PERMISSIONS]

export type LeadPermission = (typeof LEAD_PERMISSIONS)[number]['key']
export type KassaPermission = (typeof KASSA_PERMISSIONS)[number]['key']
export type PointPermission = LeadPermission | KassaPermission

export const ALL_LEAD_PERMISSIONS: LeadPermission[] = LEAD_PERMISSIONS.map((p) => p.key)
export const ALL_KASSA_PERMISSIONS: KassaPermission[] = KASSA_PERMISSIONS.map((p) => p.key)
export const ALL_POINT_PERMISSIONS: PointPermission[] = [...ALL_LEAD_PERMISSIONS, ...ALL_KASSA_PERMISSIONS]

const KNOWN = new Set<string>(ALL_POINT_PERMISSIONS)
const LEAD = new Set<string>(ALL_LEAD_PERMISSIONS)

/**
 * Права кассы, которые были у всех до галочек. «Удалять чужие долги» сюда не
 * входит: чужой долг оператора удалить было нельзя (только через дыру с
 * долгами клиентов) — это право владелец выдаёт сам.
 */
export const DEFAULT_KASSA_PERMISSIONS: KassaPermission[] = ALL_KASSA_PERMISSIONS.filter((p) => p !== 'kassa_delete_debts')

/** Права по старой роли — для людей без должности и должностей без списка. */
export function legacyPointPermissions(role: string | null | undefined): PointPermission[] {
  const senior = role === 'senior_operator' || role === 'senior_cashier'
  return senior ? [...ALL_LEAD_PERMISSIONS, ...DEFAULT_KASSA_PERMISSIONS] : [...DEFAULT_KASSA_PERMISSIONS]
}

export function sanitizePointPermissions(input: unknown): PointPermission[] {
  if (!Array.isArray(input)) return []
  return ALL_POINT_PERMISSIONS.filter((key) => input.includes(key))
}

/** Есть ли хоть одно право старшего — тогда у человека кабинет старшего. */
export function hasLeadPermission(permissions: readonly string[]) {
  return permissions.some((p) => LEAD.has(p))
}

/**
 * Права назначения: действующая должность со списком — её список, иначе — по
 * роли назначения (как было до галочек).
 */
export function assignmentPointPermissions(params: {
  role_in_company?: string | null
  position?: { permissions?: unknown; base_role?: string | null; is_active?: boolean | null } | null
}): PointPermission[] {
  const position = params.position
  if (position && position.is_active !== false) {
    if (Array.isArray(position.permissions)) return position.permissions.filter((p): p is PointPermission => KNOWN.has(String(p)))
    return legacyPointPermissions(position.base_role)
  }
  return legacyPointPermissions(params.role_in_company)
}
