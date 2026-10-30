import 'server-only'

import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { requireAnyCapability, requireCapability } from '@/lib/server/capabilities'
import { requireOrgFeature } from '@/lib/server/entitlements'
import { resolveCompanyScope } from '@/lib/server/organizations'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'
import { createCompanyForOrganization } from '@/lib/server/company-create'

/**
 * Детейлинг (миграция 20261029_detailing_core): точки с companies.industry =
 * 'detailing', боксы, прайс по классу кузова, машины клиентов, запись на неделю.
 *
 * Все роуты берут точку из запроса и сверяют её с охватом сотрудника
 * (resolveCompanyScope) и с нишей. Чужую точку или точку другой ниши — 404.
 */

export const DETAILING_FEATURE = 'service.jobs'

export function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

export type DetailingCompany = { id: string; name: string; code: string | null }

export type DetailingCtx = {
  access: any
  supabase: any
  orgId: string
  companies: DetailingCompany[]
}

/** Авторизация + список точек-детейлингов, доступных сотруднику. */
export async function detailingContext(
  request: Request,
  capability: string | string[],
): Promise<DetailingCtx | { response: Response }> {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return { response: access.response }
  const denied = Array.isArray(capability)
    ? await requireAnyCapability(access, capability)
    : await requireCapability(access, capability)
  if (denied) return { response: denied }
  const entitlementGuard = await requireOrgFeature(access, DETAILING_FEATURE)
  if (entitlementGuard) return { response: entitlementGuard }

  const orgId = access.activeOrganization?.id || null
  if (!orgId) return { response: json({ error: 'Выберите организацию' }, 400) }
  const supabase = hasAdminSupabaseCredentials() ? createAdminSupabaseClient() : access.supabase

  const scope = await resolveCompanyScope({
    activeOrganizationId: orgId,
    isSuperAdmin: access.isSuperAdmin,
    companyRestriction: access.companyRestriction,
  })
  const load = async () => {
    let q = supabase
      .from('companies')
      .select('id, name, code')
      .eq('organization_id', orgId)
      .eq('industry', 'detailing')
      .is('archived_at', null)
      .order('name')
    if (scope.allowedCompanyIds) q = q.in('id', scope.allowedCompanyIds)
    const { data, error } = await q
    if (error) throw error
    return ((data || []) as any[]).map((c) => ({ id: String(c.id), name: String(c.name), code: c.code ?? null }))
  }
  let companies = await load()
  // У детейлинга точку руками не заводят: нет ни одной — создаём сами.
  if (!companies.length && !scope.allowedCompanyIds && (await ensureNichePoint(supabase, orgId))) companies = await load()
  return { access, supabase, orgId, companies }
}

/**
 * Организация-детейлинг (settings.niche) без точек — заводим одну с именем
 * организации. Ниша живёт в организации, точка для владельца невидима.
 */
export async function ensureNichePoint(supabase: any, orgId: string): Promise<boolean> {
  const { data: org } = await supabase.from('organizations').select('name, settings').eq('id', orgId).maybeSingle()
  if ((org as any)?.settings?.niche !== 'detailing') return false
  const { count } = await supabase.from('companies').select('id', { count: 'exact', head: true }).eq('organization_id', orgId).is('archived_at', null)
  if ((count || 0) > 0) return false
  const res = await createCompanyForOrganization(
    supabase,
    { activeOrganization: { id: orgId }, isSuperAdmin: true, companyRestriction: null },
    { name: String((org as any).name || 'Детейлинг'), industry: 'detailing' },
  )
  return res.ok
}

/** Точка из запроса, если она детейлинг и доступна; иначе null. */
export function pickCompany(ctx: DetailingCtx, requested: string | null | undefined): DetailingCompany | null {
  const id = String(requested || '').trim()
  if (!id) return ctx.companies[0] || null
  return ctx.companies.find((c) => c.id === id) || null
}

export function isMissingDetailingTable(error: any) {
  const code = String(error?.code || '')
  const message = String(error?.message || '')
  return code === '42P01' || code === 'PGRST205' || (/detailing_|customer_cars/.test(message) && /does not exist|schema cache/.test(message))
}

/** «777 abc 02» → «777ABC02»: по этому ищем и не даём завести номер дважды. */
export function normalizePlate(plate: string) {
  return String(plate || '').toUpperCase().replace(/[^0-9A-ZА-ЯЁ]/g, '')
}

/** Телефон в виде +7XXXXXXXXXX; 10 цифр без кода тоже считаем казахстанскими. */
export function normalizePhone(phone: string) {
  const digits = String(phone || '').replace(/\D/g, '')
  if (digits.length === 10) return `+7${digits}`
  if (digits.length === 11 && (digits.startsWith('7') || digits.startsWith('8'))) return `+7${digits.slice(1)}`
  return digits ? `+${digits}` : ''
}

