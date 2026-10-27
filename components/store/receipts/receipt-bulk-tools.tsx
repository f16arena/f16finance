'use client'

import { ChevronDown } from 'lucide-react'

import { useCapabilities } from '@/lib/client/use-capabilities'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { StoreSection } from '@/components/store/store-ui'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'

function Chevron({ open }: { open: boolean }) {
  return <ChevronDown className={`h-4 w-4 text-slate-400 transition ${open ? 'rotate-180' : ''}`} />
}

/** Сворачиваемые блоки «Массовые операции по строкам» и «Шаблоны приемки». */
export function ReceiptBulkTools({ form }: { form: ReceiptForm }) {
  const { can } = useCapabilities()
  const canSaveTemplate = can('store-receipts.save_template')
  const canExport = can('store-receipts.export')
  const {
    showBulkTools, setShowBulkTools, bulkMarkupPercent, setBulkMarkupPercent,
    bulkSalePrice, setBulkSalePrice, applyBulkMarkupPercent, applyBulkSalePrice,
    showTemplatesTools, setShowTemplatesTools, templateName, setTemplateName,
    saveTemplate, exportCsv, savedTemplates, applyTemplate, deleteTemplate,
  } = form

  return (
    <>
      <StoreSection
        title="Массовые операции по строкам"
        action={
          <button type="button" aria-label="Развернуть" onClick={() => setShowBulkTools((v) => !v)} className="grid h-7 w-7 place-items-center">
            <Chevron open={showBulkTools} />
          </button>
        }
        flush={!showBulkTools}
      >
        {showBulkTools ? (
        <div className="grid gap-2 md:grid-cols-2">
          <div className="flex gap-2">
            <Input value={bulkMarkupPercent} onChange={(e) => setBulkMarkupPercent(e.target.value)} placeholder="Наценка % для всех" />
            <Button type="button" variant="outline" onClick={applyBulkMarkupPercent}>Применить</Button>
          </div>
          <div className="flex gap-2">
            <Input value={bulkSalePrice} onChange={(e) => setBulkSalePrice(e.target.value)} placeholder="Цена продажи для всех" />
            <Button type="button" variant="outline" onClick={applyBulkSalePrice}>Применить</Button>
          </div>
        </div>
        ) : null}
      </StoreSection>

      <StoreSection
        title="Шаблоны приемки"
        action={
          <button type="button" aria-label="Развернуть" onClick={() => setShowTemplatesTools((v) => !v)} className="grid h-7 w-7 place-items-center">
            <Chevron open={showTemplatesTools} />
          </button>
        }
        flush={!showTemplatesTools}
      >
        {showTemplatesTools ? (
        <>
        <div className="flex flex-wrap gap-2">
          <Input value={templateName} onChange={(e) => setTemplateName(e.target.value)} placeholder="Название шаблона" className="w-full min-w-0 flex-1 sm:w-auto sm:min-w-[220px]" />
          {canSaveTemplate && (
            <Button type="button" variant="outline" onClick={saveTemplate}>Сохранить шаблон</Button>
          )}
          {canExport && (
            <Button type="button" variant="outline" onClick={exportCsv}>Экспорт CSV</Button>
          )}
        </div>
        {savedTemplates.length > 0 && (
          <div className="mt-2 flex flex-wrap gap-2">
            {savedTemplates.map((tpl) => (
              <div key={tpl.name} className="inline-flex items-center gap-1 rounded-full border border-border bg-slate-50 dark:bg-white/[0.04] px-3 py-1 text-xs">
                <button type="button" onClick={() => applyTemplate(tpl.name)} className="text-body hover:text-slate-900 dark:hover:text-white">{tpl.name}</button>
                <button type="button" onClick={() => deleteTemplate(tpl.name)} className="text-rose-700 dark:text-rose-300 hover:text-rose-900 dark:hover:text-rose-200">×</button>
              </div>
            ))}
          </div>
        )}
        </>
        ) : null}
      </StoreSection>
    </>
  )
}
