import { NextResponse } from 'next/server'

import { normalizeHolidaySettings } from '@/lib/core/holidays'
import {
  normalizeOperatorPaySchedule,
  normalizeStaffPaySchedule,
  serializeOperatorPaySchedule,
  serializeStaffPaySchedule,
} from '@/lib/core/org-settings'
import { writeAuditLog } from '@/lib/server/audit'
import { requireOwnerOrSuper } from '@/lib/server/capabilities'
import { getOrgSettings } from '@/lib/server/org-settings'
import { listOrganizationOperatorIds, listOrganizationStaffIds } from '@/lib/server/organizations'
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

  const body = (await request.json().catch(() => null)) as { staffPaySchedule?: unknown; operatorPaySchedule?: unknown; holidays?: unknown } | null
  if (!body || (body.staffPaySchedule === undefined && body.operatorPaySchedule === undefined && body.holidays === undefined)) {
    return json({ error: 'Нечего сохранять' }, 400)
  }

  const supabase = createAdminSupabaseClient()
  const { data: current, error: readError } = await supabase
    .from('organizations')
    .select('settings')
    .eq('id', organizationId)
    .single()
  if (readError) return json({ error: readError.message }, 500)

  const before = await getOrgSettings(organizationId)
  const nextStaff = body.staffPaySchedule !== undefined ? normalizeStaffPaySchedule(body.staffPaySchedule) : null
  const nextOperator = body.operatorPaySchedule !== undefined ? normalizeOperatorPaySchedule(body.operatorPaySchedule) : null
  const staffChanged = !!nextStaff && JSON.stringify(nextStaff) !== JSON.stringify(before.staffPaySchedule)
  const operatorChanged = !!nextOperator && JSON.stringify(nextOperator) !== JSON.stringify(before.operatorPaySchedule)

  // Сменить график можно, пока по нему ничего не выплачено. Иначе прошлые периоды
  // пересчитаются по-новому: неделя, выплаченная по старому графику, войдёт в новый
  // месяц как невыплаченная (двойная выплата), а оплаченная «1–15» закроет весь месяц.
  if (staffChanged) {
    const staffIds = await listOrganizationStaffIds({ activeOrganizationId: organizationId, isSuperAdmin: false, companyRestriction: null })
    if (staffIds.length > 0) {
      const { count } = await supabase
        .from('staff_salary_payments')
        .select('id', { count: 'exact', head: true })
        .in('staff_id', staffIds)
      if ((count || 0) > 0) {
        return json({ error: 'По окладу уже есть выплаты — график менять нельзя: прошлые выплаты пересчитались бы по-новому. Напишите в поддержку.' }, 409)
      }
    }
  }
  if (operatorChanged) {
    const operatorIds = await listOrganizationOperatorIds({ activeOrganizationId: organizationId, isSuperAdmin: false, includeInactive: true, companyRestriction: null })
    if (operatorIds.length > 0) {
      const { count } = await supabase
        .from('operator_salary_week_payments')
        .select('id', { count: 'exact', head: true })
        .in('operator_id', operatorIds)
        .eq('status', 'active')
      if ((count || 0) > 0) {
        return json({ error: 'Операторам уже есть выплаты — период менять нельзя: выплаченные недели посчитались бы заново. Напишите в поддержку.' }, 409)
      }
    }
  }

  const settings: Record<string, unknown> = { ...(((current as any)?.settings as Record<string, unknown>) || {}) }
  const changes: Record<string, unknown> = {}
  if (nextStaff) {
    const staffPaySchedule = nextStaff
    settings.staff_pay_schedule = serializeStaffPaySchedule(staffPaySchedule)
    changes.staffPaySchedule = { before: before.staffPaySchedule, after: staffPaySchedule }
  }
  if (nextOperator) {
    const operatorPaySchedule = nextOperator
    settings.operator_pay_schedule = serializeOperatorPaySchedule(operatorPaySchedule)
    changes.operatorPaySchedule = { before: before.operatorPaySchedule, after: operatorPaySchedule }
  }
  if (body.holidays !== undefined) {
    settings.holidays = normalizeHolidaySettings(body.holidays)
    changes.holidays = settings.holidays
  }
  const { error } = await supabase.from('organizations').update({ settings }).eq('id', organizationId)
  if (error) return json({ error: error.message }, 500)

  await writeAuditLog(supabase, {
    actorUserId: access.user?.id || null,
    entityType: 'organization-settings',
    entityId: organizationId,
    action: 'update',
    payload: changes,
  })

  return json({ ok: true, settings: await getOrgSettings(organizationId) })
}