export function addDays(isoDate: string, days: number) {
  const d = new Date(`${isoDate}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
}

export const BOOKING_STATUSES = ['requested', 'booked', 'in_box', 'done', 'no_show', 'cancelled'] as const
export type BookingStatus = (typeof BOOKING_STATUSES)[number]

/** Статусы, которые занимают бокс. */
export const OCCUPYING_STATUSES: BookingStatus[] = ['booked', 'in_box', 'done']

/**
 * Занят ли бокс в дни [start, start+days). Выданная машина тоже держит свои
 * дни: иначе на прошедшую неделю можно было бы записать поверх.
 */
export async function findBoxConflict(
  supabase: any,
  args: { companyId: string; boxId: string; startDate: string; days: number; ignoreId?: string | null },
) {
  const end = addDays(args.startDate, args.days) // не включительно
  // Брони, начавшиеся до конца нашего окна; пересечение досчитываем здесь —
  // в PostgREST нельзя сравнить start_date + days с датой.
  let q = supabase
    .from('detailing_bookings')
    .select('id, start_date, days')
    .eq('company_id', args.companyId)
    .eq('box_id', args.boxId)
    .in('status', OCCUPYING_STATUSES)
    .lt('start_date', end)
    .gte('start_date', addDays(args.startDate, -60))
  if (args.ignoreId) q = q.neq('id', args.ignoreId)
  const { data, error } = await q
  if (error) throw error
  return ((data || []) as any[]).find((b) => addDays(String(b.start_date), Number(b.days)) > args.startDate) || null
}

export type PriceRow = { service_id: string; body_class_id: string; price: number; materials: number }

/** Строки услуг для записи по прайсу: цена и материалы для класса кузова. */
export async function priceLines(
  supabase: any,
  args: { companyId: string; serviceIds: string[]; bodyClassId: string | null },
) {
  if (!args.serviceIds.length) return { lines: [], missing: [] as string[] }
  const { data: services, error } = await supabase
    .from('detailing_services')
    .select('id, name, days, sort_order, prices:detailing_service_prices(body_class_id, price, materials)')
    .eq('company_id', args.companyId)
    .in('id', args.serviceIds)
  if (error) throw error
  const byId = new Map(((services || []) as any[]).map((s) => [String(s.id), s]))
  const lines: Array<{ service_id: string; name: string; price: number; materials: number; days: number; sort_order: number }> = []
  const missing: string[] = []
  args.serviceIds.forEach((id, index) => {
    const s = byId.get(id)
    if (!s) {
      missing.push(id)
      return
    }
    const p = ((s.prices || []) as any[]).find((row) => String(row.body_class_id) === String(args.bodyClassId))
    if (!p) {
      missing.push(String(s.name))
      return
    }
    lines.push({
      service_id: id,
      name: String(s.name),
      price: Number(p.price || 0),
      materials: Number(p.materials || 0),
      days: Number(s.days || 1),
      sort_order: index,
    })
  })
  return { lines, missing }
}

/** Мастера точки: активные сотрудники, назначенные на неё. */
export async function loadMasters(supabase: any, companyId: string) {
  const { data, error } = await supabase
    .from('operator_company_assignments')
    .select('operator_id, is_active, operator:operators(id, name, short_name, is_active)')
    .eq('company_id', companyId)
    .eq('is_active', true)
  if (error) throw error
  return ((data || []) as any[])
    .map((row) => row.operator)
    .filter((o) => o && o.is_active !== false)
    .map((o) => ({ id: String(o.id), name: String(o.short_name || o.name || 'Без имени') }))
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'))
}

/** Пример прайса для новой точки: правится сразу на странице «Прайс и боксы». */
export const DEFAULT_SETUP = {
  boxes: [
    { name: 'Бокс 1', note: 'универсальный' },
    { name: 'Бокс 2', note: 'плёнка, полный кузов' },
    { name: 'Бокс 3', note: 'универсальный' },
    { name: 'Бокс 4', note: 'мойка, химчистка' },
  ],
  classes: ['Седан', 'Кроссовер', 'Джип / минивэн'],
  services: [
    { name: 'Капот плёнкой', days: 1, prices: [[70000, 22000], [85000, 26000], [100000, 31000]] },
    { name: 'Полный перед плёнкой', days: 2, package_note: 'капот, бампер, фары, зеркала', prices: [[220000, 70000], [260000, 82000], [300000, 95000]] },
    { name: 'Весь кузов плёнкой', days: 5, prices: [[950000, 330000], [1150000, 400000], [1350000, 470000]] },
    { name: 'Керамика кузова', days: 1, prices: [[150000, 28000], [180000, 34000], [220000, 40000]] },
    { name: 'Полировка кузова', days: 1, prices: [[60000, 8000], [75000, 10000], [90000, 12000]] },
    { name: 'Химчистка салона', days: 1, prices: [[45000, 6000], [55000, 7000], [65000, 9000]] },
    { name: 'Тонировка', days: 1, prices: [[35000, 9000], [40000, 11000], [50000, 14000]] },
  ],
}

/** Справочники точки: боксы, классы кузова, прайс, мастера. */
export async function loadReference(ctx: Pick<DetailingCtx, 'supabase'>, companyId: string) {
  const [boxes, classes, services, masters] = await Promise.all([
    ctx.supabase.from('detailing_boxes').select('id, name, note, sort_order, is_active').eq('company_id', companyId).order('sort_order').order('name'),
    ctx.supabase.from('detailing_body_classes').select('id, name, sort_order, is_active').eq('company_id', companyId).order('sort_order').order('name'),
    ctx.supabase
      .from('detailing_services')
      .select('id, name, package_note, days, sort_order, is_active, prices:detailing_service_prices(body_class_id, price, materials)')
      .eq('company_id', companyId)
      .order('sort_order')
      .order('name'),
    loadMasters(ctx.supabase, companyId),
  ])
  for (const r of [boxes, classes, services]) if (r.error) throw r.error
  return {
    boxes: boxes.data || [],
    classes: classes.data || [],
    services: ((services.data || []) as any[]).map((s) => ({
      ...s,
      prices: ((s.prices || []) as any[]).map((p) => ({ body_class_id: p.body_class_id, price: Number(p.price), materials: Number(p.materials) })),
    })),
    masters,
  }
}
