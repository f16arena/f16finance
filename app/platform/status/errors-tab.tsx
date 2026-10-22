'use client'

import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, Check, ChevronDown, Globe, Loader2, Monitor, RefreshCw, Server, User } from 'lucide-react'

// Вкладка «Ошибки» на /platform/status: ошибки сервера и браузеров группами, как
// в Vercel/Sentry — где, что, сколько, первая/последняя и последние случаи с подробностями.

type Occurrence = {
  at: number; actor: string | null; pathname: string | null; user_agent: string | null
  code: string | null; details: string | null; hint: string | null; stage: string | null; stack: string | null; full_message: string; translated?: boolean
}
type Group = { area: string; message: string; scope: string; count: number; first_at: number; last_at: number; users: number; samples: Occurrence[] }
type Data = { now: number; period: string; total: number; truncated: boolean; timeline: number[]; step: number; groups: Group[] }

const PERIODS = [['1h', 'Час'], ['24h', 'Сутки'], ['7d', 'Неделя'], ['30d', 'Месяц']] as const
const TZ = 'Asia/Almaty'
const dt = (t: number) => new Date(t * 1000).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: TZ })
function ago(t: number, now: number) {
  const s = Math.max(0, now - t)
  if (s < 60) return 'только что'
  if (s < 3600) return Math.round(s / 60) + ' мин назад'
  if (s < 86400) return Math.floor(s / 3600) + ' ч назад'
  return Math.floor(s / 86400) + ' дн назад'
}
const browser = (ua: string | null) => {
  if (!ua) return null
  const m = ua.match(/(Edg|OPR|Chrome|Firefox|Safari)\/(\d+)/)
  const os = /iPhone|iPad/.test(ua) ? 'iOS' : /Android/.test(ua) ? 'Android' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : ''
  return [m ? `${m[1] === 'Edg' ? 'Edge' : m[1]} ${m[2]}` : 'браузер', os].filter(Boolean).join(' · ')
}

