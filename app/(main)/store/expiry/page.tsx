'use client'

import { useMemo, useState } from 'react'
import { AlertTriangle, CalendarClock, Clock, PackageCheck } from 'lucide-react'
import { TableSkeleton } from '@/components/skeleton'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { useStoreApiUrl } from '@/components/store/store-scope'
import { StoreAttention, StoreChip, StoreEmpty, StoreSection, StoreStat, StoreStatGrid, plural, type StoreTodo } from '@/components/store/store-ui'
import { useApiCache } from '@/lib/client/use-api-cache'

type Row = {
  id: string
  item_name: string
  barcode: string | null
  unit: string | null
  quantity: number
  remaining: number
  production_date: string | null
  expiry_date: string
  days_left: number
  status: 'expired' | 'soon' | 'ok'
  received_at: string | null
  kind: 'supplier' | 'posting'
  location_name: string
}
type Data = { rows: Row[]; summary: { expired: number; soon: number; depleted?: number; total: number } }

const fmtDate = (s: string | null) => (s ? new Date(s).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: '2-digit' }) : '—')

function daysLabel(d: number) {
  if (d < 0) return `просрочен ${Math.abs(d)} дн.`
  if (d === 0) return 'сегодня'
  return `через ${d} дн.`
}

type Filter = 'all' | 'expired' | 'soon'

