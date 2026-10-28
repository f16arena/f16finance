import { writeAuditLog } from '@/lib/server/audit'
import { requireApiKey, v1Error, v1Json, v1Options } from '@/lib/server/api-keys'
import { ScheduleError, mondayOf, parseIsoDate, scheduleErrorResponse } from '@/lib/server/api-v1-schedule'
import { publishShiftWeekForCompany } from '@/lib/server/shift-workflow'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

export const OPTIONS = v1Options

/**
 * Опубликовать неделю: каждому оператору уходит в Telegram его смены
 * с кнопками «Подтверждаю» / «Есть проблема» — так же, как кнопка на сайте.
 */
export async function POST(request: Request) {
  const auth = await requireApiKey(request, 'schedule:publish')
  if ('response' in auth) return auth.response
  try {
    const body = (await request.json().catch(() => null)) as Record<string, unknown> | null
    const weekStart = mondayOf(parseIsoDate(body?.week, 'week'))
    const db = createAdminSupabaseClient()

    let result: Awaited<ReturnType<typeof publishShiftWeekForCompany>>
    try {
      result = await publishShiftWeekForCompany({ supabase: db, companyId: auth.key.companyId, weekStart, actorUserId: null })
    } catch (error) {
      // «Нет смен на неделю» и т.п. — понятные ошибки данных, а не сбой.
      if (error instanceof Error && /смен|оператор/i.test(error.message)) {
        throw new ScheduleError(422, 'nothing_to_publish', error.message)
      }
      throw error
    }

    await writeAuditLog(db, {
      entityType: 'shift-publication',
      entityId: result.publication.id,
      action: 'api-publish-week',
      organizationId: auth.key.organizationId,
      payload: {
        company_id: auth.key.companyId,
        api_key_id: auth.key.id,
        api_key_name: auth.key.name,
        week_start: weekStart,
        delivered: result.delivered,
        missing_telegram: result.missingTelegram,
        failed: result.failed,
      },
    })

    return v1Json({
      weekStart,
      weekEnd: result.weekEnd,
      version: result.publication.version,
      delivered: result.delivered,
      missingTelegram: result.missingTelegram,
      failed: result.failed,
      details: result.deliveryDetails.map((d) => ({ name: d.operator_name, status: d.status, reason: d.reason || null })),
    })
  } catch (error) {
    return scheduleErrorResponse(error, v1Error)
  }
}
