'use client'

import { ClipboardList, Plus, RefreshCw } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { CardSkeleton } from '@/components/skeleton'
import { Button } from '@/components/ui/button'
import { StoreAttention, StoreChip, StoreEmpty, StoreSection, plural, storeCard } from '@/components/store/store-ui'
import { formatMoney as money } from '@/lib/core/format'
import { fmtDate, pairedAct, type ActListRow } from './types'

/** Вид «Список актов»: очередь открытых + карточки всех актов. */
export function ActsList({
  acts,
  loading,
  error,
  onRefresh,
  onNew,
  onOpen,
}: {
  acts: ActListRow[]
  loading: boolean
  error: string | null
  onRefresh: () => void
  onNew: () => void
  onOpen: (id: string) => void
}) {
  const openActs = acts.filter((a) => a.status === 'open')

  return (
    <div className="app-page-wide space-y-4">
      <AdminPageHeader
        title="Аудит-ревизии"
        description="Слепой подсчёт несколькими операторами по секциям"
        icon={<ClipboardList className="h-5 w-5" />}
        accent="emerald"
        backHref="/store/revisions"
        actions={
          <>
            <Button variant="outline" size="sm" onClick={onRefresh} className="h-9 gap-1.5">
              <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
              Обновить
            </Button>
            <Button size="sm" onClick={onNew} className="h-9 gap-1.5 bg-amber-600 hover:bg-amber-700">
              <Plus className="h-3.5 w-3.5" />
              Новый акт
            </Button>
          </>
        }
      />

      {error ? <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-300">{error}</div> : null}

      <StoreAttention
        loading={loading}
        calm={<><span className="font-semibold">Открытых актов нет.</span> Все подсчёты закрыты.</>}
        items={openActs.length > 0 ? [{
          key: 'open',
          icon: ClipboardList,
          tone: 'amber',
          text: `${openActs.length} ${plural(openActs.length, ['акт', 'акта', 'актов'])} в работе: подсчёт идёт, итог ещё не закрыт`,
          onClick: () => { const first = acts.find((a) => a.status === 'open'); if (first) onOpen(first.id) },
          cta: 'Открыть',
        }] : []}
      />

      {loading ? (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 3 }).map((_, i) => <CardSkeleton key={i} rows={3} />)}
        </div>
      ) : acts.length === 0 ? (
        <StoreSection title="Аудит-акты" flush>
          <StoreEmpty
            icon={<ClipboardList className="h-5 w-5" />}
            title="Аудит-актов пока нет"
            hint="Аудит — слепой подсчёт: операторы считают товар по секциям и не видят остатков системы. После закрытия акта появятся недостачи и излишки."
            action={<Button size="sm" onClick={onNew} className="gap-1.5 bg-amber-600 hover:bg-amber-700"><Plus className="h-3.5 w-3.5" /> Создать первый акт</Button>}
          />
        </StoreSection>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {acts.map((a) => {
            const pct = a.totalItems ? Math.round((a.countedItems / a.totalItems) * 100) : 0
            const barCls = a.status === 'closed' ? 'bg-emerald-500' : a.status === 'cancelled' ? 'bg-red-400/70' : 'bg-amber-500'
            return (
              <button key={a.id} type="button" onClick={() => onOpen(a.id)} className="text-left">
                <div className={`${storeCard} flex h-full flex-col gap-3 p-4 transition hover:-translate-y-0.5 hover:border-amber-400/50 hover:shadow-md`}>
                  <div className="flex items-start justify-between gap-2">
                    <span className="min-w-0 truncate text-sm font-semibold text-foreground">{a.locationName}</span>
                    <StoreChip tone={a.status === 'open' ? 'amber' : a.status === 'closed' ? 'neutral' : 'rose'}>
                      {a.status === 'open' ? 'Идёт подсчёт' : a.status === 'closed' ? 'Закрыт' : 'Отменён'}
                    </StoreChip>
                  </div>
                  <div className="text-xs text-muted-foreground">
                    {fmtDate(a.opened_at)}{a.comment ? ` · ${a.comment}` : ''}
                  </div>
                  <div className="mt-auto space-y-1.5">
                    <div className="flex items-center justify-between text-xs">
                      <span className="tabular-nums text-muted-foreground">{a.countedItems} / {a.totalItems} поз.</span>
                      <span className="tabular-nums font-semibold text-foreground">{pct}%</span>
                    </div>
                    <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-white/10">
                      <div className={`h-full rounded-full ${barCls} transition-all`} style={{ width: `${Math.min(100, pct)}%` }} />
                    </div>
                    {a.resultValue != null ? (
                      <div className="space-y-0.5 pt-1 text-xs">
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-muted-foreground">Итог по закупу</span>
                          <span className={`tabular-nums font-semibold ${a.resultValue < -0.5 ? 'text-rose-600 dark:text-rose-300' : 'text-foreground'}`}>{money(Math.round(a.resultValue))}</span>
                        </div>
                        {a.zeroedValue && Math.abs(a.zeroedValue) >= 1 ? (
                          <div className="flex items-center justify-between gap-2 text-muted-foreground">
                            <span>из них обнулено непосчитанное</span>
                            <span className="tabular-nums">{money(Math.round(a.zeroedValue))}</span>
                          </div>
                        ) : null}
                        {(() => {
                          const pair = pairedAct(a, acts)
                          if (!pair || pair.resultValue == null) return null
                          return (
                            <div className="flex items-center justify-between gap-2 text-muted-foreground">
                              <span>по точке с {pair.locationType === 'warehouse' ? 'складом' : 'витриной'} {fmtDate(pair.closed_at).split(',')[0]}</span>
                              <span className="tabular-nums font-medium text-foreground">{money(Math.round(a.resultValue + pair.resultValue))}</span>
                            </div>
                          )
                        })()}
                      </div>
                    ) : null}
                  </div>
                </div>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
