import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import {
  detailingContext,
  isMissingDetailingTable,
  json,
  normalizePhone,
  normalizePlate,
  pickCompany,
} from '@/lib/server/detailing'

/**
 * Клиенты и машины точки-детейлинга. Поиск по телефону, госномеру или имени.
 * Визиты, сколько заплатил и долг считаются по записям точки.
 */

const LIMIT = 200

export async function GET(request: Request) {
  try {
    const ctx = await detailingContext(request, ['detailing-clients.view', 'detailing-bookings.view'])
    if ('response' in ctx) return ctx.response
    const url = new URL(request.url)
    const company = pickCompany(ctx, url.searchParams.get('company_id'))
    if (!company) return json({ ok: true, data: { companies: ctx.companies, company: null, clients: [] } })

    const q = String(url.searchParams.get('q') || '').trim()
    const plateQ = normalizePlate(q)
    const digits = q.replace(/\D/g, '')

    // Совпадения по номеру машины дают id клиентов.
    let carCustomerIds: string[] = []
    if (plateQ.length >= 2) {
      const { data } = await ctx.supabase.from('customer_cars').select('customer_id').eq('company_id', company.id).ilike('plate_norm', `%${plateQ}%`).limit(LIMIT)
      carCustomerIds = ((data || []) as any[]).map((r) => String(r.customer_id))
    }

    let cq = ctx.supabase
      .from('customers')
      .select('id, name, phone, notes, created_at, cars:customer_cars(id, plate, model, body_class_id, note)')
      .eq('company_id', company.id)
      .order('created_at', { ascending: false })
      .limit(LIMIT)
    if (q) {
      const ors = [`name.ilike.%${q.replace(/[%,()]/g, ' ')}%`]
      if (digits.length >= 3) ors.push(`phone.ilike.%${digits.slice(-10)}%`)
      if (carCustomerIds.length) ors.push(`id.in.(${carCustomerIds.join(',')})`)
      cq = cq.or(ors.join(','))
    }
    const { data: customers, error } = await cq
    if (error) throw error
    const ids = ((customers || []) as any[]).map((c) => String(c.id))

    const stats = new Map<string, { visits: number; spent: number; debt: number; last: string | null }>()
    if (ids.length) {
      const { data: bookings, error: bErr } = await ctx.supabase
        .from('detailing_bookings')
        .select('customer_id, status, start_date, price_total, payments:detailing_payments(amount)')
        .eq('company_id', company.id)
        .in('customer_id', ids)
        .neq('status', 'cancelled')
      if (bErr) throw bErr
      for (const b of (bookings || []) as any[]) {
        const key = String(b.customer_id)
        const s = stats.get(key) || { visits: 0, spent: 0, debt: 0, last: null }
        const paid = ((b.payments || []) as any[]).reduce((sum, p) => sum + Number(p.amount || 0), 0)
        if (b.status !== 'no_show' && b.status !== 'requested') s.visits += 1
        s.spent += paid
        if (b.status === 'done') s.debt += Math.max(0, Number(b.price_total || 0) - paid)
        if (!s.last || String(b.start_date) > s.last) s.last = String(b.start_date)
        stats.set(key, s)
      }
    }

    const clients = ((customers || []) as any[]).map((c) => ({
      ...c,
      ...(stats.get(String(c.id)) || { visits: 0, spent: 0, debt: 0, last: null }),
    }))
    return json({ ok: true, data: { companies: ctx.companies, company, clients, limited: clients.length >= LIMIT } })
  } catch (error: any) {
    if (isMissingDetailingTable(error)) return json({ error: 'Детейлинг ещё не включён в базе — нужна миграция 20261029_detailing_core' }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-clients:get', message: error?.message || 'Detailing clients GET error' })
    return json({ error: 'Не удалось загрузить клиентов' }, 500)
  }
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as any
  const action = String(body?.action || '')
  const capability = action === 'car.delete' ? 'detailing-clients.delete' : 'detailing-clients.edit'
  if (!['customer.save', 'car.save', 'car.delete'].includes(action)) return json({ error: 'invalid-action' }, 400)
  try {
    const ctx = await detailingContext(request, capability)
    if ('response' in ctx) return ctx.response
    const company = pickCompany(ctx, body?.company_id)
    if (!company) return json({ error: 'Точка не найдена' }, 404)
    const actorUserId = ctx.access.user?.id || null

    const ownCustomer = async (id: string) => {
      const { data } = await ctx.supabase.from('customers').select('id').eq('id', id).eq('company_id', company.id).maybeSingle()
      return data ? String(data.id) : null
    }

    if (action === 'customer.save') {
      const id = await ownCustomer(String(body?.id || ''))
      if (!id) return json({ error: 'Клиент не найден' }, 404)
      const patch: Record<string, unknown> = { updated_at: new Date().toISOString() }
      if ('name' in body) {
        const name = String(body.name || '').trim()
        if (!name) return json({ error: 'Укажите имя' }, 400)
        patch.name = name.slice(0, 120)
      }
      if ('phone' in body) {
        const phone = normalizePhone(body.phone)
        if (phone.replace(/\D/g, '').length < 10) return json({ error: 'Проверьте телефон' }, 400)
        const { data: dup } = await ctx.supabase.from('customers').select('id').eq('company_id', company.id).eq('phone', phone).neq('id', id).maybeSingle()
        if (dup) return json({ error: 'Клиент с таким телефоном уже есть' }, 409)
        patch.phone = phone
      }
      if ('notes' in body) patch.notes = String(body.notes || '').trim().slice(0, 1000) || null
      const { error } = await ctx.supabase.from('customers').update(patch).eq('id', id)
      if (error) throw error
      await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'customer', entityId: id, action: 'update', payload: patch })
      return json({ ok: true })
    }

    if (action === 'car.delete') {
      const carId = String(body?.id || '')
      const { data: car } = await ctx.supabase.from('customer_cars').select('id, plate').eq('id', carId).eq('company_id', company.id).maybeSingle()
      if (!car) return json({ error: 'Машина не найдена' }, 404)
      // Записи остаются: ссылка на машину обнулится, госномер есть в истории аудита.
      const { error } = await ctx.supabase.from('customer_cars').delete().eq('id', carId)
      if (error) throw error
      await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'customer-car', entityId: carId, action: 'delete', payload: { plate: car.plate } })
      return json({ ok: true })
    }

    // car.save
    const customerId = await ownCustomer(String(body?.customer_id || ''))
    if (!customerId) return json({ error: 'Клиент не найден' }, 404)
    const plate = String(body?.plate || '').trim().toUpperCase()
    const plateNorm = normalizePlate(plate)
    if (!plateNorm) return json({ error: 'Укажите госномер' }, 400)
    const carId = String(body?.id || '').trim() || null
    const { data: dup } = await ctx.supabase.from('customer_cars').select('id, customer_id').eq('company_id', company.id).eq('plate_norm', plateNorm).maybeSingle()
    if (dup && String(dup.id) !== carId) return json({ error: 'Машина с таким номером уже есть у другого клиента' }, 409)
    let bodyClassId = String(body?.body_class_id || '').trim() || null
    if (bodyClassId) {
      const { data: cls } = await ctx.supabase.from('detailing_body_classes').select('id').eq('id', bodyClassId).eq('company_id', company.id).maybeSingle()
      if (!cls) bodyClassId = null
    }
    const row = {
      customer_id: customerId,
      plate,
      plate_norm: plateNorm,
      model: String(body?.model || '').trim().slice(0, 80) || null,
      body_class_id: bodyClassId,
      note: String(body?.note || '').trim().slice(0, 300) || null,
      updated_at: new Date().toISOString(),
    }
    if (carId) {
      const { data: own } = await ctx.supabase.from('customer_cars').select('id').eq('id', carId).eq('company_id', company.id).maybeSingle()
      if (!own) return json({ error: 'Машина не найдена' }, 404)
      const { error } = await ctx.supabase.from('customer_cars').update(row).eq('id', carId)
      if (error) throw error
    } else {
      const { error } = await ctx.supabase.from('customer_cars').insert({ ...row, organization_id: ctx.orgId, company_id: company.id })
      if (error) throw error
    }
    await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'customer-car', entityId: carId || plateNorm, action: carId ? 'update' : 'create', payload: row })
    return json({ ok: true })
  } catch (error: any) {
    if (isMissingDetailingTable(error)) return json({ error: 'Детейлинг ещё не включён в базе — нужна миграция 20261029_detailing_core' }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-clients:post', message: error?.message || 'Detailing clients POST error' })
    return json({ error: 'Не удалось сохранить' }, 500)
  }
}
