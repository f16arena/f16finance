'use client'

import { useEffect, useMemo, useState } from 'react'
import { AlertTriangle, Boxes, CheckCircle2, MoreHorizontal, Package, PackageX, RefreshCw, Store } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { StoreEmpty, StoreRow, StoreSection, StoreStat, StoreStatGrid, plural } from '@/components/store/store-ui'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { formatMoney } from '@/lib/core/format'
import { isAbortError } from '@/lib/is-abort-error'
import { useStoreApiUrl } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'

type InventoryLocation = {
  id: string
  name: string
  code: string | null
  location_type: 'warehouse' | 'point_display'
  company?: { id: string; name: string; code: string | null } | null
}

type InventoryBalance = {
  location_id: string
  item_id: string
  quantity: number
  item?: { id: string; name: string; barcode: string; unit?: string | null; low_stock_threshold?: number | null } | null
  location?: InventoryLocation | null
}

type InventoryMovement = {
  id: string
  movement_type: string
  quantity: number
  total_amount: number | null
  created_at: string
  item?: { id: string; name: string; barcode: string; unit?: string | null } | null
  from_location?: InventoryLocation | null
  to_location?: InventoryLocation | null
}

type AnalyticsResponse = {
  ok: boolean
  data?: {
    locations: InventoryLocation[]
    balances: InventoryBalance[]
    movements: InventoryMovement[]
  }
  error?: string
}

function firstOrSelf<T>(value: T | T[] | null | undefined): T | null {
  if (Array.isArray(value)) return (value[0] as T) || null
  return value ?? null
}

