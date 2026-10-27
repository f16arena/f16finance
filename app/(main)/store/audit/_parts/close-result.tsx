'use client'

import { Check } from 'lucide-react'

import { StoreEmpty, StoreSection, StoreStat } from '@/components/store/store-ui'
import { formatMoney as money } from '@/lib/core/format'
import { fmt, type CloseRow, type CloseSummary } from './types'

/** Результат ревизии после закрытия: недостача / излишек / обнулено и расхождения по позициям. */
export function CloseResult({ report, summary }: { report: CloseRow[]; summary: CloseSummary | null }) {
  const hasZeroed = (summary?.zeroedItems || 0) > 0

  return (
    <StoreSection title="Результат ревизии">
      <div className={`grid grid-cols-2 gap-2 ${hasZeroed ? 'sm:grid-cols-3' : ''}`}>
        <StoreStat
          label="Недостача"
          tone="rose"
          value={money(summary?.shrinkageValue || 0)}
          hint={`${fmt(summary?.shrinkageQty || 0)} шт · ${summary?.shrinkageItems || 0} поз.`}
        />
        <StoreStat
          label="Излишек"
          tone="emerald"
          value={money(summary?.surplusValue || 0)}
          hint={`${fmt(summary?.surplusQty || 0)} шт · ${summary?.surplusItems || 0} поз.`}
        />
        {hasZeroed ? (
          <StoreStat
            label="Обнулено (не посчитано)"
            value={money(summary?.zeroedValue || 0)}
            hint={`${fmt(summary?.zeroedQty || 0)} шт · ${summary?.zeroedItems || 0} поз.`}
          />
        ) : null}
      </div>

      {/* Сводка: движения во время ревизии учтены в факте, не считаются пропажей */}
      {summary && summary.movedItems > 0 ? (
        <div className="mt-3 rounded-md border border-sky-500/30 bg-sky-500/10 px-3 py-2 text-xs text-sky-700 dark:text-sky-300">
          Во время ревизии были движения по {summary.movedItems} поз.
          {summary.movedIn > 0 ? ` приход +${fmt(summary.movedIn)}` : ''}
          {summary.movedOut > 0 ? ` продажи −${fmt(summary.movedOut)}` : ''}
          {' '}— учтены в итоге, не записаны как недостача.
        </div>
      ) : null}

      {report.length === 0 ? (
        <StoreEmpty title="Нет данных." />
      ) : (
        <div className="mt-3 space-y-1">
          {report
            .slice()
            .sort((a, b) => (b.shrinkage + b.surplus) - (a.shrinkage + a.surplus) || Math.abs(b.variance) - Math.abs(a.variance))
            .map((r) => {
              const moved = r.movedIn > 0 || r.movedOut > 0
              const diff = r.zeroed ? 'zeroed' : r.shrinkage > 0 ? 'short' : r.surplus > 0 ? 'surplus' : 'ok'
              return (
                <div
                  key={r.item_id}
                  className={`flex flex-col gap-1 rounded-lg border px-3 py-2 sm:flex-row sm:items-center sm:justify-between sm:gap-3 ${
                    diff === 'short' ? 'border-rose-500/30 bg-rose-500/[0.05]'
                    : diff === 'surplus' ? 'border-emerald-500/30 bg-emerald-500/[0.05]'
                    : diff === 'zeroed' ? 'border-slate-400/30 bg-slate-500/[0.05]'
                    : 'border-transparent opacity-55'
                  }`}
                >
                  <span className={`min-w-0 truncate text-sm ${diff === 'ok' ? 'text-muted-foreground' : 'font-medium text-foreground'}`}>{r.name}</span>
                  <div className="flex shrink-0 items-center gap-3 tabular-nums">
                    <span className="text-[11px] text-muted-foreground">{fmt(r.expected)} → <span className="text-foreground">{fmt(r.final)}</span></span>
                    {diff === 'zeroed' ? (
                      <span className="flex items-center gap-2">
                        {r.purchase_price ? <span className="text-[11px] tabular-nums text-muted-foreground">{money(r.expected * r.purchase_price)}</span> : null}
                        <span className="rounded-md bg-slate-500/15 px-2 py-0.5 text-xs font-semibold text-slate-600 dark:text-slate-300">обнулено −{fmt(r.expected)}</span>
                      </span>
                    ) : diff === 'short' ? (
                      <span className="flex items-center gap-2">
                        {r.purchase_price ? <span className="text-[11px] tabular-nums text-rose-400/80">{money(r.shrinkage * r.purchase_price)}</span> : null}
                        <span className="rounded-md bg-rose-500/15 px-2 py-0.5 text-sm font-bold text-rose-600 dark:text-rose-300">−{fmt(r.shrinkage)}</span>
                      </span>
                    ) : diff === 'surplus' ? (
                      <span className="flex items-center gap-2">
                        {r.purchase_price ? <span className="text-[11px] tabular-nums text-emerald-400/80">{money(r.surplus * r.purchase_price)}</span> : null}
                        <span className="rounded-md bg-emerald-500/15 px-2 py-0.5 text-sm font-bold text-emerald-600 dark:text-emerald-300">+{fmt(r.surplus)}</span>
                      </span>
                    ) : (
                      <span className="flex items-center gap-1 text-xs text-emerald-500/80"><Check className="h-3.5 w-3.5" /> сошлось</span>
                    )}
                  </div>
                  {moved ? (
                    <div className="text-[11px] text-sky-400/80 tabular-nums sm:basis-full sm:text-right">
                      во время ревизии:{r.movedIn > 0 ? ` приход +${fmt(r.movedIn)}` : ''}{r.movedOut > 0 ? ` продажи −${fmt(r.movedOut)}` : ''}
                    </div>
                  ) : null}
                </div>
              )
            })}
        </div>
      )}
    </StoreSection>
  )
}
