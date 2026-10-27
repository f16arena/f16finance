'use client'

import { PackagePlus } from 'lucide-react'

import { StoreSection } from '@/components/store/store-ui'
import type { InventoryReceiptDraft } from '@/components/store/receipts/types'

/** Черновики приёмки: нажатие загружает черновик в форму. */
export function ReceiptDraftsSection({
  drafts,
  canDelete,
  onApply,
  onDelete,
}: {
  drafts: InventoryReceiptDraft[]
  canDelete: boolean
  onApply: (draft: InventoryReceiptDraft) => void
  onDelete: (id: string) => void
}) {
  if (drafts.length === 0) return null
  return (
    <StoreSection title="Черновики приёмки" subtitle="Нажмите, чтобы продолжить заполнение" icon={<PackagePlus className="h-4 w-4 text-amber-500" />}>
      <div className="flex flex-wrap gap-2">
        {drafts.map((draft) => (
          <div key={draft.id} className="inline-flex items-center gap-1 rounded-full border border-amber-500/30 bg-amber-500/[0.06] px-3 py-1 text-xs">
            <button type="button" onClick={() => onApply(draft)} className="font-medium text-amber-700 hover:text-foreground dark:text-amber-200">
              {draft.title || 'Черновик'}
            </button>
            {canDelete && (
              <button type="button" aria-label="Удалить черновик" onClick={() => onDelete(draft.id)} className="text-rose-700 hover:text-rose-900 dark:text-rose-300 dark:hover:text-rose-100">×</button>
            )}
          </div>
        ))}
      </div>
    </StoreSection>
  )
}
