import { requireApiKey, v1Error, v1Json, v1Options } from '@/lib/server/api-keys'
import { getWeek, mondayOf, parseIsoDate, scheduleErrorResponse, todayLocal } from '@/lib/server/api-v1-schedule'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

export const OPTIONS = v1Options

/** График недели. ?week=<любая дата недели>, без него — текущая неделя. */
export async function GET(request: Request) {
  const auth = await requireApiKey(request, 'schedule:read')
  if ('response' in auth) return auth.response
  try {
    const raw = new URL(request.url).searchParams.get('week')
    const day = raw ? parseIsoDate(raw, 'week') : todayLocal()
    return v1Json(await getWeek(createAdminSupabaseClient(), auth.key.companyId, mondayOf(day)))
  } catch (error) {
    return scheduleErrorResponse(error, v1Error)
  }
}
