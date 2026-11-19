import { NextResponse } from 'next/server'

import { writeSystemErrorLogSafe } from '@/lib/server/audit'
import { addDays, isIsoDate, isOnlineBookingOn, normalizePhone, normalizePlate, normalizeTime, priceLines, upsertCar, upsertCustomer } from '@/lib/server/detailing'
import { checkRateLimit, getClientIp } from '@/lib/server/rate-limit'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'
import { resolveHostNiche } from '@/lib/server/tenant-hosts'

/**
 * Онлайн-запись клиента детейлинга (<адрес детейлинга>/book), без входа.
 *
 * Организация берётся только из адреса, не из запроса. Клиент ничего не
 * бронирует: приходит заявка (status = requested, без бокса), администратор
 * выбирает бокс и подтверждает. Цена считается здесь по прайсу — присланной
 * клиентом цене не верим.
 *
 * Работает, только если владелец включил онлайн-запись в «Прайсе».
 */

const json = (data: unknown, status = 200) => NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
const HORIZON_DAYS = 30

async function resolve(request: Request) {
  if (!hasAdminSupabaseCredentials()) return null
  const found = await resolveHostNiche(request.headers.get('host')).catch(() => null)
  if (!found || found.niche !== 'detailing' || found.org.status === 'suspended') return null
  const supabase = createAdminSupabaseClient()
  if (!(await isOnlineBookingOn(supabase, found.org.id))) return null
  const { data: company } = await supabase
    .from('companies')
    .select('id, name')
    .eq('organization_id', found.org.id)
    .eq('industry', 'detailing')
    .is('archived_at', null)
    .order('name')
    .limit(1)
    .maybeSingle()
  if (!company) return null
  return { supabase, org: found.org, companyId: String((company as any).id) }
}

export async function GET(request: Request) {
  try {
    const ctx = await resolve(request)
    if (!ctx) return json({ error: 'Онлайн-запись выключена' }, 404)
    const [classes, services] = await Promise.all([
      ctx.supabase.from('detailing_body_classes').select('id, name, sort_order').eq('company_id', ctx.companyId).eq('is_active', true).order('sort_order'),
      ctx.supabase
        .from('detailing_services')
        .select('id, name, package_note, days, duration_minutes, sort_order, prices:detailing_service_prices(body_class_id, price)')
        .eq('company_id', ctx.companyId)
        .eq('is_active', true)
        .order('sort_order'),
    ])
    if (classes.error) throw classes.error
    if (services.error) throw services.error
    // Материалы и себестоимость наружу не отдаём — только цена для клиента.
    return json({
      ok: true,
      data: {
        name: ctx.org.name,
        classes: ((classes.data || []) as any[]).map((c) => ({ id: String(c.id), name: String(c.name) })),
        services: ((services.data || []) as any[])
          .map((s) => ({
            id: String(s.id),
            name: String(s.name),
            note: s.package_note || null,
            days: Number(s.days || 1),
            minutes: s.duration_minutes ? Number(s.duration_minutes) : null,
            prices: ((s.prices || []) as any[]).map((p) => ({ body_class_id: String(p.body_class_id), price: Number(p.price || 0) })),
          }))
          .filter((s) => s.prices.length),
        horizonDays: HORIZON_DAYS,
      },
    })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'public-detailing-book:get', message: error?.message || 'Public booking GET error' })
    return json({ error: 'Не удалось открыть запись' }, 500)
  }
}

