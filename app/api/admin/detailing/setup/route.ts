import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import {
  DEFAULT_SETUP,
  detailingContext,
  isMissingDetailingTable,
  json,
  loadReference,
  pickCompany,
} from '@/lib/server/detailing'

/**
 * Прайс и боксы точки-детейлинга: боксы, классы кузова, услуги с ценой и
 * материалами по каждому кузову. Правка прайса не меняет уже созданные
 * записи — у них свой снимок цены.
 */

export async function GET(request: Request) {
  try {
    const ctx = await detailingContext(request, ['detailing-prices.view', 'detailing-bookings.view'])
    if ('response' in ctx) return ctx.response
    const company = pickCompany(ctx, new URL(request.url).searchParams.get('company_id'))
    if (!company) return json({ ok: true, data: { companies: ctx.companies, company: null } })
    const [ref, bookings] = await Promise.all([
      loadReference(ctx, company.id),
      ctx.supabase.from('detailing_bookings').select('id', { count: 'exact', head: true }).eq('company_id', company.id),
    ])
    return json({ ok: true, data: { companies: ctx.companies, company, ...ref, bookingsCount: bookings.count || 0 } })
  } catch (error: any) {
    if (isMissingDetailingTable(error)) return json({ error: 'Детейлинг ещё не включён в базе — нужна миграция 20261029_detailing_core' }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-setup:get', message: error?.message || 'Detailing setup GET error' })
    return json({ error: 'Не удалось загрузить прайс' }, 500)
  }
}

const CAPABILITY_BY_ACTION: Record<string, string> = {
  seed: 'detailing-prices.create',
  'box.save': 'detailing-prices.edit',
  'box.delete': 'detailing-prices.delete',
  'class.save': 'detailing-prices.edit',
  'class.delete': 'detailing-prices.delete',
  'service.save': 'detailing-prices.edit',
  'service.delete': 'detailing-prices.delete',
}

const TABLE_BY_KIND: Record<string, string> = {
  box: 'detailing_boxes',
  class: 'detailing_body_classes',
  service: 'detailing_services',
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as any
  const action = String(body?.action || '')
  const capability = CAPABILITY_BY_ACTION[action]
  if (!capability) return json({ error: 'invalid-action' }, 400)
  try {
    const ctx = await detailingContext(request, capability)
    if ('response' in ctx) return ctx.response
    const company = pickCompany(ctx, body?.company_id)
    if (!company) return json({ error: 'Точка не найдена' }, 404)
    const actorUserId = ctx.access.user?.id || null
    const base = { organization_id: ctx.orgId, company_id: company.id }
    const audit = (entityType: string, entityId: string, act: string, payload: Record<string, unknown>) =>
      writeAuditLog(ctx.supabase, { actorUserId, entityType, entityId, action: act, payload })

    if (action === 'seed') {
      // Только для пустой точки: второй раз примерный прайс не накатываем.
      const { count } = await ctx.supabase.from('detailing_services').select('id', { count: 'exact', head: true }).eq('company_id', company.id)
      if ((count || 0) > 0) return json({ error: 'Прайс уже заполнен' }, 409)
      const { data: boxRows } = await ctx.supabase.from('detailing_boxes').select('id').eq('company_id', company.id)
      if (!(boxRows || []).length) {
        const { error } = await ctx.supabase.from('detailing_boxes').insert(DEFAULT_SETUP.boxes.map((b, i) => ({ ...base, ...b, sort_order: i })))
        if (error) throw error
      }
      let { data: classes } = await ctx.supabase.from('detailing_body_classes').select('id, name').eq('company_id', company.id).order('sort_order')
      if (!(classes || []).length) {
        const { data, error } = await ctx.supabase
          .from('detailing_body_classes')
          .insert(DEFAULT_SETUP.classes.map((name, i) => ({ ...base, name, sort_order: i })))
          .select('id, name, sort_order')
        if (error) throw error
        classes = (data || []).sort((a: any, b: any) => a.sort_order - b.sort_order)
      }
      for (let i = 0; i < DEFAULT_SETUP.services.length; i += 1) {
        const s = DEFAULT_SETUP.services[i]
        const { data: svc, error } = await ctx.supabase
          .from('detailing_services')
          .insert({ ...base, name: s.name, days: s.days, package_note: (s as any).package_note || null, sort_order: i })
          .select('id')
          .single()
        if (error) throw error
        const prices = (classes || []).slice(0, s.prices.length).map((c: any, ci: number) => ({
          service_id: svc.id,
          body_class_id: c.id,
          price: s.prices[ci][0],
          materials: s.prices[ci][1],
        }))
        if (prices.length) {
          const { error: pErr } = await ctx.supabase.from('detailing_service_prices').insert(prices)
          if (pErr) throw pErr
        }
      }
      await audit('detailing-setup', company.id, 'seed', { services: DEFAULT_SETUP.services.length })
      return json({ ok: true })
    }

    const [kind, verb] = action.split('.')
    const table = TABLE_BY_KIND[kind]
    const id = String(body?.id || '').trim() || null

    if (id) {
      const { data: own } = await ctx.supabase.from(table).select('id').eq('id', id).eq('company_id', company.id).maybeSingle()
      if (!own) return json({ error: 'Не найдено' }, 404)
    }

    if (verb === 'delete') {
      if (!id) return json({ error: 'Не найдено' }, 404)
      if (kind === 'box') {
        // Бокс с машинами не удаляем, а прячем: записи должны остаться на своих местах.
        const { count } = await ctx.supabase.from('detailing_bookings').select('id', { count: 'exact', head: true }).eq('box_id', id).neq('status', 'cancelled')
        if ((count || 0) > 0) {
          const { error } = await ctx.supabase.from(table).update({ is_active: false }).eq('id', id)
          if (error) throw error
          await audit('detailing-box', id, 'deactivate', {})
          return json({ ok: true, data: { hidden: true } })
        }
      }
      if (kind === 'class') {
        const { count } = await ctx.supabase.from('detailing_bookings').select('id', { count: 'exact', head: true }).eq('body_class_id', id)
        if ((count || 0) > 0) {
          const { error } = await ctx.supabase.from(table).update({ is_active: false }).eq('id', id)
          if (error) throw error
          await audit('detailing-class', id, 'deactivate', {})
          return json({ ok: true, data: { hidden: true } })
        }
      }
      const { error } = await ctx.supabase.from(table).delete().eq('id', id)
      if (error) throw error
      await audit(`detailing-${kind}`, id, 'delete', {})
      return json({ ok: true })
    }

    // save
    const name = String(body?.name || '').trim()
    if (!name) return json({ error: 'Укажите название' }, 400)
    const row: Record<string, unknown> = { name: name.slice(0, kind === 'service' ? 80 : 40) }
    if (body?.is_active !== undefined) row.is_active = body.is_active !== false
    if (body?.sort_order !== undefined) row.sort_order = Math.round(Number(body.sort_order) || 0)
    if (kind === 'box') row.note = String(body?.note || '').trim().slice(0, 80) || null
    if (kind === 'service') {
      row.package_note = String(body?.package_note || '').trim().slice(0, 160) || null
      row.days = Math.min(30, Math.max(1, Math.round(Number(body?.days) || 1)))
      row.updated_at = new Date().toISOString()
    }

    let savedId = id
    if (id) {
      const { error } = await ctx.supabase.from(table).update(row).eq('id', id)
      if (error) throw error
    } else {
      const { data: last } = await ctx.supabase.from(table).select('sort_order').eq('company_id', company.id).order('sort_order', { ascending: false }).limit(1).maybeSingle()
      const { data, error } = await ctx.supabase
        .from(table)
        .insert({ ...base, ...row, sort_order: row.sort_order ?? Number(last?.sort_order ?? -1) + 1 })
        .select('id')
        .single()
      if (error) throw error
      savedId = String(data.id)
    }

    if (kind === 'service' && Array.isArray(body?.prices)) {
      const { data: classRows } = await ctx.supabase.from('detailing_body_classes').select('id').eq('company_id', company.id)
      const ownClasses = new Set(((classRows || []) as any[]).map((c) => String(c.id)))
      const prices = (body.prices as any[])
        .filter((p) => ownClasses.has(String(p?.body_class_id)) && p?.price !== '' && p?.price !== null && p?.price !== undefined)
        .map((p) => ({
          service_id: savedId,
          body_class_id: String(p.body_class_id),
          price: Math.max(0, Math.round(Number(p.price) || 0)),
          materials: Math.max(0, Math.round(Number(p.materials) || 0)),
        }))
      const bad = prices.find((p) => p.materials > p.price)
      if (bad) return json({ error: 'Материалы не могут быть дороже работы' }, 400)
      const { error: delErr } = await ctx.supabase.from('detailing_service_prices').delete().eq('service_id', savedId)
      if (delErr) throw delErr
      if (prices.length) {
        const { error: insErr } = await ctx.supabase.from('detailing_service_prices').insert(prices)
        if (insErr) throw insErr
      }
    }

    await audit(`detailing-${kind}`, String(savedId), id ? 'update' : 'create', { ...row, prices: body?.prices })
    return json({ ok: true, data: { id: savedId } })
  } catch (error: any) {
    if (isMissingDetailingTable(error)) return json({ error: 'Детейлинг ещё не включён в базе — нужна миграция 20261029_detailing_core' }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-setup:post', message: error?.message || 'Detailing setup POST error' })
    return json({ error: 'Не удалось сохранить' }, 500)
  }
}
