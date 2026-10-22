import 'server-only'

import { readdir, readFile, stat, statfs } from 'node:fs/promises'
import os from 'node:os'

/**
 * Живое состояние сервера для /platform/status — без чужих панелей.
 * Ресурсы хоста: /proc контейнера показывает хост (load, память, CPU, диски), раз в 5 с.
 * Сеть хоста, контейнеры и хвосты их логов кладёт deploy/vps/status-snapshot.sh в /control.
 * Деплои — /control/deploys.jsonl (пишет deploy.sh), журналы кронов/бэкапа/сборки и
 * бэкапы смонтированы только на чтение (deploy/vps/docker-compose.yml).
 */

export const CONTROL = '/control'
export const HOST_LOGS: Record<string, string> = {
  deploy: '/hostlogs/deploy.log',
  cron: '/hostlogs/cron.log',
  backup: '/hostlogs/backup.log',
}
const BACKUPS = '/backups'
const SITE_CHECKS = [
  { url: 'https://www.ordaops.kz/', probe: 'https://www.ordaops.kz/login' },
  { url: 'https://db.ordaops.kz/', probe: 'https://db.ordaops.kz/auth/v1/health' },
]

type Point = { t: number; cpu: number; mem: number; swap: number; rx: number; tx: number; rd: number; wr: number; load: number }
type CheckPoint = { t: number; ok: boolean; ms: number | null }

type HostState = {
  history: Point[]
  prevCpu?: { idle: number; total: number }
  prevDisk?: { t: number; rd: number; wr: number }
  prevNet?: { t: number; rx: number; tx: number }
  rate: { rx: number; tx: number }
  checks: Map<string, { code: number; ms: number | null; at: number; history: CheckPoint[] }>
  started: boolean
}
// В globalThis: модуль может грузиться в нескольких чанках, а сэмплер нужен один.
const g = globalThis as unknown as { __ordaHost?: HostState }
const S: HostState = (g.__ordaHost ??= { history: [], rate: { rx: 0, tx: 0 }, checks: new Map(), started: false })

async function readText(path: string) {
  try { return await readFile(path, 'utf-8') } catch { return '' }
}

async function cpuPercent() {
  const line = (await readText('/proc/stat')).split('\n')[0] || ''
  const v = line.trim().split(/\s+/).slice(1).map(Number)
  const idle = (v[3] || 0) + (v[4] || 0)
  const total = v.reduce((a, b) => a + (b || 0), 0)
  const prev = S.prevCpu
  S.prevCpu = { idle, total }
  if (!prev || total <= prev.total) return 0
  return Math.max(0, Math.min(100, (100 * (1 - (idle - prev.idle) / (total - prev.total)))))
}

async function memInfo() {
  const m: Record<string, number> = {}
  for (const l of (await readText('/proc/meminfo')).split('\n')) {
    const [k, v] = l.split(':')
    if (k && v) m[k.trim()] = Number(v.trim().split(/\s+/)[0]) * 1024
  }
  return {
    mem_total: m.MemTotal || 0,
    mem_used: (m.MemTotal || 0) - (m.MemAvailable || 0),
    swap_total: m.SwapTotal || 0,
    swap_used: (m.SwapTotal || 0) - (m.SwapFree || 0),
  }
}

// Чтение/запись дисков хоста (физические vdX/sdX/nvmeXnY, без разделов), байт/с.
async function diskRate(now: number) {
  let rd = 0, wr = 0
  for (const l of (await readText('/proc/diskstats')).split('\n')) {
    const f = l.trim().split(/\s+/)
    if (f.length < 10 || !/^(vd|sd|xvd)[a-z]+$|^nvme\d+n\d+$/.test(f[2])) continue
    rd += Number(f[5]) * 512
    wr += Number(f[9]) * 512
  }
  const prev = S.prevDisk
  S.prevDisk = { t: now, rd, wr }
  if (!prev || now <= prev.t) return { rd: 0, wr: 0 }
  return { rd: Math.max(0, (rd - prev.rd) / (now - prev.t)), wr: Math.max(0, (wr - prev.wr) / (now - prev.t)) }
}

async function netRate() {
  const [t, rx, tx] = (await readText(`${CONTROL}/hostnet`)).trim().split(/\s+/).map(Number)
  if (!t) return S.rate
  const prev = S.prevNet
  if (!prev || t > prev.t) {
    if (prev && t > prev.t) S.rate = { rx: Math.max(0, (rx - prev.rx) / (t - prev.t)), tx: Math.max(0, (tx - prev.tx) / (t - prev.t)) }
    S.prevNet = { t, rx, tx }
  }
  return S.rate
}

async function sample() {
  const now = Date.now() / 1000
  const [cpu, mem, disk, net] = await Promise.all([cpuPercent(), memInfo(), diskRate(now), netRate()])
  const load = Number((await readText('/proc/loadavg')).split(' ')[0]) || 0
  S.history.push({ t: Math.floor(now), cpu: Math.round(cpu * 10) / 10, mem: mem.mem_used, swap: mem.swap_used, rx: net.rx, tx: net.tx, rd: disk.rd, wr: disk.wr, load })
  if (S.history.length > 720) S.history.splice(0, S.history.length - 720)
}

