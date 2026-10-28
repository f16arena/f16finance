import { writeAuditLog } from '@/lib/server/audit'
import { requireApiKey, v1Error, v1Json, v1Options } from '@/lib/server/api-keys'
import { ScheduleError, parseIsoDate, scheduleErrorResponse, setShift } from '@/lib/server/api-v1-schedule'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

export const OPTIONS = v1Options

/** Поставить оператора на смену. Слот = дата + day/night; занятый слот заменяется. */
export async function PUT(request: Request) {
  const auth = await requireApiKey(request, 'schedule:write')
  if ('response' in auth) return auth.response
  try {
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null
    if (!body) throw new ScheduleError(400, 'bad_request', 'Тело запроса — JSON')
    const date = parseIsoDate(body.date, 'date')
    const shift = body.shift
    if (shift !== 'day' && shift !== 'night') throw new ScheduleError(400, 'bad_request', 'shift: "day" или "night"')
    const operatorId = String(body.operatorId || '')
    if (!operatorId) throw new ScheduleError(400, 'bad_request', 'operatorId обязателен')
    const comment = typeof body.comment === 'string' ? body.comment.slice(0, 500) : null

    const db = createAdminSupabaseClient()
    const result = await setShift(db, {
      companyId: auth.key.companyId,
      organizationId: auth.key.organizationId,
      date,
      shift,
      operatorId,
      comment,
    })
    await writeAuditLog(db, {
      entityType: 'shift',
      entityId: result.shift.id,
      action: `api-${result.mode}`,
      organizationId: auth.key.organizationId,
      payload: { company_id: auth.key.companyId, api_key_id: auth.key.id, api_key_name: auth.key.name, ...result.shift },
    })
    return v1Json(result, result.mode === 'created' ? 201 : 200)
  } catch (error) {
    return scheduleErrorResponse(error, v1Error)
  }
}
