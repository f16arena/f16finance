'use client'

import { ScanSearch } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { actorLabel, formatDate, formatQty, type InventoryRevision } from './shared'

/** Состав проведённого акта ревизии: система, факт, расхождение. */
export function RevisionDetailsModal(props: {
  open: boolean
  revision: InventoryRevision | null
  canExport: boolean
  onClose: () => void
  onPrint: (revision: InventoryRevision) => void
}) {
  const r = props.revision
  const items = r?.items || []
  const deltaCls = (d: number) => (d < 0 ? 'text-rose-600 dark:text-rose-300' : d > 0 ? 'text-emerald-700 dark:text-emerald-300' : 'text-muted-foreground')
  const deltaText = (d: number) => (d === 0 ? '0' : `${d > 0 ? '+' : ''}${formatQty(d)}`)
  return (
    <AppModal
      open={props.open}
      onClose={props.onClose}
      maxWidth="max-w-4xl"
      icon={<ScanSearch className="h-4.5 w-4.5" />}
      title="Детали ревизии"
      description={r ? `${formatDate(r.counted_at)} · ${r.location?.company?.name || r.location?.name || 'Локация'}` : 'Проведённый акт ревизии'}
      footer={r && props.canExport ? (
        <div className="flex justify-end">
          <Button type="button" variant="outline" size="sm" onClick={() => props.onPrint(r)}>Печать / PDF</Button>
        </div>
      ) : undefined}
    >
      {!r ? (
        <p className="text-sm text-muted-foreground">Акт не выбран.</p>
      ) : (
        <div className="space-y-4">
          <div className="text-sm text-muted-foreground">
            Провёл: <span className="text-foreground">{actorLabel(r.created_by_staff, r.created_by || null)}</span>
            {r.comment ? <span> · Комментарий: <span className="text-foreground">{r.comment}</span></span> : null}
          </div>

          {/* Телефон */}
          <div className="divide-y divide-border rounded-xl border border-border sm:hidden">
            {items.map((item) => {
              const d = Number(item.delta_qty || 0)
              return (
                <div key={item.id} className="flex items-center justify-between gap-3 px-3 py-2.5">
                  <div className="min-w-0">
                    <div className="truncate text-sm text-foreground">{item.item?.name || 'Товар'}</div>
                    <div className="text-[11px] tabular-nums text-muted-foreground">система {formatQty(Number(item.expected_qty || 0))} → факт {formatQty(Number(item.actual_qty || 0))}</div>
                    {item.comment ? <div className="truncate text-[11px] text-muted-foreground">{item.comment}</div> : null}
                  </div>
                  <span className={`shrink-0 text-sm font-semibold tabular-nums ${deltaCls(d)}`}>{deltaText(d)}</span>
                </div>
              )
            })}
          </div>

          {/* Компьютер */}
          <div className="hidden overflow-x-auto rounded-xl border border-border sm:block">
            <table className="w-full min-w-[640px] table-fixed text-sm">
              <thead className="bg-surface-muted">
                <tr className="text-left text-xs text-muted-foreground">
                  <th className="px-3 py-2 font-normal">Товар</th>
                  <th className="px-3 py-2 font-normal">Штрихкод</th>
                  <th className="px-3 py-2 text-right font-normal">Система</th>
                  <th className="px-3 py-2 text-right font-normal">Факт</th>
                  <th className="px-3 py-2 text-right font-normal">Δ</th>
                  <th className="px-3 py-2 font-normal">Комментарий</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {items.map((item) => {
                  const d = Number(item.delta_qty || 0)
                  return (
                    <tr key={item.id}>
                      <td className="px-3 py-2" title={item.item?.name || 'Товар'}><span className="block truncate">{item.item?.name || 'Товар'}</span></td>
                      <td className="px-3 py-2 font-mono text-xs text-muted-foreground">{item.item?.barcode || '—'}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{formatQty(Number(item.expected_qty || 0))}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{formatQty(Number(item.actual_qty || 0))}</td>
                      <td className={`px-3 py-2 text-right font-semibold tabular-nums ${deltaCls(d)}`}>{deltaText(d)}</td>
                      <td className="px-3 py-2 text-muted-foreground">{item.comment || '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </AppModal>
  )
}
