'use client'

import { Check } from 'lucide-react'

import { StoreChip, StoreEmpty, StoreSection } from '@/components/store/store-ui'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { formatMoney as money } from '@/lib/core/format'
import { fmt, type ReportRow } from './types'

/** Подсчёт в процессе / исторический акт: что посчитано и (по праву) расхождения. */
export function CountProgress({
  rows,
  isOpen,
  canSeeVariance,
  showVariance,
  revealVariance,
  onToggleReveal,
  totals,
  itemBusy,
  onResolve,
  onRecount,
}: {
  rows: ReportRow[]
  isOpen: boolean
  canSeeVariance: boolean
  showVariance: boolean
  revealVariance: boolean
  onToggleReveal: () => void
  totals: { short: number; surplus: number; shortValue: number; surplusValue: number }
  itemBusy: string | null
  onResolve: (itemId: string, qty: number) => void
  onRecount: (itemId: string) => void
}) {
  const { can } = useCapabilities()

  return (
    <StoreSection
      title={isOpen ? 'Что уже посчитано' : 'Расхождение'}
      action={
        <div className="flex flex-wrap items-center justify-end gap-x-4 gap-y-1">
          {isOpen && canSeeVariance ? (
            <button
              type="button"
              onClick={onToggleReveal}
              className={`rounded-lg border px-2 py-0.5 text-[11px] transition ${revealVariance ? 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300' : 'border-border text-muted-foreground hover:text-foreground'}`}
              title="Пока идёт счёт, расхождения скрыты: так считающий не подгоняет факт под учёт"
            >
              {revealVariance ? 'Скрыть расхождения' : 'Показать расхождения'}
            </button>
          ) : null}
          {showVariance ? (
            <div className="flex flex-wrap items-center justify-end gap-x-4 gap-y-1 text-xs tabular-nums">
              <span className="text-rose-400">
                недостача {fmt(totals.short)} шт
                {totals.shortValue > 0 ? <span className="ml-1.5 font-semibold">−{money(totals.shortValue)}</span> : null}
              </span>
              <span className="text-emerald-400">
                излишек {fmt(totals.surplus)} шт
                {totals.surplusValue > 0 ? <span className="ml-1.5 font-semibold">+{money(totals.surplusValue)}</span> : null}
              </span>
            </div>
          ) : null}
        </div>
      }
    >
      {isOpen ? (
        <p className="mb-3 text-xs text-muted-foreground">
          {showVariance
            ? 'Предварительные расхождения по посчитанным позициям: учёт на момент старта минус факт. Продажи и приходы, прошедшие во время счёта, будут учтены при закрытии — итог может измениться.'
            : 'Зелёная галочка — позиция уже посчитана. Недостачу/излишек посчитаем при закрытии акта (учтём продажи, что прошли во время счёта).'}
        </p>
      ) : null}
      {rows.length === 0 ? (
        <StoreEmpty title="Пока ничего не посчитано." />
      ) : (
        <div className="space-y-1">
          {rows
            .slice()
            .sort((a, b) => (b.conflict ? 1 : 0) - (a.conflict ? 1 : 0) || Math.abs(b.variance) - Math.abs(a.variance))
            .map((r) =>
              r.conflict && isOpen ? (
                <div key={r.item_id} className="border-b border-slate-100 dark:border-white/5 py-2 last:border-0">
                  <div className="flex items-center justify-between gap-2 text-sm">
                    <span className="min-w-0 truncate text-foreground">{r.name}</span>
                    <StoreChip tone="rose">расхождение</StoreChip>
                  </div>
                  {can('store-revisions.edit') && (
                    <div className="mt-1.5 flex flex-wrap items-center gap-2">
                      {(r.counts || []).map((c: any, i: number) => (
                        <button key={i} type="button" onClick={() => onResolve(r.item_id, c.qty)} disabled={!!itemBusy} title="Принять это значение" className="rounded border border-border px-2 py-1 text-xs tabular-nums text-foreground transition hover:border-amber-400/40 hover:text-amber-700 dark:hover:text-amber-300 disabled:opacity-50">
                          {c.by || 'счёт'}: {fmt(c.qty)}
                        </button>
                      ))}
                      <button type="button" onClick={() => onRecount(r.item_id)} disabled={!!itemBusy} className="rounded border border-amber-500/30 px-2 py-1 text-xs text-amber-700 dark:text-amber-300 transition hover:bg-amber-500/10 disabled:opacity-50">на пересчёт</button>
                    </div>
                  )}
                </div>
              ) : (
                <div key={r.item_id} className="flex items-center justify-between gap-3 border-b border-slate-100 dark:border-white/5 py-1.5 text-sm last:border-0">
                  <span className="min-w-0 truncate text-foreground">{r.name}</span>
                  <div className="flex items-center gap-4 tabular-nums">
                    {!isOpen || showVariance ? <span className="text-xs text-muted-foreground">сист. {fmt(r.expected)}</span> : null}
                    <span className="text-xs text-muted-foreground">факт {fmt(r.counted)}</span>
                    {isOpen && !showVariance ? (
                      // Слепая ревизия: пока акт открыт — расхождение не показываем
                      // (как и обещает заголовок). Это лишь отметка «посчитано».
                      <span className="flex w-16 items-center justify-end text-emerald-500"><Check className="h-4 w-4" /></span>
                    ) : (
                      <>
                        {r.variance !== 0 && Number(r.purchase_price || 0) > 0 ? (
                          <span className={`text-[11px] ${r.variance < 0 ? 'text-rose-400/80' : 'text-emerald-400/80'}`}>
                            {r.variance < 0 ? '−' : '+'}{money(Math.abs(r.variance) * Number(r.purchase_price || 0))}
                          </span>
                        ) : null}
                        <span className={`w-16 text-right font-medium ${r.variance < 0 ? 'text-rose-400' : r.variance > 0 ? 'text-emerald-400' : 'text-muted-foreground'}`}>{r.variance > 0 ? '+' : ''}{fmt(r.variance)}</span>
                      </>
                    )}
                  </div>
                </div>
              ),
            )}
        </div>
      )}
    </StoreSection>
  )
}
