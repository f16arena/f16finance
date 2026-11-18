import { writeSystemErrorLogSafe } from '@/lib/server/audit'
import { addDays, detailingContext, json, normalizeTime } from '@/lib/server/detailing'
import { isMissingMastersTable, loadMasterPay, monthStart } from '@/lib/server/detailing-masters'

/**
 * Экран мастера: только его машины и его заработок за месяц.
 *
 * Кто он — берём из связи учётки с мастером (operator_staff_links), а не из
 * запроса: чужие машины и чужой заработок отсюда не достать. Телефон клиента
 * мастеру не отдаём — с клиентом говорит администратор.
 */
export async function GET(request: Request) {
  try {
    const ctx = await detailingContext(request, 'detailing-my.view')
    if ('response' in ctx) return ctx.response
    const staffId = String(ctx.access.staffMember?.id || '')
    const { data: link } = staffId
      ? await ctx.supabase.from('operator_staff_links').select('operator_id').eq('staff_id', staffId).maybeSingle()
      : { data: null }
    const operatorId = link?.operator_id ? String(link.operator_id) : ''
    const companyIds = ctx.companies.map((c) => c.id)
    if (!operatorId || !companyIds.length) return json({ ok: true, data: { linked: false } })

    const today = new Date(Date.now() + 5 * 3600_000).toISOString().slice(0, 10)
    const month = monthStart(null, today)
    const [carsRes, pay, boxesRes] = await Promise.all([
      ctx.supabase
        .from('detailing_bookings')
        .select('id, box_id, start_date, start_time, days, status, price_total, materials_total, comment, car:customer_cars(plate, model), services:detailing_booking_services(name, sort_order)')
        .in('company_id', companyIds)
        .eq('master_operator_id', operatorId)
        .in('status', ['booked', 'in_box'])
        .lte('start_date', addDays(today, 7))
        .order('start_date')
        .order('start_time', { nullsFirst: false })
        .limit(50),
      loadMasterPay(ctx.supabase, { companyIds, month, tolerant: true }),
      ctx.supabase.from('detailing_boxes').select('id, name').in('company_id', companyIds),
    ])
    if (carsRes.error) throw carsRes.error
    const boxName = new Map(((boxesRes.data || []) as any[]).map((b) => [String(b.id), String(b.name)]))
    const mine = pay.masters.filter((m) => m.id === operatorId)
    const sum = (key: 'jobs' | 'base' | 'accrued' | 'paid' | 'due') => mine.reduce((s, m) => s + m[key], 0)

    return json({
      ok: true,
      data: {
        linked: true,
        name: mine[0]?.name || '',
        today,
        month,
        cars: ((carsRes.data || []) as any[]).map((b) => ({
          id: String(b.id),
          box: b.box_id ? boxName.get(String(b.box_id)) || null : null,
          start_date: String(b.start_date),
          start_time: normalizeTime(b.start_time),
          days: Number(b.days),
          status: String(b.status),
          car: b.car ? { plate: b.car.plate, model: b.car.model } : null,
          services: ((b.services || []) as any[]).sort((x, y) => x.sort_order - y.sort_order).map((s) => String(s.name)),
          comment: b.comment || null,
          // С этой суммы считается процент: цена минус материалы.
          base: Math.max(0, Number(b.price_total || 0) - Number(b.materials_total || 0)),
        })),
        pay: { jobs: sum('jobs'), base: sum('base'), accrued: sum('accrued'), paid: sum('paid'), due: sum('due'), percent: mine[0]?.terms.percent_on ? mine[0].terms.percent : null },
      },
    })
  } catch (error: any) {
    if (isMissingMastersTable(error)) return json({ error: 'Раздел ещё не включён' }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-my:get', message: error?.message || 'Detailing my GET error' })
    return json({ error: 'Не удалось загрузить' }, 500)
  }
}
