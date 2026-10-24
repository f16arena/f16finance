import 'server-only'

import { NextResponse } from 'next/server'

import { KASSA_PERMISSIONS, assignmentPointPermissions, type PointPermission } from '@/lib/core/point-permissions'
import { loadPointPositionsById } from '@/lib/server/request-auth'

/** Права оператора на точке: по его назначению и должности. null — не работает на этой точке. */
export async function loadPointOperatorPermissions(supabase: any, companyId: string, operatorId: string): Promise<PointPermission[] | null> {
  const { data, error } = await supabase
    .from('operator_company_assignments')
    .select('role_in_company, position_id')
    .eq('company_id', companyId)
    .eq('operator_id', operatorId)
    .eq('is_active', true)
    .limit(1)
    .maybeSingle()
  if (error) throw error
  if (!data) return null
  const positions = await loadPointPositionsById(supabase, [data.position_id])
  return assignmentPointPermissions({
    role_in_company: data.role_in_company,
    position: data.position_id ? positions.get(String(data.position_id)) || null : null,
  })
}

/** Кто работает на кассе: явно переданный оператор или заголовок кассы. */
export function pointOperatorFromRequest(request: Request, explicit?: string | null) {
  return explicit?.trim() || request.headers.get('x-point-operator-id')?.trim() || null
}

/**
 * Проверка права кассы. Ответ 403 с понятным текстом — или null, если можно.
 *
 * Оператор не указан (старая касса без заголовка) — пропускаем, как было до
 * галочек: иначе обновление сервера остановило бы продажи на старых версиях.
 * Жёстко «без оператора нельзя» — только там, где передаёт `strict`.
 */
export async function requirePointPermission(params: {
  request: Request
  supabase: any
  companyId: string
  permission: PointPermission
  operatorId?: string | null
  strict?: boolean
}): Promise<NextResponse | null> {
  const operatorId = pointOperatorFromRequest(params.request, params.operatorId)
  const label = KASSA_PERMISSIONS.find((p) => p.key === params.permission)?.label || params.permission
  if (!operatorId) {
    if (!params.strict) return null
    return NextResponse.json(
      { error: 'point-permission-denied', permission: params.permission, message: `Нужно право «${label}»: войдите под своим логином.` },
      { status: 403 },
    )
  }
  const permissions = await loadPointOperatorPermissions(params.supabase, params.companyId, operatorId)
  if (permissions?.includes(params.permission)) return null
  return NextResponse.json(
    {
      error: 'point-permission-denied',
      permission: params.permission,
      message: `У вашей должности нет права «${label}». Попросите старшего или владельца.`,
    },
    { status: 403 },
  )
}
