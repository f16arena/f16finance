import { NextResponse } from 'next/server'

import { writeSystemErrorLogSafe } from '@/lib/server/audit'
import { resolveCompanyScope } from '@/lib/server/organizations'
import { createRequestSupabaseClient, getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'
import { requireCapability } from '@/lib/server/capabilities'
import { createCompanyForOrganization } from '@/lib/server/company-create'

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status })
}

export async function GET(req: Request) {
  try {
    const access = await getRequestAccessContext(req)
    if ('response' in access) return access.response
    // Роут был открыт любому аутентифицированному — включая клиента гостевого
    // контура, которому справочник точек организации знать незачем.
    if (!access.isSuperAdmin && !access.staffMember) return json({ error: 'forbidden' }, 403)

    const supabase = hasAdminSupabaseCredentials()
      ? createAdminSupabaseClient()
      : createRequestSupabaseClient(req)

    // Скоуп по организации: allowedCompanyIds === null только у супер-админа
    // без выбранной организации, [] → .in('id', []) вернёт 0 строк.
    const scope = await resolveCompanyScope({
      activeOrganizationId: access.activeOrganization?.id || null,
      isSuperAdmin: access.isSuperAdmin,
      companyRestriction: access.companyRestriction,
    })

    let query = supabase
      .from('companies')
      .select('id, name, code, exclude_from_totals, archived_at, industry')
      .order('name', { ascending: true })
    // Закрытые точки (archived_at) отдаём: по списку отчёты подписывают и суммируют
    // историю. ?open=1 — для форм новой работы (приход, расход, смена, найм, касса).
    if (new URL(req.url).searchParams.get('open') === '1') query = query.is('archived_at', null)

    if (scope.allowedCompanyIds) {
      if (scope.allowedCompanyIds.length === 0) return json({ data: [] })
      query = query.in('id', scope.allowedCompanyIds)
    }

    const { data, error } = await query
    if (error) throw error

    return json({ data: data ?? [] })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'api/admin/companies GET', message: error?.message || 'error' })
    return json({ error: error?.message || 'Ошибка сервера' }, 500)
  }
}

export async function POST(req: Request) {
  try {
    const access = await getRequestAccessContext(req)
    if ('response' in access) return access.response

    const denied = await requireCapability(access, 'settings.manage_companies')
    if (denied) return denied

    const body = (await req.json().catch(() => null)) as {
      name?: string | null
      code?: string | null
      showInStructure?: boolean | null
      excludeFromTotals?: boolean | null
      industry?: string | null
      address?: string | null
    } | null

    const supabase = hasAdminSupabaseCredentials()
      ? createAdminSupabaseClient()
      : createRequestSupabaseClient(req)

    const created = await createCompanyForOrganization(supabase, access, {
      name: String(body?.name || ''),
      code: body?.code,
      showInStructure: body?.showInStructure,
      excludeFromTotals: body?.excludeFromTotals,
      industry: body?.industry,
      address: body?.address,
    })
    if (!created.ok) return json({ error: created.error, code: created.code }, created.status)

    return json({ ok: true, company: created.company })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'api/admin/companies POST', message: error?.message || 'error' })
    return json({ error: error?.message || 'Ошибка сервера' }, 500)
  }
}