export default function StoreExpiryPage({ embedded = false }: { embedded?: boolean } = {}) {
  const storeUrl = useStoreApiUrl()
  const [filter, setFilter] = useState<Filter>('all')
  const [showDepleted, setShowDepleted] = useState(false)

  // Вкладка «Срок годности» живёт в «Документах» и размонтируется при
  // переключении — SWR-кэш возвращает на неё уже с данными.
  const { data, loading, error } = useApiCache<Data>(storeUrl('/api/admin/store/expiry'))

  const depletedCount = useMemo(() => (data?.rows || []).filter((r) => (r.remaining ?? 0) <= 0.0005).length, [data?.rows])

  const rows = useMemo(() => {
    let all = data?.rows || []
    // По FIFO партия израсходована — прячем, если не попросили показать
    if (!showDepleted) all = all.filter((r) => (r.remaining ?? 0) > 0.0005)
    if (filter === 'all') return all
    return all.filter((r) => r.status === filter)
  }, [data?.rows, filter, showDepleted])

  const expired = data?.summary.expired ?? 0
  const soon = data?.summary.soon ?? 0

  const todos: StoreTodo[] = []
  if (expired > 0) {
    todos.push({
      key: 'expired', icon: AlertTriangle, tone: 'rose',
      text: <>{expired} {plural(expired, ['партия просрочена', 'партии просрочены', 'партий просрочено'])} — спишите, пока не продали</>,
      onClick: () => setFilter('expired'), cta: 'Показать',
    })
  }
  if (soon > 0) {
    todos.push({
      key: 'soon', icon: Clock, tone: 'amber',
      text: <>{soon} {plural(soon, ['партия истекает', 'партии истекают', 'партий истекает'])} в ближайшие 14 дней — продайте или сделайте скидку</>,
      onClick: () => setFilter('soon'), cta: 'Показать',
    })
  }

  const statusChip = (r: Row) => {
    if (r.status === 'expired') return <StoreChip tone="rose"><AlertTriangle className="h-3 w-3" />{daysLabel(r.days_left)}</StoreChip>
    if (r.status === 'soon') return <StoreChip tone="amber">{daysLabel(r.days_left)}</StoreChip>
    return <span className="text-xs text-muted-foreground">{daysLabel(r.days_left)}</span>
  }

  const body = (
    <div className="space-y-4">
      <StoreAttention items={todos} loading={loading && !data} calm="Всё спокойно: просроченных партий нет и в ближайшие 14 дней ничего не истекает." />

      <StoreStatGrid cols={3}>
        <button type="button" onClick={() => setFilter('all')} className="text-left">
          <StoreStat label="Партий со сроком" value={data?.summary.total ?? 0} loading={loading && !data} />
        </button>
        <button type="button" onClick={() => setFilter('soon')} className="text-left">
          <StoreStat label="Истекают (≤14 дн.)" value={soon} tone={soon > 0 ? 'amber' : 'neutral'} loading={loading && !data} />
        </button>
        <button type="button" onClick={() => setFilter('expired')} className="text-left">
          <StoreStat label="Просрочено" value={expired} tone={expired > 0 ? 'rose' : 'neutral'} loading={loading && !data} />
        </button>
      </StoreStatGrid>

      {error ? <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-600 dark:text-rose-300">{error}</div> : null}

      <StoreSection
        title={filter === 'all' ? 'Все партии' : filter === 'soon' ? 'Скоро истекают' : 'Просроченные'}
        subtitle={filter === 'all' ? 'Отсортированы по сроку годности' : undefined}
        action={filter !== 'all' ? (
          <button type="button" onClick={() => setFilter('all')} className="text-xs font-medium text-slate-500 hover:text-foreground">Показать все</button>
        ) : undefined}
        flush
      >
        {depletedCount > 0 && (
          <label className="flex cursor-pointer items-start gap-2 border-b border-border px-4 py-2.5 text-xs text-muted-foreground">
            <input type="checkbox" className="mt-0.5 h-3.5 w-3.5 shrink-0 accent-emerald-600" checked={showDepleted} onChange={(e) => setShowDepleted(e.target.checked)} />
            <span>Показать израсходованные партии ({depletedCount}) — по FIFO их остаток уже продан или списан</span>
          </label>
        )}
        {loading && !data ? (
          <div className="p-4"><TableSkeleton rows={6} cols={6} /></div>
        ) : rows.length === 0 ? (
          filter === 'all' ? (
            <StoreEmpty icon={<PackageCheck className="h-5 w-5" />} title="Партий со сроком годности пока нет" hint="Срок указывается при приёмке или оприходовании товара — после этого партии появятся здесь." />
          ) : (
            <StoreEmpty icon={<PackageCheck className="h-5 w-5" />} title="По этому фильтру ничего нет" hint="Это хорошо: нет партий, требующих действий." />
          )
        ) : (
          <>
            {/* Телефон: карточки */}
            <div className="divide-y divide-border md:hidden">
              {rows.map((r) => {
                const depleted = (r.remaining ?? 0) <= 0.0005
                return (
                  <div key={r.id} className={`space-y-1.5 px-4 py-3 ${depleted ? 'opacity-50' : r.status === 'expired' ? 'bg-rose-500/[0.04]' : ''}`}>
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium text-foreground">{r.item_name}</div>
                        <div className="text-[11px] text-muted-foreground">{r.location_name}</div>
                      </div>
                      {depleted ? <span className="text-xs text-muted-foreground">{daysLabel(r.days_left)}</span> : statusChip(r)}
                    </div>
                    <div className="flex flex-wrap items-center justify-between gap-x-3 text-xs text-muted-foreground tabular-nums">
                      <span>
                        {depleted ? `израсходована · было ${r.quantity}` : <b className="font-semibold text-foreground">{r.remaining} из {r.quantity}</b>}{r.unit ? ` ${r.unit}` : ''}
                      </span>
                      <span>годен до {fmtDate(r.expiry_date)}</span>
                    </div>
                  </div>
                )
              })}
            </div>

            {/* Компьютер: таблица */}
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border text-left text-[10px] uppercase tracking-wider text-muted-foreground">
                    <th className="py-2 pl-4 pr-2 font-normal">Товар</th>
                    <th className="px-2 py-2 font-normal">Где</th>
                    <th className="px-2 py-2 text-right font-normal">Остаток партии</th>
                    <th className="px-2 py-2 font-normal">Изготовлен</th>
                    <th className="px-2 py-2 font-normal">Годен до</th>
                    <th className="py-2 pl-2 pr-4 font-normal">Осталось</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {rows.map((r) => {
                    const depleted = (r.remaining ?? 0) <= 0.0005
                    return (
                      <tr key={r.id} className={depleted ? 'opacity-50' : r.status === 'expired' ? 'bg-rose-500/[0.04]' : ''}>
                        <td className="py-2.5 pl-4 pr-2">
                          <div className="font-medium text-foreground">{r.item_name}</div>
                          {r.barcode ? <div className="font-mono text-[10px] tabular-nums text-muted-foreground">{r.barcode}</div> : null}
                        </td>
                        <td className="px-2 py-2.5 text-xs text-muted-foreground">{r.location_name}</td>
                        <td className="px-2 py-2.5 text-right text-xs tabular-nums">
                          {depleted ? (
                            <span className="text-muted-foreground">израсходована · было {r.quantity}{r.unit ? ` ${r.unit}` : ''}</span>
                          ) : (
                            <span className="font-medium text-foreground">{r.remaining} из {r.quantity}{r.unit ? ` ${r.unit}` : ''}</span>
                          )}
                        </td>
                        <td className="px-2 py-2.5 text-xs tabular-nums text-muted-foreground">{fmtDate(r.production_date)}</td>
                        <td className="px-2 py-2.5 text-xs tabular-nums">{fmtDate(r.expiry_date)}</td>
                        <td className="py-2.5 pl-2 pr-4">
                          {depleted ? <span className="text-xs text-muted-foreground">{daysLabel(r.days_left)}</span> : statusChip(r)}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </>
        )}
      </StoreSection>
    </div>
  )

  if (embedded) return body

  return (
    <div className="app-page-wide space-y-4">
      <AdminPageHeader
        title="Срок годности"
        description="Партии товара по сроку годности — что просрочено и что скоро истекает"
        icon={<CalendarClock className="h-5 w-5" />}
        accent="emerald"
        backHref="/store/documents"
      />
      {body}
    </div>
  )
}
