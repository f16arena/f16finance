'use client'

import { Loader2 } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { formatMoney } from '@/lib/core/format'
import type { InventoryReceipt } from '@/components/store/receipts/types'

/** Причина отмены спрашивается в окне приложения, а не в системном prompt(). */
export function ReceiptCancelModal({
  target,
  reason,
  busy,
  onReason,
  onClose,
  onConfirm,
}: {
  target: InventoryReceipt | null
  reason: string
  busy: boolean
  onReason: (value: string) => void
  onClose: () => void
  onConfirm: () => void
}) {
  return (
    <AppModal
      open={!!target}
      onClose={() => { if (!busy) onClose() }}
      disableBackdropClose={busy}
      tone="danger"
      title="Отменить приёмку?"
      description={target ? `От ${target.received_at} на сумму ${formatMoney(Number(target.total_amount || 0))}. Остатки и долг поставщику откатятся.` : undefined}
      maxWidth="max-w-md"
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose} disabled={busy}>Не отменять</Button>
          <Button variant="destructive" onClick={onConfirm} disabled={busy}>
            {busy ? <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" /> : null}
            Отменить приёмку
          </Button>
        </div>
      }
    >
      <div className="space-y-1.5">
        <Label>Причина (необязательно)</Label>
        <Textarea value={reason} onChange={(e) => onReason(e.target.value)} rows={3} placeholder="Например: ошиблись поставщиком" />
      </div>
    </AppModal>
  )
}
