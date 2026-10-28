'use client'

import { useState } from 'react'
import { ChevronDown, FileWarning, Loader2, Package, PackagePlus } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { SortableTh } from '@/components/ui/sortable-th'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { StoreChip, StoreEmpty } from '@/components/store/store-ui'
import { ReceiptPayChip } from '@/components/store/receipts/receipt-pay-chip'
import type { DebtSummary, InventoryReceipt } from '@/components/store/receipts/types'
import { formatMoney } from '@/lib/core/format'
import { formatDate, formatQty, formatUnitCost } from '@/lib/store/receipts/format'
import type { SortState } from '@/lib/core/table-sort'

export type ReceiptSortKey = 'date' | 'supplier' | 'location' | 'invoice' | 'positions' | 'amount' | 'payment'

function receiptTitle(r: InventoryReceipt) {
  return r.kind === 'posting' ? 'Оприходование' : r.supplier?.name || 'Без поставщика'
}

function InvoiceLink({ r, className = '' }: { r: InventoryReceipt; className?: string }) {
  return r.invoice_file_url ? (
    <a href={r.invoice_file_url} target="_blank" rel="noreferrer" className={`truncate font-mono text-xs text-blue-700 underline dark:text-blue-300 ${className}`}>
      {r.invoice_number || 'Открыть'}
    </a>
  ) : (
    <span className={`inline-flex items-center gap-1 font-mono text-xs text-rose-700 dark:text-rose-300 ${className}`}>
      <FileWarning className="h-3 w-3" /> Нет файла
    </span>
  )
}

function RefreshOverlay() {
  return (
    <div className="absolute inset-0 z-20 flex items-start justify-center bg-background/35 pt-10 backdrop-blur-[0.5px]">
      <div className="flex items-center gap-2 rounded-lg border border-border bg-card/90 px-3 py-1.5 text-xs text-muted-foreground shadow-md">
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
        Обновление…
      </div>
    </div>
  )
}

