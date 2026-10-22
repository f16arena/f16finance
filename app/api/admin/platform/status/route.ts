import { NextResponse } from 'next/server'

import vercelConfig from '@/vercel.json'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'
import { containers, deploys, ensureHostSampler, HOST_LOGS, latestBackup, serverInfo, siteChecks, tailFile } from '@/lib/server/host-status'

// Платформа → «Сервер и деплои»: живое состояние Orda на cloud-001 (только суперадмин).
// Ресурсы, сайты, деплои, контейнеры, бэкап — lib/server/host-status.ts; кроны — из
// журнала cron, ошибки — из audit_log.

export const dynamic = 'force-dynamic'

const json = (data: unknown, status = 200) => NextResponse.json(data, { status })

type CronRun = { t: number; code: number }

async function cronSummary(now: number) {
  const schedule = new Map<string, string>()
  for (const c of (vercelConfig as { crons?: Array<{ path: string; schedule: string }> }).crons || []) schedule.set(c.path, c.schedule)

  const text = await tailFile(HOST_LOGS.cron, 6000)

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

  ensureHostSampler()
  const now = Math.floor(Date.now() / 1000)
  const [server, crons, errors, deployList, cont, backup] = await Promise.all([
    serverInfo(),
    cronSummary(now),
    errorSummary(now).catch(() => ({ total: 0, groups: [], hourly: [] })),
    deploys(),
    containers(),
    latestBackup(),
  ])

  return json({
    now,
    // Снимок контейнеров делает cron на хосте раз в 20 секунд; старше 3 минут — он не работает.
    panel_error: cont.at && now - cont.at < 180 ? null : 'нет свежего снимка контейнеров (status-snapshot.sh на сервере)',
    server,
    project: {
      repo: 'https://github.com/f16arena/f16finance',
      deploys: deployList,
      checks: siteChecks(),
      containers: cont.list,
      backup,
    },
    crons,
    errors,
  })
}
