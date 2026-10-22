'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ArrowDown,
  ArrowUp,
  ChevronLeft,
  ChevronRight,
  Database,
  ExternalLink,
  Gauge,
  HardDrive,
  KeyRound,
  Link2,
  Loader2,
  Lock,
  Search,
  Table2,
  Users,
  X,
} from 'lucide-react'

// Вкладка «База данных» на /platform/status: как Table Editor в Supabase, но только
// для чтения. Править данные — в Supabase Studio (/platform/studio), ссылка в шапке.

type Overview = {
  version: string; size: number; connections: number; active: number; max_connections: number
  cache_hit: number | null; started_at: string; tables: number; functions: number
  auth_users: number; storage_objects: number; storage_bytes: number
  long_queries: Array<{ pid: number; seconds: number; state: string; query: string }>
}
type TableInfo = {
  name: string; rows: number; total_bytes: number; table_bytes: number; index_bytes: number
  columns: number; rls: boolean; policies: number; inserts: number; updates: number; deletes: number; comment: string | null
}
type Column = { name: string; type: string; nullable: boolean; default: string | null; pk: boolean; fk: string | null }
type TableData = { columns: Column[]; rows: Record<string, unknown>[]; count: number | null; page: number; pageSize: number; sort: string; asc: boolean; error?: string }

const size = (b: number) => (b >= 1024 ** 3 ? (b / 1024 ** 3).toFixed(1) + ' ГБ' : b >= 1024 ** 2 ? (b / 1024 ** 2).toFixed(1) + ' МБ' : Math.max(1, Math.round(b / 1024)) + ' КБ')
const num = (n: number) => Number(n || 0).toLocaleString('ru-RU')
const STUDIO_URL = '/platform/studio'

