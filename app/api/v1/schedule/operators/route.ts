import { requireApiKey, v1Error, v1Json, v1Options } from '@/lib/server/api-keys'
import { listPointOperators, scheduleErrorResponse } from '@/lib/server/api-v1-schedule'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

export const OPTIONS = v1Options

/** Кто может стоять в графике точки. Без ставок и зарплат. */
export async function GET(request: Request) {
  const auth = await requireApiKey(request, 'schedule:read')
  if ('response' in auth) return auth.response
  try {
    const operators = await listPointOperators(createAdminSupabaseClient(), auth.key.companyId)
    return v1Json({ operators })
  } catch (error) {
    return scheduleErrorResponse(error, v1Error)
  }
}
