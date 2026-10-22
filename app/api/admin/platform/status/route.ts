import { NextResponse } from 'next/server'

import vercelConfig from '@/vercel.json'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'
import { statusPanelAuth } from '@/lib/server/status-panel'

// Платформа → «Сервер»: состояние Orda на cloud-001 (только суперадмин).
// Метрики сервера, деплои, проверки сайтов, контейнеры и бэкап собирает панель
// status.commrent.kz (deploy/vps/status в репозитории Commrent) — берём у неё,
// чтобы не сэмплировать хост второй раз. Своё добавляем: кроны и ошибки из журнала.

export const dynamic = 'force-dynamic'

const json = (data: unknown, status = 200) => NextResponse.json(data, { status })

// Панель отдаёт состояние раз в 5 секунд — несколько открытых вкладок не должны
// дёргать её каждая отдельно.
let cache: { at: number; state: any } | null = null
async function panelState(auth: { url: string; header: string }) {
  if (cache && Date.now() - cache.at < 4000) return cache.state
  const res = await fetch(`${auth.url}/api/state`, { headers: { authorization: auth.header }, cache: 'no-store', signal: AbortSignal.timeout(8000) })
  if (!res.ok) throw new Error(`панель ответила ${res.status}`)
  const state = await res.json()
  cache = { at: Date.now(), state }
  return state
}

type CronRun = { t: number; code: number }

async function cronSummary(auth: { url: string; header: string }, now: number) {
  const schedule = new Map<string, string>()
  for (const c of (vercelConfig as { crons?: Array<{ path: string; schedule: string }> }).crons || []) schedule.set(c.path, c.schedule)

  let text = ''
  try {
    const res = await fetch(`${auth.url}/api/logs?project=orda&log=cron&tail=6000`, { headers: { authorization: auth.header }, cache: 'no-store', signal: AbortSignal.timeout(8000) })
    if (res.ok) text = await res.text()
  } catch { /* без журнала покажем только расписание */ }

  const runs = new Map<string, CronRun[]>()
  for (const line of text.split('\n')) {
    const m = line.match(/^(\S+Z)\s+(\d{3}|000)\s+https?:\/\/[^/]+(\/\S*)/)
    if (!m) continue
    const t = Math.floor(Date.parse(m[1]) / 1000)
    if (!Number.isFinite(t) || now - t > 86400) continue
    const list = runs.get(m[3]) || []
    list.push({ t, code: Number(m[2]) })
    runs.set(m[3], list)
  }

  const paths = new Set([...schedule.keys(), ...runs.keys()])
  return [...paths].map((path) => {
    const list = runs.get(path) || []
    const last = list[list.length - 1] || null
    const failed = list.filter((r) => r.code !== 200)
    return {
      path,
      name: path.replace('/api/cron/', ''),
      schedule: schedule.get(path) || null,
      last_at: last?.t ?? null,
      last_code: last?.code ?? null,
      runs_24h: list.length,
      failed_24h: failed.length,
      last_failed_at: failed.length ? failed[failed.length - 1].t : null,
      // последние 24 запуска — полоска как у проверок сайтов
      recent: list.slice(-24).map((r) => ({ t: r.t, ok: r.code === 200 })),
    }
  }).sort((a, b) => (b.failed_24h - a.failed_24h) || a.name.localeCompare(b.name))
}

async function errorSummary(now: number) {
  if (!hasAdminSupabaseCredentials()) return { total: 0, groups: [], hourly: [] }
  const since = new Date((now - 86400) * 1000).toISOString()
  const { data } = await createAdminSupabaseClient()
    .from('audit_log')
    .select('created_at, action, payload')
    .eq('entity_type', 'system-error')
    .gte('created_at', since)
    .order('created_at', { ascending: false })
    .limit(1000)
  const rows = (data || []) as Array<{ created_at: string; action: string; payload: any }>

  const groups = new Map<string, { area: string; message: string; scope: string; count: number; last_at: number }>()
  const hourly = Array.from({ length: 24 }, () => 0)
  for (const r of rows) {
    const t = Math.floor(Date.parse(r.created_at) / 1000)
    const area = String(r.payload?.area || '—')
    const message = String(r.payload?.message || '').split('\n')[0].slice(0, 220)
    const key = `${area}|${message}`
    const g = groups.get(key)
    if (g) { g.count++; g.last_at = Math.max(g.last_at, t) }
    else groups.set(key, { area, message, scope: String(r.payload?.scope || r.action || ''), count: 1, last_at: t })
    const h = 23 - Math.min(23, Math.floor((now - t) / 3600))
    hourly[h]++
  }
  return {
    total: rows.length,
    groups: [...groups.values()].sort((a, b) => b.last_at - a.last_at).slice(0, 30),
    hourly,
  }
}

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return json({ error: 'forbidden' }, 403)

  const auth = statusPanelAuth()
  const now = Math.floor(Date.now() / 1000)
  const [stateRes, crons, errors] = await Promise.all([
    auth ? panelState(auth).then((s) => ({ ok: true as const, s }), (e: any) => ({ ok: false as const, error: String(e?.message || e) })) : Promise.resolve({ ok: false as const, error: 'не настроен доступ к панели (STATUS_PANEL_*)' }),
    auth ? cronSummary(auth, now) : Promise.resolve([]),
    errorSummary(now).catch(() => ({ total: 0, groups: [], hourly: [] })),
  ])

  const project = stateRes.ok ? (stateRes.s.projects || []).find((p: any) => p.id === 'orda') || null : null
  return json({
    now,
    panel_error: stateRes.ok ? null : stateRes.error,
    server: stateRes.ok ? stateRes.s.server : null,
    project,
    crons,
    errors,
  })
}
