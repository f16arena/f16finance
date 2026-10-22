'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Loader2, Pause, Play, ScrollText, Search } from 'lucide-react'

// Вкладка «Логи» на /platform/status: общая лента журналов Orda по времени, как Logs в
// Vercel — сайт, база и сервисы Supabase, кроны, сборка, бэкап. Источники — тот же
// /api/admin/platform/status/logs; хвосты контейнеров обновляет снимок раз в 20 с.

type Line = { t: number; ts: string; src: string; text: string; level: 'error' | 'warn' | 'info' }

const FILE_SOURCES: Array<[string, string]> = [['log:deploy', 'сборка'], ['log:cron', 'кроны'], ['log:backup', 'бэкап']]
const DEFAULT_ON = ['c:orda-web', 'log:cron', 'log:deploy']
const COLORS = ['text-violet-400', 'text-sky-400', 'text-emerald-400', 'text-amber-300', 'text-pink-400', 'text-cyan-300', 'text-lime-300', 'text-orange-300', 'text-fuchsia-300', 'text-teal-300']
const TZ = 'Asia/Almaty'

function levelOf(text: string): Line['level'] {
  if (/\b(error|fatal|panic|exception|failed|unhandled)\b|⨯|ошибк|упал|\s(5\d\d)\s/i.test(text)) return 'error'
  if (/\bwarn(ing)?\b|предупр/i.test(text)) return 'warn'
  return 'info'
}

function parse(src: string, raw: string): Line[] {
  const out: Line[] = []
  let lastT = 0, lastTs = ''
  for (const l of raw.split('\n')) {
    if (!l.trim()) continue
    const m = l.match(/^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.\d+)?Z\s?(.*)$/)
    if (m) { lastT = Date.parse(m[1] + 'Z'); lastTs = m[1]; out.push({ t: lastT, ts: lastTs, src, text: m[2], level: levelOf(m[2]) }) }
    else if (lastT) out.push({ t: lastT, ts: lastTs, src, text: l, level: levelOf(l) }) // продолжение строки (вывод сборки, стек)
  }
  return out
}

