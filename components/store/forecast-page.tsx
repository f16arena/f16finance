'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { PackageSearch, RefreshCw, AlertTriangle, TrendingDown, Clock, CheckCircle2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { NativeSelect } from '@/components/ui/native-select'
import { SortableTh } from '@/components/ui/sortable-th'
import { useTableSort } from '@/lib/client/use-table-sort'
import type { SortColumns } from '@/lib/core/table-sort'
import { StoreAttention, StoreChip, StoreEmpty, StoreSection, StoreStat, StoreStatGrid, plural } from '@/components/store/store-ui'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { useStoreScope } from '@/components/store/store-scope'
import { useCompanies } from '@/hooks/use-companies'
import { isAbortError } from '@/lib/is-abort-error'

// ─── Types ─────────────────────────────────────────────────────────────────────

type ForecastItem = {
  item_id: string
  name: string
  category: string | null
  balance: number
  daily_velocity: number
  days_left: number | null
  threshold: number | null
  status: 'critical' | 'warning' | 'low' | 'ok' | 'no_sales'
}

type Location = {
  id: string
  name: string
  location_type: string
  company_id: string | null
}

type FilterStatus = 'all' | 'critical' | 'warning' | 'low' | 'ok' | 'no_sales'

// ─── Helpers ───────────────────────────────────────────────────────────────────

function statusLabel(status: ForecastItem['status']) {
  switch (status) {
    case 'critical': return 'Критично'
    case 'warning': return 'Мало'
    case 'low': return 'Предупреждение'
    case 'ok': return 'В порядке'
    case 'no_sales': return 'Нет продаж'
  }
}

function statusTone(status: ForecastItem['status']): 'rose' | 'amber' | 'sky' | 'emerald' | 'neutral' {
  switch (status) {
    case 'critical': return 'rose'
    case 'warning': return 'amber'
    case 'low': return 'sky'
    case 'ok': return 'emerald'
    case 'no_sales': return 'neutral'
  }
}

function daysLeftColor(daysLeft: number | null) {
  if (daysLeft === null) return 'text-slate-400'
  if (daysLeft <= 3) return 'font-bold text-rose-600 dark:text-rose-300'
  if (daysLeft <= 7) return 'font-semibold text-amber-600 dark:text-amber-300'
  if (daysLeft <= 14) return 'text-foreground'
  return 'text-emerald-600 dark:text-emerald-300'
}

type SortKey = 'name' | 'category' | 'balance' | 'velocity' | 'days'

// ─── Main Component ────────────────────────────────────────────────────────────

