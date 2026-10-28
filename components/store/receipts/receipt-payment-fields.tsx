'use client'

import { Loader2 } from 'lucide-react'

import { DatePicker } from '@/components/ui/date-picker'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'

/** Оплата приёмки: сразу (способ + чек) или в долг / под реализацию. Ячейки сетки `md:grid-cols-2`. */
export function ReceiptPaymentFields({ form }: { form: ReceiptForm }) {
  const {
    paymentMode, setPaymentMode, paymentMethod, setPaymentMethod,
    uploadPaymentReceipt, uploadingPaymentReceipt, paymentReceiptFileUrl,
    dueDate, setDueDate, isConsignment, setIsConsignment,
  } = form

  return (
    <>
      <div className="space-y-1.5 md:col-span-2">
        <Label>Оплата</Label>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setPaymentMode('now')}
            className={`px-3 py-1.5 rounded-lg text-xs border ${paymentMode === 'now' ? 'bg-emerald-500/15 border-emerald-500/40 text-emerald-700 dark:text-emerald-200' : 'border-border text-muted-foreground hover:bg-surface-muted'}`}
          >
            Сразу
          </button>
          <button
            type="button"
            onClick={() => setPaymentMode('deferred')}
            className={`px-3 py-1.5 rounded-lg text-xs border ${paymentMode === 'deferred' ? 'bg-amber-500/15 border-amber-500/40 text-amber-700 dark:text-amber-200' : 'border-border text-muted-foreground hover:bg-surface-muted'}`}
          >
            В долг / Под реализацию
          </button>
        </div>
      </div>

      {paymentMode === 'now' ? (
        <>
          <div className="space-y-1.5">
            <Label>Способ оплаты</Label>
            <NativeSelect value={paymentMethod} onChange={(event) => setPaymentMethod(event.target.value === 'kaspi' ? 'kaspi' : 'cash')}>
              <option value="cash">Наличные</option>
              <option value="kaspi">Безналичный</option>
            </NativeSelect>
          </div>
          <div className="space-y-1.5">
            <Label>Чек об оплате (обязательно)</Label>
            <div className="flex flex-wrap items-center gap-2">
              <Input
                type="file"
                accept="image/jpeg,image/png,image/webp,application/pdf"
                onChange={(event) => void uploadPaymentReceipt(event.target.files?.[0] || null)}
                disabled={uploadingPaymentReceipt}
              />
              {uploadingPaymentReceipt ? <Loader2 className="h-4 w-4 animate-spin text-blue-600 dark:text-blue-300" /> : null}
            </div>
            {paymentReceiptFileUrl ? (
              <a href={paymentReceiptFileUrl} target="_blank" rel="noreferrer" className="text-xs text-blue-700 dark:text-blue-300 underline">
                Чек загружен — открыть файл
              </a>
            ) : (
              <p className="text-xs text-muted-foreground">Этот чек попадет в расход (не накладная).</p>
            )}
          </div>
        </>
      ) : (
        <>
          <div className="space-y-1.5">
            <Label>Срок оплаты (опционально)</Label>
            <DatePicker value={dueDate} onChange={setDueDate} />
          </div>
          <div className="space-y-1.5 flex items-end">
            <label className="flex items-center gap-2 text-sm cursor-pointer">
              <input
                type="checkbox"
                className="h-4 w-4 rounded border-slate-300 dark:border-white/20 bg-transparent"
                checked={isConsignment}
                onChange={(event) => setIsConsignment(event.target.checked)}
              />
              Под реализацию (consignment)
            </label>
          </div>
          <p className="text-xs text-muted-foreground md:col-span-2">
            Расход не создается. Долг повиснет в разделе «Долги и накладные» и закроется при оплате.
          </p>
        </>
      )}
    </>
  )
}