function formatQty(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function formatDateTime(value: string | null | undefined) {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(parsed)
}

export default function StoreAnalyticsPage({ embedded = false }: { embedded?: boolean } = {}) {
  const storeUrl = useStoreApiUrl()
  const [tab, setTab] = useState<'showcase' | 'warehouse'>('showcase')
  const [days, setDays] = useState(30) // период движения: 1 / 7 / 30
  const [data, setData] = useState<AnalyticsResponse['data'] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const analyticsUrl = storeUrl(`/api/admin/store/analytics?days=${days}`)
  // Суффикс: в кэше лежит разобранный вид, а не сырой ответ.
  const analyticsCacheKey = `${analyticsUrl}#analytics`

  const load = async (signal?: AbortSignal, opts?: { fresh?: boolean }) => {
    const cached = opts?.fresh ? null : readApiCache<NonNullable<AnalyticsResponse['data']>>(analyticsCacheKey)
    if (cached) { setData(cached); setLoading(false) }
    else setLoading(true)
    setError(null)
    try {
      const response = await fetch(analyticsUrl, { cache: 'no-store', signal })
      const json = (await response.json().catch(() => null)) as AnalyticsResponse | null
      if (signal?.aborted) return
      if (!response.ok || !json?.ok || !json.data) throw new Error(json?.error || 'Не удалось загрузить аналитику')

      const payload = {
        locations: (json.data.locations || []).map((location) => ({
          ...location,
          company: firstOrSelf(location.company),
        })),
        balances: (json.data.balances || []).map((balance) => ({
          ...balance,
          quantity: Number(balance.quantity || 0),
          item: firstOrSelf(balance.item),
          location: firstOrSelf(balance.location),
        })),
        movements: (json.data.movements || []).map((movement) => ({
          ...movement,
          quantity: Number(movement.quantity || 0),
          total_amount: movement.total_amount == null ? null : Number(movement.total_amount || 0),
          item: firstOrSelf(movement.item),
          from_location: firstOrSelf(movement.from_location),
          to_location: firstOrSelf(movement.to_location),
        })),
      }
      writeApiCache(analyticsCacheKey, payload)
      setData(payload)
    } catch (err: any) {
      if (isAbortError(err) || signal?.aborted) return
      setData(null)
      setError(err?.message || 'Не удалось загрузить аналитику')
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }

  useEffect(() => {
    const ac = new AbortController()
    void load(ac.signal)
    return () => ac.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [days, storeUrl])

  const pointLocations = useMemo(
    () => (data?.locations || []).filter((location) => location.location_type === 'point_display'),
    [data?.locations],
  )

  const pointAnalytics = useMemo(() => {
    const summary = new Map<string, {
      location: InventoryLocation
      stockQty: number
      stockItems: number
      incomingQty: number
      incomingAmount: number
      saleQty: number
      saleAmount: number
      debtQty: number
      debtAmount: number
      returnQty: number
      returnAmount: number
      writeoffQty: number
      writeoffAmount: number
      adjustmentQty: number
      lastMovementAt: string | null
    }>()

    for (const location of pointLocations) {
      summary.set(location.id, {
        location,
        stockQty: 0,
        stockItems: 0,
        incomingQty: 0,
        incomingAmount: 0,
        saleQty: 0,
        saleAmount: 0,
        debtQty: 0,
        debtAmount: 0,
        returnQty: 0,
        returnAmount: 0,
        writeoffQty: 0,
        writeoffAmount: 0,
        adjustmentQty: 0,
        lastMovementAt: null,
      })
    }

    for (const balance of data?.balances || []) {
      const point = balance.location?.location_type === 'point_display' ? summary.get(balance.location_id) : null
      if (!point) continue
      point.stockQty += Number(balance.quantity || 0)
      point.stockItems += 1
    }

    for (const movement of data?.movements || []) {
      const qty = Number(movement.quantity || 0)
      const amount = Number(movement.total_amount || 0)
      const fromPoint = movement.from_location?.location_type === 'point_display' ? summary.get(movement.from_location.id) : null
      const toPoint = movement.to_location?.location_type === 'point_display' ? summary.get(movement.to_location.id) : null

      if (fromPoint && (!fromPoint.lastMovementAt || new Date(movement.created_at).getTime() > new Date(fromPoint.lastMovementAt).getTime())) {
        fromPoint.lastMovementAt = movement.created_at
      }
      if (toPoint && (!toPoint.lastMovementAt || new Date(movement.created_at).getTime() > new Date(toPoint.lastMovementAt).getTime())) {
        toPoint.lastMovementAt = movement.created_at
      }

      if (movement.movement_type === 'transfer_to_point' && toPoint) {
        toPoint.incomingQty += qty
        toPoint.incomingAmount += amount
      }
      if (movement.movement_type === 'sale' && fromPoint) {
        fromPoint.saleQty += qty
        fromPoint.saleAmount += amount
      }
      if (movement.movement_type === 'debt' && fromPoint) {
        fromPoint.debtQty += qty
        fromPoint.debtAmount += amount
      }
      if (movement.movement_type === 'return' && toPoint) {
        toPoint.returnQty += qty
        toPoint.returnAmount += amount
      }
      if (movement.movement_type === 'writeoff' && fromPoint) {
        fromPoint.writeoffQty += qty
        fromPoint.writeoffAmount += amount
      }
      if (movement.movement_type === 'inventory_adjustment') {
        if (fromPoint) fromPoint.adjustmentQty -= qty
        if (toPoint) toPoint.adjustmentQty += qty
      }
    }

    return [...summary.values()].sort((a, b) => {
      if (b.saleAmount !== a.saleAmount) return b.saleAmount - a.saleAmount
      return (a.location.company?.name || a.location.name).localeCompare(b.location.company?.name || b.location.name, 'ru')
    })
  }, [data?.balances, data?.movements, pointLocations])

  const warehouseAnalytics = useMemo(() => {
    const stockBalances = (data?.balances || []).filter((b) => b.location?.location_type === 'warehouse')
    const totalStockQty = stockBalances.reduce((sum, b) => sum + Number(b.quantity || 0), 0)
    const totalStockValue = stockBalances.reduce(
      (sum, b) => sum + Number(b.quantity || 0) * Number((b as any).item?.default_purchase_price || 0),
      0,
    )
    const topByValue = [...stockBalances]
      .map((b) => ({
        ...b,
        stockValue: Number(b.quantity || 0) * Number((b as any).item?.default_purchase_price || 0),
      }))
      .sort((a, b) => b.stockValue - a.stockValue)
      .slice(0, 10)
    return { totalStockQty, totalStockValue, topByValue }
  }, [data?.balances])

  const riskyBalances = useMemo(() => {
    return (data?.balances || [])
      .filter((balance) => balance.location?.location_type === 'point_display')
      .filter((balance) => {
        const threshold = Number(balance.item?.low_stock_threshold || 0)
        return threshold > 0 && Number(balance.quantity || 0) <= threshold
      })
      .sort((a, b) => Number(a.quantity || 0) - Number(b.quantity || 0))
      .slice(0, 12)
  }, [data?.balances])

  const periodLabel = days === 1 ? 'сегодня' : days === 7 ? 'за неделю' : 'за месяц'
  const totalSales = pointAnalytics.reduce((sum, p) => sum + p.saleAmount, 0)
  const totalDebt = pointAnalytics.reduce((sum, p) => sum + p.debtAmount, 0)
  const totalStock = pointAnalytics.reduce((sum, p) => sum + p.stockQty, 0)
  const maxSale = Math.max(1, ...pointAnalytics.map((p) => p.saleAmount))
  const firstLoad = loading && !data

  const hdrActions = (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm">
          <MoreHorizontal className="h-3.5 w-3.5" />
          Действия
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel>Управление аналитикой</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => void load(undefined, { fresh: true })} disabled={loading}>
          <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          Обновить данные
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
  const pill = (active: boolean) => `rounded-xl px-3 py-2 text-sm font-medium transition-all ${active ? 'bg-white text-foreground shadow-sm ring-1 ring-slate-200 dark:bg-white/10 dark:ring-white/10' : 'text-muted-foreground hover:text-foreground'}`
  const hdrToolbar = (
    <div className="flex flex-wrap items-center gap-2">
      <div className="inline-flex gap-1 rounded-2xl border border-border bg-slate-50 p-1 dark:bg-slate-950/50">
        <button type="button" onClick={() => setTab('showcase')} className={pill(tab === 'showcase')}>Витрина (продажи)</button>
        <button type="button" onClick={() => setTab('warehouse')} className={pill(tab === 'warehouse')}>Склад (запасы)</button>
      </div>
      {tab === 'showcase' && (
        <div className="inline-flex gap-1 rounded-2xl border border-border bg-slate-50 p-1 dark:bg-slate-950/50">
          {[{ d: 1, l: 'Сегодня' }, { d: 7, l: 'Неделя' }, { d: 30, l: 'Месяц' }].map((p) => (
            <button key={p.d} type="button" onClick={() => setDays(p.d)} className={pill(days === p.d)}>{p.l}</button>
          ))}
        </div>
      )}
    </div>
  )

  return (
    <div className={embedded ? 'space-y-5' : 'app-page-wide space-y-5'}>
      {embedded ? (
        <div className="flex flex-wrap items-center justify-between gap-3">
          {hdrToolbar}
          <div className="flex flex-wrap items-center gap-2">{hdrActions}</div>
        </div>
      ) : (
        <AdminPageHeader
          title="Аналитика точек"
          description="Приход на витрины, продажи, долги, возвраты и риски по остаткам"
          icon={<Store className="h-5 w-5" />}
          accent="emerald"
          backHref="/store"
          actions={hdrActions}
          toolbar={hdrToolbar}
        />
      )}

      {error ? (
        <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-200">{error}</div>
      ) : null}

      {tab === 'showcase' ? (
        <>
          <StoreStatGrid cols={4}>
            <StoreStat label={`Продано ${periodLabel}`} value={formatMoney(totalSales)} tone={totalSales > 0 ? 'emerald' : 'neutral'} loading={firstLoad} hint={`${pointAnalytics.length} ${plural(pointAnalytics.length, ['витрина', 'витрины', 'витрин'])}`} />
            <StoreStat label="Сейчас на витринах" value={`${formatQty(totalStock)} ед`} loading={firstLoad} hint="текущий остаток" />
            <StoreStat label="Отдано в долг" value={formatMoney(totalDebt)} tone={totalDebt > 0 ? 'amber' : 'neutral'} loading={firstLoad} hint={periodLabel} />
            <StoreStat label="Подходят к порогу" value={riskyBalances.length} tone={riskyBalances.length > 0 ? 'amber' : 'neutral'} loading={firstLoad} hint={riskyBalances.length > 0 ? 'товаров требуют внимания' : 'всё выше порога'} />
          </StoreStatGrid>

          <section className="grid gap-5 xl:grid-cols-[minmax(0,1.3fr)_400px]">
            <StoreSection
              title="Сводка по витринам"
              subtitle={`Остаток — на сейчас, остальное — движение ${periodLabel}`}
              icon={<Boxes className="h-4 w-4" />}
              flush
            >
              {firstLoad ? (
                <div className="space-y-3 p-4">
                  {Array.from({ length: 3 }).map((_, idx) => <Skeleton key={idx} className="h-32 w-full rounded-xl" />)}
                </div>
              ) : pointAnalytics.length === 0 ? (
                <StoreEmpty
                  icon={<Store className="h-5 w-5" />}
                  title="Витрин пока нет"
                  hint="Аналитика появится, когда на точке заведена витрина и по ней пошло движение товара."
                />
              ) : (
                <div className="divide-y divide-border">
                  {pointAnalytics.map((point) => (
                    <div key={point.location.id} className="space-y-3 p-4">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div className="min-w-0">
                          <p className="truncate text-sm font-semibold text-foreground">{point.location.company?.name || point.location.name}</p>
                          <p className="text-[11px] text-muted-foreground">Последнее движение: {formatDateTime(point.lastMovementAt)}</p>
                        </div>
                        <div className="text-right">
                          <p className="text-[11px] text-muted-foreground">Сейчас на витрине</p>
                          <p className="text-sm font-semibold tabular-nums text-foreground">{formatQty(point.stockQty)} ед · {point.stockItems} SKU</p>
                        </div>
                      </div>

                      <div>
                        <div className="flex items-baseline justify-between gap-3">
                          <span className="text-xs text-muted-foreground">Продано {periodLabel} · {formatQty(point.saleQty)} ед</span>
                          <span className={`text-xl font-bold tabular-nums ${point.saleAmount > 0 ? 'text-emerald-600 dark:text-emerald-300' : 'text-slate-400'}`}>{formatMoney(point.saleAmount)}</span>
                        </div>
                        <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5">
                          <div className="h-full rounded-full bg-emerald-500" style={{ width: `${(point.saleAmount / maxSale) * 100}%` }} />
                        </div>
                      </div>

                      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                        {[
                          { label: 'Пришло', qty: point.incomingQty, money: point.incomingAmount, tone: 'text-sky-600 dark:text-sky-300' },
                          { label: 'В долг', qty: point.debtQty, money: point.debtAmount, tone: 'text-amber-600 dark:text-amber-300' },
                          { label: 'Возвраты', qty: point.returnQty, money: null as number | null, tone: 'text-foreground' },
                          { label: 'Списания', qty: point.writeoffQty, money: null as number | null, tone: 'text-rose-600 dark:text-rose-300' },
                        ].map((s) => (
                          <div key={s.label} className="rounded-xl bg-slate-50 px-3 py-2 dark:bg-white/[0.04]">
                            <p className="text-[11px] text-muted-foreground">{s.label}</p>
                            <p className={`mt-0.5 text-sm font-semibold tabular-nums ${s.qty > 0 ? s.tone : 'text-slate-400'}`}>{formatQty(s.qty)}</p>
                            {s.money != null && s.money > 0 ? <p className="text-[11px] tabular-nums text-muted-foreground">{formatMoney(s.money)}</p> : null}
                          </div>
                        ))}
                      </div>
                      {point.adjustmentQty !== 0 && (
                        <p className="text-[11px] text-muted-foreground" title="Ручные правки остатка: ревизия/корректировка (не продажа и не приход)">
                          Корректировка остатка за период: {formatQty(point.adjustmentQty)}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </StoreSection>

            <StoreSection
              title="Риск по витринам"
              subtitle="Товары, которые подошли к порогу остатка"
              icon={<AlertTriangle className="h-4 w-4 text-amber-500" />}
              flush
            >
              {firstLoad ? (
                <div className="space-y-2 p-4">{Array.from({ length: 4 }).map((_, idx) => <Skeleton key={idx} className="h-12 w-full rounded-xl" />)}</div>
              ) : riskyBalances.length === 0 ? (
                <StoreEmpty
                  icon={<CheckCircle2 className="h-5 w-5 text-emerald-500" />}
                  title="Критичных остатков нет"
                  hint="Порог задаётся в карточке товара — «Минимальный остаток»."
                />
              ) : (
                <div className="divide-y divide-border">
                  {riskyBalances.map((balance) => {
                    const out = Number(balance.quantity || 0) <= 0
                    return (
                      <StoreRow
                        key={`${balance.location_id}:${balance.item_id}`}
                        icon={out ? PackageX : Package}
                        tone={out ? 'rose' : 'amber'}
                        title={balance.item?.name || 'Товар'}
                        subtitle={balance.location?.company?.name || balance.location?.name || 'Точка'}
                        right={
                          <>
                            <p className={`text-sm font-semibold tabular-nums ${out ? 'text-rose-600 dark:text-rose-300' : 'text-amber-600 dark:text-amber-300'}`}>{out ? 'нет' : `${formatQty(Number(balance.quantity || 0))} ${balance.item?.unit || 'шт'}`}</p>
                            <p className="text-[11px] text-muted-foreground">порог {Number(balance.item?.low_stock_threshold || 0)}</p>
                          </>
                        }
                      />
                    )
                  })}
                </div>
              )}
            </StoreSection>
          </section>
        </>
      ) : (
        <>
          <StoreStatGrid cols={3}>
            <StoreStat label="Единиц товара" value={formatQty(warehouseAnalytics.totalStockQty)} icon={<Boxes className="h-4 w-4" />} loading={firstLoad} hint="в подсобке" />
            <StoreStat label="Стоимость запасов" value={formatMoney(warehouseAnalytics.totalStockValue)} tone="sky" loading={firstLoad} hint="по закупочной цене" />
            <StoreStat label="Позиций в топе" value={warehouseAnalytics.topByValue.length} loading={firstLoad} hint="самые дорогие остатки" />
          </StoreStatGrid>
          <StoreSection title="Топ по стоимости (склад)" subtitle="Где лежит больше всего денег" icon={<Boxes className="h-4 w-4" />} flush>
            {firstLoad ? (
              <div className="space-y-2 p-4">{Array.from({ length: 5 }).map((_, idx) => <Skeleton key={idx} className="h-10 w-full rounded-xl" />)}</div>
            ) : warehouseAnalytics.topByValue.length === 0 ? (
              <StoreEmpty title="На складе пусто" hint="Оформите приёмку — и здесь появятся самые дорогие остатки." />
            ) : (
              <div className="divide-y divide-border">
                {warehouseAnalytics.topByValue.map((row: any, i: number) => (
                  <StoreRow
                    key={`${row.location_id}:${row.item_id}`}
                    title={<><span className="mr-2 text-xs text-muted-foreground">{i + 1}</span>{row.item?.name || 'Товар'}</>}
                    subtitle={`${formatQty(Number(row.quantity || 0))} ${row.item?.unit || 'шт'}`}
                    right={<span className={`text-sm font-semibold tabular-nums ${row.stockValue > 0 ? 'text-foreground' : 'text-slate-400'}`}>{formatMoney(row.stockValue)}</span>}
                  />
                ))}
              </div>
            )}
          </StoreSection>
        </>
      )}
    </div>
  )
}