function Cell({ value, type }: { value: unknown; type: string }) {
  if (value === null || value === undefined) return <span className="text-slate-300 dark:text-slate-600">NULL</span>
  if (typeof value === 'boolean') return <span className={value ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400'}>{String(value)}</span>
  if (typeof value === 'object') return <span className="font-mono text-violet-600 dark:text-violet-300">{JSON.stringify(value).slice(0, 120)}</span>
  const s = String(value)
  if (/timestamp/.test(type)) return <span className="tabular-nums">{s.replace('T', ' ').slice(0, 19)}</span>
  if (/uuid/.test(type)) return <span className="font-mono text-slate-500 dark:text-slate-400" title={s}>{s.slice(0, 8)}…</span>
  if (/int|numeric|double|real/.test(type)) return <span className="tabular-nums">{s}</span>
  return <span title={s.length > 80 ? s : undefined}>{s.length > 80 ? s.slice(0, 80) + '…' : s}</span>
}

export default function DatabaseTab() {
  const [overview, setOverview] = useState<Overview | null>(null)
  const [tables, setTables] = useState<TableInfo[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [view, setView] = useState<'rows' | 'columns'>('rows')
  const [data, setData] = useState<TableData | null>(null)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(0)
  const [sort, setSort] = useState<{ col: string; asc: boolean } | null>(null)
  const [filterCol, setFilterCol] = useState('')
  const [filterQ, setFilterQ] = useState('')
  const [appliedFilter, setAppliedFilter] = useState<{ col: string; q: string } | null>(null)
  const [openRow, setOpenRow] = useState<Record<string, unknown> | null>(null)

  useEffect(() => {
    fetch('/api/admin/platform/database', { cache: 'no-store' })
      .then(async (r) => { const j = await r.json(); if (!r.ok) throw new Error(j?.error || `HTTP ${r.status}`); return j })
      .then((j) => { setOverview(j.overview); setTables(j.tables || []) })
      .catch((e) => setLoadError(e.message))
  }, [])

  const loadTable = useCallback(async () => {
    if (!selected) return
    setLoading(true)
    const qs = new URLSearchParams({ name: selected, page: String(page) })
    if (sort) { qs.set('sort', sort.col); qs.set('dir', sort.asc ? 'asc' : 'desc') }
    if (appliedFilter) { qs.set('col', appliedFilter.col); qs.set('q', appliedFilter.q) }
    try {
      const r = await fetch(`/api/admin/platform/database/table?${qs}`, { cache: 'no-store' })
      const j = await r.json()
      setData(r.ok ? j : { columns: j.columns || data?.columns || [], rows: [], count: 0, page, pageSize: 50, sort: '', asc: false, error: j?.error || `HTTP ${r.status}` })
    } finally { setLoading(false) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected, page, sort, appliedFilter])
  useEffect(() => { void loadTable() }, [loadTable])

  const pick = (name: string) => {
    setSelected(name); setPage(0); setSort(null); setAppliedFilter(null); setFilterQ(''); setFilterCol(''); setData(null); setView('rows')
  }

  const filtered = useMemo(() => tables.filter((t) => !search || t.name.toLowerCase().includes(search.toLowerCase())), [tables, search])
  const current = tables.find((t) => t.name === selected) || null
  const totalPages = data?.count != null ? Math.max(1, Math.ceil(data.count / (data.pageSize || 50))) : null

  if (loadError) return <div className="mx-auto mt-10 max-w-6xl rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-3 text-sm text-rose-600 dark:text-rose-300">Не удалось загрузить базу: {loadError}</div>
  if (!overview) return <div className="grid min-h-[50vh] place-items-center"><Loader2 className="h-6 w-6 animate-spin text-violet-400" /></div>

  const stats: Array<[any, string, string, string?]> = [
    [HardDrive, 'Размер базы', size(overview.size), `${overview.tables} таблиц · ${overview.functions} функций`],
    [Gauge, 'Подключения', `${overview.connections} / ${overview.max_connections}`, `${overview.active} активных сейчас`],
    [Database, 'Кэш', overview.cache_hit != null ? overview.cache_hit + '%' : '—', 'чтений из памяти, а не с диска'],
    [Users, 'Пользователи', num(overview.auth_users), 'аккаунтов входа (auth)'],
    [HardDrive, 'Файлы', num(overview.storage_objects), size(overview.storage_bytes) + ' в Storage'],
  ]

  return (
    <div className="mx-auto max-w-[1400px] px-4 pb-16 sm:px-6">
      {/* обзор */}
      <div className="flex flex-wrap items-center gap-3 pt-8">
        <div className="relative grid h-12 w-12 place-items-center rounded-2xl text-emerald-500">
          <span className="absolute inset-0 rounded-2xl bg-current opacity-[.14]" />
          <Database className="relative h-6 w-6" />
        </div>
        <div>
          <h1 className="text-2xl font-bold tracking-tight">База данных</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">{overview.version} · работает с {new Date(overview.started_at).toLocaleString('ru-RU', { timeZone: 'Asia/Almaty', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}</p>
        </div>
        <a href={STUDIO_URL} className="ml-auto inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-emerald-500 to-teal-500 px-4 py-2 text-sm font-semibold text-white shadow-lg shadow-emerald-500/20 hover:opacity-95">
          Открыть Supabase Studio <ExternalLink className="h-4 w-4" />
        </a>
      </div>
      <div className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        {stats.map(([Icon, k, v, s]) => (
          <div key={k} className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-white/[0.07] dark:bg-white/[0.025] dark:shadow-none">
            <div className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400"><Icon className="h-3.5 w-3.5" />{k}</div>
            <div className="mt-1 text-xl font-semibold tabular-nums tracking-tight">{v}</div>
            {s ? <div className="text-[11px] text-slate-400">{s}</div> : null}
          </div>
        ))}
      </div>
      {overview.long_queries.length ? (
        <div className="mt-3 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-2.5 text-sm text-amber-700 dark:text-amber-300">
          Долгие запросы прямо сейчас: {overview.long_queries.map((q) => `${q.seconds} с — ${q.query}`).join(' · ')}
        </div>
      ) : null}

      {/* редактор таблиц */}
      <div className="mt-6 grid overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-white/[0.07] dark:bg-white/[0.02] dark:shadow-none lg:h-[calc(100vh-150px)] lg:min-h-[560px] lg:grid-cols-[280px_1fr]">
        {/* список таблиц */}
        <div className="flex min-h-0 flex-col border-b border-slate-200 dark:border-white/[0.07] lg:border-b-0 lg:border-r">
          <div className="border-b border-slate-200 p-3 dark:border-white/[0.07]">
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
              <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={`Поиск по ${tables.length} таблицам`} className="w-full rounded-lg border border-slate-200 bg-slate-50 py-1.5 pl-8 pr-2 text-sm outline-none focus:border-emerald-400 dark:border-white/10 dark:bg-white/[0.03]" />
            </div>
          </div>
          <div className="max-h-72 flex-1 overflow-y-auto p-1.5 lg:max-h-none">
            {filtered.map((t) => (
              <button key={t.name} onClick={() => pick(t.name)} className={`flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left text-sm transition ${selected === t.name ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300' : 'text-slate-600 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-white/[0.04]'}`}>
                <Table2 className="h-3.5 w-3.5 shrink-0 opacity-60" />
                <span className="min-w-0 flex-1 truncate font-mono text-xs">{t.name}</span>
                {!t.rls ? <span title="RLS выключен" className="text-amber-500"><Lock className="h-3 w-3" /></span> : null}
                <span className="shrink-0 text-[11px] tabular-nums text-slate-400">{t.rows >= 1000 ? Math.round(t.rows / 1000) + 'k' : t.rows}</span>
              </button>
            ))}
          </div>
        </div>

        {/* таблица */}
        <div className="flex min-h-[420px] min-w-0 flex-col">
          {!current ? (
            <div className="grid flex-1 place-items-center p-10 text-center">
              <div>
                <Table2 className="mx-auto h-10 w-10 text-slate-300 dark:text-slate-600" />
                <div className="mt-3 font-medium">Выберите таблицу слева</div>
                <div className="mt-1 text-sm text-slate-500 dark:text-slate-400">Сверху списка — самые большие. Здесь только просмотр; менять данные — в Supabase Studio.</div>
              </div>
            </div>
          ) : <>
            <div className="flex flex-wrap items-center gap-3 border-b border-slate-200 px-4 py-3 dark:border-white/[0.07]">
              <div className="min-w-0">
                <div className="font-mono text-sm font-semibold">{current.name}</div>
                <div className="text-[11px] text-slate-500 dark:text-slate-400">≈{num(current.rows)} строк · {size(current.total_bytes)} (индексы {size(current.index_bytes)}) · {current.rls ? `RLS вкл, ${current.policies} ${current.policies === 1 ? 'политика' : 'политик'}` : 'RLS выключен'}</div>
              </div>
              <div className="ml-auto flex gap-0.5 rounded-lg border border-slate-200 bg-slate-50 p-0.5 text-xs dark:border-white/10 dark:bg-white/[0.03]">
                {(['rows', 'columns'] as const).map((v) => (
                  <button key={v} onClick={() => setView(v)} className={`rounded-md px-3 py-1 font-medium ${view === v ? 'bg-white text-slate-900 shadow-sm dark:bg-white/10 dark:text-white' : 'text-slate-500 dark:text-slate-400'}`}>{v === 'rows' ? 'Данные' : 'Структура'}</button>
                ))}
              </div>
            </div>

            {view === 'rows' ? <>
              <form onSubmit={(e) => { e.preventDefault(); setPage(0); setAppliedFilter(filterCol && filterQ.trim() ? { col: filterCol, q: filterQ.trim() } : null) }} className="flex flex-wrap items-center gap-2 border-b border-slate-200 px-4 py-2 dark:border-white/[0.07]">
                <select value={filterCol} onChange={(e) => setFilterCol(e.target.value)} className="rounded-lg border border-slate-200 bg-white px-2 py-1 font-mono text-xs dark:border-white/10 dark:bg-slate-900">
                  <option value="">колонка…</option>
                  {(data?.columns || []).map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
                </select>
                <input value={filterQ} onChange={(e) => setFilterQ(e.target.value)} placeholder="содержит / равно" className="w-48 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-xs outline-none focus:border-emerald-400 dark:border-white/10 dark:bg-white/[0.03]" />
                <button className="rounded-lg bg-emerald-500 px-3 py-1 text-xs font-medium text-white hover:bg-emerald-600">Фильтр</button>
                {appliedFilter ? <button type="button" onClick={() => { setAppliedFilter(null); setFilterQ(''); setPage(0) }} className="inline-flex items-center gap-1 rounded-lg px-2 py-1 text-xs text-slate-500 hover:text-slate-900 dark:hover:text-white"><X className="h-3 w-3" />сбросить</button> : null}
                <div className="ml-auto flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                  {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : null}
                  <span className="tabular-nums">{data?.count != null ? `${num(data.count)} строк` : ''}</span>
                  <button disabled={page === 0} onClick={() => setPage((p) => p - 1)} className="grid h-7 w-7 place-items-center rounded-lg border border-slate-200 disabled:opacity-40 dark:border-white/10"><ChevronLeft className="h-3.5 w-3.5" /></button>
                  <span className="tabular-nums">{page + 1}{totalPages ? ` / ${num(totalPages)}` : ''}</span>
                  <button disabled={totalPages != null && page + 1 >= totalPages} onClick={() => setPage((p) => p + 1)} className="grid h-7 w-7 place-items-center rounded-lg border border-slate-200 disabled:opacity-40 dark:border-white/10"><ChevronRight className="h-3.5 w-3.5" /></button>
                </div>
              </form>
              {data?.error ? <div className="m-4 rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-sm text-rose-600 dark:text-rose-300">{data.error}</div> : null}
              <div className="min-h-0 flex-1 overflow-auto">
                {data ? (
                  <table className="min-w-full border-separate border-spacing-0 text-xs">
                    <thead className="sticky top-0 z-10">
                      <tr>
                        {data.columns.map((c) => {
                          const active = data.sort === c.name
                          return (
                            <th key={c.name} onClick={() => { setPage(0); setSort({ col: c.name, asc: active ? !data.asc : true }) }}
                              className="cursor-pointer whitespace-nowrap border-b border-r border-slate-200 bg-slate-50 px-3 py-2 text-left font-medium text-slate-700 hover:bg-slate-100 dark:border-white/[0.07] dark:bg-[#0b1020] dark:text-slate-200 dark:hover:bg-white/[0.04]">
                              <div className="flex items-center gap-1.5">
                                {c.pk ? <KeyRound className="h-3 w-3 text-amber-500" /> : c.fk ? <Link2 className="h-3 w-3 text-sky-500" /> : null}
                                <span className="font-mono">{c.name}</span>
                                <span className="font-normal text-slate-400">{c.type.replace('timestamp with time zone', 'timestamptz').replace('character varying', 'varchar')}</span>
                                {active ? (data.asc ? <ArrowUp className="h-3 w-3 text-emerald-500" /> : <ArrowDown className="h-3 w-3 text-emerald-500" />) : null}
                              </div>
                            </th>
                          )
                        })}
                      </tr>
                    </thead>
                    <tbody>
                      {data.rows.map((r, i) => (
                        <tr key={i} onClick={() => setOpenRow(r)} className="cursor-pointer hover:bg-emerald-500/[0.04]">
                          {data.columns.map((c) => <td key={c.name} className="max-w-[320px] truncate whitespace-nowrap border-b border-r border-slate-100 px-3 py-1.5 dark:border-white/[0.05]"><Cell value={r[c.name]} type={c.type} /></td>)}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ) : <div className="grid h-40 place-items-center"><Loader2 className="h-5 w-5 animate-spin text-emerald-400" /></div>}
                {data && !data.rows.length && !data.error ? <div className="py-12 text-center text-sm text-slate-400">Строк нет</div> : null}
              </div>
            </> : (
              <div className="min-h-0 flex-1 overflow-auto">
                <table className="w-full text-sm">
                  <thead><tr className="border-b border-slate-200 text-left text-xs text-slate-500 dark:border-white/[0.07] dark:text-slate-400">
                    <th className="px-4 py-2 font-medium">Колонка</th><th className="px-3 py-2 font-medium">Тип</th><th className="px-3 py-2 font-medium">Пусто?</th><th className="px-3 py-2 font-medium">По умолчанию</th><th className="px-4 py-2 font-medium">Связь</th>
                  </tr></thead>
                  <tbody className="divide-y divide-slate-100 dark:divide-white/[0.05]">
                    {(data?.columns || []).map((c) => (
                      <tr key={c.name}>
                        <td className="px-4 py-2 font-mono text-xs"><span className="inline-flex items-center gap-1.5">{c.pk ? <KeyRound className="h-3 w-3 text-amber-500" /> : null}{c.name}</span></td>
                        <td className="px-3 py-2 font-mono text-xs text-violet-600 dark:text-violet-300">{c.type}</td>
                        <td className="px-3 py-2 text-xs text-slate-500">{c.nullable ? 'да' : 'нет'}</td>
                        <td className="max-w-[280px] truncate px-3 py-2 font-mono text-xs text-slate-500">{c.default || '—'}</td>
                        <td className="px-4 py-2 font-mono text-xs text-sky-600 dark:text-sky-400">{c.fk ? `→ ${c.fk}` : ''}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>}
        </div>
      </div>

      {/* строка целиком */}
      {openRow && data ? (
        <>
          <div onClick={() => setOpenRow(null)} className="fixed inset-0 z-40 bg-slate-950/50 backdrop-blur-[2px]" />
          <aside className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[560px] flex-col border-l border-slate-200 bg-white dark:border-white/10 dark:bg-[#070b17]">
            <div className="flex items-center gap-3 border-b border-slate-200 px-5 py-4 dark:border-white/[0.07]">
              <Table2 className="h-4 w-4 text-emerald-500" /><div className="font-mono text-sm font-semibold">{selected}</div>
              <button onClick={() => setOpenRow(null)} className="ml-auto grid h-8 w-8 place-items-center rounded-lg border border-slate-200 text-slate-500 hover:bg-slate-50 dark:border-white/10 dark:hover:bg-white/5"><X className="h-4 w-4" /></button>
            </div>
            <div className="flex-1 space-y-3 overflow-y-auto p-5">
              {data.columns.map((c) => {
                const v = openRow[c.name]
                return (
                  <div key={c.name}>
                    <div className="flex items-center gap-1.5 text-[11px] text-slate-500 dark:text-slate-400"><span className="font-mono font-medium text-slate-700 dark:text-slate-200">{c.name}</span>{c.type}</div>
                    <pre className="mt-1 whitespace-pre-wrap break-words rounded-lg bg-slate-50 px-3 py-2 font-mono text-xs dark:bg-white/[0.04]">{v === null || v === undefined ? 'NULL' : typeof v === 'object' ? JSON.stringify(v, null, 2) : String(v)}</pre>
                  </div>
                )
              })}
            </div>
          </aside>
        </>
      ) : null}
    </div>
  )
}