export default function LogsTab() {
  const [sources, setSources] = useState<Array<[string, string]>>(FILE_SOURCES)
  const [on, setOn] = useState<string[]>(DEFAULT_ON)
  const [lines, setLines] = useState<Line[]>([])
  const [q, setQ] = useState('')
  const [level, setLevel] = useState<'all' | 'warn' | 'error'>('all')
  const [live, setLive] = useState(true)
  const [loading, setLoading] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)
  const stick = useRef(true)

  // Список контейнеров Orda — из состояния сервера.
  useEffect(() => {
    fetch('/api/admin/platform/status', { cache: 'no-store' }).then((r) => r.json()).then((j) => {
      const names: string[] = (j?.project?.containers || []).map((c: { name: string }) => c.name).sort((a: string, b: string) => (a === 'orda-web' ? -1 : b === 'orda-web' ? 1 : a.localeCompare(b)))
      setSources([...names.map((n) => [`c:${n}`, n.replace('supabase-', 'db·').replace('realtime-dev.supabase-realtime', 'db·realtime')] as [string, string]), ...FILE_SOURCES])
    }).catch(() => null)
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const parts = await Promise.all(on.map(async (s) => {
        const [kind, name] = [s.slice(0, s.indexOf(':')), s.slice(s.indexOf(':') + 1)]
        const qs = kind === 'c' ? `container=${encodeURIComponent(name)}` : `log=${name}`
        const text = await fetch(`/api/admin/platform/status/logs?${qs}&tail=600`, { cache: 'no-store' }).then((r) => (r.ok ? r.text() : '')).catch(() => '')
        return parse(s, text)
      }))
      const merged = parts.flat().sort((a, b) => a.t - b.t)
      setLines(merged.slice(-3000))
      requestAnimationFrame(() => { if (stick.current && boxRef.current) boxRef.current.scrollTop = boxRef.current.scrollHeight })
    } finally { setLoading(false) }
  }, [on])
  useEffect(() => { void load() }, [load])
  useEffect(() => {
    if (!live) return
    const id = setInterval(() => void load(), 5000)
    return () => clearInterval(id)
  }, [live, load])

  const color = useMemo(() => new Map(sources.map(([k], i) => [k, COLORS[i % COLORS.length]])), [sources])
  const label = useMemo(() => new Map(sources), [sources])
  const shown = useMemo(() => lines.filter((l) =>
    (level === 'all' || (level === 'error' ? l.level === 'error' : l.level !== 'info')) &&
    (!q || l.text.toLowerCase().includes(q.toLowerCase()))), [lines, level, q])
  const errors = lines.filter((l) => l.level === 'error').length

  return (
    <div className="mx-auto flex max-w-[1400px] flex-col px-4 pb-6 sm:px-6" style={{ height: 'calc(100vh - 49px)' }}>
      <div className="flex flex-wrap items-center gap-3 pt-6">
        <div className="relative grid h-11 w-11 place-items-center rounded-2xl text-sky-500">
          <span className="absolute inset-0 rounded-2xl bg-current opacity-[.14]" />
          <ScrollText className="relative h-5 w-5" />
        </div>
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Логи</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">Все журналы Orda одной лентой · время по Алматы</p>
        </div>
        <div className="ml-auto flex items-center gap-2 text-xs">
          <span className="text-slate-500 dark:text-slate-400">{shown.length} строк{errors ? <> · <span className="text-rose-500">{errors} с ошибкой</span></> : null}</span>
          <button onClick={() => setLive((v) => !v)} className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 font-medium ${live ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-600 dark:text-emerald-300' : 'border-slate-200 text-slate-500 dark:border-white/10'}`}>
            {live ? <><span className="relative flex h-2 w-2"><span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60" /><span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" /></span>Live<Pause className="h-3 w-3" /></> : <>Пауза<Play className="h-3 w-3" /></>}
          </button>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap gap-1.5">
        {sources.map(([k, l]) => {
          const active = on.includes(k)
          return (
            <button key={k} onClick={() => setOn((cur) => (active ? cur.filter((x) => x !== k) : [...cur, k]))}
              className={`rounded-full border px-2.5 py-1 font-mono text-[11px] transition ${active ? 'border-slate-800 bg-slate-900 text-white dark:border-white/20 dark:bg-white/10' : 'border-slate-200 text-slate-500 hover:text-slate-900 dark:border-white/10 dark:text-slate-400 dark:hover:text-white'}`}>
              <span className={active ? color.get(k) : ''}>●</span> {l}
            </button>
          )
        })}
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <div className="relative w-full max-w-sm">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Поиск по строкам…" className="w-full rounded-lg border border-slate-200 bg-white py-1.5 pl-8 pr-2 text-sm outline-none focus:border-sky-400 dark:border-white/10 dark:bg-white/[0.03]" />
        </div>
        <div className="flex gap-0.5 rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-xs dark:border-white/10 dark:bg-white/[0.03]">
          {([['all', 'Все'], ['warn', 'Предупреждения+'], ['error', 'Ошибки']] as const).map(([k, l]) => (
            <button key={k} onClick={() => setLevel(k)} className={`rounded-md px-3 py-1 font-medium ${level === k ? 'bg-white text-slate-900 shadow-sm dark:bg-white/10 dark:text-white' : 'text-slate-500 dark:text-slate-400'}`}>{l}</button>
          ))}
        </div>
        {loading ? <Loader2 className="h-4 w-4 animate-spin text-sky-400" /> : null}
      </div>

      <div ref={boxRef} onScroll={(e) => { const el = e.currentTarget; stick.current = el.scrollTop + el.clientHeight >= el.scrollHeight - 60 }}
        className="mt-3 min-h-0 flex-1 overflow-auto rounded-2xl border border-slate-800 bg-[#050505] py-2 font-mono text-[11.5px] leading-[1.55]">
        {shown.length ? shown.map((l, i) => (
          <div key={i} className={`flex gap-3 px-4 hover:bg-white/[0.04] ${l.level === 'error' ? 'bg-rose-500/[0.07]' : ''}`}>
            <span className="shrink-0 text-slate-600">{new Date(l.t).toLocaleTimeString('ru-RU', { timeZone: TZ })}</span>
            <span className={`w-28 shrink-0 truncate ${color.get(l.src)}`}>{label.get(l.src) || l.src}</span>
            <span className={`min-w-0 whitespace-pre-wrap break-words ${l.level === 'error' ? 'text-rose-300' : l.level === 'warn' ? 'text-amber-200' : 'text-slate-300'}`}>{l.text}</span>
          </div>
        )) : <div className="grid h-full place-items-center text-slate-500">{on.length ? (loading ? 'загрузка…' : 'Нет строк — смените фильтр или источники') : 'Выберите источники выше'}</div>}
      </div>
    </div>
  )
}
