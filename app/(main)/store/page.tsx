'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import {
  AlertTriangle, ArrowRight, Boxes, ClipboardList, FileText, History, Package,
  Search, Warehouse, Building2, Activity, Receipt, Users2,
  PackagePlus, ArchiveX, ScanSearch, CheckCircle2, Scale, PackageX, Store,
} from 'lucide-react'
import { Skeleton } from '@/components/skeleton'

import { isAbortError } from '@/lib/is-abort-error'
import { formatMoney } from '@/lib/core/format'
import { SupplierDebtsWidget } from '@/components/store/supplier-debts-widget'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { useStoreApiUrl } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'

type LowStockRow = {
  item_id: string; name: string; quantity: number; threshold: number
  location_name: string | null; location_kind?: 'warehouse' | 'showcase' | null
}
type StoreOverviewMetrics = {
  pendingRequests: number
  disputedRequests: number
  showcases: number
  receipts: number
  unresolvedWriteoffs: number
  lowStock: number
  lowStockTop: LowStockRow[]
  // Поля новее страницы: в кэше браузера может лежать ответ без них.
  outOfStock?: number
  stockValue?: { warehouse: number; showcase: number; positions: number }
}
type AuditTimelineEntry = {
  id: string; entity_type: string; entity_id: string; action: string; created_at: string
  payload?: Record<string, any> | null
  actor_user_id: string | null; actor_staff?: { full_name: string | null; role: string | null } | null
}
type SearchResult = { type: string; title: string; subtitle: string; href: string; score: number }

const card = 'rounded-2xl border border-border bg-white dark:bg-slate-900/60 shadow-sm'

const HUBS = [
  { href: '/store/stock', label: 'Склад', note: 'Остатки, витрина, движения, каталог', icon: Warehouse, color: 'text-emerald-600 dark:text-emerald-300', bg: 'bg-emerald-500/10' },
  { href: '/store/documents', label: 'Документы', note: 'Приёмка, оприходование, списания, ревизия', icon: FileText, color: 'text-sky-600 dark:text-sky-300', bg: 'bg-sky-500/10' },
  { href: '/store/orders', label: 'Заявки', note: 'Заявки точек и заказы поставщикам', icon: ClipboardList, color: 'text-violet-600 dark:text-violet-300', bg: 'bg-violet-500/10' },
  { href: '/store/vendors', label: 'Поставщики', note: 'Поставщики, долги, расходники', icon: Building2, color: 'text-amber-600 dark:text-amber-300', bg: 'bg-amber-500/10' },
  { href: '/store/sales', label: 'Аналитика', note: 'Продажи, товары, ABC, прогноз', icon: Activity, color: 'text-emerald-600 dark:text-emerald-300', bg: 'bg-emerald-500/10' },
  { href: '/store/cashbox', label: 'Касса', note: 'Чеки, возвраты, реклама', icon: Receipt, color: 'text-sky-600 dark:text-sky-300', bg: 'bg-sky-500/10' },
  { href: '/store/clients', label: 'Клиенты', note: 'Клиенты и скидки', icon: Users2, color: 'text-violet-600 dark:text-violet-300', bg: 'bg-violet-500/10' },
]

/** Русское множественное число: plural(3, ['заявка', 'заявки', 'заявок']). */
function plural(n: number, forms: [string, string, string]) {
  const a = Math.abs(n) % 100
  const b = a % 10
  if (a > 10 && a < 20) return forms[2]
  if (b > 1 && b < 5) return forms[1]
  if (b === 1) return forms[0]
  return forms[2]
}

