/**
 * Права человека на точке — галочки у должности (point_positions.permissions).
 *
 * Раньше прав не было вовсе: «старший» (senior_operator / senior_cashier в
 * role_in_company) получал всё сразу, остальные — ничего, и своя должность
 * могла только «быть как одна из трёх ролей». Теперь должность сама
 * перечисляет, что можно. Без должности или с должностью без списка —
 * как раньше: старший получает всё.
 *
 * Касса на компьютере эти права пока не спрашивает — это следующий шаг.
 */

export const POINT_PERMISSIONS = [
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

export type PointPermission = (typeof POINT_PERMISSIONS)[number]['key']

export const ALL_POINT_PERMISSIONS: PointPermission[] = POINT_PERMISSIONS.map((p) => p.key)

const KNOWN = new Set<string>(ALL_POINT_PERMISSIONS)

/** Права по старой роли — для людей без должности и должностей без списка. */
export function legacyPointPermissions(role: string | null | undefined): PointPermission[] {
  return role === 'senior_operator' || role === 'senior_cashier' ? [...ALL_POINT_PERMISSIONS] : []
}

export function sanitizePointPermissions(input: unknown): PointPermission[] {
  if (!Array.isArray(input)) return []
  return ALL_POINT_PERMISSIONS.filter((key) => input.includes(key))
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
