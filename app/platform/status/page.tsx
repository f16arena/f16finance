'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Activity,
  AlertTriangle,
  Check,
  Clock,
  Cpu,
  Database,
  GitCommit,
  Globe,
  HardDrive,
  Loader2,
  MemoryStick,
  Pin,
  RefreshCw,
  ScrollText,
  Server,
  Timer,
  X,
} from 'lucide-react'

import { confirmDialog } from '@/components/ui/confirm-dialog'

import DatabaseTab from './database-tab'
import ErrorsTab from './errors-tab'
import LogsTab from './logs-tab'

// Платформа → «Сервер»: здоровье Orda на cloud-001 вживую. Данные — /api/admin/platform/status
// (панель сервера + кроны из журнала + ошибки из audit_log), обновление каждые 5 секунд.

type Point = { t: number; cpu: number; mem: number; swap: number; rx: number; tx: number; rd: number; wr: number; load: number }
type ServerInfo = {
  host: string; ncpu: number; uptime: number; load: number[]
  mem_total: number; mem_used: number; swap_total: number; swap_used: number
  disk_total: number; disk_used: number; reboot_required: boolean; history: Point[]
}
type Deploy = { start: number; end?: number; duration?: number; from?: string; sha?: string; sha_full?: string; status: string; subject?: string; error?: string; inferred?: boolean; action?: string }
type Check = { url: string; code: number; ms: number | null; at: number; history: Array<{ t: number; ok: boolean; ms: number | null }> }
type Container = { name: string; image: string; state: string; status: string; health?: string; cpu: number | null; mem: number; mem_limit?: number }
type Project = {
  repo?: string; head?: { sha: string; subject: string; time: number }
  deploys: Deploy[]; checks: Check[]; containers: Container[]
  backup?: { name: string; time: number; size: number; count: number } | null
}
type Cron = { path: string; name: string; schedule: string | null; last_at: number | null; last_code: number | null; runs_24h: number; failed_24h: number; last_failed_at: number | null; recent: Array<{ t: number; ok: boolean }> }
type ErrorGroup = { area: string; message: string; scope: string; count: number; last_at: number }
type StatusData = {
  now: number; panel_error: string | null; server: ServerInfo | null; project: Project | null
  crons: Cron[]; errors: { total: number; groups: ErrorGroup[]; hourly: number[] }
}

const G = 1024 ** 3
const gb = (b: number) => (b / G).toFixed(b >= 10 * G ? 0 : 1) + ' ГБ'
const mb = (b: number) => (b >= G ? gb(b) : Math.round(b / 1024 ** 2) + ' МБ')
const rate = (b: number) => (b >= 1024 ** 2 ? (b / 1024 ** 2).toFixed(1) + ' МБ/с' : (b / 1024).toFixed(b < 10240 ? 1 : 0) + ' КБ/с')
const pct = (a: number, b: number) => (b ? (100 * a) / b : 0)
const plural = (n: number, a: string, b: string, c: string) => { const m = n % 10, h = n % 100; return m === 1 && h !== 11 ? a : m >= 2 && m <= 4 && (h < 12 || h > 14) ? b : c }
function ago(t: number, now: number) {
  const s = Math.max(0, now - t)
  if (s < 45) return 'только что'
  if (s < 3600) return Math.round(s / 60) + ' мин назад'
  if (s < 86400) return Math.floor(s / 3600) + ' ч назад'
  const d = Math.floor(s / 86400); return `${d} ${plural(d, 'день', 'дня', 'дней')} назад`
}
const dur = (s?: number | null) => (s == null ? '' : s < 60 ? `${s} с` : `${Math.floor(s / 60)} мин ${String(s % 60).padStart(2, '0')} с`)
// Время — по Алматы: сервер и журналы в UTC, а читает владелец.
const TZ = 'Asia/Almaty'
const hm = (t: number) => new Date(t * 1000).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', timeZone: TZ })
const dt = (t: number) => new Date(t * 1000).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: TZ })
const upTxt = (up: number) => (up >= 86400 ? `${Math.floor(up / 86400)} дн ${Math.floor((up % 86400) / 3600)} ч` : `${Math.floor(up / 3600)} ч ${Math.floor((up % 3600) / 60)} мин`)