export default function StoreOverviewPage() {
  const storeUrl = useStoreApiUrl()
  const [overview, setOverview] = useState<StoreOverviewMetrics | null>(null)
  const [timeline, setTimeline] = useState<AuditTimelineEntry[]>([])
  const [q, setQ] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchResults, setSearchResults] = useState<SearchResult[]>([])

  useEffect(() => {
    if (!q.trim()) { setSearchResults([]); return }
    const ac = new AbortController()
    const timer = setTimeout(async () => {
      setSearching(true)
      try {
        const res = await fetch(storeUrl(`/api/admin/store/global-search?q=${encodeURIComponent(q.trim())}`), { cache: 'no-store', signal: ac.signal })
        const json = await res.json().catch(() => null)
        if (ac.signal.aborted) return
        setSearchResults(res.ok && json?.ok && Array.isArray(json?.data?.results) ? json.data.results : [])
      } catch (e) { if (!isAbortError(e) && !ac.signal.aborted) setSearchResults([]) }
      finally { if (!ac.signal.aborted) setSearching(false) }
    }, 250)
    return () => { clearTimeout(timer); ac.abort() }
  }, [q, storeUrl])

  const overviewUrl = storeUrl('/api/admin/store/overview')
  const timelineUrl = storeUrl('/api/admin/store/audit-timeline?limit=12')
  // Суффикс ключа: в кэше лежат только счётчики, а ответ содержит ещё списки
  // для мобильных приложений — путать эти два вида одним ключом нельзя.
  const overviewCacheKey = `${overviewUrl}#metrics`

  useEffect(() => {
    // Главная магазина открывается чаще всех остальных страниц раздела: прошлые
    // цифры показываем сразу, свежие подтягиваем фоном.
    const cachedOverview = readApiCache<StoreOverviewMetrics>(overviewCacheKey)
    if (cachedOverview) setOverview(cachedOverview)
    const cachedTimeline = readApiCache<AuditTimelineEntry[]>(timelineUrl)
    if (cachedTimeline) setTimeline(cachedTimeline)

    const ac = new AbortController()
    async function load() {
      try {
        const [resOverview, resTimeline] = await Promise.all([
          fetch(overviewUrl, { cache: 'no-store', signal: ac.signal }),
          fetch(timelineUrl, { cache: 'no-store', signal: ac.signal }),
        ])
        if (ac.signal.aborted) return
        const jsonOverview = await resOverview.json().catch(() => null)
        if (resOverview.ok && jsonOverview?.ok) {
          const d = jsonOverview.data
          const metrics: StoreOverviewMetrics = {
            pendingRequests: d.pendingRequests, disputedRequests: d.disputedRequests, showcases: d.showcases,
            receipts: d.receipts, unresolvedWriteoffs: d.unresolvedWriteoffs, lowStock: d.lowStock,
            lowStockTop: d.lowStockTop || [], outOfStock: d.outOfStock, stockValue: d.stockValue,
          }
          writeApiCache(overviewCacheKey, metrics)
          setOverview(metrics)
        }
        const jsonTimeline = await resTimeline.json().catch(() => null)
        if (ac.signal.aborted) return
        if (resTimeline.ok && jsonTimeline?.ok) {
          const timeline = Array.isArray(jsonTimeline?.data?.timeline) ? jsonTimeline.data.timeline : []
          writeApiCache(timelineUrl, timeline)
          setTimeline(timeline)
        }
      } catch (e) {
        // Фоновая ошибка при живом кэше не должна стирать экран.
        if (!isAbortError(e) && !ac.signal.aborted && !cachedOverview) { setOverview(null); setTimeline([]) }
      }
    }
    void load()
    return () => ac.abort()
  }, [overviewUrl, timelineUrl, overviewCacheKey])

  const loaded = overview !== null
  const lowRows = overview?.lowStockTop || []
  const outRows = lowRows.filter((r) => r.quantity <= 0)
  const fewRows = lowRows.filter((r) => r.quantity > 0)
  const outOfStock = overview?.outOfStock ?? outRows.length
  const fewCount = Math.max(0, (overview?.lowStock ?? 0) - outOfStock)

  // Только то, что ждёт человека. Нули не показываем — они не требуют действия.
  const todo: Array<{ key: string; icon: any; tone: 'amber' | 'rose'; text: string; href: string; cta: string }> = []
  if (overview) {
    if (overview.pendingRequests > 0) todo.push({ key: 'req', icon: ClipboardList, tone: 'amber', text: `${overview.pendingRequests} ${plural(overview.pendingRequests, ['заявка ждёт', 'заявки ждут', 'заявок ждут'])} решения`, href: '/store/orders', cta: 'Решить' })
    if (overview.disputedRequests > 0) todo.push({ key: 'disp', icon: Scale, tone: 'rose', text: `${overview.disputedRequests} ${plural(overview.disputedRequests, ['спорная заявка', 'спорные заявки', 'спорных заявок'])}: пришло не столько, сколько выдали`, href: '/store/orders', cta: 'Разобрать' })
    if (outOfStock > 0) todo.push({ key: 'out', icon: PackageX, tone: 'rose', text: `${outOfStock} ${plural(outOfStock, ['позиция закончилась', 'позиции закончились', 'позиций закончились'])}`, href: '/store/purchase-plan', cta: 'В план закупа' })
    if (overview.unresolvedWriteoffs > 0) todo.push({ key: 'wo', icon: ArchiveX, tone: 'amber', text: `${overview.unresolvedWriteoffs} ${plural(overview.unresolvedWriteoffs, ['списание', 'списания', 'списаний'])} без причины за 90 дней`, href: '/store/documents', cta: 'Указать' })
  }

  const value = overview?.stockValue
  const valueTotal = value ? value.warehouse + value.showcase : 0
  const whShare = valueTotal > 0 && value ? Math.round((value.warehouse / valueTotal) * 100) : 0

  return (
    <div className="app-page-wide space-y-5">
      <AdminPageHeader
        title="Обзор магазина"
        description="Что требует внимания, что заканчивается и сколько денег лежит в товаре"
        icon={<Boxes className="h-5 w-5" />}
        accent="emerald"
        backHref="/"
        toolbar={
          <div className="relative w-full max-w-xl">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Поиск по магазину: товар, штрихкод, заявка, приёмка…"
              className="w-full rounded-xl border border-border bg-white dark:bg-slate-950/50 py-2.5 pl-10 pr-3 text-sm text-foreground placeholder-slate-500 focus:border-emerald-400/50 focus:outline-none"
            />
            {(searching || searchResults.length > 0) && q.trim() && (
              <div className="absolute z-20 mt-2 w-full overflow-hidden rounded-xl border border-border bg-white dark:bg-slate-950/95 shadow-2xl backdrop-blur">
                <div className="px-3 py-2 text-[11px] text-slate-500">{searching ? 'Ищу…' : `Найдено: ${searchResults.length}`}</div>
                <div className="max-h-72 overflow-y-auto">
                  {searchResults.slice(0, 10).map((row, idx) => (
                    <Link key={`${row.type}-${idx}`} href={row.href} className="flex items-center justify-between gap-3 px-3 py-2 text-sm text-body hover:bg-slate-100 dark:hover:bg-white/5">
                      <span className="truncate">{row.title}</span>
                      <span className="shrink-0 text-xs text-slate-500">{row.subtitle}</span>
                    </Link>
                  ))}
                </div>
              </div>
            )}
          </div>
        }
      />

      {/* Требует внимания */}
      {!loaded ? (
        <Skeleton className="h-16 w-full rounded-2xl" />
      ) : todo.length === 0 ? (
        <div className="flex items-center gap-3 rounded-2xl border border-emerald-500/25 bg-emerald-500/[0.07] px-4 py-3.5">
          <CheckCircle2 className="h-5 w-5 shrink-0 text-emerald-600 dark:text-emerald-300" />
          <div className="text-sm text-emerald-800 dark:text-emerald-100">
            <span className="font-semibold">Всё спокойно.</span> Заявки разобраны, списания с причинами, ничего не закончилось.
          </div>
        </div>
      ) : (
        <div className={`${card} overflow-hidden`}>
          <div className="flex items-center gap-2 border-b border-border px-4 py-2.5 text-xs font-semibold uppercase tracking-[0.14em] text-slate-500">
            <AlertTriangle className="h-3.5 w-3.5 text-amber-500" /> Требует внимания
          </div>
          <div className="divide-y divide-border">
            {todo.map((t) => (
              <Link key={t.key} href={t.href} className="group flex items-center gap-3 px-4 py-3 transition-colors hover:bg-slate-50 dark:hover:bg-white/[0.03]">
                <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${t.tone === 'rose' ? 'bg-rose-500/10 text-rose-600 dark:text-rose-300' : 'bg-amber-500/10 text-amber-600 dark:text-amber-300'}`}>
                  <t.icon className="h-4 w-4" />
                </span>
                <span className="min-w-0 flex-1 text-sm font-medium text-foreground">{t.text}</span>
                <span className="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-slate-500 group-hover:text-foreground">
                  {t.cta} <ArrowRight className="h-3.5 w-3.5 transition group-hover:translate-x-0.5" />
                </span>
              </Link>
            ))}
          </div>
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-3">
        {/* Остатки под контролем */}
        <div className={`${card} overflow-hidden lg:col-span-2`}>
          <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
            <div>
              <div className="text-sm font-semibold text-foreground">Остатки под контролем</div>
              <div className="text-xs text-muted-foreground">
                {loaded
                  ? overview!.lowStock === 0
                    ? 'Всё выше порога'
                    : `Закончилось ${outOfStock} · мало ${fewCount}`
                  : 'Считаю…'}
              </div>
            </div>
            <Link href="/store/purchase-plan" className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-border px-2.5 py-1.5 text-xs font-medium text-body hover:bg-slate-50 dark:hover:bg-white/5">
              План закупа <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          </div>
          {!loaded ? (
            <div className="space-y-2 p-4">{Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-9 w-full" />)}</div>
          ) : lowRows.length === 0 ? (
            <div className="px-4 py-10 text-center text-sm text-muted-foreground">
              Ни один товар не опустился ниже порога.
              <div className="mt-1 text-xs">Порог задаётся в карточке товара — «Минимальный остаток».</div>
            </div>
          ) : (
            <div className="divide-y divide-border">
              {[...outRows, ...fewRows].map((row) => <StockRow key={row.item_id} row={row} />)}
              {(overview!.lowStock > lowRows.length) && (
                <Link href="/store/stock" className="block px-4 py-2.5 text-center text-xs font-medium text-muted-foreground hover:text-foreground">
                  Ещё {overview!.lowStock - lowRows.length} — на складе
                </Link>
              )}
            </div>
          )}
        </div>

        <div className="space-y-5">
          {/* Деньги в товаре */}
          <div className={`${card} p-4`}>
            <div className="text-sm font-semibold text-foreground">Деньги в товаре</div>
            <div className="text-xs text-muted-foreground">По закупочной цене</div>
            {!loaded || !value ? (
              loaded ? <div className="mt-3 text-sm text-muted-foreground">Обновите страницу — цифры появятся.</div>
                : <Skeleton className="mt-3 h-20 w-full" />
            ) : (
              <>
                <div className="mt-3 text-3xl font-bold tabular-nums tracking-tight text-foreground">{formatMoney(valueTotal)}</div>
                <div className="mt-0.5 text-xs text-muted-foreground">
                  {value.positions} {plural(value.positions, ['позиция', 'позиции', 'позиций'])} в наличии
                </div>
                <div className="mt-4 flex h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5">
                  <div className="bg-sky-500" style={{ width: `${whShare}%` }} />
                  <div className="bg-emerald-500" style={{ width: `${100 - whShare}%` }} />
                </div>
                <div className="mt-3 space-y-1.5 text-sm">
                  <div className="flex items-center justify-between gap-2">
                    <span className="flex items-center gap-2 text-muted-foreground"><span className="h-2 w-2 rounded-full bg-sky-500" /><Warehouse className="h-3.5 w-3.5" /> Подсобка</span>
                    <span className="font-semibold tabular-nums text-foreground">{formatMoney(value.warehouse)}</span>
                  </div>
                  <div className="flex items-center justify-between gap-2">
                    <span className="flex items-center gap-2 text-muted-foreground"><span className="h-2 w-2 rounded-full bg-emerald-500" /><Store className="h-3.5 w-3.5" /> Витрина</span>
                    <span className="font-semibold tabular-nums text-foreground">{formatMoney(value.showcase)}</span>
                  </div>
                </div>
              </>
            )}
          </div>

          <SupplierDebtsWidget />
        </div>
      </div>

      {/* Разделы магазина */}
      <div>
        <div className="mb-2 text-xs font-semibold uppercase tracking-[0.18em] text-slate-500">Разделы</div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-7">
          {HUBS.map((h) => (
            <Link key={h.href} href={h.href} className={`${card} group flex flex-col gap-2.5 p-3.5 transition hover:-translate-y-0.5 hover:border-slate-300 hover:shadow-md dark:hover:border-white/20`}>
              <div className={`grid h-9 w-9 place-items-center rounded-xl ${h.bg}`}><h.icon className={`h-4.5 w-4.5 ${h.color}`} /></div>
              <div className="min-w-0">
                <div className="flex items-center gap-1 text-sm font-semibold text-foreground">{h.label}<ArrowRight className="h-3.5 w-3.5 text-slate-400 opacity-0 transition group-hover:translate-x-0.5 group-hover:opacity-100" /></div>
                <div className="mt-0.5 text-[11px] leading-snug text-muted-foreground">{h.note}</div>
              </div>
            </Link>
          ))}
        </div>
      </div>

      {/* Последние действия */}
      <div className={`${card} overflow-hidden`}>
        <div className="flex items-center gap-2 border-b border-border px-4 py-3 text-sm font-semibold text-foreground">
          <History className="h-4 w-4 text-violet-500 dark:text-violet-300" /> Последние действия
        </div>
        {!loaded ? (
          <div className="space-y-3 px-4 py-4">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3">
                <Skeleton className="h-8 w-8 rounded-lg" />
                <Skeleton className="h-4 flex-1" />
                <Skeleton className="h-3 w-16" />
              </div>
            ))}
          </div>
        ) : timeline.length === 0 ? (
          <div className="px-4 py-10 text-center text-sm text-slate-400">Действий пока нет.</div>
        ) : (
          <div className="divide-y divide-border">
            {timeline.map((e) => {
              const m = activityMeta(e.entity_type, e.action)
              const actor = (e.actor_staff?.full_name || '').trim()
              const detail = activityDetail(e)
              const Icon = m.icon
              return (
                <div key={e.id} className="flex items-center gap-3 px-4 py-2.5">
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-slate-100 dark:bg-white/5"><Icon className={`h-4 w-4 ${m.color}`} /></span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm text-body">
                      {m.phrase}
                      {detail ? <span className="text-muted-foreground"> · {detail}</span> : null}
                    </div>
                    <div className="text-[11px] text-slate-500">{actor || 'Система'}</div>
                  </div>
                  <span className="shrink-0 text-xs tabular-nums text-slate-500">{new Date(e.created_at).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}</span>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}

function StockRow({ row }: { row: LowStockRow }) {
  const out = row.quantity <= 0
  const pct = row.threshold > 0 ? Math.max(0, Math.min(100, (row.quantity / row.threshold) * 100)) : 0
  const where = row.location_kind === 'warehouse' ? 'подсобка' : row.location_kind === 'showcase' ? 'витрина' : null
  return (
    <div className="flex items-center gap-3 px-4 py-2.5">
      <span className={`grid h-8 w-8 shrink-0 place-items-center rounded-lg ${out ? 'bg-rose-500/10 text-rose-600 dark:text-rose-300' : 'bg-amber-500/10 text-amber-600 dark:text-amber-300'}`}>
        {out ? <PackageX className="h-4 w-4" /> : <Package className="h-4 w-4" />}
      </span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-foreground">{row.name}</div>
        <div className="truncate text-[11px] text-muted-foreground">
          {[where, row.location_name].filter(Boolean).join(' · ') || '—'}
        </div>
      </div>
      <div className="w-28 shrink-0 sm:w-36">
        <div className="flex items-baseline justify-end gap-1 text-sm tabular-nums">
          <span className={`font-semibold ${out ? 'text-rose-600 dark:text-rose-300' : 'text-amber-600 dark:text-amber-300'}`}>{out ? 'нет' : row.quantity}</span>
          <span className="text-[11px] text-muted-foreground">/ порог {row.threshold}</span>
        </div>
        <div className="mt-1 h-1 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5">
          <div className={`h-full rounded-full ${out ? 'bg-rose-500' : 'bg-amber-500'}`} style={{ width: `${pct}%` }} />
        </div>
      </div>
    </div>
  )
}

const ACTIVITY_PHRASE: Record<string, string> = {
  'inventory-receipt:create': 'Приёмка создана',
  'inventory-receipt:create_posting': 'Оприходование',
  'inventory-receipt:update': 'Приёмка изменена',
  'inventory-receipt:delete': 'Приёмка удалена',
  'inventory-writeoff:create': 'Списание создано',
  'inventory-writeoff:cancel': 'Списание отменено',
  'inventory-request:create': 'Заявка создана',
  'inventory-request:approve': 'Заявка одобрена',
  'inventory-request:reject': 'Заявка отклонена',
  'inventory-request:issue': 'Заявка выдана',
  'inventory-request:receive': 'Заявка получена',
  'inventory-request:bulk-approve-full': 'Заявки одобрены пакетом',
  'inventory-request:bulk-approve': 'Заявки одобрены пакетом',
  'inventory-request:bulk-reject': 'Заявки отклонены пакетом',
  'inventory-request:bulk-issue': 'Заявки выданы пакетом',
  'inventory-request:bulk-receive': 'Заявки приняты пакетом',
  'inventory-revision:create': 'Ревизия создана',
  'inventory-revision:apply': 'Ревизия проведена',
  'inventory-audit-act:open': 'Ревизия начата',
  'inventory-audit-act:close': 'Ревизия закрыта',
  'inventory-audit-act:cancel': 'Ревизия отменена',
  'inventory-posting:create': 'Оприходование',
  'inventory-showcase-alloc:set_showcase': 'Остаток витрины изменён',
  'inventory-warehouse-alloc:set_warehouse': 'Остаток подсобки изменён',
  'inventory_warehouse_alloc:set_warehouse': 'Остаток подсобки изменён',
  'inventory-warehouse-alloc:backroom_bulk_upload': 'Подсобка загружена из Excel',
  'inventory-warehouse-stock:set_stock': 'Подсобка синхронизирована',
  'inventory-warehouse:alloc': 'Склад точки назначен',
}
const ENTITY_META: Record<string, { label: string; icon: any; color: string }> = {
  'inventory-receipt': { label: 'Приёмка', icon: PackagePlus, color: 'text-sky-500 dark:text-sky-300' },
  'inventory-writeoff': { label: 'Списание', icon: ArchiveX, color: 'text-rose-500 dark:text-rose-300' },
  'inventory-request': { label: 'Заявка', icon: ClipboardList, color: 'text-violet-500 dark:text-violet-300' },
  'inventory-revision': { label: 'Ревизия', icon: ScanSearch, color: 'text-amber-500 dark:text-amber-300' },
  'inventory-audit-act': { label: 'Ревизия', icon: ScanSearch, color: 'text-amber-500 dark:text-amber-300' },
  'inventory-posting': { label: 'Оприходование', icon: Package, color: 'text-emerald-500 dark:text-emerald-300' },
  'inventory-showcase-alloc': { label: 'Витрина', icon: Store, color: 'text-emerald-500 dark:text-emerald-300' },
  'inventory-warehouse-alloc': { label: 'Склад', icon: Warehouse, color: 'text-sky-500 dark:text-sky-300' },
  'inventory_warehouse_alloc': { label: 'Склад', icon: Warehouse, color: 'text-sky-500 dark:text-sky-300' },
  'inventory-warehouse-stock': { label: 'Склад', icon: Warehouse, color: 'text-sky-500 dark:text-sky-300' },
  'inventory-warehouse': { label: 'Склад', icon: Warehouse, color: 'text-sky-500 dark:text-sky-300' },
}
const ACTION_FALLBACK: Record<string, string> = {
  create: 'создано', create_posting: 'оприходование', approve: 'одобрено', reject: 'отклонено',
  cancel: 'отменено', update: 'изменено', delete: 'удалено', issue: 'выдано', receive: 'получено',
  apply: 'проведено', close: 'закрыто', open: 'открыто',
  'bulk-approve-full': 'одобрены пакетом', 'bulk-approve': 'одобрены пакетом',
  'bulk-reject': 'отклонены пакетом', 'bulk-issue': 'выданы пакетом', 'bulk-receive': 'приняты пакетом',
  set_warehouse: 'назначен', alloc: 'назначен',
}
function activityMeta(entity: string, action: string): { phrase: string; icon: any; color: string } {
  const meta = ENTITY_META[entity] || { label: String(entity || '').replace(/[-_]/g, ' '), icon: History, color: 'text-muted-foreground' }
  const phrase = ACTIVITY_PHRASE[`${entity}:${action}`] || `${meta.label} · ${ACTION_FALLBACK[action] || action}`
  return { phrase, icon: meta.icon, color: meta.color }
}

/** Короткая подробность из payload журнала: сумма, поставщик, сколько штук. */
function activityDetail(e: AuditTimelineEntry): string | null {
  const p = e.payload || {}
  const parts: string[] = []
  if (p.supplier_name) parts.push(String(p.supplier_name))
  if (p.company_name && !p.supplier_name) parts.push(String(p.company_name))
  const total = Number(p.total_amount ?? 0)
  if (total > 0) parts.push(formatMoney(total))
  if (Array.isArray(p.succeeded) && p.succeeded.length > 1) parts.push(`${p.succeeded.length} шт`)
  if (e.action === 'open' && Number(p.items) > 0) parts.push(`${p.items} позиций`)
  if ((e.action === 'set_showcase' || e.action === 'set_warehouse') && p.quantity != null) parts.push(`→ ${p.quantity}`)
  return parts.length ? parts.join(' · ') : null
}
