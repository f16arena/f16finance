'use client'

import { PackagePlus, RefreshCw } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'

export type ReceiptsScope = 'all' | 'warehouse' | 'showcase'

/** Шапка страницы приёмки: в «Документах» (embedded) — только переключатель и кнопки. */
export function ReceiptsPageHeader({
  embedded,
  scope,
  onScope,
  busy,
  onRefresh,
  canCreate,
  onNew,
}: {
  embedded: boolean
  scope: ReceiptsScope
  onScope: (scope: ReceiptsScope) => void
  busy: boolean
  onRefresh: () => void
  canCreate: boolean
  onNew: () => void
}) {
  const actions = (
    <>
      <Button variant="outline" size="sm" onClick={onRefresh} disabled={busy} className="h-9 gap-1.5">
        <RefreshCw className={`h-3.5 w-3.5 ${busy ? 'animate-spin' : ''}`} />
        Обновить
      </Button>
      {canCreate && (
        <Button data-tour="receipt-new" size="sm" onClick={onNew} className="h-9 gap-1.5 bg-amber-600 hover:bg-amber-700">
          <PackagePlus className="h-3.5 w-3.5" />
          Новый документ
        </Button>
      )}
    </>
  )
  const toolbar = (
    <div className="inline-flex w-fit rounded-lg border border-border bg-white dark:bg-white/[0.03] p-0.5 text-xs">
      {(['all', 'warehouse', 'showcase'] as const).map((s) => (
        <button
          key={s}
          type="button"
          onClick={() => onScope(s)}
          className={`rounded-md px-3 py-1.5 transition ${scope === s ? 'bg-slate-200 dark:bg-white/10 text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
        >
          {s === 'all' ? 'Все' : s === 'warehouse' ? 'Подсобка' : 'Витрина'}
        </button>
      ))}
    </div>
  )
  return embedded ? (
    <div className="flex flex-wrap items-center justify-between gap-3">
      {toolbar}
      <div className="flex flex-wrap items-center gap-2">{actions}</div>
    </div>
  ) : (
    <AdminPageHeader
      title="Приёмка"
      description="Приходные документы от поставщиков"
      icon={<PackagePlus className="h-5 w-5" />}
      accent="emerald"
      backHref="/"
      actions={actions}
      toolbar={toolbar}
    />
  )
}
