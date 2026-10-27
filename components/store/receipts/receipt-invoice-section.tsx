'use client'

import { Loader2, Sparkles } from 'lucide-react'

import { useCapabilities } from '@/lib/client/use-capabilities'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ReceiptAiResult } from '@/components/store/receipts/receipt-ai-result'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'
import type { InventoryItem } from '@/components/store/receipts/types'

/** Файл накладной (обязателен) + запуск ИИ-распознавания и его результат. */
export function ReceiptInvoiceSection({ form, items }: { form: ReceiptForm; items: InventoryItem[] }) {
  const { can } = useCapabilities()
  const canAiParse = can('store-receipts.ai_parse')
  const { uploadInvoice, uploadingInvoice, invoiceFileUrl, aiParsing, runAiInvoiceParse } = form

  return (
    <div data-tour="receipt-invoice" className="space-y-2 rounded-2xl border border-amber-500/25 bg-amber-500/[0.06] p-3">
      <Label>Файл накладной (обязательно)</Label>
      <div className="flex flex-wrap items-center gap-2">
        <Input
          type="file"
          accept="image/jpeg,image/png,image/webp,application/pdf"
          onChange={(event) => void uploadInvoice(event.target.files?.[0] || null)}
          disabled={uploadingInvoice}
        />
        {uploadingInvoice ? <Loader2 className="h-4 w-4 animate-spin text-amber-600 dark:text-amber-300" /> : null}
        {canAiParse && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="gap-1.5"
            onClick={() => void runAiInvoiceParse()}
            disabled={!invoiceFileUrl || uploadingInvoice || aiParsing}
          >
            {aiParsing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            Распознать ИИ
          </Button>
        )}
      </div>
      {invoiceFileUrl ? (
        <a href={invoiceFileUrl} target="_blank" rel="noreferrer" className="text-xs text-emerald-700 dark:text-emerald-300 underline">
          Накладная загружена — открыть файл
        </a>
      ) : (
        <p className="text-xs text-amber-700 dark:text-amber-200">Без загруженной накладной приемка не будет проведена.</p>
      )}
      <ReceiptAiResult form={form} items={items} />
    </div>
  )
}
