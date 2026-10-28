'use client'

import { Loader2, Lock, Undo2, Users } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { StoreChip, StoreSection } from '@/components/store/store-ui'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { formatMoney as money } from '@/lib/core/format'
import { fmtDate, isBigZeroing, locLabel, type Detail } from './types'

/** Карточка акта: прогресс операторов, настройки закрытия, закрыть / отменить / откатить. */
export function ActHeaderCard({
  detail,
  isOpen,
  hasConflicts,
  assignDebt,
  setAssignDebt,
  zeroUncounted,
  setZeroUncounted,
  assignDebtZeroed,
  setAssignDebtZeroed,
  debtsCreated,
  closing,
  canceling,
  reverting,
  onCloseClick,
  onForceClose,
  onCancel,
  onRevert,
}: {
  detail: Detail
  isOpen: boolean
  hasConflicts: boolean
  assignDebt: boolean
  setAssignDebt: (value: boolean) => void
  zeroUncounted: boolean
  setZeroUncounted: (value: boolean) => void
  assignDebtZeroed: boolean
  setAssignDebtZeroed: (value: boolean) => void
  debtsCreated: number | null
  closing: boolean
  canceling: boolean
  reverting: boolean
  onCloseClick: () => void
  onForceClose: () => void
  onCancel: () => void
  onRevert: () => void
}) {
  const { can } = useCapabilities()

  return (
    <StoreSection
      title={locLabel(detail.location)}
      subtitle={`${fmtDate(detail.act.opened_at)} · ${isOpen ? 'открыт' : 'закрыт'}${detail.act.comment ? ` · ${detail.act.comment}` : ''}`}
      action={<span className="text-xs text-muted-foreground tabular-nums">посчитано {detail.countedItems} из {detail.totalItems}</span>}
    >
      <div className="space-y-3">
        {/* Прогресс по операторам */}
        {detail.progress && detail.progress.length > 0 ? (
          <div className="space-y-2">
            {detail.progress.map((p, i) => (
              <div key={i}>
                <div className="flex items-center justify-between text-xs">
                  <span className="flex items-center gap-1.5 text-foreground"><Users className="h-3 w-3 text-muted-foreground" /> {p.operatorName}</span>
                  <span className="text-muted-foreground tabular-nums">{p.counted} / {p.total}</span>
                </div>
                <div className="mt-1 h-1.5 w-full overflow-hidden rounded bg-slate-200 dark:bg-white/[0.05]">
                  <div className="h-full bg-amber-500 transition-all" style={{ width: `${p.total ? Math.min(100, (p.counted / p.total) * 100) : 0}%` }} />
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            {detail.assignments.map((a) => (
              <StoreChip key={a.id}>
                <Users className="h-3 w-3" /> {a.operatorName} · {a.categoryName || 'Вся локация'}
              </StoreChip>
            ))}
          </div>
        )}
        {isOpen ? (
          <div className="space-y-2 border-t border-slate-200 dark:border-white/5 pt-3">
            <label className="flex cursor-pointer items-center gap-2 text-xs text-muted-foreground">
              <input type="checkbox" checked={assignDebt} onChange={(e) => setAssignDebt(e.target.checked)} className="h-4 w-4 accent-amber-500" />
              Повесить недостачу долгом на ответственных (удержится из зарплаты)
            </label>
            {(() => {
              const uncountedRows = detail.uncounted || []
              const zeroSum = uncountedRows.reduce((s, r) => s + r.expected * (r.purchase_price || 0), 0)
              if (uncountedRows.length === 0) {
                return <div className="rounded-md border border-emerald-500/30 bg-emerald-500/10 px-3 py-2 text-xs text-emerald-700 dark:text-emerald-300">Все позиции с остатком посчитаны — обнулять нечего.</div>
              }
              return (
                <div className="space-y-1.5 rounded-md border border-border bg-surface-muted px-3 py-2">
                  <label className="flex cursor-pointer items-start gap-2 text-xs text-foreground">
                    <input type="checkbox" checked={zeroUncounted} onChange={(e) => setZeroUncounted(e.target.checked)} className="mt-0.5 h-4 w-4 accent-rose-500" />
                    <span>
                      <span className="font-medium">Полная ревизия: обнулить непосчитанное.</span>{' '}
                      <span className="text-muted-foreground">Не посчитано {uncountedRows.length} поз. на {money(zeroSum)} по закупу — «чего не посчитали, того нет». Список — ниже.</span>
                    </span>
                  </label>
                  {isBigZeroing(uncountedRows, detail.totalItems) ? (
                    <div className="rounded-md border border-rose-500/40 bg-rose-500/10 px-3 py-2 text-xs text-rose-700 dark:text-rose-300">
                      <b>Не посчитано {uncountedRows.length} из {detail.totalItems} поз. на {money(zeroSum)}.</b>{' '}
                      Обнуление такого объёма почти всегда даёт ложную недостачу: товар есть, его просто не досчитали.
                      {(() => {
                        const sold = uncountedRows.filter((r) => (r.sold_recent || 0) > 0).length
                        return sold > 0 ? ` ${sold} из них продавались за последнюю неделю.` : ''
                      })()}{' '}
                      Лучше досчитать.
                    </div>
                  ) : null}
                  {/* Что не посчитали — открыто до галочки, чтобы было видно, что досчитать */}
                  <details className="group rounded-md border border-border bg-background">
                    <summary className="flex cursor-pointer list-none items-center justify-between gap-2 px-3 py-2 text-xs font-medium text-foreground [&::-webkit-details-marker]:hidden">
                      <span>Что не посчитали — {uncountedRows.length} поз.</span>
                      <span className="text-muted-foreground group-open:hidden">показать список</span>
                      <span className="hidden text-muted-foreground group-open:inline">скрыть</span>
                    </summary>
                    <div className="max-h-80 overflow-y-auto border-t border-border">
                      <table className="w-full text-xs">
                        <thead className="sticky top-0 bg-surface-muted text-muted-foreground">
                          <tr>
                            <th className="px-3 py-1.5 text-left font-medium">Товар</th>
                            <th className="px-3 py-1.5 text-right font-medium">По системе</th>
                            <th className="px-3 py-1.5 text-right font-medium">По закупу</th>
                          </tr>
                        </thead>
                        <tbody>
                          {uncountedRows.map((r) => (
                            <tr key={r.item_id} className="border-t border-border/60">
                              <td className="px-3 py-1.5 text-foreground">
                                {r.name}
                                {(r.sold_recent || 0) > 0 ? <span className="ml-1.5 text-[10px] text-amber-600 dark:text-amber-300">продавался на неделе</span> : null}
                                {(r.other_location_qty || 0) > 0 ? (
                                  <span className="ml-1.5 text-[10px] text-muted-foreground">
                                    · ещё {r.other_location_qty} шт {detail.otherLocationType === 'warehouse' ? 'на складе' : 'на витрине'}
                                  </span>
                                ) : null}
                              </td>
                              <td className="px-3 py-1.5 text-right tabular-nums text-foreground">{r.expected} шт</td>
                              <td className="px-3 py-1.5 text-right tabular-nums text-muted-foreground">{money(r.expected * (r.purchase_price || 0))}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <p className="border-t border-border px-3 py-2 text-[11px] text-muted-foreground">Досчитайте эти позиции в сканере ревизии — после подсчёта они уйдут из списка.</p>
                  </details>
                  {zeroUncounted ? (
                    <label className="flex cursor-pointer items-start gap-2 pl-6 text-xs text-muted-foreground">
                      <input type="checkbox" checked={assignDebtZeroed} onChange={(e) => setAssignDebtZeroed(e.target.checked)} className="mt-0.5 h-4 w-4 accent-rose-500" />
                      <span>Обнулённое тоже повесить долгом — поровну на операторов акта. <span className="text-rose-500/80">Для первой полной ревизии не рекомендуется: спишется старая грязь каталога.</span></span>
                    </label>
                  ) : null}
                </div>
              )
            })()}
            {hasConflicts ? <div className="rounded-md border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-700 dark:text-rose-300">Есть расхождения между счётчиками — решите их (примите значение или на пересчёт), затем закрывайте.</div> : null}
            {can('store-revisions.commit') && (
              <Button
                onClick={onCloseClick}
                disabled={closing || detail.countedItems === 0 || hasConflicts}
                className="w-full gap-2 bg-amber-600 hover:bg-amber-700"
              >
                {closing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Lock className="h-4 w-4" />}
                Закрыть акт и провести
              </Button>
            )}
            {can('store-revisions.commit') && (
              <button
                type="button"
                onClick={onForceClose}
                disabled={closing}
                className="w-full rounded-md border border-rose-500/30 px-3 py-2 text-xs text-rose-700 dark:text-rose-300 transition hover:bg-rose-500/10 disabled:opacity-50"
              >
                Принудительно закрыть (обойти блокировки)
              </button>
            )}
            <p className="text-[11px] text-muted-foreground">Принудительно — когда акт «завис»: есть заявки в пути, расхождения или операторы не досчитали. Проведёт что посчитано, остальное не тронет. Только для владельца.</p>
            {can('store-revisions.cancel') && (
              <button
                type="button"
                onClick={onCancel}
                disabled={canceling}
                className="flex w-full items-center justify-center gap-2 rounded-md border border-border px-3 py-2 text-xs text-muted-foreground transition hover:border-rose-500/40 hover:text-rose-700 dark:hover:text-rose-300 disabled:opacity-50"
              >
                {canceling ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Undo2 className="h-3.5 w-3.5" />}
                Отменить акт (вернуть как было)
              </button>
            )}
            <p className="text-[11px] text-muted-foreground">Отмена — акт ещё не проведён: снимок и подсчёты отбрасываются, остатки не меняются. Используйте, если ревизию открыли по ошибке.</p>
          </div>
        ) : null}
        {debtsCreated && debtsCreated > 0 ? (
          <div className="rounded-md border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-700 dark:text-amber-300">Создано долгов: {debtsCreated} — удержатся из зарплаты ответственных.</div>
        ) : null}
        {detail.act.status === 'closed' && can('store-revisions.cancel') ? (
          <div className="space-y-2 border-t border-slate-200 dark:border-white/5 pt-3">
            <button
              type="button"
              onClick={onRevert}
              disabled={reverting}
              className="flex w-full items-center justify-center gap-2 rounded-md border border-rose-500/40 px-3 py-2.5 text-sm font-medium text-rose-700 dark:text-rose-300 transition hover:bg-rose-500/10 disabled:opacity-50"
            >
              {reverting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Undo2 className="h-4 w-4" />}
              Откатить ревизию
            </button>
            <p className="text-[11px] text-muted-foreground">Вернёт остатки к состоянию до проведения (изменения ревизии развернутся, продажи после — сохранятся) и удалит созданные актом долги. Акт станет «Отменён». Только для владельца.</p>
          </div>
        ) : null}
      </div>
    </StoreSection>
  )
}
