import { writeAuditLog } from '@/lib/server/audit'
import { requireApiKey, v1Error, v1Json, v1Options } from '@/lib/server/api-keys'
import { deleteShift, scheduleErrorResponse } from '@/lib/server/api-v1-schedule'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

export const OPTIONS = v1Options

/** Убрать смену из графика. */
export async function DELETE(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const auth = await requireApiKey(request, 'schedule:write')
  if ('response' in auth) return auth.response
  try {
    const { id } = await params
    if (!/^[0-9a-f-]{36}$/i.test(id)) return v1Error(404, 'not_found', 'Смена не найдена')
    const db = createAdminSupabaseClient()
    const removed = await deleteShift(db, auth.key.companyId, auth.key.id, id)
    await writeAuditLog(db, {
      entityType: 'shift',
      entityId: id,
      action: 'api-deleted',
      organizationId: auth.key.organizationId,
      payload: { company_id: auth.key.companyId, api_key_id: auth.key.id, api_key_name: auth.key.name, ...removed },
    })
    return v1Json({ deleted: removed })
  } catch (error) {
    return scheduleErrorResponse(error, v1Error)
  }
}
