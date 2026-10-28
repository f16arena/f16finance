import { requireApiKey, v1Error, v1Json, v1Options } from '@/lib/server/api-keys'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

export const OPTIONS = v1Options

/** Проверка ключа: какая точка и какие права. Удобно начать с него. */
export async function GET(request: Request) {
  const auth = await requireApiKey(request, 'schedule:read')
  if ('response' in auth) return auth.response
  const { data: company } = await createAdminSupabaseClient()
    .from('companies')
    .select('id, name')
    .eq('id', auth.key.companyId)
    .maybeSingle()
  if (!company) return v1Error(404, 'not_found', 'Точка не найдена')
  return v1Json({ key: { name: auth.key.name, scopes: auth.key.scopes }, point: { id: company.id, name: company.name } })
}
