import { createClient } from '@supabase/supabase-js'

import { getTenantBaseHost, normalizeTenantHost } from '@/lib/core/tenant-domain'

type HostOrganization = {
  id: string
  name: string
  slug: string
  status: string
} | null

const HOST_CACHE_TTL_MS = 60_000 // 1 minute
const hostCache = new Map<string, { value: HostOrganization; expiresAt: number }>()
const defaultOrgCache = new Map<string, { value: HostOrganization; expiresAt: number }>()

function createServiceSupabaseClient() {
  const url = process.env.NEXT_PUBLIC_SUPABASE_URL || process.env.SUPABASE_URL
  const key = process.env.SUPABASE_SERVICE_ROLE_KEY || process.env.SUPABASE_SERVICE_KEY

  if (!url || !key) return null

  return createClient(url, key, {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
    },
  })
}

export function normalizeRequestHost(hostHeader: string | null | undefined) {
  const rawHost = String(hostHeader || '')
    .trim()
    .toLowerCase()
    .split(':')[0]

  return normalizeTenantHost(rawHost)
}

export function getDefaultOrganizationSlug() {
  return String(process.env.DEFAULT_ORGANIZATION_SLUG || 'f16')
    .trim()
    .toLowerCase()
}

export async function resolveDefaultOrganization(): Promise<HostOrganization> {
  const slug = getDefaultOrganizationSlug()
  if (!slug) return null

  const cached = defaultOrgCache.get(slug)
  if (cached && cached.expiresAt > Date.now()) {
    return cached.value
  }

  const supabase = createServiceSupabaseClient()
  if (!supabase) return null

  const { data } = await supabase
    .from('organizations')
    .select('id, name, slug, status')
    .eq('slug', slug)
    .limit(1)
    .maybeSingle()

  const result: HostOrganization = (data as any)?.id
    ? {
        id: String((data as any).id),
        name: String((data as any).name || ''),
        slug: String((data as any).slug || ''),
        status: String((data as any).status || 'active'),
      }
    : null

  defaultOrgCache.set(slug, { value: result, expiresAt: Date.now() + HOST_CACHE_TTL_MS })
  return result
}

const RESERVED_TENANT_SLUGS = new Set(['www', 'db', 'studio', 'admin', 'api', 'app', 'status', 'mail', 'blog', 'docs', 'support'])

// Поддомен <slug>.ordaops.kz → организация по slug (с кэшем). apex/www/зарезервированные → null.
export async function resolveOrganizationByHost(hostHeader: string | null | undefined): Promise<HostOrganization> {
  const normalized = normalizeRequestHost(hostHeader)
  if (!normalized) return null

  const baseHost = getTenantBaseHost().toLowerCase()
  // apex (ordaops.kz) и www → дефолтная организация (F16). Так apex и f16.ordaops.kz —
  // один контекст владельца. Другие тенанты — только через свой поддомен.
  if (normalized === baseHost || normalized === `www.${baseHost}`) {
    return await resolveDefaultOrganization()
  }
  if (!normalized.endsWith(`.${baseHost}`)) return null

  const slug = normalized.slice(0, -(baseHost.length + 1))
  if (!slug || slug.includes('.') || RESERVED_TENANT_SLUGS.has(slug)) return null

  const cached = hostCache.get(slug)
  if (cached && cached.expiresAt > Date.now()) return cached.value

  const supabase = createServiceSupabaseClient()
  if (!supabase) return null

  const { data } = await supabase
    .from('organizations')
    .select('id, name, slug, status')
    .eq('slug', slug)
    .limit(1)
    .maybeSingle()

  const result: HostOrganization = (data as any)?.id
    ? {
        id: String((data as any).id),
        name: String((data as any).name || ''),
        slug: String((data as any).slug || ''),
        status: String((data as any).status || 'active'),
      }
    : null

  hostCache.set(slug, { value: result, expiresAt: Date.now() + HOST_CACHE_TTL_MS })
  return result
}

export type HostNiche = { org: NonNullable<HostOrganization>; niche: 'detailing' | null }

const nicheCache = new Map<string, { value: 'detailing' | null; expiresAt: number }>()

/**
 * Чья это дверь: организация поддомена и её ниша. Только для <slug>.ordaops.kz —
 * на основном домене (apex, www) вход общий, там null.
 *
 * Ниша считается так же, как в session-role: все действующие точки —
 * детейлинг, а пока точек нет — settings.niche организации. По ней страница
 * входа показывает человеку его программу ещё до пароля.
 */
export async function resolveHostNiche(hostHeader: string | null | undefined): Promise<HostNiche | null> {
  const normalized = normalizeRequestHost(hostHeader)
  const baseHost = getTenantBaseHost().toLowerCase()
  if (!normalized || normalized === baseHost || normalized === `www.${baseHost}`) return null

  const org = await resolveOrganizationByHost(hostHeader)
  if (!org) return null

  const cached = nicheCache.get(org.id)
  if (cached && cached.expiresAt > Date.now()) return { org, niche: cached.value }

  const supabase = createServiceSupabaseClient()
  if (!supabase) return { org, niche: null }

  const { data: rows } = await supabase.from('companies').select('industry').eq('organization_id', org.id).is('archived_at', null)
  let industries = [...new Set(((rows || []) as any[]).map((r) => String(r.industry || '')).filter(Boolean))]
  if (!industries.length) {
    const { data: orgRow } = await supabase.from('organizations').select('settings').eq('id', org.id).maybeSingle()
    const fromSettings = String((orgRow as any)?.settings?.niche || '')
    if (fromSettings) industries = [fromSettings]
  }
  const niche = industries.length > 0 && industries.every((i) => i === 'detailing') ? 'detailing' : null
  nicheCache.set(org.id, { value: niche, expiresAt: Date.now() + HOST_CACHE_TTL_MS })
  return { org, niche }
}