/** Список документов приёмки: на телефоне карточки, на компьютере таблица с сортировкой. */
export function ReceiptsList(props: {
  receipts: InventoryReceipt[]
  debtByReceiptId: Map<string, DebtSummary>
  sort: SortState<ReceiptSortKey>
  onSort: (key: ReceiptSortKey) => void
  refreshing: boolean
  searching: boolean
  canCreate: boolean
  onCreate: () => void
  onOpen: (receipt: InventoryReceipt) => void
}) {
  const { receipts, debtByReceiptId, sort, onSort, refreshing } = props
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})

  if (receipts.length === 0) {
    return (
      <StoreEmpty
        icon={<Package className="h-5 w-5" />}
        title={props.searching ? 'Ничего не найдено' : 'Приёмок пока нет'}
        hint={props.searching ? 'Попробуйте другое название поставщика, товара или номер накладной.' : 'Когда привезут товар от поставщика, создайте документ — остатки и закупочные цены обновятся сами.'}
        action={!props.searching && props.canCreate ? (
          <Button size="sm" onClick={props.onCreate} className="gap-1.5"><PackagePlus className="h-3.5 w-3.5" /> Новый документ</Button>
        ) : undefined}
      />
    )
  }

  return (
    <>
      {/* Телефон: карточки */}
      <div className="relative sm:hidden">
        {refreshing ? <RefreshOverlay /> : null}
        <div className={`divide-y divide-border ${refreshing ? 'pointer-events-none opacity-50' : ''}`}>
          {receipts.map((r) => {
            const debt = debtByReceiptId.get(String(r.id))
            const itemsCount = (r.items || []).length
            const open = Boolean(expanded[r.id])
            const cancelled = r.status === 'cancelled'
            return (
              <div key={r.id} className={`space-y-2.5 p-4 ${cancelled ? 'opacity-60' : ''}`}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className={`truncate text-sm font-semibold text-foreground ${cancelled ? 'line-through' : ''}`}>{receiptTitle(r)}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {formatDate(r.received_at)} · {r.location?.name || '—'} · {itemsCount} поз.
                    </p>
                    {r.comment ? <p className="mt-0.5 truncate text-[11px] text-muted-foreground">{r.comment}</p> : null}
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="text-lg font-bold tabular-nums leading-tight text-foreground">{formatMoney(r.total_amount || 0)}</div>
                    <div className="mt-1 flex flex-wrap justify-end gap-1">
                      {cancelled ? <StoreChip tone="rose">Отменена</StoreChip> : null}
                      <ReceiptPayChip debt={debt} />
                    </div>
                  </div>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <InvoiceLink r={r} className="max-w-[55%]" />
                  <div className="flex items-center gap-2">
                    {itemsCount > 0 ? (
                      <button
                        type="button"
                        className="inline-flex items-center gap-1 text-[11px] text-slate-500"
                        onClick={() => setExpanded((p) => ({ ...p, [r.id]: !p[r.id] }))}
                      >
                        <ChevronDown className={`h-3.5 w-3.5 transition ${open ? 'rotate-180' : ''}`} />
                        {open ? 'Скрыть состав' : 'Состав'}
                      </button>
                    ) : null}
                    <Button type="button" variant="outline" size="sm" className="h-8" onClick={() => props.onOpen(r)}>Открыть</Button>
                  </div>
                </div>
                {open ? (
                  <div className="space-y-1.5 rounded-xl bg-slate-50 p-3 dark:bg-white/[0.03]">
                    {(r.items || []).map((item) => (
                      <div key={item.id} className="flex items-center justify-between gap-2 text-xs">
                        <div className="flex min-w-0 items-center gap-1.5">
                          <span className="truncate text-body">{item.item?.name || 'Товар'}</span>
                          {item.is_bonus ? <StoreChip tone="emerald">Бонус</StoreChip> : null}
                        </div>
                        <span className="shrink-0 tabular-nums text-slate-500">{formatQty(Number(item.quantity || 0))} × {item.is_bonus ? '—' : formatUnitCost(Number(item.unit_cost || 0))}</span>
                        <span className="shrink-0 font-medium tabular-nums text-foreground">{item.is_bonus ? '0' : formatMoney(Number(item.total_cost || 0))}</span>
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            )
          })}
        </div>
      </div>

      {/* Компьютер: таблица */}
      <div className="relative hidden max-h-[calc(100vh-380px)] overflow-auto sm:block">
        {refreshing ? <RefreshOverlay /> : null}
        <div className={refreshing ? 'pointer-events-none opacity-50' : undefined}>
          <table className="w-full min-w-[840px] text-sm">
            <thead className="sticky top-0 z-10 bg-white/95 backdrop-blur dark:bg-[#0f172a]/95">
              <tr className="border-b border-border text-left text-[10px] uppercase tracking-wider text-muted-foreground">
                <SortableTh label="Дата" sortKey="date" sort={sort} onSort={onSort} className="w-24 py-2.5 pl-4 pr-2 font-normal" />
                <SortableTh label="Поставщик" sortKey="supplier" sort={sort} onSort={onSort} className="px-2 py-2.5 font-normal" />
                <SortableTh label="Локация" sortKey="location" sort={sort} onSort={onSort} className="w-40 px-2 py-2.5 font-normal" />
                <SortableTh label="Накладная" sortKey="invoice" sort={sort} onSort={onSort} className="w-32 px-2 py-2.5 font-normal" />
                <SortableTh label="Позиций" sortKey="positions" sort={sort} onSort={onSort} align="right" className="w-20 px-2 py-2.5 font-normal" />
                <SortableTh label="Сумма" sortKey="amount" sort={sort} onSort={onSort} align="right" className="w-32 px-2 py-2.5 pr-4 font-normal" />
                <SortableTh label="Оплата" sortKey="payment" sort={sort} onSort={onSort} className="w-28 px-2 py-2.5 font-normal" />
                <th className="w-28 px-2 py-2.5 pr-4 text-right font-normal" />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {receipts.map((r) => {
                const debt = debtByReceiptId.get(String(r.id))
                const cancelled = r.status === 'cancelled'
                return (
                  <tr key={r.id} className={`transition hover:bg-slate-50 dark:hover:bg-white/[0.03] ${cancelled ? 'opacity-50' : ''}`}>
                    <td className="w-24 py-2.5 pl-4 pr-2 align-middle text-xs text-muted-foreground">{formatDate(r.received_at)}</td>
                    <td className="min-w-0 max-w-0 px-2 py-2.5 align-middle">
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <p className={`truncate text-sm font-medium ${cancelled ? 'line-through' : ''}`}>
                            {receiptTitle(r)}
                            {cancelled ? <span className="ml-2 no-underline"><StoreChip tone="rose">Отменена</StoreChip></span> : null}
                          </p>
                        </TooltipTrigger>
                        <TooltipContent side="top" align="start" className="max-w-md">
                          {r.kind === 'posting' ? 'Оприходование (без поставщика)' : r.supplier?.name || 'Без поставщика'}
                          {r.comment ? <div className="mt-1 text-xs text-muted-foreground">{r.comment}</div> : null}
                          {r.cancel_reason ? <div className="mt-1 text-xs text-rose-700 dark:text-rose-300">Причина отмены: {r.cancel_reason}</div> : null}
                        </TooltipContent>
                      </Tooltip>
                      {r.comment ? <p className="truncate text-[11px] text-muted-foreground">{r.comment}</p> : null}
                    </td>
                    <td className="w-40 px-2 py-2.5 align-middle"><span className="line-clamp-1 text-xs text-muted-foreground">{r.location?.name || '—'}</span></td>
                    <td className="w-32 px-2 py-2.5 align-middle"><InvoiceLink r={r} /></td>
                    <td className="w-20 px-2 py-2.5 text-right align-middle tabular-nums text-muted-foreground">{(r.items || []).length}</td>
                    <td className="w-32 px-2 py-2.5 pr-4 text-right align-middle text-sm font-semibold tabular-nums text-foreground">{formatMoney(r.total_amount || 0)}</td>
                    <td className="w-28 px-2 py-2.5 align-middle">
                      {debt ? <ReceiptPayChip debt={debt} /> : <span className="text-xs text-muted-foreground">—</span>}
                    </td>
                    <td className="w-28 px-2 py-2.5 pr-4 text-right align-middle">
                      <Button type="button" variant="outline" size="sm" onClick={() => props.onOpen(r)}>Открыть</Button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
