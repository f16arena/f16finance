'use client'

import { Loader2, PackagePlus } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { formatMoney } from '@/lib/core/format'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'

/** Итог и «Провести» всегда на виду: форма длинная, сумму не надо искать внизу. */
export function ReceiptFormFooter({ form, error }: { form: ReceiptForm; error: string | null }) {
  const { returnTotal, receiptTotal, saveDraft, saving, loading } = form

  return (
    <div className="sticky -bottom-4 z-10 -mx-5 -mb-4 space-y-2 border-t border-border bg-card/95 px-5 py-3 backdrop-blur sm:-mx-6 sm:px-6">
      {error ? (
        <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2 text-sm text-rose-700 dark:text-rose-300">{error}</div>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          {returnTotal > 0 ? (
            <>
              <div className="flex flex-wrap gap-x-4 text-xs text-muted-foreground">
                <span>По накладной: <span className="text-foreground tabular-nums">{formatMoney(receiptTotal + returnTotal)}</span></span>
                <span>Возврат: <span className="text-rose-700 dark:text-rose-300 tabular-nums">−{formatMoney(returnTotal)}</span></span>
              </div>
              <p className="text-[11px] uppercase tracking-wide text-muted-foreground">К приёмке (на склад и долг)</p>
            </>
          ) : (
            <p className="text-xs text-muted-foreground">Сумма приёмки</p>
          )}
          <p className="text-2xl font-semibold leading-tight text-foreground tabular-nums">{formatMoney(receiptTotal)}</p>
        </div>
        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row">
          <Button type="button" variant="outline" onClick={() => void saveDraft()}>
            Сохранить как черновик
          </Button>
          <Button data-tour="receipt-submit" type="submit" disabled={saving || loading}>
            {saving ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <PackagePlus className="mr-2 h-4 w-4" />}
            Провести приёмку
          </Button>
        </div>
      </div>
    </div>
  )
}