type Level = 'ok' | 'run' | 'warn' | 'bad'
const LEVEL: Record<Level, { label: string; dot: string; pill: string; text: string }> = {
  ok: { label: 'Работает', dot: 'bg-emerald-500', pill: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400', text: 'text-emerald-500' },
  run: { label: 'Деплой', dot: 'bg-sky-500 animate-pulse', pill: 'bg-sky-500/10 text-sky-600 dark:text-sky-400', text: 'text-sky-500' },
  warn: { label: 'Внимание', dot: 'bg-amber-500', pill: 'bg-amber-500/10 text-amber-600 dark:text-amber-400', text: 'text-amber-500' },
  bad: { label: 'Сбой', dot: 'bg-rose-500', pill: 'bg-rose-500/10 text-rose-600 dark:text-rose-400', text: 'text-rose-500' },
}
const DEPLOY: Record<string, [Level, string]> = { ok: ['ok', 'Готово'], failed: ['bad', 'Ошибка'], rollback: ['bad', 'Откат'], running: ['run', 'Сборка'], unknown: ['warn', 'Оборвался'] }
const checkOk = (c: Check) => c.code > 0 && c.code < 500

function overall(d: StatusData) {
  const issues: Array<[Level, string]> = []
  const rank: Record<Level, number> = { ok: 0, run: 1, warn: 2, bad: 3 }
  let lv: Level = 'ok'
  const bump = (l: Level, why: string) => { issues.push([l, why]); if (rank[l] > rank[lv]) lv = l }
  if (d.panel_error) bump('warn', 'нет данных с панели сервера: ' + d.panel_error)
  const p = d.project
  for (const c of p?.checks || []) if (!checkOk(c)) bump('bad', 'не отвечает ' + c.url.replace('https://', '').replace(/\/$/, ''))
  for (const c of p?.containers || []) {
    if (c.state !== 'running') bump('bad', `${c.name} — ${c.state}`)
    else if (c.health === 'unhealthy') bump('bad', `${c.name} — unhealthy`)
  }
  const last = p?.deploys?.[0]
  if (last?.status === 'running') bump('run', 'собирается новая версия')
  else if (last && last.status !== 'ok') bump('warn', 'последний деплой не прошёл — работает прежняя версия')
  if (p?.backup && d.now - p.backup.time > 26 * 3600) bump('warn', 'бэкап базы старше суток')
  const badCrons = d.crons.filter((c) => c.last_code != null && c.last_code !== 200)
  if (badCrons.length) bump('warn', `${badCrons.length} ${plural(badCrons.length, 'крон падает', 'крона падают', 'кронов падают')}`)
  const recentErr = d.errors.hourly.slice(-1)[0] || 0
  if (recentErr >= 10) bump('warn', `${recentErr} ошибок за последний час`)
  if (d.server) {
    if (pct(d.server.disk_used, d.server.disk_total) >= 90) bump('bad', 'диск заполнен на 90%+')
    else if (pct(d.server.disk_used, d.server.disk_total) >= 80) bump('warn', 'диск заполнен на 80%+')
    if (d.server.reboot_required) bump('warn', 'серверу нужна перезагрузка (обновления ядра)')
  }
  return { lv, issues }
}

function Card({ className = '', children }: { className?: string; children: React.ReactNode }) {
  return <div className={`rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-white/[0.07] dark:bg-white/[0.025] dark:shadow-none ${className}`}>{children}</div>
}
function SectionTitle({ icon: Icon, title, sub, right }: { icon: any; title: string; sub?: string; right?: React.ReactNode }) {
  return (
    <div className="mb-3 mt-9 flex flex-wrap items-end gap-x-3 gap-y-1">
      <h2 className="flex items-center gap-2 text-base font-semibold tracking-tight text-slate-900 dark:text-white"><Icon className="h-4 w-4 text-slate-400" />{title}</h2>
      {sub ? <span className="text-xs text-slate-500 dark:text-slate-400">{sub}</span> : null}
      {right ? <div className="ml-auto">{right}</div> : null}
    </div>
  )
}

// ── кольцо ресурса ──
function Ring({ value, label, line1, line2, icon: Icon }: { value: number; label: string; line1: string; line2?: string; icon: any }) {
  const r = 42, C = 2 * Math.PI * r, v = Math.max(0, Math.min(100, value))
  const color = v >= 90 ? '#f43f5e' : v >= 75 ? '#f59e0b' : 'url(#ring-grad)'
  return (
    <div className="flex flex-col items-center px-3 py-5 text-center">
      <div className="relative h-[104px] w-[104px]">
        <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90">
          <defs><linearGradient id="ring-grad" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#8b5cf6" /><stop offset="1" stopColor="#d946ef" /></linearGradient></defs>
          <circle cx="50" cy="50" r={r} fill="none" strokeWidth="8" className="stroke-slate-100 dark:stroke-white/[0.07]" />
          <circle cx="50" cy="50" r={r} fill="none" stroke={color} strokeWidth="8" strokeLinecap="round" strokeDasharray={C} strokeDashoffset={C * (1 - v / 100)} style={{ transition: 'stroke-dashoffset .8s cubic-bezier(.2,.8,.2,1)' }} />
        </svg>
        <div className="absolute inset-0 grid place-items-center">
          <div className="text-[22px] font-bold tabular-nums tracking-tight text-slate-900 dark:text-white">{Math.round(v)}<span className="text-xs font-medium text-slate-400">%</span></div>
        </div>
      </div>
      <div className="mt-2 flex items-center gap-1.5 text-sm font-medium text-slate-800 dark:text-slate-200"><Icon className="h-3.5 w-3.5 text-slate-400" />{label}</div>
      <div className="text-xs tabular-nums text-slate-500 dark:text-slate-400">{line1}</div>
      {line2 ? <div className="text-xs tabular-nums text-slate-400 dark:text-slate-500">{line2}</div> : null}
    </div>
  )
}

// ── график за час ──
type MetricKey = 'cpu' | 'mem' | 'net' | 'io'
const METRICS: Record<MetricKey, { label: string; series: Array<[keyof Point, string, string]>; fmt: (v: number) => string; axis: (v: number) => string }> = {
  cpu: { label: 'Процессор', series: [['cpu', '#8b5cf6', 'CPU']], fmt: (v) => v.toFixed(0) + '%', axis: (v) => v.toFixed(0) + '%' },
  mem: { label: 'Память', series: [['mem', '#d946ef', 'Память']], fmt: gb, axis: (v) => (v / G).toFixed(0) + ' ГБ' },
  net: { label: 'Сеть', series: [['rx', '#10b981', 'Входящий'], ['tx', '#f97316', 'Исходящий']], fmt: rate, axis: (v) => rate(v).replace('/с', '') },
  io: { label: 'Диск', series: [['rd', '#06b6d4', 'Чтение'], ['wr', '#ec4899', 'Запись']], fmt: rate, axis: (v) => rate(v).replace('/с', '') },
}
const niceMax = (v: number) => { if (v <= 0) return 1; const e = 10 ** Math.floor(Math.log10(v)), f = v / e; return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * e }

function Chart({ server }: { server: ServerInfo }) {
  const [metric, setMetric] = useState<MetricKey>('cpu')
  const [hover, setHover] = useState<number | null>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const [W, setW] = useState(600)
  useEffect(() => {
    const el = boxRef.current; if (!el) return
    const ro = new ResizeObserver(([e]) => setW(e.contentRect.width))
    ro.observe(el); return () => ro.disconnect()
  }, [])
  const H = 220, L = 52, R = 10, T = 10, B = 24
  const h = server.history
  const m = METRICS[metric]
  if (h.length < 2) return <div className="grid h-[260px] place-items-center text-sm text-slate-400">Копим историю…</div>
  const last = h[h.length - 1]
  const peak = Math.max(...h.flatMap((p) => m.series.map(([k]) => Number(p[k]))))
  const max = metric === 'cpu' ? 100 : metric === 'mem' ? server.mem_total : niceMax(peak * 1.15)
  const t0 = h[0].t, t1 = last.t
  const x = (t: number) => L + ((t - t0) / Math.max(1, t1 - t0)) * (W - L - R)
  const y = (v: number) => T + (1 - Math.min(v, max) / max) * (H - T - B)
  const span = t1 - t0, step = span > 2400 ? 900 : span > 1200 ? 600 : span > 300 ? 300 : 60
  const ticks: number[] = []; for (let t = Math.ceil(t0 / step) * step; t <= t1; t += step) ticks.push(t)
  const hp = hover != null ? h[hover] : null

  return (
    <div className="flex min-w-0 flex-col p-5">
      <div className="flex flex-wrap items-start gap-4">
        <div className="flex gap-0.5 rounded-xl border border-slate-200 bg-slate-50 p-1 dark:border-white/[0.07] dark:bg-white/[0.03]">
          {(Object.keys(METRICS) as MetricKey[]).map((k) => (
            <button key={k} onClick={() => setMetric(k)} className={`rounded-lg px-3 py-1.5 text-xs font-medium transition ${metric === k ? 'bg-white text-slate-900 shadow-sm dark:bg-white/10 dark:text-white' : 'text-slate-500 hover:text-slate-800 dark:text-slate-400 dark:hover:text-white'}`}>{METRICS[k].label}</button>
          ))}
        </div>
        <div className="ml-auto text-right">
          <div className="text-2xl font-bold tabular-nums tracking-tight text-slate-900 dark:text-white">
            {m.series.map(([k, c], i) => <span key={k}>{i ? <span className="text-slate-300 dark:text-slate-600"> / </span> : null}<span style={{ color: m.series.length > 1 ? c : undefined }}>{m.fmt(Number(last[k]))}</span></span>)}
          </div>
          <div className="text-xs text-slate-500 dark:text-slate-400">{m.series.length > 1 ? m.series.map((s) => s[2]).join(' / ') + ' · ' : 'сейчас · '}пик за час {m.fmt(peak)}</div>
        </div>
      </div>
      <div ref={boxRef} className="relative mt-3 h-[220px]">
        <svg width={W} height={H} className="absolute inset-0 overflow-visible"
          onMouseMove={(e) => {
            const r = (e.currentTarget as SVGElement).getBoundingClientRect()
            const t = t0 + ((e.clientX - r.left - L) / (W - L - R)) * (t1 - t0)
            let best = 0; for (let i = 0; i < h.length; i++) if (Math.abs(h[i].t - t) < Math.abs(h[best].t - t)) best = i
            setHover(best)
          }}
          onMouseLeave={() => setHover(null)}>
          <defs>
            {m.series.map(([k, c]) => (
              <linearGradient key={k} id={`area-${k}`} x1="0" x2="0" y1="0" y2="1"><stop offset="0" stopColor={c} stopOpacity=".32" /><stop offset="1" stopColor={c} stopOpacity="0" /></linearGradient>
            ))}
          </defs>
          {[0, 1, 2, 3, 4].map((i) => {
            const v = (max / 4) * i, yy = y(v)
            return <g key={i}><line x1={L} x2={W - R} y1={yy} y2={yy} className="stroke-slate-200 dark:stroke-white/[0.07]" strokeDasharray={i ? '3 4' : undefined} /><text x={L - 8} y={yy + 4} textAnchor="end" className="fill-slate-400 font-mono text-[10px] dark:fill-slate-500">{m.axis(v)}</text></g>
          })}
          {ticks.map((t) => <text key={t} x={x(t)} y={H - 6} textAnchor="middle" className="fill-slate-400 font-mono text-[10px] dark:fill-slate-500">{hm(t)}</text>)}
          {m.series.map(([k, c]) => {
            const d = h.map((p, i) => (i ? 'L' : 'M') + x(p.t).toFixed(1) + ' ' + y(Number(p[k])).toFixed(1)).join('')
            return <g key={k}><path d={`${d}L${x(t1)} ${y(0)}L${x(t0)} ${y(0)}Z`} fill={`url(#area-${k})`} /><path d={d} fill="none" stroke={c} strokeWidth="1.8" strokeLinejoin="round" /></g>
          })}
          {hp ? <>
            <line x1={x(hp.t)} x2={x(hp.t)} y1={T} y2={H - B} className="stroke-slate-300 dark:stroke-slate-600" strokeDasharray="3 3" />
            {m.series.map(([k, c]) => <circle key={k} cx={x(hp.t)} cy={y(Number(hp[k]))} r="4" fill={c} className="stroke-white dark:stroke-[#0b1020]" strokeWidth="2" />)}
          </> : null}
        </svg>
        {hp ? (
          <div className="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-[115%] whitespace-nowrap rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs shadow-lg dark:border-white/10 dark:bg-slate-900"
            style={{ left: x(hp.t), top: y(Math.max(...m.series.map(([k]) => Number(hp[k])))) }}>
            <div className="text-slate-400">{new Date(hp.t * 1000).toLocaleTimeString('ru-RU', { timeZone: TZ })}</div>
            {m.series.map(([k, c, n]) => <div key={k} className="font-semibold text-slate-900 dark:text-white"><span style={{ color: c }}>●</span> {m.series.length > 1 ? n + ' ' : ''}{m.fmt(Number(hp[k]))}</div>)}
          </div>
        ) : null}
      </div>
    </div>
  )
}

function Bars({ cells, total = 40 }: { cells: Array<{ ok: boolean; title: string }>; total?: number }) {
  const pad = Math.max(0, total - cells.length)
  return (
    <div className="flex h-6 items-stretch gap-[2px]">
      {Array.from({ length: pad }).map((_, i) => <i key={'p' + i} className="flex-1 rounded-[2px] bg-slate-100 dark:bg-white/[0.06]" />)}
      {cells.slice(-total).map((c, i) => <i key={i} title={c.title} className={`flex-1 rounded-[2px] ${c.ok ? 'bg-emerald-500/80' : 'bg-rose-500'}`} />)}
    </div>
  )
}

function StatusIcon({ level }: { level: Level }) {
  const cls = { ok: 'bg-emerald-500', run: 'bg-sky-500 animate-pulse', warn: 'bg-amber-500', bad: 'bg-rose-500' }[level]
  return (
    <span className={`grid h-[18px] w-[18px] shrink-0 place-items-center rounded-full text-white ${cls}`}>
      {level === 'ok' ? <Check className="h-3 w-3" strokeWidth={3.5} /> : level === 'bad' ? <X className="h-3 w-3" strokeWidth={3.5} /> : level === 'warn' ? <span className="text-[11px] font-bold leading-none">!</span> : <span className="h-1.5 w-1.5 rounded-full bg-white" />}
    </span>
  )
}

function ShaLink({ repo, d }: { repo?: string; d: Deploy }) {
  const sha = (d.sha || '').split('-')[0].slice(0, 7)
  if (!sha) return null
  const inner = <><GitCommit className="h-3 w-3" />{sha}</>
  return repo
    ? <a href={`${repo}/commit/${d.sha_full || sha}`} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 font-mono text-xs text-slate-500 hover:text-blue-500 dark:text-slate-400">{inner}</a>
    : <span className="inline-flex items-center gap-1 font-mono text-xs text-slate-500">{inner}</span>
}

// ── журналы ──
function LogDrawer({ source, onClose, sources, setSource }: { source: [string, string] | null; onClose: () => void; sources: Array<[string, string]>; setSource: (s: [string, string]) => void }) {
  const [raw, setRaw] = useState('')
  const [filter, setFilter] = useState('')
  const [auto, setAuto] = useState(true)
  const preRef = useRef<HTMLPreElement>(null)
  const load = useCallback(async (jump?: boolean) => {
    if (!source) return
    const q = source[0] === 'c' ? `container=${encodeURIComponent(source[1])}` : `log=${source[1]}`
    try {
      const text = await (await fetch(`/api/admin/platform/status/logs?${q}&tail=1000`, { cache: 'no-store' })).text()
      const pre = preRef.current
      const atEnd = !pre || pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 40
      setRaw(text)
      if (jump || atEnd) requestAnimationFrame(() => { if (preRef.current) preRef.current.scrollTop = preRef.current.scrollHeight })
    } catch (e: any) { setRaw('Ошибка: ' + e?.message) }
  }, [source])
  useEffect(() => { setRaw('загрузка…'); void load(true) }, [load])
  useEffect(() => {
    if (!source || !auto) return
    const id = setInterval(() => void load(), 4000)
    return () => clearInterval(id)
  }, [source, auto, load])
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    addEventListener('keydown', onKey); return () => removeEventListener('keydown', onKey)
  }, [onClose])
  const lines = useMemo(() => raw.split('\n').filter((l) => !filter || l.toLowerCase().includes(filter.toLowerCase())), [raw, filter])
  const open = !!source
  return (
    <>
      <div onClick={onClose} className={`fixed inset-0 z-40 bg-slate-950/50 backdrop-blur-[2px] transition-opacity ${open ? 'opacity-100' : 'pointer-events-none opacity-0'}`} />
      <aside className={`fixed inset-y-0 right-0 z-50 flex w-full max-w-[860px] flex-col border-l border-slate-200 bg-white transition-transform duration-300 dark:border-white/10 dark:bg-[#070b17] ${open ? 'translate-x-0' : 'translate-x-full'}`}>
        <div className="flex items-center gap-3 border-b border-slate-200 px-5 py-4 dark:border-white/[0.07]">
          <ScrollText className="h-4 w-4 text-blue-500" />
          <div className="font-semibold text-slate-900 dark:text-white">Журналы</div>
          <button onClick={onClose} className="ml-auto grid h-8 w-8 place-items-center rounded-lg border border-slate-200 text-slate-500 hover:bg-slate-50 dark:border-white/10 dark:hover:bg-white/5"><X className="h-4 w-4" /></button>
        </div>
        <div className="flex flex-wrap gap-1.5 px-5 pt-4">
          {sources.map((s) => {
            const on = source && source[0] === s[0] && source[1] === s[1]
            return <button key={s.join(':')} onClick={() => setSource(s)} className={`rounded-full border px-2.5 py-1 font-mono text-xs transition ${on ? 'border-blue-500 bg-blue-500 text-white' : 'border-slate-200 text-slate-500 hover:text-slate-900 dark:border-white/10 dark:text-slate-400 dark:hover:text-white'}`}>{s[0] === 'log' ? '📄 ' : ''}{s[1]}</button>
          })}
        </div>
        <div className="flex items-center gap-3 px-5 py-3 text-xs text-slate-500">
          <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Фильтр строк…" className="w-full max-w-xs rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-900 outline-none focus:border-blue-400 dark:border-white/10 dark:bg-white/[0.03] dark:text-white" />
          <label className="flex items-center gap-1.5"><input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} /> обновлять</label>
          <span className="ml-auto tabular-nums">{lines.length} строк</span>
        </div>
        <pre ref={preRef} className="mx-5 mb-5 flex-1 overflow-auto whitespace-pre-wrap break-words rounded-xl border border-slate-800 bg-[#050505] p-4 font-mono text-xs leading-relaxed text-slate-300">
          {lines.map((l, i) => {
            const ts = l.match(/^(\d{4}-\d\d-\d\dT[\d:.]+Z)\s?(.*)$/)
            const body = ts ? ts[2] : l
            const cls = /error|fail|упал|ошибк|fatal|panic|⨯/i.test(l) ? 'text-rose-400' : /warn/i.test(l) ? 'text-amber-300' : ''
            return <div key={i} className={cls}>{ts ? <span className="text-slate-600">{ts[1].slice(11, 19)} </span> : null}{body}</div>
          })}
        </pre>
      </aside>
    </>
  )
}

