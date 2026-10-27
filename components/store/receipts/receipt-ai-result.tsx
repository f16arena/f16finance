'use client'

import { Loader2, Sparkles } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'
import type { InventoryItem } from '@/components/store/receipts/types'

/** Результат ИИ-разбора накладной: сводка, COGS-подсказка, ручное сопоставление строк. */
export function ReceiptAiResult({ form, items }: { form: ReceiptForm; items: InventoryItem[] }) {
  const {
    aiParseResult, aiParsing, runAiInvoiceParse, applyAiParseResult, applyAiCogsCategory,
    applySpecificCogsCategory, setAiManualItem, cogsConfidenceLabel, cogsConfidenceClass,
  } = form
  if (!aiParseResult) return null

  const learned = aiParseResult.items.filter((it) => it.match_source === 'mapping_supplier').length

  return (
    <div className="rounded-xl border border-emerald-400/20 bg-emerald-500/[0.06] p-3 text-xs text-emerald-900 dark:text-emerald-100">
      <div className="flex items-start justify-between gap-2">
        <p className="font-medium">
          ИИ-черновик: найдено {aiParseResult.items.length} строк, сопоставлено {aiParseResult.matched_count}, без совпадения {aiParseResult.unmatched_count}.
        </p>
        {aiParseResult.unmatched_count > 0 ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            className="h-7 text-[11px] gap-1"
            onClick={() => void runAiInvoiceParse()}
            disabled={aiParsing}
          >
            {aiParsing ? <Loader2 className="h-3 w-3 animate-spin" /> : <Sparkles className="h-3 w-3" />}
            Дораспознать
          </Button>
        ) : null}
      </div>
      {learned > 0 ? (
        <p className="text-[11px] text-emerald-700/90 dark:text-emerald-200/90">🎯 Узнано по предыдущим приёмкам этого поставщика: {learned}</p>
      ) : null}
      <p className="mt-1 text-emerald-700/90 dark:text-emerald-200/90">
        {aiParseResult.supplier_name ? `Поставщик: ${aiParseResult.supplier_name}. ` : ''}
        {aiParseResult.invoice_number ? `Номер: ${aiParseResult.invoice_number}. ` : ''}
        {aiParseResult.invoice_date ? `Дата: ${aiParseResult.invoice_date}.` : ''}
      </p>
      {aiParseResult.cogs_suggestion?.recommended_category_name ? (
        <div className="mt-2 rounded-lg border border-emerald-300/20 bg-slate-50 dark:bg-black/20 px-2 py-2 text-[11px] text-emerald-900 dark:text-emerald-100">
          <p>
            Рекомендованная COGS-категория: <span className="font-medium">{aiParseResult.cogs_suggestion.recommended_category_name}</span>
          </p>
          <p className={`mt-1 inline-flex rounded-full border px-2 py-0.5 ${cogsConfidenceClass}`}>
            {cogsConfidenceLabel}
          </p>
          {aiParseResult.cogs_suggestion.reason ? (
            <p className="mt-1 text-emerald-700/85 dark:text-emerald-200/85">{aiParseResult.cogs_suggestion.reason}</p>
          ) : null}
          <div className="mt-2">
            <Button type="button" variant="outline" size="sm" className="h-7 text-[11px]" onClick={applyAiCogsCategory}>
              Применить категорию
            </Button>
          </div>
          {(aiParseResult.cogs_suggestion.alternatives || []).length > 0 ? (
            <div className="mt-2 space-y-1">
              <p className="text-emerald-700/80 dark:text-emerald-200/80">Альтернативы:</p>
              <div className="flex flex-wrap gap-1.5">
                {(aiParseResult.cogs_suggestion.alternatives || []).map((alt) => (
                  <Button
                    key={alt.id}
                    type="button"
                    variant="outline"
                    size="sm"
                    className="h-7 text-[11px]"
                    onClick={() => applySpecificCogsCategory(alt.id, alt.name)}
                  >
                    {alt.name}
                  </Button>
                ))}
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
      <div className="mt-2">
        <Button type="button" size="sm" className="h-8 bg-amber-600 hover:bg-amber-700" onClick={applyAiParseResult}>
          Применить в форму
        </Button>
      </div>
      {aiParseResult.items.some((item) => !(item.manual_item_id || item.matched_item_id)) ? (
        <div className="mt-3 space-y-2 rounded-lg border border-emerald-300/20 bg-slate-50 dark:bg-black/20 p-2">
          <p className="text-[11px] text-emerald-700/90 dark:text-emerald-200/90">Ручное сопоставление несвязанных строк:</p>
          {aiParseResult.items.map((item, index) => {
            const resolvedId = item.manual_item_id || item.matched_item_id || ''
            if (resolvedId !== '') return null
            return (
              <div key={`${item.invoice_name}-${index}`} className="grid gap-2 md:grid-cols-[minmax(0,1fr)_minmax(240px,320px)]">
                <p className="truncate text-emerald-900 dark:text-emerald-100">
                  {item.invoice_name} · {item.quantity} шт
                </p>
                <NativeSelect
                  value={resolvedId}
                  onChange={(event) => setAiManualItem(index, event.target.value)}
                  className="h-8 text-xs"
                >
                  <option value="">Без сопоставления</option>
                  {items.map((catalogItem) => (
                    <option key={catalogItem.id} value={catalogItem.id} title={`${catalogItem.name} · ${catalogItem.barcode}`}>
                      {catalogItem.name} · {catalogItem.barcode}
                    </option>
                  ))}
                </NativeSelect>
              </div>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}
