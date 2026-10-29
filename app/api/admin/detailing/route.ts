import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import {
  BOOKING_STATUSES,
  addDays,
  detailingContext,
  findBoxConflict,
  isIsoDate,
  isMissingDetailingTable,
  json,
  loadMasters,
  loadReference,
  normalizePhone,
  normalizePlate,
  pickCompany,
  priceLines,
  type BookingStatus,
  type DetailingCtx,
} from '@/lib/server/detailing'

/**
 * Запись · неделя. GET — всё для недельного календаря точки: боксы, классы
 * кузова, прайс, мастера и записи, пересекающие неделю. POST — действия с
 * записью (создать, поменять статус, бокс, мастера, материалы).
 *
 * Прайс и боксы правятся в /api/admin/detailing/setup.
 */

const BOOKING_SELECT =
  'id, company_id, box_id, customer_id, car_id, body_class_id, master_operator_id, start_date, days, status, price_total, materials_total, materials_manual, comment, source, issued_at, created_at, ' +
  'customer:customers(id, name, phone), car:customer_cars(id, plate, model), ' +
  'services:detailing_booking_services(id, service_id, name, price, materials, days, sort_order), ' +
  'payments:detailing_payments(id, amount, method, kind, paid_on)'

function shapeBooking(b: any) {
  const paid = ((b.payments || []) as any[]).reduce((s, p) => s + Number(p.amount || 0), 0)
  return {
    ...b,
    price_total: Number(b.price_total || 0),
    materials_total: Number(b.materials_total || 0),
    paid,
    due: Math.max(0, Number(b.price_total || 0) - paid),
    services: ((b.services || []) as any[]).sort((x, y) => x.sort_order - y.sort_order),
  }
}

