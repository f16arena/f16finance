import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

// Платформа → «Ошибки»: журнал ошибок сервера и браузеров (audit_log, entity_type
// system-error) группами — где, что, сколько раз, первая/последняя и последние случаи.
// Только суперадмин.

export const dynamic = 'force-dynamic'

const PERIODS: Record<string, number> = { '1h': 3600, '24h': 86400, '7d': 7 * 86400, '30d': 30 * 86400 }

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  if (!hasAdminSupabaseCredentials()) return NextResponse.json({ error: 'нет доступа к базе' }, { status: 503 })

  const url = new URL(req.url)
  const period = PERIODS[url.searchParams.get('period') || '24h'] ? url.searchParams.get('period') || '24h' : '24h'
  const seconds = PERIODS[period]
  const now = Math.floor(Date.now() / 1000)
  const supabase = createAdminSupabaseClient()
  const { data, error } = await supabase
    .from('audit_log')
    .select('id, created_at, action, actor_user_id, payload')
    .eq('entity_type', 'system-error')
    .gte('created_at', new Date((now - seconds) * 1000).toISOString())
    .order('created_at', { ascending: false })
    .limit(1000)
  if (error) return NextResponse.json({ error: error.message }, { status: 500 })

  // Имена авторов — по email аккаунта (staff.user_id в базе нет).
  const actorIds = [...new Set((data || []).map((r: any) => r.actor_user_id).filter(Boolean))].slice(0, 50) as string[]
  const actors: Record<string, string> = {}
  await Promise.all(actorIds.map(async (id) => {
    try {
      const { data: u } = await (supabase as any).auth.admin.getUserById(id)
      if (u?.user?.email) actors[id] = String(u.user.email)
    } catch { /* удалённый аккаунт */ }
  }))

  const buckets = period === '1h' ? 12 : period === '24h' ? 24 : period === '7d' ? 7 * 4 : 30
  const step = seconds / buckets
  const timeline = Array.from({ length: buckets }, () => 0)
  const groups = new Map<string, any>()
  for (const r of (data || []) as any[]) {
    const p = r.payload || {}
    const t = Math.floor(Date.parse(r.created_at) / 1000)
    const idx = Math.min(buckets - 1, Math.max(0, buckets - 1 - Math.floor((now - t) / step)))
    timeline[idx]++
    const area = String(p.area || '—')
    const message = String(p.message || '').split('\n')[0].slice(0, 300)
    const key = `${area}|${message}`
    const occurrence = {
      at: t,
      actor: r.actor_user_id ? actors[r.actor_user_id] || r.actor_user_id.slice(0, 8) : null,
      pathname: p.pathname || null,
      user_agent: p.user_agent || null,
      code: p.code || null,
      details: p.details || null,
      hint: p.hint || null,
      stage: p.stage || null,
      stack: p.stack ? String(p.stack).slice(0, 2000) : null,
      full_message: String(p.message || '').slice(0, 2000),
    }
    const g = groups.get(key)
    if (g) {
      g.count++
      g.first_at = Math.min(g.first_at, t)
      if (g.samples.length < 5) g.samples.push(occurrence)
      if (occurrence.actor) g.users.add(occurrence.actor)
    } else {
      groups.set(key, {
        area, message, scope: String(p.scope || (String(r.action).startsWith('client') ? 'client' : 'server')),
        count: 1, first_at: t, last_at: t, samples: [occurrence], users: new Set(occurrence.actor ? [occurrence.actor] : []),
      })
    }
  }

  return NextResponse.json({
    now,
    period,
    total: (data || []).length,
    truncated: (data || []).length >= 1000,
    timeline,
    step,
    groups: [...groups.values()]
      .map((g) => ({ ...g, users: [...g.users].length }))
      .sort((a, b) => b.last_at - a.last_at),
  })
}
