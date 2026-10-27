'use client'

import { PackagePlus } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { ReceiptBasicsFields } from '@/components/store/receipts/receipt-basics-fields'
import { ReceiptBulkTools } from '@/components/store/receipts/receipt-bulk-tools'
import { ReceiptFormFooter } from '@/components/store/receipts/receipt-form-footer'
import { ReceiptInvoiceSection } from '@/components/store/receipts/receipt-invoice-section'
import { ReceiptLineRow } from '@/components/store/receipts/receipt-line-row'
import { ReceiptPaymentFields } from '@/components/store/receipts/receipt-payment-fields'
import { ReceiptQuickAdd } from '@/components/store/receipts/receipt-quick-add'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'
import type { ReceiptsResponse } from '@/components/store/receipts/types'

/** Окно «Новый документ приёмки»: собирает шаги формы из подкомпонентов. */
export function ReceiptFormModal({
  form,
  data,
  error,
}: {
  form: ReceiptForm
  data: ReceiptsResponse['data'] | null
  error: string | null
}) {
  const {
    formSheetOpen, setFormSheetOpen, createReceipt, comment, setComment, lines,
    catalogItems, itemsById, patchLine, removeLine, duplicateLine, addLine,
  } = form

  return (
    <AppModal
      open={formSheetOpen}
      onClose={() => setFormSheetOpen(false)}
      disableBackdropClose
      maxWidth="max-w-6xl"
      icon={<PackagePlus className="h-4.5 w-4.5" />}
      title="Новый документ приёмки"
      description="Каталог, поставщик, дата и товарные строки. Приход увеличивает общий остаток и обновляет цены."
    >
      <form onSubmit={createReceipt} className="space-y-5">
        <ReceiptQuickAdd form={form} />
        <ReceiptBulkTools form={form} />

        <div className="grid gap-4 md:grid-cols-2">
          <ReceiptBasicsFields form={form} data={data} />
          <ReceiptPaymentFields form={form} />
        </div>

        <ReceiptInvoiceSection form={form} items={catalogItems} />

        <div className="space-y-1.5">
          <Label>Комментарий</Label>
          <Textarea value={comment} onChange={(event) => setComment(event.target.value)} placeholder="Что важно по этой приемке" rows={3} />
        </div>

        <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/[0.05] px-3 py-2 text-sm text-emerald-700 dark:text-emerald-200">
          Цены применяются автоматически: закуп обновляет себестоимость, продажа и наценка синхронизируются по всем точкам.
        </div>

        <div className="space-y-3">
          {lines.map((line, idx) => (
            <ReceiptLineRow
              key={line.uid}
              line={line}
              index={idx + 1}
              items={catalogItems}
              itemsById={itemsById}
              canRemove={lines.length > 1}
              onPatch={patchLine}
              onRemove={removeLine}
              onDuplicate={duplicateLine}
            />
          ))}
        </div>

        <Button type="button" variant="outline" onClick={addLine} className="w-full sm:w-auto">
          Добавить строку
        </Button>

        <ReceiptFormFooter form={form} error={error} />
      </form>
    </AppModal>
  )
}
