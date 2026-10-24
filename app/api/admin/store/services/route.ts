import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { requireCapability } from '@/lib/server/capabilities'
import { requireOrgFeature } from '@/lib/server/entitlements'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'
import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import { loadOrganizationServices } from '@/lib/server/store-services'

/**
 * Справочник услуг магазина (Магазин → Склад → Услуги). Права — те же, что у
 * каталога товаров: услуга — позиция каталога без остатка.
 */

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

type VariantInput = { id?: string | null; name: string; price: number; duration_minutes: number | null }

function parseVariants(raw: unknown): VariantInput[] | string {
  if (!Array.isArray(raw) || raw.length === 0) return 'Добавьте хотя бы один вариант цены'
  const out: VariantInput[] = []
  for (const v of raw as any[]) {
    const name = String(v?.name || '').trim()
    const price = Math.round(Number(v?.price || 0) * 100) / 100
    const minutesRaw = v?.duration_minutes
    const minutes = minutesRaw === null || minutesRaw === undefined || minutesRaw === '' ? null : Math.round(Number(minutesRaw))
    if (!name) return 'У варианта нет названия'
    if (name.length > 60) return 'Название варианта длиннее 60 символов'
    if (!(price > 0)) return `Цена варианта «${name}» должна быть больше 0`
    if (minutes !== null && !(minutes >= 1 && minutes <= 1440)) return `Время варианта «${name}» — от 1 до 1440 минут`
    out.push({ id: String(v?.id || '').trim() || null, name, price, duration_minutes: minutes })
  }
  return out
}

async function context(request: Request, capability: string) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return { response: access.response }
  const denied = await requireCapability(access, capability)
  if (denied) return { response: denied }
  const entitlementGuard = await requireOrgFeature(access, 'shop.catalog')
  if (entitlementGuard) return { response: entitlementGuard }
  const orgId = access.activeOrganization?.id || null
  if (!orgId) return { response: json({ error: 'Выберите организацию' }, 400) }
  const supabase = hasAdminSupabaseCredentials() ? createAdminSupabaseClient() : access.supabase
  return { access, supabase, orgId }
}

export async function GET(request: Request) {
  try {
    const ctx = await context(request, 'store-catalog.view')
    if ('response' in ctx) return ctx.response
    const services = await loadOrganizationServices(ctx.supabase, ctx.orgId)
    return json({ ok: true, data: { services } })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-store-services:get', message: error?.message || 'Store services GET error' })
    return json({ error: 'Не удалось загрузить услуги' }, 500)
  }
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as any
  const action = String(body?.action || '')
  const capability = action === 'delete' ? 'store-catalog.delete' : action === 'create' ? 'store-catalog.create' : 'store-catalog.edit'
  try {
    const ctx = await context(request, capability)
    if ('response' in ctx) return ctx.response
    const { supabase, orgId, access } = ctx
    const actorUserId = access.user?.id || null

    const loadOwn = async (id: string) => {
      const { data } = await supabase
        .from('store_services')
        .select('id, name')
        .eq('id', id)
        .eq('organization_id', orgId)
        .maybeSingle()
      return data as { id: string; name: string } | null
    }

    if (action === 'delete') {
      const id = String(body?.id || '').trim()
      const own = id ? await loadOwn(id) : null
      if (!own) return json({ error: 'Услуга не найдена' }, 404)
      // Проданные услуги остаются в чеках по названию; варианты уходят каскадом,
      // ссылка в строках продаж обнуляется (on delete set null).
      const { error } = await supabase.from('store_services').delete().eq('id', own.id)
      if (error) throw error
      await writeAuditLog(supabase, { actorUserId, entityType: 'store-service', entityId: own.id, action: 'delete', payload: { name: own.name } })
      return json({ ok: true })
    }

    if (action !== 'create' && action !== 'update') return json({ error: 'invalid-action' }, 400)

    const name = String(body?.name || '').trim()
    if (!name) return json({ error: 'Укажите название услуги' }, 400)
    if (name.length > 80) return json({ error: 'Название длиннее 80 символов' }, 400)
    const variants = parseVariants(body?.variants)
    if (typeof variants === 'string') return json({ error: variants }, 400)

    // Точки — только этой организации. Пусто — на всех.
    const requested = Array.isArray(body?.company_ids) ? [...new Set((body.company_ids as any[]).map((x) => String(x || '').trim()).filter(Boolean))] : []
    let companyIds: string[] = []
    if (requested.length) {
      const { data: rows } = await supabase.from('companies').select('id').eq('organization_id', orgId).in('id', requested)
      companyIds = ((rows || []) as any[]).map((r) => String(r.id))
      if (companyIds.length !== requested.length) return json({ error: 'Точка не из этой организации' }, 400)
    }
    const isActive = body?.is_active !== false

    let serviceId: string
    if (action === 'create') {
      const { data, error } = await supabase
        .from('store_services')
        .insert({ organization_id: orgId, name, company_ids: companyIds, is_active: isActive })
        .select('id')
        .single()
      if (error) throw error
      serviceId = String(data.id)
    } else {
      const own = await loadOwn(String(body?.id || '').trim())
      if (!own) return json({ error: 'Услуга не найдена' }, 404)
      serviceId = own.id
      const { error } = await supabase
        .from('store_services')
        .update({ name, company_ids: companyIds, is_active: isActive, updated_at: new Date().toISOString() })
        .eq('id', serviceId)
      if (error) throw error
    }

    // Варианты: существующие правим на месте (id сохраняется — на него ссылаются
    // строки продаж и таймеры), лишние удаляем, новые добавляем.
    const { data: existingRows } = await supabase.from('store_service_variants').select('id').eq('service_id', serviceId)
    const existing = new Set(((existingRows || []) as any[]).map((r) => String(r.id)))
    const kept = new Set<string>()
    for (let i = 0; i < variants.length; i += 1) {
      const v = variants[i]
      const row = { name: v.name, price: v.price, duration_minutes: v.duration_minutes, sort_order: i, is_active: true }
      if (v.id && existing.has(v.id)) {
        kept.add(v.id)
        const { error } = await supabase.from('store_service_variants').update(row).eq('id', v.id)
        if (error) throw error
      } else {
        const { error } = await supabase.from('store_service_variants').insert({ ...row, service_id: serviceId })
        if (error) throw error
      }
    }
    const toDelete = [...existing].filter((id) => !kept.has(id))
    if (toDelete.length) {
      const { error } = await supabase.from('store_service_variants').delete().in('id', toDelete)
      if (error) throw error
    }

    await writeAuditLog(supabase, {
      actorUserId,
      entityType: 'store-service',
      entityId: serviceId,
      action,
      payload: { name, company_ids: companyIds, variants: variants.map((v) => ({ name: v.name, price: v.price, duration_minutes: v.duration_minutes })) },
    })
    return json({ ok: true, data: { id: serviceId } })
  } catch (error: any) {
    const missing = /store_service/.test(String(error?.message || '')) && /does not exist|schema cache/.test(String(error?.message || ''))
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-store-services:post', message: error?.message || 'Store services POST error' })
    return json({ error: missing ? 'Услуги ещё не включены в базе — нужна миграция' : 'Не удалось сохранить услугу' }, 500)
  }
}