async function checkSites() {
  const now = Math.floor(Date.now() / 1000)
  const anon = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || ''
  await Promise.all(SITE_CHECKS.map(async (c) => {
    const t0 = Date.now()
    let code = 0
    try {
      const r = await fetch(c.probe, { headers: c.probe.includes('/auth/') ? { apikey: anon } : {}, redirect: 'manual', cache: 'no-store', signal: AbortSignal.timeout(10000) })
      code = r.status
    } catch { code = 0 }
    const ms = code ? Date.now() - t0 : null
    const entry = S.checks.get(c.url) || { code, ms, at: now, history: [] }
    entry.code = code; entry.ms = ms; entry.at = now
    entry.history.push({ t: now, ok: code > 0 && code < 500, ms })
    if (entry.history.length > 120) entry.history.splice(0, entry.history.length - 120)
    S.checks.set(c.url, entry)
  }))
}

/** Запускает сэмплеры в процессе веба один раз (история за час — в памяти, после деплоя копится заново). */
export function ensureHostSampler() {
  if (S.started) return
  S.started = true
  void sample()
  void checkSites()
  setInterval(() => void sample(), 5000).unref?.()
  setInterval(() => void checkSites(), 30000).unref?.()
}

export async function serverInfo() {
  const mem = await memInfo()
  let disk_total = 0, disk_used = 0
  try {
    const fs = await statfs('/')
    disk_total = fs.blocks * fs.bsize
    disk_used = (fs.blocks - fs.bfree) * fs.bsize
  } catch { /* нет statfs — оставим нули */ }
  return {
    host: 'cloud-001',
    ncpu: os.cpus().length,
    uptime: Math.floor(Number((await readText('/proc/uptime')).split(' ')[0]) || 0),
    load: (await readText('/proc/loadavg')).split(' ').slice(0, 3).map(Number),
    ...mem,
    disk_total,
    disk_used,
    reboot_required: false,
    history: S.history,
  }
}

export function siteChecks() {
  return SITE_CHECKS.map((c) => {
    const e = S.checks.get(c.url)
    return { url: c.url, code: e?.code ?? 0, ms: e?.ms ?? null, at: e?.at ?? 0, history: e?.history ?? [] }
  })
}

export async function deploys() {
  const byId = new Map<string, any>()
  for (const l of (await readText(`${CONTROL}/deploys.jsonl`)).split('\n')) {
    if (!l.trim()) continue
    try {
      const e = JSON.parse(l)
      byId.set(e.id, { ...(byId.get(e.id) || {}), ...e })
    } catch { /* битая строка */ }
  }
  const list = [...byId.values()].map((d) => {
    const start = Number(d.start) || Number(d.id)
    const end = d.end ? Number(d.end) : undefined
    return {
      start, end, duration: end ? end - start : undefined,
      from: String(d.from || '').slice(0, 7), sha: String(d.sha || '').slice(0, 7), sha_full: String(d.sha || ''),
      status: d.status === 'running' && Date.now() / 1000 - start > 30 * 60 ? 'unknown' : String(d.status || 'unknown'),
      subject: d.subject || '', error: d.error || undefined, action: d.action || 'push',
    }
  })
  return list.sort((a, b) => b.start - a.start).slice(0, 40)
}

export async function containers() {
  try {
    const j = JSON.parse(await readText(`${CONTROL}/containers.json`))
    return {
      at: Number(j.at) || 0,
      list: (j.containers || []).map((c: any) => ({
        name: c.name, image: c.image, state: c.state, health: c.health || undefined,
        status: c.state === 'running' ? `Up с ${String(c.started_at || '').slice(0, 16).replace('T', ' ')} UTC` : c.state,
        started_at: c.started_at, restarts: c.restarts, cpu: c.cpu, mem: c.mem, mem_limit: c.mem_limit,
      })),
    }
  } catch { return { at: 0, list: [] } }
}

export async function latestBackup() {
  try {
    const files = (await readdir(BACKUPS)).filter((f) => /^db-.*\.dump$/.test(f))
    if (!files.length) return null
    const withTime = await Promise.all(files.map(async (f) => ({ f, s: await stat(`${BACKUPS}/${f}`) })))
    withTime.sort((a, b) => b.s.mtimeMs - a.s.mtimeMs)
    return { name: withTime[0].f, time: Math.floor(withTime[0].s.mtimeMs / 1000), size: withTime[0].s.size, count: files.length }
  } catch { return null }
}

/** Хвост файла журнала, не читая его целиком. */
export async function tailFile(path: string, lines: number) {
  const text = await readText(path)
  const all = text.split('\n')
  return all.slice(Math.max(0, all.length - lines - 1)).join('\n')
}

export async function containerLog(name: string) {
  return readText(`${CONTROL}/logs/${name}.log`)
}
