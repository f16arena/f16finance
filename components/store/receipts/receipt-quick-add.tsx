'use client'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'

/** Быстрое добавление товара по штрихкоду/названию + режим повтора. */
export function ReceiptQuickAdd({ form }: { form: ReceiptForm }) {
  const {
    quickInputRef, quickQuery, setQuickQuery, quickError, setQuickError,
    handleQuickAdd, repeatMode, setRepeatMode, quickMatches, upsertReceiptLine,
  } = form

  return (
    <div data-tour="receipt-quickadd" className="rounded-2xl border border-emerald-500/20 bg-emerald-500/[0.05] p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          ref={quickInputRef}
          value={quickQuery}
          onChange={(event) => {
            setQuickQuery(event.target.value)
            if (quickError) setQuickError(null)
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              handleQuickAdd()
            }
          }}
          placeholder="Сканируй штрихкод или введи название товара"
          className="w-full min-w-0 flex-1 sm:w-auto sm:min-w-[260px]"
        />
        <Button type="button" onClick={handleQuickAdd} className="w-full sm:w-auto">
          Добавить товар
        </Button>
      </div>

      {/* Что делать, если товар уже есть в списке. В накладной одна
          позиция часто идёт дважды (часть по акции) — тогда нужна
          отдельная строка. При сканировании коробки по штуке — наоборот. */}
      <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
        <span className="text-muted-foreground">Если товар уже добавлен:</span>
        <div className="inline-flex rounded-lg border border-border bg-white p-0.5 dark:bg-white/[0.04]">
          <button
            type="button"
            onClick={() => setRepeatMode('new-line')}
            className={`rounded-md px-2.5 py-1 font-medium transition ${repeatMode === 'new-line' ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300' : 'text-muted-foreground hover:text-foreground'}`}
          >
            Отдельной строкой
          </button>
          <button
            type="button"
            onClick={() => setRepeatMode('increment')}
            className={`rounded-md px-2.5 py-1 font-medium transition ${repeatMode === 'increment' ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300' : 'text-muted-foreground hover:text-foreground'}`}
          >
            Прибавить количество
          </button>
        </div>
        <span className="text-muted-foreground">
          {repeatMode === 'new-line'
            ? '— для накладной, где позиция идёт дважды по разной цене'
            : '— для сканирования коробки по одной штуке'}
        </span>
      </div>

      <p className="mt-2 text-[11px] text-emerald-700/80 dark:text-emerald-200/80">Горячая клавиша: Ctrl/Cmd + K — фокус на сканер</p>
      {quickError ? <p className="mt-2 text-xs text-rose-700 dark:text-rose-300">{quickError}</p> : null}
      {quickMatches.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {quickMatches.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => {
                upsertReceiptLine(item.id, repeatMode)
                setQuickQuery('')
                setQuickError(null)
                quickInputRef.current?.focus()
              }}
              className="rounded-full border border-border bg-slate-50 dark:bg-white/[0.04] px-3 py-1 text-xs text-body hover:bg-slate-100 dark:hover:bg-white/[0.08]"
              title={`${item.name} · ${item.barcode}`}
            >
              <span className="block max-w-[340px] truncate">{item.name} · {item.barcode}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
