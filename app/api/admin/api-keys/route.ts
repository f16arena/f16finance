import { NextResponse } from 'next/server'

import { writeAuditLog } from '@/lib/server/audit'
import { API_SCOPES, API_SCOPE_LABELS, generateApiKey, normalizeScopes } from '@/lib/server/api-keys'
import { requireOwnerOrSuper } from '@/lib/server/capabilities'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

// Ключи открытого API выдаёт и отзывает только владелец организации.
async function ownerContext(request: Request) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return { response: access.response }
  const denied = requireOwnerOrSuper(access)
  if (denied) return { response: denied }
  const organizationId = access.activeOrganization?.id || null
  if (!organizationId) return { response: json({ error: 'Выберите организацию' }, 400) }
  return { access, organizationId }
}

export async function GET(request: Request) {
  const ctx = await ownerContext(request)
  if ('response' in ctx) return ctx.response
  const db = createAdminSupabaseClient()

  const [{ data: keys, error }, { data: companies }] = await Promise.all([
    db
      .from('api_keys')
      .select('id, name, company_id, key_prefix, scopes, created_at, last_used_at, revoked_at')
      .eq('organization_id', ctx.organizationId)
      .order('created_at', { ascending: false }),
    db
      .from('companies')
      .select('id, name')
      .eq('organization_id', ctx.organizationId)
      .is('archived_at', null)
      .order('name'),
  ])
  if (error) return json({ error: error.message }, 500)

  return json({
    keys: keys || [],
    companies: companies || [],
    scopes: API_SCOPES.map((value) => ({ value, label: API_SCOPE_LABELS[value] })),
  })
}

export async function POST(request: Request) {
  const ctx = await ownerContext(request)
  if ('response' in ctx) return ctx.response
  const body = (await request.json().catch(() => null)) as { name?: string; companyId?: string; scopes?: unknown } | null

  const name = String(body?.name || '').trim().slice(0, 80)
  const companyId = String(body?.companyId || '')
  const scopes = normalizeScopes(body?.scopes)
  if (!name) return json({ error: 'Назовите ключ — например, «Планировщик смен»' }, 400)
  if (scopes.length === 0) return json({ error: 'Выберите хотя бы одно право' }, 400)

  const db = createAdminSupabaseClient()
  const { data: company } = await db
    .from('companies')
    .select('id, name, organization_id, archived_at')
    .eq('id', companyId)
    .maybeSingle()
  if (!company || String(company.organization_id) !== ctx.organizationId || company.archived_at) {
    return json({ error: 'Точка не найдена' }, 404)
  }

  const generated = generateApiKey()
  const { data: row, error } = await db
    .from('api_keys')
    .insert({
      organization_id: ctx.organizationId,
      company_id: company.id,
      name,
      key_prefix: generated.prefix,
      key_hash: generated.hash,
      scopes,
      created_by: ctx.access.user?.id || null,
    })
    .select('id, name, company_id, key_prefix, scopes, created_at, last_used_at, revoked_at')
    .single()
  if (error) return json({ error: error.message }, 500)

  await writeAuditLog(db, {
    actorUserId: ctx.access.user?.id || null,
    entityType: 'api-key',
    entityId: row.id,
    action: 'create',
    organizationId: ctx.organizationId,
    payload: { company_id: company.id, name, scopes, key_prefix: generated.prefix },
  })

  // Сам ключ отдаём один раз — в базе только его хеш.
  return json({ key: row, secret: generated.key }, 201)
}

export async function DELETE(request: Request) {
  const ctx = await ownerContext(request)
  if ('response' in ctx) return ctx.response
  const id = new URL(request.url).searchParams.get('id') || ''

  const db = createAdminSupabaseClient()
  const { data: row, error } = await db
    .from('api_keys')
    .update({ revoked_at: new Date().toISOString() })
    .eq('id', id)
    .eq('organization_id', ctx.organizationId)
    .is('revoked_at', null)
    .select('id, name, company_id, key_prefix')
    .maybeSingle()
  if (error) return json({ error: error.message }, 500)
  if (!row) return json({ error: 'Ключ не найден или уже отозван' }, 404)

  await writeAuditLog(db, {
    actorUserId: ctx.access.user?.id || null,
    entityType: 'api-key',
    entityId: row.id,
    action: 'revoke',
    organizationId: ctx.organizationId,
    payload: { company_id: row.company_id, name: row.name, key_prefix: row.key_prefix },
  })
  return json({ ok: true })
}
