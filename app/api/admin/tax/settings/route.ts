import { NextResponse } from 'next/server'

import { normalizeTaxSettings } from '@/lib/core/org-settings'
import { writeAuditLog } from '@/lib/server/audit'
import { requireCapability } from '@/lib/server/capabilities'
import { getOrgSettings } from '@/lib/server/org-settings'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

/**
 * Налоговые настройки организации (organizations.settings.tax): ставка
 * маслихата, реквизиты ИП, что входит в оборот. Раньше жили в localStorage
 * браузера — с телефона или другого компьютера налог считался по 2 %, а
 * копилот — по 3 %.
 */
export async function GET(request: Request) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return access.response
  const denied = await requireCapability(access, 'tax.view')
  if (denied) return denied
  const settings = await getOrgSettings(access.activeOrganization?.id || null)
  return json({ ok: true, tax: settings.tax, canEdit: canEditTax(access) })
}

/** Менять — владелец или управляющий: от ставки зависит сумма в бюджет. */
function canEditTax(access: { isSuperAdmin: boolean; staffRole?: string | null }) {
  return access.isSuperAdmin || access.staffRole === 'owner' || access.staffRole === 'manager'
}

export async function PATCH(request: Request) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return access.response
  const denied = await requireCapability(access, 'tax.view')
  if (denied) return denied
  if (!canEditTax(access)) return json({ error: 'Налоговые настройки меняет владелец или управляющий' }, 403)
  const organizationId = access.activeOrganization?.id || null
  if (!organizationId) return json({ error: 'Выберите организацию' }, 400)
  if (!hasAdminSupabaseCredentials()) return json({ error: 'no admin supabase' }, 500)

  const body = (await request.json().catch(() => null)) as { tax?: unknown } | null
  if (!body?.tax || typeof body.tax !== 'object') return json({ error: 'Нет настроек' }, 400)

  const supabase = createAdminSupabaseClient()
  const { data: current, error: readError } = await supabase.from('organizations').select('settings').eq('id', organizationId).maybeSingle()
  if (readError) return json({ error: readError.message }, 500)
  const settings: Record<string, unknown> = { ...(((current as any)?.settings as Record<string, unknown>) || {}) }
  // Присланное поверх сохранённого: страница шлёт только изменённое.
  const tax = normalizeTaxSettings({ ...normalizeTaxSettings(settings.tax), ...(body.tax as Record<string, unknown>) })
  settings.tax = tax

  const { error } = await supabase.from('organizations').update({ settings }).eq('id', organizationId)
  if (error) return json({ error: error.message }, 500)
  await writeAuditLog(supabase as any, {
    actorUserId: access.user?.id || null,
    entityType: 'organization-settings',
    entityId: organizationId,
    action: 'update-tax',
    payload: { rate: tax.rate, vatPayer: tax.vatPayer, includeExtra: tax.includeExtra },
  })
  return json({ ok: true, tax })
}
