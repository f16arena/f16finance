'use client'

import { Archive, ArchiveRestore, ClipboardCheck, Loader2, ScanSearch } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { StoreChip, StoreEmpty } from '@/components/store/store-ui'
import { formatMoney } from '@/lib/core/format'
import { actorLabel, formatDate, formatQty, revisionTotals, type InventoryRevision } from './shared'

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

/** Список актов ревизии: на телефоне карточки, на компьютере таблица. Нули серые, потеря — rose. */
export function RevisionsList(props: {
  revisions: InventoryRevision[]
  canArchive: boolean
  archiveBusy: boolean
  refreshing: boolean
  searching: boolean
  archiveView: boolean
  selectedSet: Set<string>
  allVisibleSelected: boolean
  onToggleAll: () => void
  onToggleOne: (id: string) => void
  onOpen: (revision: InventoryRevision) => void
  onArchive: (revision: InventoryRevision) => void
  onRestore: (revision: InventoryRevision) => void
  onCreate: () => void
}) {
  const { revisions, canArchive, archiveBusy, refreshing, selectedSet } = props

  if (revisions.length === 0) {
    return (
      <StoreEmpty
        icon={<ScanSearch className="h-5 w-5" />}
        title={props.searching ? 'Ничего не найдено' : props.archiveView ? 'В архиве пусто' : 'Ревизий пока нет'}
        hint={props.searching
          ? 'Попробуйте другую локацию, товар или слово из комментария.'
          : props.archiveView
            ? 'Сюда попадают акты, которые вы убрали из списка.'
            : 'Акт ревизии сверяет фактические остатки с системой. Основной экран для этого — «Аудит-акт»; здесь можно провести быстрый акт вручную.'}
        action={!props.searching && !props.archiveView ? (
          <Button size="sm" onClick={props.onCreate} className="gap-1.5"><ClipboardCheck className="h-3.5 w-3.5" /> Новый акт</Button>
        ) : undefined}
      />
    )
  }

  const archiveButton = (revision: InventoryRevision) =>
    canArchive ? (
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="h-8 w-8 p-0 text-muted-foreground hover:text-foreground"
            disabled={archiveBusy}
            aria-label={revision.archived_at ? 'Вернуть в список' : 'Убрать в архив'}
            onClick={() => (revision.archived_at ? props.onRestore(revision) : props.onArchive(revision))}
          >
            {revision.archived_at ? <ArchiveRestore className="h-3.5 w-3.5" /> : <Archive className="h-3.5 w-3.5" />}
          </Button>
        </TooltipTrigger>
        <TooltipContent side="left">{revision.archived_at ? 'Вернуть в список' : 'Убрать в архив'}</TooltipContent>
      </Tooltip>
    ) : null

  return (
    <>
      {/* Телефон: карточки */}
      <div className="relative md:hidden">
        {refreshing ? <RefreshOverlay /> : null}
        <div className={`divide-y divide-border ${refreshing ? 'pointer-events-none opacity-50' : ''}`}>
          {revisions.map((revision) => {
            const t = revisionTotals(revision)
            const clean = t.shortage === 0 && t.surplus === 0
            return (
              <div key={revision.id} className={`space-y-2.5 p-4 ${selectedSet.has(revision.id) ? 'bg-amber-400/[0.07]' : ''}`}>
                <div className="flex items-start gap-3">
                  {canArchive ? (
                    <Checkbox className="mt-1" checked={selectedSet.has(revision.id)} onCheckedChange={() => props.onToggleOne(revision.id)} aria-label="Выделить акт" />
                  ) : null}
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-foreground">{revision.location?.company?.name || revision.location?.name || '—'}</p>
                    <p className="text-xs text-muted-foreground">
                      {formatDate(revision.counted_at)} · {actorLabel(revision.created_by_staff, revision.created_by || null)} · {t.items.length} поз.
                    </p>
                    {revision.comment ? <p className="mt-0.5 truncate text-[11px] text-muted-foreground">{revision.comment}</p> : null}
                  </div>
                  <div className="shrink-0 text-right">
                    {clean ? (
                      <StoreChip tone="emerald">Сошлось</StoreChip>
                    ) : (
                      <div className="flex flex-col items-end gap-1">
                        {t.shortage > 0 ? <StoreChip tone="rose">−{formatQty(t.shortage)}</StoreChip> : null}
                        {t.surplus > 0 ? <StoreChip tone="emerald">+{formatQty(t.surplus)}</StoreChip> : null}
                      </div>
                    )}
                  </div>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span className="text-xs text-muted-foreground">
                    {t.purchaseAmount > 0 ? <>Ущерб по закупу: <span className="font-semibold tabular-nums text-rose-600 dark:text-rose-300">{formatMoney(Math.round(t.purchaseAmount))}</span></> : 'Без расхождений в деньгах'}
                  </span>
                  <div className="flex items-center gap-1">
                    {archiveButton(revision)}
                    <Button type="button" variant="outline" size="sm" className="h-8" onClick={() => props.onOpen(revision)}>Открыть</Button>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {/* Компьютер: таблица */}
      <div className="relative hidden max-h-[calc(100vh-380px)] overflow-auto md:block">
        {refreshing ? <RefreshOverlay /> : null}
        <div className={refreshing ? 'pointer-events-none opacity-50' : undefined}>
          <table className="w-full min-w-[900px] text-sm">
            <thead className="sticky top-0 z-10 bg-white/95 backdrop-blur dark:bg-[#0f172a]/95">
              <tr className="border-b border-border text-left text-[10px] uppercase tracking-wider text-muted-foreground">
                {canArchive ? (
                  <th className="w-10 py-2.5 pl-4 pr-0 font-normal">
                    <Checkbox checked={props.allVisibleSelected} onCheckedChange={props.onToggleAll} aria-label="Выделить все акты" />
                  </th>
                ) : null}
                <th className={`w-24 py-2.5 pr-2 font-normal ${canArchive ? 'pl-3' : 'pl-4'}`}>Дата</th>
                <th className="w-40 px-2 py-2.5 font-normal">Провёл</th>
                <th className="w-48 px-2 py-2.5 font-normal">Локация</th>
                <th className="px-2 py-2.5 font-normal">Комментарий</th>
                <th className="w-20 px-2 py-2.5 text-right font-normal">Позиций</th>
                <th className="w-24 px-2 py-2.5 text-right font-normal">Недостача</th>
                <th className="w-24 px-2 py-2.5 pr-4 text-right font-normal">Излишек</th>
                <th className="w-28 px-2 py-2.5 text-right font-normal">Сумма (прод.)</th>
                <th className="w-28 px-2 py-2.5 pr-4 text-right font-normal">Сумма (закуп.)</th>
                <th className="w-28 px-2 py-2.5 pr-4" />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {revisions.map((revision) => {
                const t = revisionTotals(revision)
                return (
                  <tr key={revision.id} className={`transition hover:bg-slate-50 dark:hover:bg-white/[0.03] ${selectedSet.has(revision.id) ? 'bg-amber-400/[0.07]' : ''}`}>
                    {canArchive ? (
                      <td className="w-10 py-2.5 pl-4 pr-0 align-middle">
                        <Checkbox checked={selectedSet.has(revision.id)} onCheckedChange={() => props.onToggleOne(revision.id)} aria-label="Выделить акт" />
                      </td>
                    ) : null}
                    <td className={`w-24 py-2.5 pr-2 align-middle text-xs text-muted-foreground ${canArchive ? 'pl-3' : 'pl-4'}`}>{formatDate(revision.counted_at)}</td>
                    <td className="w-40 px-2 py-2.5 align-middle"><span className="line-clamp-1 text-xs text-muted-foreground">{actorLabel(revision.created_by_staff, revision.created_by || null)}</span></td>
                    <td className="w-48 px-2 py-2.5 align-middle"><span className="line-clamp-1 text-xs text-muted-foreground">{revision.location?.company?.name || revision.location?.name || '—'}</span></td>
                    <td className="min-w-0 max-w-0 px-2 py-2.5 align-middle">
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <p className="truncate text-sm">{revision.comment || <span className="text-muted-foreground">Без комментария</span>}</p>
                        </TooltipTrigger>
                        <TooltipContent side="top" align="start" className="max-w-md">
                          {revision.archived_at ? (
                            <div className="mb-1 text-xs text-amber-600 dark:text-amber-300">
                              В архиве с {formatDate(revision.archived_at)}
                              {revision.archive_reason ? ` · ${revision.archive_reason}` : ''}
                            </div>
                          ) : null}
                          {revision.comment || 'Без комментария'}
                          {t.mismatches.length ? (
                            <div className="mt-1 text-xs text-muted-foreground">
                              {t.mismatches.map((i) => `${i.item?.name || 'Товар'}: ${Number(i.delta_qty) > 0 ? '+' : ''}${formatQty(Number(i.delta_qty || 0))}`).join(', ')}
                            </div>
                          ) : null}
                        </TooltipContent>
                      </Tooltip>
                    </td>
                    <td className="w-20 px-2 py-2.5 text-right align-middle tabular-nums text-muted-foreground">{t.items.length}</td>
                    <td className={`w-24 px-2 py-2.5 text-right align-middle text-sm font-semibold tabular-nums ${t.shortage > 0 ? 'text-rose-600 dark:text-rose-300' : 'text-muted-foreground'}`}>
                      {t.shortage > 0 ? `−${formatQty(t.shortage)}` : '—'}
                    </td>
                    <td className={`w-24 px-2 py-2.5 pr-4 text-right align-middle text-sm font-semibold tabular-nums ${t.surplus > 0 ? 'text-emerald-700 dark:text-emerald-300' : 'text-muted-foreground'}`}>
                      {t.surplus > 0 ? `+${formatQty(t.surplus)}` : '—'}
                    </td>
                    <td className={`w-28 px-2 py-2.5 text-right align-middle text-sm tabular-nums ${t.saleAmount > 0 ? 'font-semibold text-foreground' : 'text-muted-foreground'}`}>
                      {t.saleAmount > 0 ? formatMoney(Math.round(t.saleAmount)) : '—'}
                    </td>
                    <td className={`w-28 px-2 py-2.5 pr-4 text-right align-middle text-sm tabular-nums ${t.purchaseAmount > 0 ? 'font-semibold text-foreground' : 'text-muted-foreground'}`}>
                      {t.purchaseAmount > 0 ? formatMoney(Math.round(t.purchaseAmount)) : '—'}
                    </td>
                    <td className="w-28 px-2 py-2.5 pr-4 align-middle">
                      <div className="flex items-center justify-end gap-1.5">
                        <Button type="button" variant="outline" size="sm" onClick={() => props.onOpen(revision)}>Открыть</Button>
                        {archiveButton(revision)}
                      </div>
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
