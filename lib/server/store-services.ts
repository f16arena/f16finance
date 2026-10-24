import 'server-only'

/**
 * Услуги магазина (миграция 20261024_store_services): справочник с вариантами
 * цены, продажа без остатка и таймеры для вариантов со временем.
 *
 * Пока миграция не применена, таблиц нет — чтение возвращает пустой список,
 * чтобы касса и сайт работали как раньше.
 */

export type StoreServiceVariant = {
  id: string
  service_id: string
  name: string
  price: number
  duration_minutes: number | null
  is_active: boolean
  sort_order: number
}

export type StoreService = {
  id: string
  organization_id: string
  name: string
  company_ids: string[]
  is_active: boolean
  sort_order: number
  variants: StoreServiceVariant[]
}

export type StoreServiceSession = {
  id: string
  company_id: string
  service_id: string | null
  variant_id: string | null
  sale_id: string | null
  title: string
  started_at: string
  ends_at: string
  finished_at: string | null
}

const SESSION_FIELDS = 'id, company_id, service_id, variant_id, sale_id, title, started_at, ends_at, finished_at'

function isMissingTable(error: any) {
  const code = String(error?.code || '')
  return code === '42P01' || code === 'PGRST205' || /store_service/.test(String(error?.message || '')) && /does not exist|schema cache/.test(String(error?.message || ''))
}

export async function loadOrganizationServices(supabase: any, organizationId: string): Promise<StoreService[]> {
  const { data, error } = await supabase
    .from('store_services')
    .select('id, organization_id, name, company_ids, is_active, sort_order, variants:store_service_variants(id, service_id, name, price, duration_minutes, is_active, sort_order)')
    .eq('organization_id', organizationId)
    .order('sort_order')
    .order('name')
  if (error) {
    if (isMissingTable(error)) return []
    throw error
  }
  return ((data || []) as any[]).map((row) => ({
    ...row,
    company_ids: Array.isArray(row.company_ids) ? row.company_ids : [],
    variants: ((row.variants || []) as any[])
      .map((v) => ({ ...v, price: Number(v.price || 0), duration_minutes: v.duration_minutes == null ? null : Number(v.duration_minutes) }))
      .sort((a, b) => a.sort_order - b.sort_order || a.price - b.price),
  }))
}

/** Что продаётся на этой точке: активные услуги с активными вариантами. */
export async function loadPointServices(supabase: any, organizationId: string, companyId: string): Promise<StoreService[]> {
  const all = await loadOrganizationServices(supabase, organizationId)
  return all
    .filter((s) => s.is_active && (s.company_ids.length === 0 || s.company_ids.includes(companyId)))
    .map((s) => ({ ...s, variants: s.variants.filter((v) => v.is_active) }))
    .filter((s) => s.variants.length > 0)
}

export function serviceLineName(service: { name: string }, variant: { name: string }) {
  return `${service.name.trim()} · ${variant.name.trim()}`
}

export async function loadActiveSessions(supabase: any, companyId: string): Promise<StoreServiceSession[]> {
  // Закончившиеся, но не закрытые кнопкой, держим на экране ещё 30 минут —
  // кассир видит «время вышло», а забытые не копятся вечно.
  const since = new Date(Date.now() - 30 * 60_000).toISOString()
  const { data, error } = await supabase
    .from('store_service_sessions')
    .select(SESSION_FIELDS)
    .eq('company_id', companyId)
    .is('finished_at', null)
    .gte('ends_at', since)
    .order('ends_at')
  if (error) {
    if (isMissingTable(error)) return []
    throw error
  }
  return (data || []) as StoreServiceSession[]
}

/**
 * После продажи: каждая строка со временем запускает таймер или продлевает
 * указанный. Продление считается от конца текущего таймера, а если он уже
 * вышел — от «сейчас».
 */
export async function startSessionsForSale(params: {
  supabase: any
  companyId: string
  saleId: string
  operatorId: string | null
  lines: Array<{
    service: { id: string; name: string }
    variant: { id: string; duration_minutes: number | null }
    quantity: number
    extend_session_id: string | null
  }>
}): Promise<StoreServiceSession[]> {
  const out: StoreServiceSession[] = []
  const now = Date.now()
  for (const line of params.lines) {
    const minutes = Number(line.variant.duration_minutes || 0) * line.quantity
    if (!(minutes > 0)) continue

    if (line.extend_session_id) {
      const { data: current } = await params.supabase
        .from('store_service_sessions')
        .select(SESSION_FIELDS)
        .eq('id', line.extend_session_id)
        .eq('company_id', params.companyId)
        .is('finished_at', null)
        .maybeSingle()
      if (current) {
        const from = Math.max(now, new Date(current.ends_at).getTime())
        const { data: updated } = await params.supabase
          .from('store_service_sessions')
          .update({ ends_at: new Date(from + minutes * 60_000).toISOString() })
          .eq('id', current.id)
          .select(SESSION_FIELDS)
          .maybeSingle()
        if (updated) out.push(updated)
        continue
      }
      // Таймер уже закрыли — продление становится новым таймером.
    }

    const { data: created, error } = await params.supabase
      .from('store_service_sessions')
      .insert({
        company_id: params.companyId,
        service_id: line.service.id,
        variant_id: line.variant.id,
        sale_id: params.saleId,
        title: line.service.name.trim(),
        started_at: new Date(now).toISOString(),
        ends_at: new Date(now + minutes * 60_000).toISOString(),
        operator_id: params.operatorId,
      })
      .select(SESSION_FIELDS)
      .maybeSingle()
    if (error) throw error
    if (created) out.push(created)
  }
  return out
}