// Живой лог сборки, пока идёт деплой (как Building в Vercel): хвост журнала deploy каждые 3 с.
function BuildLog({ since, subject, now }: { since: number; subject: string; now: number }) {
  const [text, setText] = useState('')
  const preRef = useRef<HTMLPreElement>(null)
  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const t = await (await fetch('/api/admin/platform/status/logs?log=deploy&tail=400', { cache: 'no-store' })).text()
        if (!alive) return
        // Только текущая сборка: от строки «deploy …» с её временем.
        const lines = t.split('\n')
        let from = 0
        for (let i = lines.length - 1; i >= 0; i--) {
          const m = lines[i].match(/^(\S+Z) deploy /)
          if (m && Date.parse(m[1]) / 1000 >= since - 5) { from = i; break }
        }
        setText(lines.slice(from).join('\n'))
        requestAnimationFrame(() => { if (preRef.current) preRef.current.scrollTop = preRef.current.scrollHeight })
      } catch { /* следующая попытка через 3 с */ }
    }
    void load()
    const id = setInterval(load, 3000)
    return () => { alive = false; clearInterval(id) }
  }, [since])
  return (
    <Card className="mb-3 overflow-hidden border-sky-500/30">
      <div className="flex items-center gap-2 border-b border-slate-100 px-4 py-2.5 text-sm dark:border-white/[0.06]">
        <Loader2 className="h-4 w-4 animate-spin text-sky-500" />
        <span className="font-medium">Идёт сборка</span>
        <span className="min-w-0 truncate text-slate-500 dark:text-slate-400">{subject}</span>
        <span className="ml-auto shrink-0 font-mono text-xs tabular-nums text-sky-600 dark:text-sky-300">{dur(Math.max(0, now - since))}</span>
      </div>
      <pre ref={preRef} className="max-h-64 overflow-auto whitespace-pre-wrap break-words bg-[#050505] p-3 font-mono text-[11px] leading-relaxed text-slate-300">{text || 'ждём вывод сборки…'}</pre>
    </Card>
  )
}

