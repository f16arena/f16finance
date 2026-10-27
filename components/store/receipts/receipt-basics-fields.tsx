'use client'

import { DatePicker } from '@/components/ui/date-picker'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import type { ReceiptForm } from '@/components/store/receipts/use-receipt-form'
import type { ReceiptsResponse } from '@/components/store/receipts/types'

/**
 * Поля шапки приёмки: склад, поставщик, дата, номер накладной, категория COGS.
 * Возвращает ячейки сетки `md:grid-cols-2` — сетку держит родитель.
 */
export function ReceiptBasicsFields({
  form,
  data,
}: {
  form: ReceiptForm
  data: ReceiptsResponse['data'] | null
}) {
  const {
    locationId, setLocationId, supplierMode, setSupplierMode, supplierId, setSupplierId,
    supplierName, setSupplierName, supplierOrganizationName, setSupplierOrganizationName,
    supplierBinIin, setSupplierBinIin, receivedAt, setReceivedAt, invoiceNumber, setInvoiceNumber,
    expenseCategoryId, setExpenseCategoryId, cogsExpenseCategories,
  } = form

  return (
    <>
      <div className="space-y-1.5">
        <Label>Куда</Label>
        <NativeSelect value={locationId} onChange={(event) => setLocationId(event.target.value)}>
          <option value="" disabled>Выберите локацию</option>
          {(data?.locations || []).map((location) => (
            <option key={location.id} value={location.id}>
              {location.location_type === 'warehouse' ? 'Подсобка' : 'Витрина'} · {location.name}
            </option>
          ))}
        </NativeSelect>
      </div>

      <div className="space-y-2">
        <Label>Поставщик (обязательно)</Label>
        <div className="inline-flex rounded-lg border border-border bg-white dark:bg-white/[0.03] p-0.5 text-xs">
          <button
            type="button"
            onClick={() => setSupplierMode('existing')}
            className={`rounded-md px-3 py-1.5 transition ${supplierMode === 'existing' ? 'bg-slate-200 dark:bg-white/10 text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
          >
            Из списка
          </button>
          <button
            type="button"
            onClick={() => setSupplierMode('new')}
            className={`rounded-md px-3 py-1.5 transition ${supplierMode === 'new' ? 'bg-slate-200 dark:bg-white/10 text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
          >
            Новый поставщик
          </button>
        </div>
        {supplierMode === 'existing' ? (
          <NativeSelect value={supplierId} onChange={(event) => setSupplierId(event.target.value)}>
            <option value="">Выберите поставщика</option>
            {(data?.suppliers || []).map((supplier) => (
              <option key={supplier.id} value={supplier.id}>
                {supplier.name} {supplier.bin_iin ? `· ${supplier.bin_iin}` : ''}
              </option>
            ))}
          </NativeSelect>
        ) : (
          <div className="grid gap-2">
            <Input value={supplierName} onChange={(e) => setSupplierName(e.target.value)} placeholder="Название поставщика" />
            <Input value={supplierOrganizationName} onChange={(e) => setSupplierOrganizationName(e.target.value)} placeholder="Название организации" />
            <Input
              value={supplierBinIin}
              onChange={(e) => setSupplierBinIin(e.target.value.replace(/\D/g, '').slice(0, 12))}
              placeholder="ИИН/БИН (12 цифр)"
            />
          </div>
        )}
      </div>

      <div className="space-y-1.5">
        <Label>Дата приемки</Label>
        <DatePicker value={receivedAt} onChange={setReceivedAt} />
      </div>

      <div className="space-y-1.5">
        <Label>Номер накладной</Label>
        <Input value={invoiceNumber} onChange={(event) => setInvoiceNumber(event.target.value)} placeholder="Например, INV-104" />
      </div>
      <div className="space-y-1.5 md:col-span-2">
        <Label>Категория расхода (COGS, обязательно)</Label>
        <NativeSelect value={expenseCategoryId} onChange={(event) => setExpenseCategoryId(event.target.value)}>
          <option value="">Выберите категорию COGS</option>
          {cogsExpenseCategories.map((category) => (
            <option key={category.id} value={category.id}>{category.name}</option>
          ))}
        </NativeSelect>
        <p className="text-xs text-muted-foreground">
          После приемки расход создается автоматически в выбранной категории.
          {cogsExpenseCategories.length === 0 ? ' COGS-категории не найдены в справочнике.' : ''}
        </p>
      </div>
    </>
  )
}
