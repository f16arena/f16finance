import 'server-only'

// Связь сотрудника (staff) с аккаунтом входа (auth.users) — только по email:
// колонки staff.user_id в базе нет и не было. Запросы с ней падали
// («column staff.user_id does not exist»), из-за чего не работало увольнение в «Кадрах».

type AnySupabase = any

export type StaffByAuthUser = {
  id: string
  full_name: string | null
  short_name: string | null
  role: string | null
  email: string | null
  phone: string | null
}

const normalizeEmail = (value: unknown) => String(value || '').trim().toLowerCase()

/** id аккаунта входа сотрудника по его email; null — аккаунта нет. */
export async function findAuthUserIdByEmail(supabase: AnySupabase, email: string | null | undefined) {
  const target = normalizeEmail(email)
  if (!target) return null
  try {
    const { data } = await supabase.auth.admin.listUsers({ page: 1, perPage: 1000 })
    const match = (data?.users || []).find((u: any) => normalizeEmail(u.email) === target)
    return match?.id ? String(match.id) : null
  } catch {
    return null
  }
}

/** Сотрудники по id аккаунтов входа (для подписей «кто сделал»). */
export async function findStaffByAuthUserIds(supabase: AnySupabase, userIds: string[]) {
  const result = new Map<string, StaffByAuthUser>()
  const ids = Array.from(new Set(userIds.filter(Boolean).map(String)))
  if (ids.length === 0) return result

  const emailByUserId = new Map<string, string>()
  await Promise.all(
    ids.map(async (id) => {
      try {
        const { data } = await supabase.auth.admin.getUserById(id)
        const email = normalizeEmail(data?.user?.email)
        if (email) emailByUserId.set(id, email)
      } catch { /* аккаунт удалён — подписи не будет */ }
    }),
  )
  if (emailByUserId.size === 0) return result

  const { data: staffRows } = await supabase
    .from('staff')
    .select('id, full_name, short_name, role, email, phone')
    .not('email', 'is', null)
  const staffByEmail = new Map<string, StaffByAuthUser>()
  for (const row of (staffRows || []) as any[]) {
    const email = normalizeEmail(row.email)
    if (email && !staffByEmail.has(email)) staffByEmail.set(email, row as StaffByAuthUser)
  }
  for (const [userId, email] of emailByUserId) {
    const staff = staffByEmail.get(email)
    if (staff) result.set(userId, staff)
  }
  return result
}