export function InventoryForecastPageContent({ embedded = false }: { embedded?: boolean } = {}) {
  const { companies } = useCompanies()
  const [companyId, setCompanyId] = useState('')
  const { storeCompanyId } = useStoreScope()
  useEffect(() => { if (storeCompanyId) setCompanyId(storeCompanyId) }, [storeCompanyId])
  const [locationId, setLocationId] = useState('')
  const [locations, setLocations] = useState<Location[]>([])
  const [forecast, setForecast] = useState<ForecastItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [filterStatus, setFilterStatus] = useState<FilterStatus>('all')

  // Load locations when company changes
  useEffect(() => {
    setLocationId('')
    if (!companyId) {
      setLocations([])
      return
    }
    void (async () => {
      try {
        const res = await fetch(`/api/admin/inventory?action=locations&company_id=${companyId}`)
        const j = await res.json()
        setLocations(j.data?.locations || [])
      } catch {
        setLocations([])
      }
    })()
  }, [companyId])

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    setError(null)
    try {
      const params = new URLSearchParams()
      if (companyId) params.set('company_id', companyId)
      if (locationId) params.set('location_id', locationId)
      const res = await fetch(`/api/admin/inventory/forecast?${params.toString()}`, { signal })
      const j = await res.json()
      if (signal?.aborted) return
      if (!res.ok) throw new Error(j.error || 'Ошибка загрузки')
      setForecast(j.data || [])
    } catch (err: any) {
      if (isAbortError(err) || signal?.aborted) return
      setError(err?.message || 'Не удалось загрузить прогноз')
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [companyId, locationId])

  useEffect(() => {
    const ac = new AbortController()
    void load(ac.signal)
    return () => ac.abort()
  }, [load])

  // Summary counts
  const counts = {
    critical: forecast.filter(f => f.status === 'critical').length,
    warning: forecast.filter(f => f.status === 'warning').length,
    low: forecast.filter(f => f.status === 'low').length,
    ok: forecast.filter(f => f.status === 'ok').length,
    no_sales: forecast.filter(f => f.status === 'no_sales').length,
  }

  const filteredBase = useMemo(
    () => (filterStatus === 'all' ? forecast : forecast.filter(f => f.status === filterStatus)),
    [forecast, filterStatus],
  )

  const columns = useMemo<SortColumns<ForecastItem, SortKey>>(() => ({
    name: { get: (r) => r.name, defaultDir: 'asc' },
    category: { get: (r) => r.category, defaultDir: 'asc' },
    balance: { get: (r) => r.balance, defaultDir: 'desc' },
    velocity: { get: (r) => r.daily_velocity, defaultDir: 'desc' },
    // Меньше дней — срочнее: по умолчанию сверху те, что кончатся раньше.
    days: { get: (r) => r.days_left, defaultDir: 'asc' },
  }), [])
  const { sort, toggle, sortedRows: filtered } = useTableSort<ForecastItem, SortKey>({
    storageKey: 'store.forecast.tableSort',
    columns,
    initial: { key: 'days', dir: 'asc' },
    rows: filteredBase,
  })

  const filterButtons: { key: FilterStatus; label: string; count?: number; tone?: string }[] = [
    { key: 'all', label: 'Все', count: forecast.length },
    { key: 'critical', label: 'Критично', count: counts.critical },
    { key: 'warning', label: 'Мало', count: counts.warning },
    { key: 'low', label: 'Скоро', count: counts.low },
    { key: 'ok', label: 'В порядке', count: counts.ok },
    { key: 'no_sales', label: 'Нет продаж', count: counts.no_sales },
  ]

  const urgent = counts.critical + counts.warning
  const hdrActions = (
    <>
      {companies.length > 0 && !storeCompanyId && (
        <NativeSelect value={companyId} onChange={e => setCompanyId(e.target.value)} className="w-full rounded-xl sm:w-[180px]">
          <option value="">Все компании</option>
          {companies.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </NativeSelect>
      )}
      {locations.length > 0 && (
        <NativeSelect value={locationId} onChange={e => setLocationId(e.target.value)} className="w-full rounded-xl sm:w-[180px]">
          <option value="">Все локации</option>
          {locations.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
        </NativeSelect>
      )}
      <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
        <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
        Обновить
      </Button>
    </>
  )

  return (
    <div className={embedded ? 'space-y-5' : 'app-page-wide space-y-5'}>
      {embedded ? (
        <div className="flex flex-wrap items-center justify-end gap-2">{hdrActions}</div>
      ) : (
        <AdminPageHeader
          title="Прогноз остатков"
          description="На сколько дней хватит каждого товара — по продажам за последние 30 дней"
          icon={<PackageSearch className="h-5 w-5" />}
          accent="emerald"
          backHref="/store"
          actions={hdrActions}
        />
      )}

      {/* Что скоро закончится */}
      <StoreAttention
        loading={loading && forecast.length === 0}
        calm={<><span className="font-semibold">Запаса хватает.</span> Ни один ходовой товар не закончится в ближайшую неделю.</>}
        items={[
          ...(counts.critical > 0 ? [{ key: 'crit', icon: AlertTriangle, tone: 'rose' as const, text: `${counts.critical} ${plural(counts.critical, ['товар закончится', 'товара закончатся', 'товаров закончатся'])} в ближайшие 3 дня`, onClick: () => setFilterStatus('critical'), cta: 'Показать' }] : []),
          ...(counts.warning > 0 ? [{ key: 'warn', icon: TrendingDown, tone: 'amber' as const, text: `${counts.warning} ${plural(counts.warning, ['товар закончится', 'товара закончатся', 'товаров закончатся'])} в течение недели`, onClick: () => setFilterStatus('warning'), cta: 'Показать' }] : []),
        ]}
      />

      <StoreStatGrid cols={4}>
        <StoreStat label="Критично · до 3 дн" value={counts.critical} tone={counts.critical > 0 ? 'rose' : 'neutral'} icon={<AlertTriangle className="h-4 w-4" />} loading={loading && forecast.length === 0} />
        <StoreStat label="Мало · до 7 дн" value={counts.warning} tone={counts.warning > 0 ? 'amber' : 'neutral'} icon={<TrendingDown className="h-4 w-4" />} loading={loading && forecast.length === 0} />
        <StoreStat label="Скоро · до 14 дн" value={counts.low} tone={counts.low > 0 ? 'sky' : 'neutral'} icon={<Clock className="h-4 w-4" />} loading={loading && forecast.length === 0} />
        <StoreStat label="В порядке · больше 14" value={counts.ok} tone={counts.ok > 0 ? 'emerald' : 'neutral'} icon={<CheckCircle2 className="h-4 w-4" />} loading={loading && forecast.length === 0} />
      </StoreStatGrid>

      {/* Фильтр по срочности */}
      <div className="flex gap-1.5 overflow-x-auto pb-1">
        {filterButtons.map(btn => (
          <button
            key={btn.key}
            type="button"
            onClick={() => setFilterStatus(btn.key)}
            className={`shrink-0 rounded-full px-3 py-1.5 text-sm font-medium transition-colors ${
              filterStatus === btn.key
                ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-200'
                : 'bg-slate-100 text-muted-foreground hover:bg-slate-200 hover:text-foreground dark:bg-white/5 dark:hover:bg-white/10'
            }`}
          >
            {btn.label}
            <span className="ml-1.5 text-xs tabular-nums opacity-70">{btn.count}</span>
          </button>
        ))}
      </div>

      {error && (
        <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-200">
          {error}
        </div>
      )}

      <StoreSection
        title="Прогноз по товарам"
        subtitle={urgent > 0 ? `Сверху те, что закончатся раньше всех` : 'Отсортировано по срочности'}
        action={<StoreChip>{filtered.length}</StoreChip>}
        flush
      >
        {loading && forecast.length === 0 ? (
          <div className="space-y-2 p-4">
            {Array.from({ length: 8 }).map((_, i) => <Skeleton key={i} className="h-9 w-full" />)}
          </div>
        ) : filtered.length === 0 ? (
          <StoreEmpty
            icon={<PackageSearch className="h-5 w-5" />}
            title={forecast.length === 0 ? 'Прогноз пока не из чего считать' : 'В этой группе товаров нет'}
            hint={forecast.length === 0 ? 'Нужны продажи за последние 30 дней и остатки на витрине. Они появятся, как только касса начнёт пробивать чеки.' : 'Выберите другую срочность выше.'}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead>
                <tr className="border-b border-border text-[11px] uppercase tracking-wider text-muted-foreground [&_th]:px-4 [&_th]:py-2.5 [&_th]:font-medium">
                  <SortableTh label="Товар" sortKey="name" sort={sort} onSort={toggle} />
                  <SortableTh label="Категория" sortKey="category" sort={sort} onSort={toggle} />
                  <SortableTh label="Остаток" sortKey="balance" sort={sort} onSort={toggle} align="right" />
                  <SortableTh label="Продаж/день" sortKey="velocity" sort={sort} onSort={toggle} align="right" />
                  <SortableTh label="Хватит дней" sortKey="days" sort={sort} onSort={toggle} align="right" />
                  <th className="text-center">Статус</th>
                  <th className="text-right" />
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filtered.map(item => (
                  <tr key={item.item_id} className="transition-colors hover:bg-slate-50 dark:hover:bg-white/[0.03]">
                    <td className="px-4 py-2.5 font-medium text-foreground">{item.name}</td>
                    <td className="px-4 py-2.5 text-muted-foreground">{item.category || '—'}</td>
                    <td className={`px-4 py-2.5 text-right tabular-nums ${item.balance > 0 ? 'text-foreground' : 'text-slate-400'}`}>{item.balance.toLocaleString('ru-RU')}</td>
                    <td className={`px-4 py-2.5 text-right tabular-nums ${item.daily_velocity > 0 ? 'text-body' : 'text-slate-400'}`}>
                      {item.daily_velocity > 0 ? item.daily_velocity.toFixed(2) : '0'}
                    </td>
                    <td className={`px-4 py-2.5 text-right tabular-nums ${daysLeftColor(item.days_left)}`}>
                      {item.days_left === null ? <span className="text-xs font-normal text-slate-400">нет данных</span> : item.days_left}
                    </td>
                    <td className="px-4 py-2.5 text-center">
                      <StoreChip tone={statusTone(item.status)}>{statusLabel(item.status)}</StoreChip>
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      <Button asChild variant="outline" size="xs">
                        <Link href={`/store/requests?item_id=${item.item_id}`}>Заявка</Link>
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </StoreSection>
    </div>
  )
}
