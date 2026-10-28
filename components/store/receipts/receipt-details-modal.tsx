'use client'

import { FileText } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { StoreChip } from '@/components/store/store-ui'
import type { InventoryReceipt } from '@/components/store/receipts/types'
import { formatMoney } from '@/lib/core/format'
import { formatDate, formatQty, formatUnitCost } from '@/lib/store/receipts/format'

/** Карточка проведённой приёмки: шапка документа, состав, отмена. */
export function ReceiptDetailsModal(props: {
  open: boolean
  receipt: InventoryReceipt | null
  canCancel: boolean
  onClose: () => void
  onCancel: (receipt: InventoryReceipt) => void
}) {
  const r = props.receipt
  const items = r?.items || []
  return (
    <AppModal
      open={props.open}
      onClose={props.onClose}
      maxWidth="max-w-4xl"
      icon={<FileText className="h-4.5 w-4.5" />}
      title="Детали приёмки"
      description={r ? `${formatDate(r.received_at)} · ${r.kind === 'posting' ? 'Оприходование' : r.supplier?.name || 'Без поставщика'}` : 'Проведённый акт приёмки'}
    >
      {!r ? (
        <p className="text-sm text-muted-foreground">Документ не выбран.</p>
      ) : (
        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Fact label="Сумма" value={<span className="text-lg font-bold tabular-nums">{formatMoney(Number(r.total_amount || 0))}</span>} />
            <Fact label="Локация" value={r.location?.name || '—'} />
            <Fact
              label="Накладная"
              value={r.invoice_file_url ? (
                <a href={r.invoice_file_url} target="_blank" rel="noreferrer" className="text-blue-700 underline dark:text-blue-300">{r.invoice_number || 'Открыть файл'}</a>
              ) : (r.invoice_number || <span className="text-rose-700 dark:text-rose-300">Нет файла</span>)}
            />
            <Fact
              label="Статус"
              value={r.status === 'cancelled'
                ? <StoreChip tone="rose">Отменена{r.cancelled_at ? ` · ${formatDate(r.cancelled_at)}` : ''}</StoreChip>
                : <StoreChip tone="emerald">Проведена</StoreChip>}
            />
          </div>

          {r.cancel_reason ? (
            <div className="rounded-xl border border-rose-500/20 bg-rose-500/5 px-3 py-2 text-xs text-rose-700 dark:text-rose-300">
              Причина отмены: {r.cancel_reason}
            </div>
          ) : null}

          {r.kind === 'posting' || (r.status !== 'cancelled' && props.canCancel) ? (
            <div className="flex flex-wrap justify-end gap-2">
              {r.kind === 'posting' ? (
                <Button asChild type="button" variant="outline" size="sm">
                  <a href={`/store/postings?edit=${r.id}`}>Редактировать в оприходовании</a>
                </Button>
              ) : null}
              {r.status !== 'cancelled' && props.canCancel ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="border-rose-500/40 text-rose-700 hover:bg-rose-500/10 dark:text-rose-300"
                  onClick={() => props.onCancel(r)}
                >
                  Отменить приёмку
                </Button>
              ) : null}
            </div>
          ) : null}

          {/* Телефон: строки составом */}
          <div className="divide-y divide-border rounded-xl border border-border sm:hidden">
            {items.map((item) => (
              <div key={item.id} className="flex items-center justify-between gap-3 px-3 py-2.5">
                <div className="min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className="truncate text-sm text-foreground">{item.item?.name || 'Товар'}</span>
                    {item.is_bonus ? <StoreChip tone="emerald">Бонус</StoreChip> : null}
                  </div>
                  <div className="text-[11px] tabular-nums text-muted-foreground">{formatQty(Number(item.quantity || 0))} × {item.is_bonus ? '—' : formatUnitCost(Number(item.unit_cost || 0))}</div>
                </div>
                <span className="shrink-0 text-sm font-semibold tabular-nums">{item.is_bonus ? '0' : formatMoney(Number(item.total_cost || 0))}</span>
              </div>
            ))}
          </div>

          {/* Компьютер: таблица */}
          <div className="hidden overflow-auto rounded-xl border border-border sm:block">
            <table className="w-full min-w-[560px] table-fixed text-sm">
              <thead className="bg-surface-muted">
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="px-3 py-2 font-normal">Товар</th>
                  <th className="px-3 py-2 font-normal">Штрихкод</th>
                  <th className="px-3 py-2 text-right font-normal">Кол-во</th>
                  <th className="px-3 py-2 text-right font-normal">Цена закупа</th>
                  <th className="px-3 py-2 text-right font-normal">Сумма</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {items.map((item) => (
                  <tr key={item.id}>
                    <td className="px-3 py-2" title={item.item?.name || 'Товар'}>
                      <span className="flex min-w-0 items-center gap-2">
                        <span className="block truncate">{item.item?.name || 'Товар'}</span>
                        {item.is_bonus ? <StoreChip tone="emerald">Бонус</StoreChip> : null}
                      </span>
                    </td>
                    <td className="px-3 py-2 font-mono text-xs text-muted-foreground">{item.item?.barcode || '—'}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{formatQty(Number(item.quantity || 0))}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{item.is_bonus ? '—' : formatUnitCost(Number(item.unit_cost || 0))}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{item.is_bonus ? '0' : formatMoney(Number(item.total_cost || 0))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </AppModal>
  )
}

function Fact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="min-w-0 rounded-xl border border-border bg-slate-50 px-3 py-2 dark:bg-white/[0.03]">
      <div className="text-[10px] font-medium uppercase tracking-wider text-slate-500">{label}</div>
      <div className="mt-0.5 truncate text-sm text-foreground">{value}</div>
    </div>
  )
}
