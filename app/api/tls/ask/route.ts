import { getTenantBaseHost } from '@/lib/core/tenant-domain'
import { resolveOrganizationByHost } from '@/lib/server/tenant-hosts'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'

// Caddy на своём сервере спрашивает перед выпуском сертификата on-demand: GET ?domain=<host>.
// 200 — выпускать (apex, www, db и поддомены существующих организаций), 404 — нет.
// Без этой проверки любой мог бы заставить сервер выпускать сертификаты на случайные имена
// и выжечь лимит Let's Encrypt на домен.
export async function GET(request: Request) {
  const domain = String(new URL(request.url).searchParams.get('domain') || '').trim().toLowerCase()
  const base = getTenantBaseHost().toLowerCase()
  if (!domain) return new Response('no', { status: 404 })
  if ([base, `www.${base}`, `db.${base}`].includes(domain)) return new Response('ok')
  const org = await resolveOrganizationByHost(domain)
  return org ? new Response('ok') : new Response('no', { status: 404 })
}