export async function GET(request: Request) {
  try {
    const ctx = await detailingContext(request, 'detailing-bookings.view')
    if ('response' in ctx) return ctx.response
    const url = new URL(request.url)
    const company = pickCompany(ctx, url.searchParams.get('company_id'))
    if (!company) return json({ ok: true, data: { companies: ctx.companies, company: null } })

    const from = url.searchParams.get('from')
    const weekStart = isIsoDate(from) ? from : new Date().toISOString().slice(0, 10)
    const weekEnd = addDays(weekStart, 7)

    const ref = await loadReference(ctx, company.id)
    // Записи, начавшиеся до конца недели и не раньше чем за 60 дней — длинные
    // работы (весь кузов) тянутся с прошлой недели. Пересечение досчитываем здесь.
    const { data: rows, error } = await ctx.supabase
      .from('detailing_bookings')
      .select(BOOKING_SELECT)
      .eq('company_id', company.id)
      .lt('start_date', weekEnd)
      .gte('start_date', addDays(weekStart, -60))
      .neq('status', 'cancelled')
      .order('start_date')
    if (error) throw error
    const bookings = ((rows || []) as any[])
      .filter((b) => b.status === 'requested' || addDays(String(b.start_date), Number(b.days)) > weekStart)
      .map(shapeBooking)

    return json({ ok: true, data: { companies: ctx.companies, company, weekStart, ...ref, bookings } })
  } catch (error: any) {
    if (isMissingDetailingTable(error)) return json({ error: 'Детейлинг ещё не включён в базе — нужна миграция 20261029_detailing_core' }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing:get', message: error?.message || 'Detailing GET error' })
    return json({ error: 'Не удалось загрузить запись' }, 500)
  }
}

async function upsertCustomer(ctx: DetailingCtx, companyId: string, phone: string, name: string | null) {
  const { data: found } = await ctx.supabase.from('customers').select('id, name').eq('company_id', companyId).eq('phone', phone).maybeSingle()
  if (found) {
    if (name && (!found.name || /^Клиент /.test(found.name))) {
      await ctx.supabase.from('customers').update({ name, updated_at: new Date().toISOString() }).eq('id', found.id)
    }
    return String(found.id)
  }
  const { data, error } = await ctx.supabase
    .from('customers')
    .insert({ company_id: companyId, phone, name: name || `Клиент ${phone.slice(-4)}` })
    .select('id')
    .single()
  if (error) throw error
  return String(data.id)
}

async function upsertCar(
  ctx: DetailingCtx,
  args: { companyId: string; customerId: string; plate: string; model: string | null; bodyClassId: string | null },
) {
  const plateNorm = normalizePlate(args.plate)
  const { data: found } = await ctx.supabase
    .from('customer_cars')
    .select('id, customer_id, model, body_class_id')
    .eq('company_id', args.companyId)
    .eq('plate_norm', plateNorm)
    .maybeSingle()
  if (found) {
    // Номер уже есть. Машина могла сменить владельца — ставим нового и
    // дописываем модель/кузов, если раньше их не знали.
    const patch: Record<string, unknown> = { updated_at: new Date().toISOString() }
    if (String(found.customer_id) !== args.customerId) patch.customer_id = args.customerId
    if (args.model && !found.model) patch.model = args.model
    if (args.bodyClassId && !found.body_class_id) patch.body_class_id = args.bodyClassId
    await ctx.supabase.from('customer_cars').update(patch).eq('id', found.id)
    return String(found.id)
  }
  const { data, error } = await ctx.supabase
    .from('customer_cars')
    .insert({
      organization_id: ctx.orgId,
      company_id: args.companyId,
      customer_id: args.customerId,
      plate: args.plate.trim().toUpperCase(),
      plate_norm: plateNorm,
      model: args.model,
      body_class_id: args.bodyClassId,
    })
    .select('id')
    .single()
  if (error) throw error
  return String(data.id)
}

async function ownBox(ctx: DetailingCtx, companyId: string, boxId: string | null) {
  if (!boxId) return null
  const { data } = await ctx.supabase.from('detailing_boxes').select('id').eq('id', boxId).eq('company_id', companyId).maybeSingle()
  return data ? String(data.id) : null
}

async function ownMaster(ctx: DetailingCtx, companyId: string, operatorId: string | null) {
  if (!operatorId) return null
  const masters = await loadMasters(ctx.supabase, companyId)
  return masters.some((m) => m.id === operatorId) ? operatorId : null
}

const CAPABILITY_BY_ACTION: Record<string, string> = {
  create: 'detailing-bookings.create',
  update: 'detailing-bookings.edit',
  status: 'detailing-bookings.edit',
  cancel: 'detailing-bookings.delete',
  pay: 'detailing-bookings.take_payment',
  unpay: 'detailing-bookings.take_payment',
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as any
  const action = String(body?.action || '')
  const capability = CAPABILITY_BY_ACTION[action]
  if (!capability) return json({ error: 'invalid-action' }, 400)
  try {
    const ctx = await detailingContext(request, capability)
    if ('response' in ctx) return ctx.response
    const actorUserId = ctx.access.user?.id || null

    if (action === 'create') {
      const company = pickCompany(ctx, body?.company_id)
      if (!company) return json({ error: 'Точка не найдена' }, 404)

      const phone = normalizePhone(body?.phone)
      const plate = String(body?.plate || '').trim()
      if (phone.replace(/\D/g, '').length < 10) return json({ error: 'Укажите телефон клиента' }, 400)
      if (!normalizePlate(plate)) return json({ error: 'Укажите госномер' }, 400)
      if (!isIsoDate(body?.start_date)) return json({ error: 'Укажите день' }, 400)
      const serviceIds: string[] = Array.isArray(body?.service_ids) ? [...new Set((body.service_ids as any[]).map(String))] : []
      if (!serviceIds.length) return json({ error: 'Выберите хотя бы одну услугу' }, 400)

      const bodyClassId = String(body?.body_class_id || '').trim() || null
      const { data: cls } = bodyClassId
        ? await ctx.supabase.from('detailing_body_classes').select('id').eq('id', bodyClassId).eq('company_id', company.id).maybeSingle()
        : { data: null }
      if (!cls) return json({ error: 'Выберите кузов' }, 400)

      const { lines, missing } = await priceLines(ctx.supabase, { companyId: company.id, serviceIds, bodyClassId })
      if (missing.length) return json({ error: `Нет цены для этого кузова: ${missing.join(', ')}` }, 400)

      const boxId = await ownBox(ctx, company.id, String(body?.box_id || '') || null)
      if (!boxId) return json({ error: 'Выберите бокс' }, 400)
      const days = Math.min(60, Math.max(1, Number(body?.days) || lines.reduce((s, l) => s + l.days, 0) || 1))
      const conflict = await findBoxConflict(ctx.supabase, { companyId: company.id, boxId, startDate: body.start_date, days })
      if (conflict) return json({ error: 'Бокс занят в эти дни. Выберите другой бокс или день.' }, 409)

      const masterId = await ownMaster(ctx, company.id, String(body?.master_operator_id || '') || null)
      const name = String(body?.client_name || '').trim().slice(0, 120) || null
      const customerId = await upsertCustomer(ctx, company.id, phone, name)
      const carId = await upsertCar(ctx, {
        companyId: company.id,
        customerId,
        plate,
        model: String(body?.model || '').trim().slice(0, 80) || null,
        bodyClassId,
      })

      const { data: booking, error } = await ctx.supabase
        .from('detailing_bookings')
        .insert({
          organization_id: ctx.orgId,
          company_id: company.id,
          box_id: boxId,
          customer_id: customerId,
          car_id: carId,
          body_class_id: bodyClassId,
          master_operator_id: masterId,
          start_date: body.start_date,
          days,
          status: 'booked',
          price_total: lines.reduce((s, l) => s + l.price, 0),
          materials_total: lines.reduce((s, l) => s + l.materials, 0),
          comment: String(body?.comment || '').trim().slice(0, 500) || null,
          created_by: actorUserId,
        })
        .select('id')
        .single()
      if (error) throw error
      const { error: linesError } = await ctx.supabase
        .from('detailing_booking_services')
        .insert(lines.map((l) => ({ ...l, booking_id: booking.id })))
      if (linesError) {
        await ctx.supabase.from('detailing_bookings').delete().eq('id', booking.id)
        throw linesError
      }

      await writeAuditLog(ctx.supabase, {
        actorUserId,
        entityType: 'detailing-booking',
        entityId: String(booking.id),
        action: 'create',
        payload: { company_id: company.id, plate, start_date: body.start_date, days, services: lines.map((l) => l.name) },
      })
      return json({ ok: true, data: { id: booking.id } })
    }

    // Остальные действия — над существующей записью своей точки.
    const id = String(body?.id || '').trim()
    const { data: current } = id
      ? await ctx.supabase.from('detailing_bookings').select('id, company_id, box_id, start_date, days, status, price_total, customer:customers(name), car:customer_cars(plate, model)').eq('id', id).maybeSingle()
      : { data: null }
    const company = current ? pickCompany(ctx, current.company_id) : null
    if (!current || !company || company.id !== current.company_id) return json({ error: 'Запись не найдена' }, 404)

    if (action === 'pay') {
      const amount = Math.round(Number(body?.amount) * 100) / 100
      const method = String(body?.method || '')
      if (!(amount > 0)) return json({ error: 'Укажите сумму' }, 400)
      if (!['cash', 'kaspi', 'card'].includes(method)) return json({ error: 'Выберите способ оплаты' }, 400)
      if (current.status === 'cancelled' || current.status === 'requested') return json({ error: 'Сначала подтвердите запись' }, 400)
      const paidOn = isIsoDate(body?.paid_on) ? body.paid_on : new Date().toISOString().slice(0, 10)
      const { data: prev } = await ctx.supabase.from('detailing_payments').select('amount').eq('booking_id', id)
      const paidBefore = ((prev || []) as any[]).reduce((s, p) => s + Number(p.amount || 0), 0)
      const due = Math.max(0, Number(current.price_total) - paidBefore)
      if (amount > due + 0.01) return json({ error: `Больше, чем осталось: осталось ${Math.round(due).toLocaleString('ru-RU')} ₸` }, 400)
      // До выдачи машины первая оплата — предоплата.
      const kind = current.status === 'booked' && paidBefore === 0 && amount < Number(current.price_total) ? 'prepayment' : 'payment'

      // Строка в общем журнале доходов: по ней считают ОПиУ, Cash Flow, отчёты.
      const car = (current as any).car
      const label = [car?.model, car?.plate].filter(Boolean).join(' ') || 'машина'
      const { data: income, error: incomeError } = await ctx.supabase
        .from('incomes')
        .insert({
          date: paidOn,
          company_id: company.id,
          shift: 'day',
          zone: 'other',
          cash_amount: method === 'cash' ? amount : 0,
          kaspi_amount: method === 'kaspi' ? amount : 0,
          card_amount: method === 'card' ? amount : 0,
          online_amount: 0,
          comment: `Детейлинг: ${label}${kind === 'prepayment' ? ' (предоплата)' : ''}`,
          created_by: actorUserId,
          source: 'detailing',
          meta: { detailing_booking_id: id },
        })
        .select('id')
        .single()
      if (incomeError) throw incomeError
      const { data: payment, error } = await ctx.supabase
        .from('detailing_payments')
        .insert({
          organization_id: ctx.orgId,
          company_id: company.id,
          booking_id: id,
          paid_on: paidOn,
          amount,
          method,
          kind,
          income_id: income.id,
          created_by: actorUserId,
        })
        .select('id')
        .single()
      if (error) {
        await ctx.supabase.from('incomes').delete().eq('id', income.id)
        throw error
      }
      await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'detailing-payment', entityId: String(payment.id), action: 'create', payload: { booking_id: id, amount, method, kind, income_id: income.id } })
      return json({ ok: true, data: { id: payment.id, kind } })
    }

    if (action === 'unpay') {
      const paymentId = String(body?.payment_id || '')
      const { data: payment } = await ctx.supabase.from('detailing_payments').select('id, income_id, amount, method').eq('id', paymentId).eq('booking_id', id).maybeSingle()
      if (!payment) return json({ error: 'Оплата не найдена' }, 404)
      if (payment.income_id) {
        const { error: incErr } = await ctx.supabase.from('incomes').delete().eq('id', payment.income_id).eq('company_id', company.id)
        if (incErr) throw incErr
      }
      const { error } = await ctx.supabase.from('detailing_payments').delete().eq('id', payment.id)
      if (error) throw error
      await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'detailing-payment', entityId: String(payment.id), action: 'delete', payload: { booking_id: id, amount: payment.amount, method: payment.method } })
      return json({ ok: true })
    }

    if (action === 'cancel') {
      const { count: paidCount } = await ctx.supabase.from('detailing_payments').select('id', { count: 'exact', head: true }).eq('booking_id', id)
      if ((paidCount || 0) > 0) return json({ error: 'По записи есть оплаты. Сначала верните их.' }, 400)
      const { error } = await ctx.supabase.from('detailing_bookings').update({ status: 'cancelled', updated_at: new Date().toISOString() }).eq('id', id)
      if (error) throw error
      await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'detailing-booking', entityId: id, action: 'cancel', payload: {} })
      return json({ ok: true })
    }

    if (action === 'status') {
      const status = String(body?.status || '') as BookingStatus
      if (!(BOOKING_STATUSES as readonly string[]).includes(status) || status === 'cancelled') return json({ error: 'Неизвестный статус' }, 400)
      const patch: Record<string, unknown> = { status, updated_at: new Date().toISOString() }
      if (status === 'done') patch.issued_at = new Date().toISOString()
      if (status !== 'done') patch.issued_at = null
      // Вернули «не приехал» в работу — бокс мог за это время занять другой.
      if ((current.status === 'no_show' || current.status === 'requested') && ['booked', 'in_box', 'done'].includes(status)) {
        if (!current.box_id) return json({ error: 'Сначала выберите бокс' }, 400)
        const conflict = await findBoxConflict(ctx.supabase, { companyId: company.id, boxId: current.box_id, startDate: current.start_date, days: current.days, ignoreId: id })
        if (conflict) return json({ error: 'Бокс уже занят другой машиной' }, 409)
      }
      const { error } = await ctx.supabase.from('detailing_bookings').update(patch).eq('id', id)
      if (error) throw error
      await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'detailing-booking', entityId: id, action: 'status', payload: { from: current.status, to: status } })
      return json({ ok: true })
    }

    // update: бокс/день, мастер, материалы, комментарий.
    const patch: Record<string, unknown> = { updated_at: new Date().toISOString() }
    if ('box_id' in body || 'start_date' in body) {
      const boxId = 'box_id' in body ? await ownBox(ctx, company.id, String(body.box_id || '') || null) : current.box_id
      const startDate = 'start_date' in body ? body.start_date : current.start_date
      if (!boxId) return json({ error: 'Выберите бокс' }, 400)
      if (!isIsoDate(startDate)) return json({ error: 'Укажите день' }, 400)
      const conflict = await findBoxConflict(ctx.supabase, { companyId: company.id, boxId, startDate, days: current.days, ignoreId: id })
      if (conflict) return json({ error: 'Бокс занят в эти дни' }, 409)
      patch.box_id = boxId
      patch.start_date = startDate
    }
    if ('master_operator_id' in body) {
      const masterId = String(body.master_operator_id || '') || null
      const own = await ownMaster(ctx, company.id, masterId)
      if (masterId && !own) return json({ error: 'Мастер не работает на этой точке' }, 400)
      patch.master_operator_id = own
    }
    if ('materials_total' in body) {
      const value = Math.round(Number(body.materials_total) * 100) / 100
      if (!(value >= 0)) return json({ error: 'Материалы не могут быть меньше нуля' }, 400)
      if (value > Number(current.price_total)) return json({ error: 'Материалы больше цены работы' }, 400)
      patch.materials_total = value
      patch.materials_manual = true
    }
    if ('comment' in body) patch.comment = String(body.comment || '').trim().slice(0, 500) || null
    const { error } = await ctx.supabase.from('detailing_bookings').update(patch).eq('id', id)
    if (error) throw error
    await writeAuditLog(ctx.supabase, { actorUserId, entityType: 'detailing-booking', entityId: id, action: 'update', payload: patch })
    return json({ ok: true })
  } catch (error: any) {
    if (isMissingDetailingTable(error)) return json({ error: 'Детейлинг ещё не включён в базе — нужна миграция 20261029_detailing_core' }, 503)
    if (error?.message === 'company-out-of-scope') return json({ error: 'Нет доступа к этой точке' }, 403)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing:post', message: error?.message || 'Detailing POST error' })
    return json({ error: 'Не удалось сохранить запись' }, 500)
  }
}