export default function ErrorsTab() {
  const [period, setPeriod] = useState('24h')
  const [scope, setScope] = useState<'all' | 'server' | 'client'>('all')
  const [data, setData] = useState<Data | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await fetch(`/api/admin/platform/errors?period=${period}`, { cache: 'no-store' })
      const j = await r.json()
      if (!r.ok) throw new Error(j?.error || `HTTP ${r.status}`)
      setData(j); setError(null)
    } catch (e: any) { setError(e?.message || 'ошибка') } finally { setLoading(false) }
  }, [period])
  useEffect(() => {
    void load()
    const id = setInterval(() => void load(), 30000)
    return () => clearInterval(id)
  }, [load])

  const groups = (data?.groups || []).filter((g) => scope === 'all' || (scope === 'client' ? g.scope === 'client' : g.scope !== 'client'))
  const max = Math.max(1, ...(data?.timeline || [0]))
  const serverCount = (data?.groups || []).filter((g) => g.scope !== 'client').reduce((a, g) => a + g.count, 0)
  const clientCount = (data?.groups || []).filter((g) => g.scope === 'client').reduce((a, g) => a + g.count, 0)

  return (
    <div className="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
      <div className="flex flex-wrap items-center gap-3 pt-8">
        <div className="relative grid h-12 w-12 place-items-center rounded-2xl text-rose-500">
          <span className="absolute inset-0 rounded-2xl bg-current opacity-[.14]" />
          <AlertTriangle className="relative h-6 w-6" />
        </div>
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Ошибки</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">Сервер (API, кроны, бот) и браузеры пользователей · обновление каждые 30 секунд</p>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <div className="flex gap-0.5 rounded-xl border border-slate-200 bg-slate-50 p-1 dark:border-white/[0.07] dark:bg-white/[0.03]">
            {PERIODS.map(([k, l]) => <button key={k} onClick={() => setPeriod(k)} className={`rounded-lg px-3 py-1.5 text-xs font-medium ${period === k ? 'bg-white text-slate-900 shadow-sm dark:bg-white/10 dark:text-white' : 'text-slate-500 dark:text-slate-400'}`}>{l}</button>)}
          </div>
          <button onClick={() => void load()} className="grid h-8 w-8 place-items-center rounded-lg border border-slate-200 text-slate-500 hover:bg-slate-50 dark:border-white/10 dark:hover:bg-white/5">{loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}</button>
        </div>
      </div>

      {error ? <div className="mt-4 rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-600 dark:text-rose-300">{error}</div> : null}

      {data ? <>
        <div className="mt-6 grid gap-3 sm:grid-cols-4">
          {[
            ['Всего', data.total + (data.truncated ? '+' : ''), 'all'],
            ['Групп', String(data.groups.length), 'all'],
            ['Сервер', String(serverCount), 'server'],
            ['Браузеры', String(clientCount), 'client'],
          ].map(([k, v, sc]) => (
            <button key={k} onClick={() => setScope(sc as any)} className={`rounded-2xl border p-4 text-left shadow-sm transition dark:shadow-none ${scope === sc && k !== 'Групп' ? 'border-rose-400/60 bg-rose-500/[0.04]' : 'border-slate-200 bg-white hover:border-slate-300 dark:border-white/[0.07] dark:bg-white/[0.025]'}`}>
              <div className="text-xs text-slate-500 dark:text-slate-400">{k}</div>
              <div className="mt-1 text-2xl font-semibold tabular-nums">{v}</div>
            </button>
          ))}
        </div>

        <div className="mt-4 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm dark:border-white/[0.07] dark:bg-white/[0.025] dark:shadow-none">
          <div className="flex h-20 items-end gap-1">
            {data.timeline.map((n, i) => (
              <div key={i} className="flex-1" title={`${n} ${n === 1 ? 'ошибка' : 'ошибок'}`}>
                <div className={`w-full rounded-t-[3px] ${n ? 'bg-gradient-to-t from-rose-500 to-rose-400' : 'bg-slate-100 dark:bg-white/[0.05]'}`} style={{ height: n ? `${Math.max(8, (n / max) * 80)}px` : '4px' }} />
              </div>
            ))}
          </div>
          <div className="mt-1.5 flex justify-between text-[11px] text-slate-400"><span>{PERIODS.find((p) => p[0] === data.period)?.[1].toLowerCase()} назад</span><span>сейчас</span></div>
        </div>

        <div className="mt-4 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-white/[0.07] dark:bg-white/[0.025] dark:shadow-none">
          {groups.length ? groups.map((g) => {
            const key = g.area + '|' + g.message
            const isOpen = open === key
            return (
              <div key={key} className="border-b border-slate-100 last:border-0 dark:border-white/[0.06]">
                <button onClick={() => setOpen(isOpen ? null : key)} className="flex w-full items-start gap-3 px-5 py-3.5 text-left hover:bg-slate-50 dark:hover:bg-white/[0.02]">
                  <span className="mt-0.5 min-w-[2.75rem] rounded-md bg-rose-500/10 px-1.5 py-0.5 text-center text-xs font-semibold tabular-nums text-rose-600 dark:text-rose-400">{g.count}×</span>
                  <div className="min-w-0 flex-1">
                    <div className="break-words text-sm font-medium">{g.message || 'без текста'}</div>
                    <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500 dark:text-slate-400">
                      <span className="inline-flex items-center gap-1 font-mono">{g.scope === 'client' ? <Monitor className="h-3 w-3" /> : <Server className="h-3 w-3" />}{g.area}</span>
                      {g.users ? <span className="inline-flex items-center gap-1"><User className="h-3 w-3" />{g.users} {g.users === 1 ? 'пользователь' : 'польз.'}</span> : null}
                      <span>впервые {ago(g.first_at, data.now)}</span>
                    </div>
                  </div>
                  <div className="shrink-0 text-right text-xs text-slate-500 dark:text-slate-400">{ago(g.last_at, data.now)}</div>
                  <ChevronDown className={`mt-0.5 h-4 w-4 shrink-0 text-slate-400 transition ${isOpen ? 'rotate-180' : ''}`} />
                </button>
                {isOpen ? (
                  <div className="space-y-3 bg-slate-50/60 px-5 pb-5 pt-1 dark:bg-white/[0.015]">
                    {g.samples.map((o, i) => (
                      <div key={i} className="rounded-xl border border-slate-200 bg-white p-3.5 text-xs dark:border-white/[0.07] dark:bg-[#070b17]">
                        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-slate-500 dark:text-slate-400">
                          <span className="font-medium text-slate-700 dark:text-slate-200">{dt(o.at)}</span>
                          {o.actor ? <span className="inline-flex items-center gap-1"><User className="h-3 w-3" />{o.actor}</span> : null}
                          {o.pathname ? <span className="inline-flex items-center gap-1 font-mono"><Globe className="h-3 w-3" />{o.pathname}</span> : null}
                          {browser(o.user_agent) ? <span>{browser(o.user_agent)}</span> : null}
                          {o.code ? <span className="font-mono">код {o.code}</span> : null}
                          {o.stage ? <span>этап: {o.stage}</span> : null}
                          {o.translated ? <span className="rounded-full bg-amber-500/10 px-2 py-px font-medium text-amber-600 dark:text-amber-300" title="Переводчик браузера меняет текст страницы — частая причина ошибок React">включён переводчик</span> : null}
                        </div>
                        <pre className="mt-2 whitespace-pre-wrap break-words font-mono text-[11px] text-rose-600 dark:text-rose-300">{o.full_message}</pre>
                        {o.details || o.hint ? <div className="mt-1.5 text-slate-500 dark:text-slate-400">{[o.details, o.hint].filter(Boolean).join(' · ')}</div> : null}
                        {o.stack ? <pre className="mt-2 max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-[#050505] p-3 font-mono text-[11px] leading-relaxed text-slate-300">{o.stack}</pre> : null}
                      </div>
                    ))}
                    {g.count > g.samples.length ? <div className="text-xs text-slate-400">Показаны последние {g.samples.length} из {g.count}</div> : null}
                  </div>
                ) : null}
              </div>
            )
          }) : <div className="flex items-center justify-center gap-2 py-14 text-sm text-emerald-600 dark:text-emerald-400"><Check className="h-4 w-4" />Ошибок за этот период нет</div>}
        </div>
      </> : <div className="grid min-h-[40vh] place-items-center"><Loader2 className="h-6 w-6 animate-spin text-rose-400" /></div>}
    </div>
  )
}