function ServerTab() {
  const [data, setData] = useState<StatusData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [lastOk, setLastOk] = useState(0)
  const [logSource, setLogSource] = useState<[string, string] | null>(null)
  const [, force] = useState(0)
  const [control, setControl] = useState<{ pin: string | null; pending: { action: string; sha?: string } | null; rollback_since: number } | null>(null)
  const [busy, setBusy] = useState(false)
  const [controlMsg, setControlMsg] = useState<string | null>(null)

  const tick = useCallback(async () => {
    try {
      const res = await fetch('/api/admin/platform/status', { cache: 'no-store' })
      const j = await res.json()
      if (!res.ok) throw new Error(j?.error || `HTTP ${res.status}`)
      setData(j); setError(null); setLastOk(Date.now())
    } catch (e: any) { setError(e?.message || 'нет связи') }
    fetch('/api/admin/platform/deploy', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : null)).then((c) => c && setControl(c)).catch(() => null)
  }, [])

  // Пересобрать / откатить / вернуть автодеплой: заявку забирает deploy.sh на сервере в течение минуты.
  const deployAction = async (action: 'redeploy' | 'rollback' | 'unpin', d?: Deploy) => {
    const ask = {
      redeploy: { title: 'Пересобрать текущую версию?', description: 'Сайт соберётся заново из того же коммита (~2 минуты). Пока идёт сборка, работает прежняя версия.', confirmLabel: 'Пересобрать' },
      rollback: { title: `Откатить сайт на ${(d?.sha || '').slice(0, 7)}?`, description: `«${d?.subject || ''}». Откатывается только код, база остаётся как есть. Версия закрепится: новые коммиты не будут выкатываться, пока не нажмёте «Вернуть автодеплой».`, confirmLabel: 'Откатить' },
      unpin: { title: 'Вернуть автодеплой?', description: 'Сервер соберёт последний коммит из main и дальше снова будет выкатывать новые сам.', confirmLabel: 'Вернуть' },
    }[action]
    if (!(await confirmDialog(ask))) return
    setBusy(true); setControlMsg(null)
    try {
      const r = await fetch('/api/admin/platform/deploy', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action, sha: d?.sha_full || d?.sha }) })
      const j = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error(j?.error || `HTTP ${r.status}`)
      setControlMsg('Заявка принята — сервер возьмёт её в течение минуты, сборка ~2 минуты.')
      void tick()
    } catch (e: any) { setControlMsg('Не получилось: ' + (e?.message || e)) } finally { setBusy(false) }
  }
  useEffect(() => {
    void tick()
    const id = setInterval(() => void tick(), 5000)
    const clock = setInterval(() => force((n) => n + 1), 1000)
    return () => { clearInterval(id); clearInterval(clock) }
  }, [tick])

  const state = data ? overall(data) : null
  const sv = data?.server || null
  const p = data?.project || null
  const now = data?.now || Math.floor(Date.now() / 1000)
  const stale = lastOk > 0 && Date.now() - lastOk > 20000
  const sources: Array<[string, string]> = [['log', 'deploy'], ['log', 'cron'], ['log', 'backup'], ...(p?.containers || []).map((c) => ['c', c.name] as [string, string])]

  if (!data && !error) {
    return <div className="grid min-h-[60vh] place-items-center"><Loader2 className="h-6 w-6 animate-spin text-blue-400" /></div>
  }

  const hero = state ? {
    ok: ['Все системы работают', 'Сайт отвечает, контейнеры здоровы, деплой прошёл, кроны в порядке'],
    run: ['Идёт деплой', 'Сайт работает, новая версия собирается — обычно 2 минуты'],
    warn: ['Есть предупреждения', 'Сайт работает, но что-то стоит проверить'],
    bad: ['Есть сбой', 'Что-то не отвечает — подробности ниже'],
  }[state.lv] : null
  const heroColor = state ? { ok: '#10b981', run: '#0ea5e9', warn: '#f59e0b', bad: '#f43f5e' }[state.lv] : '#94a3b8'
  const containers = p?.containers || []
  const lastDeploy = p?.deploys?.[0]
  const cronsBad = (data?.crons || []).filter((c) => c.last_code != null && c.last_code !== 200)
  const maxHourly = Math.max(1, ...(data?.errors.hourly || [0]))

  return (
    <div className="relative min-h-screen text-slate-900 dark:text-white">
      {/* мягкое свечение сверху, как на панели сервера */}
      <div className="pointer-events-none absolute inset-x-0 top-0 h-80 bg-[radial-gradient(700px_300px_at_15%_-80px,rgba(139,92,246,.14),transparent_70%),radial-gradient(600px_280px_at_90%_-100px,rgba(217,70,239,.10),transparent_70%)]" />
      <div className="relative mx-auto max-w-6xl px-4 pb-16 sm:px-6">
        {/* шапка */}
        <div className="flex flex-wrap items-center gap-3 pt-6">
          <div className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
            <Server className="h-4 w-4" />
            <span className="font-medium text-slate-800 dark:text-slate-200">{sv?.host || 'cloud-001'}</span>
            <span className="rounded-full border border-slate-200 px-2 py-0.5 font-mono text-[11px] dark:border-white/10">185.113.132.3</span>
          </div>
          <div className="ml-auto flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
            <span className="relative flex h-2 w-2">
              {!stale && !error ? <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" /> : null}
              <span className={`relative inline-flex h-2 w-2 rounded-full ${stale || error ? 'bg-rose-500' : 'bg-emerald-500'}`} />
            </span>
            <span className="tabular-nums">{lastOk ? (stale ? 'нет данных с ' : 'обновлено ') + new Date(lastOk).toLocaleTimeString('ru-RU') : 'подключение…'}</span>
            <button onClick={() => void tick()} className="grid h-7 w-7 place-items-center rounded-lg border border-slate-200 hover:bg-slate-50 dark:border-white/10 dark:hover:bg-white/5" title="Обновить"><RefreshCw className="h-3.5 w-3.5" /></button>
          </div>
        </div>

        {error ? <div className="mt-4 rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-600 dark:text-rose-300">Нет связи: {error}</div> : null}

        {/* герой */}
        {state && hero ? (
          <div className="flex flex-wrap items-center gap-5 pb-2 pt-8">
            <div className="relative grid h-14 w-14 shrink-0 place-items-center rounded-2xl" style={{ color: heroColor }}>
              <span className="absolute inset-0 rounded-2xl bg-current opacity-[.14]" />
              <span className="absolute -inset-2.5 rounded-3xl bg-current opacity-20 blur-xl" />
              {state.lv === 'ok' ? <Check className="relative h-7 w-7" strokeWidth={3} /> : state.lv === 'bad' ? <X className="relative h-7 w-7" strokeWidth={3} /> : state.lv === 'run' ? <Loader2 className="relative h-7 w-7 animate-spin" /> : <AlertTriangle className="relative h-7 w-7" />}
            </div>
            <div className="min-w-0">
              <h1 className="text-3xl font-bold tracking-tight">{hero[0]}</h1>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">{hero[1]}</p>
            </div>
            <div className="ml-auto flex flex-wrap gap-7">
              {[
                ['Версия', lastDeploy?.sha ? lastDeploy.sha.slice(0, 7) : '—', lastDeploy ? ago(lastDeploy.end || lastDeploy.start, now) : ''],
                ['Контейнеры', `${containers.filter((c) => c.state === 'running').length}/${containers.length}`, 'запущено'],
                ['Аптайм', sv ? upTxt(sv.uptime) : '—', 'сервера'],
              ].map(([k, v, s]) => (
                <div key={k}>
                  <div className="text-xs text-slate-500 dark:text-slate-400">{k}</div>
                  <div className="font-mono text-lg font-semibold tabular-nums">{v}</div>
                  <div className="text-[11px] text-slate-400">{s}</div>
                </div>
              ))}
            </div>
          </div>
        ) : null}
        {state && state.issues.filter(([l]) => l === 'bad' || l === 'warn').length ? (
          <div className="mt-4 flex flex-wrap gap-2">
            {state.issues.filter(([l]) => l === 'bad' || l === 'warn').map(([l, why]) => (
              <span key={why} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${LEVEL[l].pill}`}><span className={`h-1.5 w-1.5 rounded-full ${LEVEL[l].dot}`} />{why}</span>
            ))}
          </div>
        ) : null}

        {/* ресурсы */}
        {sv ? <>
          <SectionTitle icon={Activity} title="Ресурсы сервера" sub="обновление каждые 5 секунд · сервер общий с Commrent и Jiyn · история с последнего деплоя" />
          <div className="grid gap-4 lg:grid-cols-[340px_1fr]">
            <Card className="grid grid-cols-2 divide-slate-100 dark:divide-white/[0.06] [&>*:nth-child(-n+2)]:border-b [&>*:nth-child(odd)]:border-r [&>*]:border-slate-100 dark:[&>*]:border-white/[0.06]">
              <Ring icon={Cpu} value={sv.history.length ? sv.history[sv.history.length - 1].cpu : 0} label="Процессор" line1={`${sv.ncpu} ${plural(sv.ncpu, 'ядро', 'ядра', 'ядер')}`} line2={`load ${sv.load[0].toFixed(2)}`} />
              <Ring icon={MemoryStick} value={pct(sv.mem_used, sv.mem_total)} label="Память" line1={`${gb(sv.mem_used)} из ${gb(sv.mem_total)}`} />
              <Ring icon={HardDrive} value={pct(sv.disk_used, sv.disk_total)} label="Диск" line1={`${gb(sv.disk_used)} из ${gb(sv.disk_total)}`} line2={`свободно ${gb(sv.disk_total - sv.disk_used)}`} />
              <Ring icon={Database} value={pct(sv.swap_used, sv.swap_total)} label="Swap" line1={`${gb(sv.swap_used)} из ${gb(sv.swap_total)}`} />
            </Card>
            <Card className="min-w-0"><Chart server={sv} /></Card>
          </div>
        </> : null}

        {/* сайты */}
        {p ? <>
          <SectionTitle icon={Globe} title="Сайты" sub="проверка каждые 30 секунд" />
          <div className="grid gap-4 md:grid-cols-2">
            {p.checks.map((c) => {
              const ok = checkOk(c)
              const ms = c.history.filter((h) => h.ms != null).map((h) => h.ms as number)
              const avg = ms.length ? Math.round(ms.reduce((a, b) => a + b, 0) / ms.length) : null
              return (
                <Card key={c.url} className="p-5">
                  <div className="flex items-center gap-3">
                    <span className={`h-2.5 w-2.5 rounded-full ${ok ? 'bg-emerald-500 shadow-[0_0_0_4px_rgba(16,185,129,.15)]' : 'bg-rose-500 shadow-[0_0_0_4px_rgba(244,63,94,.15)]'}`} />
                    <a href={c.url} target="_blank" rel="noopener noreferrer" className="truncate font-medium hover:text-blue-500">{c.url.replace('https://', '').replace(/\/$/, '')}</a>
                    <span className={`ml-auto rounded-full px-2.5 py-0.5 text-xs font-medium ${ok ? LEVEL.ok.pill : LEVEL.bad.pill}`}>{c.code || 'нет ответа'}{c.code === 401 ? ' · ждёт ключ' : ''}</span>
                  </div>
                  <div className="mt-4"><Bars cells={c.history.map((h) => ({ ok: h.ok, title: `${hm(h.t)} · ${h.ok ? 'ок' : 'сбой'}${h.ms != null ? ' · ' + h.ms + ' мс' : ''}` }))} /></div>
                  <div className="mt-2 flex justify-between text-[11px] text-slate-400">
                    <span>{c.history.length ? `${Math.round((c.history.length * 30) / 60)} мин назад` : ''}</span>
                    <span className="tabular-nums">сейчас {c.ms ?? '—'} мс · в среднем {avg ?? '—'} мс</span>
                  </div>
                </Card>
              )
            })}
          </div>
        </> : null}

        {/* деплои + бэкап */}
        {p ? (
          <div className="grid gap-4 lg:grid-cols-[1fr_320px]">
            <div className="min-w-0">
              <SectionTitle icon={GitCommit} title="Деплои" sub={control?.pin ? 'версия закреплена — новые коммиты ждут' : 'push в main → сервер собирает сам'}
                right={<button disabled={busy || !!control?.pending} onClick={() => void deployAction('redeploy')} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-2.5 py-1 text-xs font-medium text-slate-600 hover:bg-slate-50 disabled:opacity-50 dark:border-white/10 dark:text-slate-300 dark:hover:bg-white/5"><RefreshCw className="h-3 w-3" />Пересобрать</button>} />
              {control?.pin ? (
                <div className="mb-3 flex flex-wrap items-center gap-3 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-sm text-amber-700 dark:text-amber-300">
                  <Pin className="h-4 w-4" />
                  <span>Сайт закреплён на <b className="font-mono">{control.pin.slice(0, 7)}</b> после отката — автодеплой на паузе.</span>
                  <button disabled={busy || !!control.pending} onClick={() => void deployAction('unpin')} className="ml-auto rounded-lg bg-amber-500 px-3 py-1 text-xs font-semibold text-white hover:bg-amber-600 disabled:opacity-50">Вернуть автодеплой</button>
                </div>
              ) : null}
              {control?.pending ? <div className="mb-3 flex items-center gap-2 rounded-xl border border-sky-500/30 bg-sky-500/10 px-4 py-2.5 text-sm text-sky-700 dark:text-sky-300"><Loader2 className="h-4 w-4 animate-spin" />Заявка «{({ redeploy: 'пересобрать', rollback: 'откатить', unpin: 'вернуть автодеплой' } as Record<string, string>)[control.pending.action] || control.pending.action}» ждёт сервер (до минуты)…</div> : null}
              {controlMsg && !control?.pending ? <div className="mb-3 rounded-xl border border-slate-200 bg-slate-50 px-4 py-2.5 text-sm text-slate-600 dark:border-white/10 dark:bg-white/[0.03] dark:text-slate-300">{controlMsg}</div> : null}
              {lastDeploy?.status === 'running' ? <BuildLog since={lastDeploy.start} subject={lastDeploy.subject || ''} now={now} /> : null}
              <Card className="divide-y divide-slate-100 dark:divide-white/[0.06]">
                {p.deploys.length ? p.deploys.slice(0, 10).map((d, i) => {
                  const [lv, label] = DEPLOY[d.status] || DEPLOY.unknown
                  const live = control?.pin ? !!d.sha_full && d.sha_full === control.pin && i === p.deploys.findIndex((x) => x.sha_full === control.pin) : i === p.deploys.findIndex((x) => x.status === 'ok')
                  const canRollback = !live && d.status === 'ok' && !!d.sha_full && !!control && d.start >= control.rollback_since
                  return (
                    <div key={i} className="flex items-start gap-3 px-5 py-3.5 transition hover:bg-slate-50 dark:hover:bg-white/[0.02]">
                      <span className="mt-0.5"><StatusIcon level={lv} /></span>
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium">{d.subject || 'без описания'}</div>
                        <div className="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                          <ShaLink repo={p.repo} d={d} />
                          <span className={LEVEL[lv].text}>{label}</span>
                          {d.action === 'rollback' ? <span className="rounded-full bg-amber-500/10 px-2 py-px text-[11px] font-medium text-amber-600 dark:text-amber-300">откат</span> : d.action === 'redeploy' ? <span className="rounded-full bg-sky-500/10 px-2 py-px text-[11px] font-medium text-sky-600 dark:text-sky-300">пересборка</span> : d.action === 'unpin' ? <span className="rounded-full bg-emerald-500/10 px-2 py-px text-[11px] font-medium text-emerald-600 dark:text-emerald-300">возврат автодеплоя</span> : null}
                          {live ? <span className="rounded-full bg-blue-500/10 px-2 py-px text-[11px] font-medium text-blue-600 dark:text-blue-300">сейчас на сайте</span> : null}
                          {d.error ? <span className="text-rose-500">· {d.error}</span> : null}
                        </div>
                      </div>
                      {canRollback ? <button disabled={busy || !!control?.pending} onClick={() => void deployAction('rollback', d)} className="shrink-0 self-center rounded-lg border border-slate-200 px-2 py-1 text-[11px] text-slate-500 hover:border-amber-400 hover:text-amber-600 disabled:opacity-50 dark:border-white/10 dark:text-slate-400">Откатить сюда</button> : null}
                      <div className="shrink-0 text-right text-xs tabular-nums text-slate-500 dark:text-slate-400">
                        <div>{dt(d.start)}</div>
                        <div className="text-slate-400">{d.status === 'running' ? 'идёт ' + dur(now - d.start) : dur(d.duration)}</div>
                      </div>
                    </div>
                  )
                }) : <div className="py-10 text-center text-sm text-slate-400">Журнала деплоев нет</div>}
              </Card>
            </div>
            <div>
              <SectionTitle icon={Database} title="Бэкап" sub="каждую ночь" />
              <Card className="p-5">
                {p.backup ? <>
                  <div className="flex items-center gap-3">
                    <StatusIcon level={now - p.backup.time > 26 * 3600 ? 'warn' : 'ok'} />
                    <div className="font-medium">{ago(p.backup.time, now)}</div>
                  </div>
                  <dl className="mt-4 space-y-2 text-sm">
                    {[['Когда', dt(p.backup.time)], ['Дамп базы', mb(p.backup.size)], ['Хранится', `${p.backup.count} ${plural(p.backup.count, 'копия', 'копии', 'копий')} на сервере`], ['Копия', 'на ПК владельца (задача «Orda backup pull»)']].map(([k, v]) => (
                      <div key={k} className="flex justify-between gap-3"><dt className="text-slate-500 dark:text-slate-400">{k}</dt><dd className="text-right tabular-nums">{v}</dd></div>
                    ))}
                  </dl>
                  <button onClick={() => setLogSource(['log', 'backup'])} className="mt-4 w-full rounded-lg border border-slate-200 py-1.5 text-xs text-slate-500 hover:bg-slate-50 hover:text-slate-900 dark:border-white/10 dark:hover:bg-white/5 dark:hover:text-white">Журнал бэкапов</button>
                </> : <div className="text-sm text-slate-400">Бэкапов не найдено</div>}
              </Card>
            </div>
          </div>
        ) : null}

        {/* кроны */}
        {data?.crons.length ? <>
          <SectionTitle icon={Timer} title="Кроны" sub={`${data.crons.length} задач по расписанию (UTC) · за сутки ${data.crons.reduce((a, c) => a + c.runs_24h, 0)} запусков`}
            right={<button onClick={() => setLogSource(['log', 'cron'])} className="rounded-lg border border-slate-200 px-2.5 py-1 text-xs text-slate-500 hover:bg-slate-50 hover:text-slate-900 dark:border-white/10 dark:hover:bg-white/5 dark:hover:text-white">Журнал</button>} />
          {cronsBad.length ? <div className="mb-3 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-sm text-amber-700 dark:text-amber-300">Последний запуск не прошёл: {cronsBad.map((c) => c.name).join(', ')}</div> : null}
          <Card className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead><tr className="border-b border-slate-100 text-left text-xs text-slate-500 dark:border-white/[0.06] dark:text-slate-400">
                <th className="px-5 py-2.5 font-medium">Задача</th><th className="px-3 py-2.5 font-medium">Расписание</th><th className="px-3 py-2.5 font-medium">Последний запуск</th><th className="w-[180px] px-3 py-2.5 font-medium">Запуски</th><th className="px-5 py-2.5 text-right font-medium">Сбоев за сутки</th>
              </tr></thead>
              <tbody className="divide-y divide-slate-100 dark:divide-white/[0.06]">
                {data.crons.map((c) => {
                  const lv: Level = c.last_code == null ? 'warn' : c.last_code === 200 ? (c.failed_24h ? 'warn' : 'ok') : 'bad'
                  return (
                    <tr key={c.path} className="hover:bg-slate-50 dark:hover:bg-white/[0.02]">
                      <td className="px-5 py-2.5"><div className="flex items-center gap-2"><span className={`h-2 w-2 rounded-full ${LEVEL[lv].dot}`} /><span className="font-mono text-xs">{c.name}</span></div></td>
                      <td className="px-3 py-2.5 font-mono text-xs text-slate-500 dark:text-slate-400">{c.schedule || '—'}</td>
                      <td className="px-3 py-2.5 text-xs tabular-nums text-slate-500 dark:text-slate-400">{c.last_at ? <>{ago(c.last_at, now)}{c.last_code !== 200 ? <span className="ml-1 text-rose-500">· {c.last_code === 0 ? 'нет ответа' : c.last_code}</span> : null}</> : 'не запускался за сутки'}</td>
                      <td className="px-3 py-2.5"><div className="w-[160px]"><Bars total={24} cells={c.recent.map((r) => ({ ok: r.ok, title: hm(r.t) }))} /></div></td>
                      <td className={`px-5 py-2.5 text-right tabular-nums ${c.failed_24h ? 'font-semibold text-rose-500' : 'text-slate-400'}`}>{c.failed_24h || '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </Card>
        </> : null}

        {/* ошибки */}
        {data ? <>
          <SectionTitle icon={AlertTriangle} title="Ошибки за сутки" sub={`${data.errors.total} ${plural(data.errors.total, 'запись', 'записи', 'записей')} в журнале ошибок сервера и браузеров`} />
          <Card className="p-5">
            <div className="flex h-16 items-end gap-1">
              {data.errors.hourly.map((n, i) => (
                <div key={i} className="group relative flex-1" title={`${23 - i === 0 ? 'последний час' : `${23 - i} ч назад`}: ${n}`}>
                  <div className={`w-full rounded-t-[3px] ${n ? 'bg-gradient-to-t from-rose-500 to-rose-400' : 'bg-slate-100 dark:bg-white/[0.05]'}`} style={{ height: n ? `${Math.max(10, (n / maxHourly) * 64)}px` : '4px' }} />
                </div>
              ))}
            </div>
            <div className="mt-1.5 flex justify-between text-[11px] text-slate-400"><span>24 ч назад</span><span>сейчас</span></div>
            {data.errors.groups.length ? (
              <div className="mt-5 divide-y divide-slate-100 dark:divide-white/[0.06]">
                {data.errors.groups.map((g, i) => (
                  <div key={i} className="flex items-start gap-3 py-2.5">
                    <span className="mt-0.5 min-w-[2.25rem] rounded-md bg-rose-500/10 px-1.5 py-0.5 text-center text-xs font-semibold tabular-nums text-rose-600 dark:text-rose-400">{g.count}×</span>
                    <div className="min-w-0 flex-1">
                      <div className="font-mono text-xs text-slate-500 dark:text-slate-400">{g.area}<span className="ml-2 rounded bg-slate-100 px-1.5 py-px text-[10px] dark:bg-white/[0.06]">{g.scope === 'client' ? 'браузер' : 'сервер'}</span></div>
                      <div className="mt-0.5 break-words text-sm">{g.message || '—'}</div>
                    </div>
                    <div className="shrink-0 text-xs tabular-nums text-slate-400">{ago(g.last_at, now)}</div>
                  </div>
                ))}
              </div>
            ) : <div className="mt-5 flex items-center gap-2 text-sm text-emerald-600 dark:text-emerald-400"><Check className="h-4 w-4" /> Ошибок нет</div>}
          </Card>
        </> : null}

        {/* контейнеры */}
        {containers.length ? <>
          <SectionTitle icon={Server} title="Контейнеры" sub="сайт и собственный Supabase" />
          <Card className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead><tr className="border-b border-slate-100 text-left text-xs text-slate-500 dark:border-white/[0.06] dark:text-slate-400">
                <th className="px-5 py-2.5 font-medium">Контейнер</th><th className="px-3 py-2.5 text-right font-medium">CPU</th><th className="px-3 py-2.5 text-right font-medium">Память</th><th className="px-3 py-2.5 font-medium">Работает</th><th className="px-5 py-2.5" />
              </tr></thead>
              <tbody className="divide-y divide-slate-100 dark:divide-white/[0.06]">
                {[...containers].sort((a, b) => (b.mem || 0) - (a.mem || 0)).map((c) => {
                  const lv: Level = c.state !== 'running' || c.health === 'unhealthy' ? 'bad' : c.health === 'starting' ? 'warn' : 'ok'
                  const memMax = Math.max(...containers.map((x) => x.mem || 0), 1)
                  return (
                    <tr key={c.name} className="hover:bg-slate-50 dark:hover:bg-white/[0.02]">
                      <td className="px-5 py-2.5"><div className="flex items-center gap-2"><span className={`h-2 w-2 rounded-full ${LEVEL[lv].dot}`} /><span className="font-mono text-xs">{c.name}</span></div><div className="ml-4 truncate text-[11px] text-slate-400">{c.image}</div></td>
                      <td className="px-3 py-2.5 text-right font-mono text-xs tabular-nums">{c.cpu == null ? '—' : c.cpu.toFixed(1) + '%'}</td>
                      <td className="px-3 py-2.5 text-right"><div className="flex items-center justify-end gap-2"><span className="font-mono text-xs tabular-nums">{c.mem ? mb(c.mem) : '—'}</span><span className="h-1.5 w-16 overflow-hidden rounded-full bg-slate-100 dark:bg-white/[0.07]"><span className="block h-full rounded-full bg-gradient-to-r from-blue-500 to-sky-500" style={{ width: `${pct(c.mem || 0, memMax)}%` }} /></span></div></td>
                      <td className="px-3 py-2.5 text-xs text-slate-500 dark:text-slate-400"><Clock className="mr-1 inline h-3 w-3" />{c.status.replace(/^Up /, '').replace(/\s*\(.*\)/, '').replace('About a minute', '~1 minute').replace('About an hour', '~1 hour')}</td>
                      <td className="px-5 py-2.5 text-right"><button onClick={() => setLogSource(['c', c.name])} className="rounded-md border border-slate-200 px-2 py-0.5 text-xs text-slate-500 hover:bg-slate-50 hover:text-slate-900 dark:border-white/10 dark:hover:bg-white/5 dark:hover:text-white">Логи</button></td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </Card>
        </> : null}
      </div>

      <LogDrawer source={logSource} onClose={() => setLogSource(null)} sources={sources} setSource={setLogSource} />
    </div>
  )
}

const TABS = [
  { key: 'server', label: 'Сервер и деплои', icon: Server },
  { key: 'logs', label: 'Логи', icon: ScrollText },
  { key: 'errors', label: 'Ошибки', icon: AlertTriangle },
  { key: 'database', label: 'База данных', icon: Database },
] as const
type TabKey = (typeof TABS)[number]['key']

export default function PlatformStatusPage() {
  // Вкладка — в адресе (?tab=database), чтобы ссылкой можно было открыть сразу базу.
  const [tab, setTab] = useState<TabKey>('server')
  useEffect(() => {
    const t = new URLSearchParams(window.location.search).get('tab')
    if (t === 'database' || t === 'logs' || t === 'errors') setTab(t)
  }, [])
  const choose = (t: TabKey) => {
    setTab(t)
    const url = new URL(window.location.href)
    if (t === 'server') url.searchParams.delete('tab'); else url.searchParams.set('tab', t)
    window.history.replaceState(null, '', url)
  }
  return (
    <div className="min-h-screen text-slate-900 dark:text-white">
      <div className="sticky top-0 z-30 border-b border-slate-200 bg-white/80 backdrop-blur-xl dark:border-white/[0.06] dark:bg-[#050816]/80">
        <div className="mx-auto flex max-w-[1400px] gap-1 px-4 sm:px-6">
          {TABS.map(({ key, label, icon: Icon }) => (
            <button key={key} onClick={() => choose(key)} className={`-mb-px flex items-center gap-2 border-b-2 px-3 py-3 text-sm font-medium transition ${tab === key ? 'border-blue-500 text-slate-900 dark:text-white' : 'border-transparent text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white'}`}>
              <Icon className="h-4 w-4" />{label}
            </button>
          ))}
        </div>
      </div>
      {tab === 'server' ? <ServerTab /> : tab === 'logs' ? <LogsTab /> : tab === 'errors' ? <ErrorsTab /> : <DatabaseTab />}
    </div>
  )
}
