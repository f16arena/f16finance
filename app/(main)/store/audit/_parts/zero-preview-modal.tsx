'use client'

import type { Dispatch, SetStateAction } from 'react'
import { Loader2, Lock } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { formatMoney as money } from '@/lib/core/format'
import { fmt, type Detail } from './types'

/** Предпросмотр жёсткого закрытия: что будет обнулено (выборочно, по галочкам). */
export function ZeroPreviewModal({
  open,
  detail,
  zeroSelected,
  setZeroSelected,
  assignDebtZeroed,
  closing,
  onClose,
  onConfirm,
}: {
  open: boolean
  detail: Detail
  zeroSelected: Set<string>
  setZeroSelected: Dispatch<SetStateAction<Set<string>>>
  assignDebtZeroed: boolean
  closing: boolean
  onClose: () => void
  onConfirm: () => void
}) {
  const { can } = useCapabilities()
  const uncountedRows = detail.uncounted || []
  const selectedRows = uncountedRows.filter((r) => zeroSelected.has(r.item_id))
  const zeroSum = selectedRows.reduce((s, r) => s + r.expected * (r.purchase_price || 0), 0)
  const keptSold = uncountedRows.filter((r) => (r.sold_recent || 0) > 0 && !zeroSelected.has(r.item_id)).length
  const otherLabel = detail.otherLocationType === 'warehouse' ? 'на складе' : 'на витрине'
  const toggle = (id: string) =>
    setZeroSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  return (
    <AppModal
      open={open}
      onClose={() => { if (!closing) onClose() }}
      disableBackdropClose={closing}
      tone="danger"
      maxWidth="max-w-2xl"
      title="Полная ревизия: обнуление непосчитанного"
    >
      <div className="space-y-3">
        <div>
          <p className="text-xs text-muted-foreground">
            Эти позиции никто не посчитал. У отмеченных остаток в системе станет <b>0</b>, остальные останутся как есть.
            Будет обнулено <b className="text-foreground">{selectedRows.length} из {uncountedRows.length} поз.</b> на{' '}
            <b className="text-rose-500">{money(zeroSum)}</b> по закупу.
            {assignDebtZeroed ? ' Сумма ляжет долгом поровну на операторов акта.' : ' В долг операторам это не пойдёт.'}
          </p>
          {keptSold > 0 ? (
            <p className="mt-1 text-xs text-amber-700 dark:text-amber-300">
              {keptSold} поз. продавались за последние 7 дней — скорее всего они есть, поэтому отметка с них снята. Лучше досчитать их.
            </p>
          ) : null}
        </div>
        <div className="flex gap-3 text-xs">
          <button type="button" className="text-muted-foreground underline-offset-2 hover:underline" onClick={() => setZeroSelected(new Set(uncountedRows.map((r) => r.item_id)))}>Отметить все</button>
          <button type="button" className="text-muted-foreground underline-offset-2 hover:underline" onClick={() => setZeroSelected(new Set())}>Снять все</button>
        </div>
        <div className="max-h-72 overflow-auto rounded-lg border border-border">
          {uncountedRows.slice(0, 300).map((r) => (
            <label key={r.item_id} className="flex cursor-pointer items-center justify-between gap-3 border-b border-slate-100 dark:border-white/5 px-3 py-1.5 text-sm last:border-0">
              <span className="flex min-w-0 items-center gap-2">
                <input type="checkbox" checked={zeroSelected.has(r.item_id)} onChange={() => toggle(r.item_id)} className="h-4 w-4 shrink-0 accent-rose-500" />
                <span className="min-w-0">
                  <span className="block truncate text-foreground">{r.name}</span>
                  {(r.sold_recent || 0) > 0 || (r.other_location_qty || 0) > 0 ? (
                    <span className="block truncate text-xs text-amber-700 dark:text-amber-300">
                      {[
                        (r.sold_recent || 0) > 0 ? `продано ${fmt(r.sold_recent || 0)} шт за 7 дней` : null,
                        (r.other_location_qty || 0) > 0 ? `${otherLabel} ${fmt(r.other_location_qty || 0)} шт` : null,
                      ].filter(Boolean).join(' · ')}
                    </span>
                  ) : null}
                </span>
              </span>
              <span className="shrink-0 tabular-nums text-xs text-muted-foreground">
                {fmt(r.expected)} шт{r.purchase_price ? ` · ${money(r.expected * r.purchase_price)}` : ''}
              </span>
            </label>
          ))}
          {uncountedRows.length > 300 ? (
            <div className="px-3 py-2 text-center text-xs text-muted-foreground">…и ещё {uncountedRows.length - 300} поз. (обнуляются по отметке «Отметить все»)</div>
          ) : null}
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={closing}>Отмена</Button>
          {can('store-revisions.commit') && (
            <Button onClick={onConfirm} disabled={closing} className="gap-2 bg-rose-600 hover:bg-rose-700">
              {closing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Lock className="h-4 w-4" />}
              {selectedRows.length > 0 ? `Обнулить ${selectedRows.length} поз. и провести` : 'Провести без обнуления'}
            </Button>
          )}
        </div>
      </div>
    </AppModal>
  )
}