export async function POST(request: Request) {
  const ip = getClientIp(request)
  const rate = checkRateLimit(`public-detailing-book:${ip}`, 6, 60 * 60 * 1000)
  if (!rate.allowed) return json({ error: 'Слишком много заявок подряд. Позвоните нам или попробуйте позже.' }, 429)

  const body = (await request.json().catch(() => null)) as any
  // Ловушка для ботов: человек это поле не видит. Отвечаем «ок», чтобы бот не подбирал.
  if (String(body?.website || '').trim()) return json({ ok: true })

  try {
    const ctx = await resolve(request)
    if (!ctx) return json({ error: 'Онлайн-запись выключена' }, 404)

    const phone = normalizePhone(String(body?.phone || ''))
    const plate = String(body?.plate || '').trim().slice(0, 20)
    const name = String(body?.name || '').trim().slice(0, 120)
    const model = String(body?.model || '').trim().slice(0, 80) || null
    const bodyClassId = String(body?.body_class_id || '').trim()
    const serviceIds: string[] = Array.isArray(body?.service_ids) ? [...new Set((body.service_ids as any[]).map(String))].slice(0, 10) : []
    const today = new Date().toISOString().slice(0, 10)

    if (name.length < 2) return json({ error: 'Напишите, как к вам обращаться' }, 400)
    if (phone.replace(/\D/g, '').length !== 11) return json({ error: 'Проверьте номер телефона' }, 400)
    if (!normalizePlate(plate)) return json({ error: 'Укажите госномер машины' }, 400)
    if (!serviceIds.length) return json({ error: 'Выберите хотя бы одну услугу' }, 400)
    if (!isIsoDate(body?.date) || body.date < addDays(today, -1) || body.date > addDays(today, HORIZON_DAYS)) return json({ error: 'Выберите день из списка' }, 400)

    const { data: cls } = await ctx.supabase.from('detailing_body_classes').select('id').eq('id', bodyClassId).eq('company_id', ctx.companyId).eq('is_active', true).maybeSingle()
    if (!cls) return json({ error: 'Выберите кузов' }, 400)
    const { lines, missing, plan } = await priceLines(ctx.supabase, { companyId: ctx.companyId, serviceIds, bodyClassId })
    if (missing.length || !lines.length) return json({ error: 'Для этого кузова часть услуг не делается. Выберите другие.' }, 400)

    // Не плодим одинаковые заявки: одна необработанная на телефон.
    const scoped = { supabase: ctx.supabase, orgId: ctx.org.id }
    const customerId = await upsertCustomer(scoped, ctx.companyId, phone, name)
    const { count: pending } = await ctx.supabase
      .from('detailing_bookings')
      .select('id', { count: 'exact', head: true })
      .eq('company_id', ctx.companyId)
      .eq('customer_id', customerId)
      .eq('status', 'requested')
    if ((pending || 0) > 0) return json({ error: 'Ваша заявка уже у нас. Мы скоро свяжемся, чтобы подтвердить время.' }, 409)

    // Номер уже записан на другого клиента — машину не трогаем: по открытой
    // форме чужую машину на себя не переписывают. Номер уходит в комментарий.
    const { data: knownCar } = await ctx.supabase.from('customer_cars').select('id, customer_id').eq('company_id', ctx.companyId).eq('plate_norm', normalizePlate(plate)).maybeSingle()
    const foreign = !!knownCar && String((knownCar as any).customer_id) !== customerId
    const carId = foreign ? null : await upsertCar(scoped, { companyId: ctx.companyId, customerId, plate, model, bodyClassId })
    const note = [foreign ? `Машина: ${[model, plate.toUpperCase()].filter(Boolean).join(' ')} (номер уже есть у другого клиента)` : '', String(body?.comment || '').trim()].filter(Boolean).join(' · ')
    const { data: booking, error } = await ctx.supabase
      .from('detailing_bookings')
      .insert({
        organization_id: ctx.org.id,
        company_id: ctx.companyId,
        box_id: null,
        customer_id: customerId,
        car_id: carId,
        body_class_id: bodyClassId,
        start_date: body.date,
        start_time: normalizeTime(body?.time),
        // Быстрые услуги со временем занимают часы — администратор поставит их в бокс рядом с другими.
        duration_minutes: normalizeTime(body?.time) ? plan.minutes : null,
        days: Math.min(60, plan.days),
        status: 'requested',
        price_total: lines.reduce((s, l) => s + l.price, 0),
        materials_total: lines.reduce((s, l) => s + l.materials, 0),
        comment: note.slice(0, 500) || null,
        source: 'online',
      })
      .select('id')
      .single()
    if (error) throw error
    const { error: linesError } = await ctx.supabase.from('detailing_booking_services').insert(lines.map((l) => ({ ...l, booking_id: booking.id })))
    if (linesError) {
      await ctx.supabase.from('detailing_bookings').delete().eq('id', booking.id)
      throw linesError
    }
    return json({ ok: true })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'public-detailing-book:post', message: error?.message || 'Public booking POST error' })
    return json({ error: 'Не получилось отправить заявку. Позвоните нам.' }, 500)
  }
}
