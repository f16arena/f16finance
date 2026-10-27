'use client'

import { StoreChip } from '@/components/store/store-ui'
import type { DebtSummary } from '@/components/store/receipts/types'

/** Статус оплаты накладной: оплачена / списана / долг / реализация / просрочка. */
export function ReceiptPayChip({ debt }: { debt: DebtSummary | undefined }) {
  if (!debt) return null
  if (debt.status === 'paid') return <StoreChip tone="emerald">Оплачен</StoreChip>
  if (debt.status === 'written_off') return <StoreChip>Списан</StoreChip>
  const overdue = debt.due_date ? new Date(debt.due_date).getTime() < Date.now() : false
  if (overdue) return <StoreChip tone="rose">Просрочен</StoreChip>
  return <StoreChip tone="amber">{debt.is_consignment ? 'Реализация' : 'Долг'}</StoreChip>
}
