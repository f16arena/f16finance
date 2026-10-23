import { NextResponse } from 'next/server'

import { normalizeStaffPaySchedule, serializeStaffPaySchedule } from '@/lib/core/org-settings'
import { writeAuditLog } from '@/lib/server/audit'
import { requireOwnerOrSuper } from '@/lib/server/capabilities'
import { getOrgSettings } from '@/lib/server/org-settings'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

/** Настройки своей организации (график выплат и т.п.). Видит любой сотрудник. */
export async function GET(request: Request) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin && !access.staffMember) return json({ error: 'forbidden' }, 403)
  const organizationId = access.activeOrganization?.id || null
  return json({ ok: true, organizationId, settings: await getOrgSettings(organizationId) })
}

/** Меняет только владелец: от графика зависят суммы зарплаты. */
export async function PATCH(request: Request) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return access.response
  const denied = requireOwnerOrSuper(access)
  if (denied) return denied
  const organizationId = access.activeOrganization?.id || null
  if (!organizationId) return json({ error: 'Выберите организацию' }, 400)

  const body = (await request.json().catch(() => null)) as { staffPaySchedule?: unknown } | null
  if (!body || body.staffPaySchedule === undefined) return json({ error: 'Нечего сохранять' }, 400)

  const supabase = createAdminSupabaseClient()
  const { data: current, error: readError } = await supabase
    .from('organizations')
    .select('settings')
    .eq('id', organizationId)
    .single()
  if (readError) return json({ error: readError.message }, 500)

  const before = await getOrgSettings(organizationId)
  const staffPaySchedule = normalizeStaffPaySchedule(body.staffPaySchedule)
  const settings = {
    ...(((current as any)?.settings as Record<string, unknown>) || {}),
    staff_pay_schedule: serializeStaffPaySchedule(staffPaySchedule),
  }
  const { error } = await supabase.from('organizations').update({ settings }).eq('id', organizationId)
  if (error) return json({ error: error.message }, 500)

  await writeAuditLog(supabase, {
    actorUserId: access.user?.id || null,
    entityType: 'organization-settings',
    entityId: organizationId,
    action: 'update',
    payload: { staffPaySchedule: { before: before.staffPaySchedule, after: staffPaySchedule } },
  })

  return json({ ok: true, settings: await getOrgSettings(organizationId) })
}
