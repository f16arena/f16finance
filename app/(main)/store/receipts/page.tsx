'use client'

import { useEffect, useMemo, useState } from 'react'
import { MoreHorizontal, PackagePlus, Search, Trash2 } from 'lucide-react'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { useStoreApiUrl, useStoreScope } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { toast } from '@/components/ui/use-toast'
import { TooltipProvider } from '@/components/ui/tooltip'
import { formatMoney } from '@/lib/core/format'
import { StoreDataTableSkeleton } from '@/components/store/store-data-table-skeleton'
import { Skeleton } from '@/components/ui/skeleton'
import { isAbortError } from '@/lib/is-abort-error'
import { ReceiptsList, type ReceiptSortKey } from '@/components/store/receipts/receipts-list'
import { ReceiptDetailsModal } from '@/components/store/receipts/receipt-details-modal'
import { ReceiptsPageHeader, type ReceiptsScope } from '@/components/store/receipts/receipts-page-header'
import { ReceiptDraftsSection } from '@/components/store/receipts/receipt-drafts-section'
import { ReceiptCancelModal } from '@/components/store/receipts/receipt-cancel-modal'
import { ReceiptFormModal } from '@/components/store/receipts/receipt-form-modal'
import { useReceiptForm } from '@/components/store/receipts/use-receipt-form'
import { StoreSection, StoreStat, StoreStatGrid } from '@/components/store/store-ui'
import type {
  DebtSummary,
  InventoryReceipt,
  ReceiptsResponse,
} from '@/components/store/receipts/types'
import { asArray, normalizeReceipt } from '@/lib/store/receipts/format'
import { invalidateStoreCaches } from '@/lib/client/store-cache'
import { useTableSort } from '@/lib/client/use-table-sort'
import type { SortColumns, SortState } from '@/lib/core/table-sort'

const RECEIPT_SORT_INITIAL: SortState<ReceiptSortKey> = { key: 'date', dir: 'desc' }

/**
 * Ранг столбца «Оплата»: сначала то, что требует денег (просрочка, долг),
 * потом закрытые накладные. Накладные без долга — пусто, уходят в конец.
 */
function debtRank(debt: DebtSummary | undefined): number | null {
  if (!debt) return null
  if (debt.status === 'paid') return 3
  if (debt.status === 'written_off') return 4
  const overdue = debt.due_date ? new Date(debt.due_date).getTime() < Date.now() : false
  return overdue ? 0 : debt.is_consignment ? 2 : 1
}

export default function StoreReceiptsPage({ embedded = false }: { embedded?: boolean } = {}) {
  const { storeCompanyId } = useStoreScope()
  const storeUrl = useStoreApiUrl()
  const { can } = useCapabilities()
  const canCreate = can('store-receipts.create')
  const canDelete = can('store-receipts.delete')
  const canCancel = can('store-receipts.cancel')

  const [data, setData] = useState<ReceiptsResponse['data'] | null>(null)
  const [debtByReceiptId, setDebtByReceiptId] = useState<Map<string, DebtSummary>>(new Map())
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [locationId, setLocationId] = useState('')
  const [scope, setScope] = useState<ReceiptsScope>('all')
  const [receiptSearch, setReceiptSearch] = useState('')
  const [selectedReceipt, setSelectedReceipt] = useState<InventoryReceipt | null>(null)
  const [cancelTarget, setCancelTarget] = useState<InventoryReceipt | null>(null)
  const [cancelReason, setCancelReason] = useState('')
  const [cancelBusy, setCancelBusy] = useState(false)
  const [receiptDetailsOpen, setReceiptDetailsOpen] = useState(false)

  const receiptsUrl = `/api/admin/store/receipts?scope=${scope}${storeCompanyId ? `&company_id=${encodeURIComponent(storeCompanyId)}` : ''}`
  // Суффикс ключа: тот же адрес читает страница оприходования, но раскладывает
  // ответ по-своему. Один ключ на два разных вида payload — ошибка ожидающая часа.
  const receiptsCacheKey = `${receiptsUrl}#receipts`

  const applyReceipts = (payload: NonNullable<ReceiptsResponse['data']>) => {
    setData(payload)
    // Сброс локации при смене точки: если текущая не из набора новой точки — берём первую.
    setLocationId((current) => (current && payload.locations.some((l: any) => l.id === current)) ? current : (payload.locations?.[0]?.id || ''))
  }

  const load = async (signal?: AbortSignal, opts?: { soft?: boolean }) => {
    // Прошлый ответ показываем сразу, свежий догружаем фоном: вкладка «Приёмка»
    // в «Документах» размонтируется при каждом переключении, и без кэша возврат
    // на неё — это снова пустой экран со скелетоном.
    // opts.soft — это перезагрузка после мутации: там кэш заведомо устарел,
    // подставлять его нельзя, иначе на секунду вернутся старые цифры.
    const cached = opts?.soft ? null : readApiCache<NonNullable<ReceiptsResponse['data']>>(receiptsCacheKey)
    if (cached) applyReceipts(cached)
    const soft = Boolean(opts?.soft) || !!cached
    if (soft) {
      setRefreshing(true)
      setLoading(false)
    } else {
      setLoading(true)
    }
    setError(null)
    try {
      const response = await fetch(receiptsUrl, { cache: 'no-store', signal })
      const json = (await response.json().catch(() => null)) as ReceiptsResponse | null
      if (signal?.aborted) return
      if (!response.ok || !json?.ok || !json.data) throw new Error(json?.error || 'Не удалось загрузить приемку')
      const normalized = {
        items: asArray(json.data.items),
        suppliers: asArray(json.data.suppliers),
        locations: asArray(json.data.locations),
        receipts: asArray(json.data.receipts).map(normalizeReceipt),
        drafts: asArray(json.data.drafts),
        expense_categories: asArray(json.data.expense_categories),
      }
      writeApiCache(receiptsCacheKey, normalized)
      applyReceipts(normalized)

      // load debts and build a map by receipt_id
      try {
        const debtsResponse = await fetch(storeUrl('/api/admin/store/debts?status=all'), { cache: 'no-store', signal })
        const debtsJson = await debtsResponse.json().catch(() => null)
        if (debtsResponse.ok && debtsJson?.ok && Array.isArray(debtsJson.data?.debts)) {
          const map = new Map<string, DebtSummary>()
          for (const d of debtsJson.data.debts) {
            if (d?.receipt_id) {
              map.set(String(d.receipt_id), {
                status: d.status,
                total_amount: Number(d.total_amount || 0),
                due_date: d.due_date || null,
                is_consignment: Boolean(d.is_consignment),
              })
            }
          }
          setDebtByReceiptId(map)
        }
      } catch {
        // non-fatal
      }
    } catch (err: any) {
      if (isAbortError(err) || signal?.aborted) return
      if (!soft) setData(null)
      setError(err?.message || 'Не удалось загрузить приемку')
    } finally {
      if (!signal?.aborted) {
        if (soft) setRefreshing(false)
        else setLoading(false)
      }
    }
  }

  useEffect(() => {
    const ac = new AbortController()
    void load(ac.signal)
    return () => ac.abort()
    // storeCompanyId: при смене точки в шапке — перезапрос локаций/данных этой точки.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scope, storeCompanyId])

  const form = useReceiptForm({
    data,
    loading,
    storeCompanyId,
    locationId,
    setLocationId,
    setError,
    setSuccess,
    reload: () => load(undefined, { soft: true }),
  })
  const { setFormSheetOpen } = form


  // Причина отмены спрашивается в окне приложения, а не в системном prompt().
  const cancelReceipt = (receipt: InventoryReceipt) => {
    if (receipt.status === 'cancelled') return
    setCancelReason('')
    setCancelTarget(receipt)
  }

  const confirmCancelReceipt = async () => {
    const receipt = cancelTarget
    if (!receipt) return
    setCancelBusy(true)
    try {
      const response = await fetch('/api/admin/store/receipts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'cancelReceipt',
          receipt_id: receipt.id,
          cancel_reason: cancelReason.trim() || null,
        }),
      })
      const json = await response.json()
      if (!response.ok) {
        toast({ title: json?.message || json?.error || 'Не удалось отменить приёмку', variant: 'destructive' })
        return
      }
      setCancelTarget(null)
      setReceiptDetailsOpen(false)
      setSelectedReceipt(null)
      invalidateStoreCaches()
      toast({ title: 'Приёмка отменена, остатки откатились' })
      await load(undefined, { soft: true })
    } catch (e: any) {
      toast({ title: e?.message || 'Ошибка сети', variant: 'destructive' })
    } finally {
      setCancelBusy(false)
    }
  }

  const filteredReceipts = useMemo(() => {
    const q = receiptSearch.trim().toLowerCase()
    const list = data?.receipts || []
    if (!q) return list
    return list.filter((r) => {
      const parts = [
        r.supplier?.name,
        r.location?.name,
        r.invoice_number,
        r.comment,
        ...(r.items || []).map((i) => i.item?.name || ''),
      ]
      return parts.filter(Boolean).join(' ').toLowerCase().includes(q)
    })
  }, [data?.receipts, receiptSearch])

  // Сортировка по заголовку — после поиска, чтобы фильтр оставался в силе
  const receiptSortColumns = useMemo<SortColumns<InventoryReceipt, ReceiptSortKey>>(
    () => ({
      date: { get: (r) => r.received_at || null, defaultDir: 'desc' },
      supplier: { get: (r) => (r.kind === 'posting' ? 'Оприходование' : r.supplier?.name || null) },
      location: { get: (r) => r.location?.name || null },
      invoice: { get: (r) => r.invoice_number || null },
      positions: { get: (r) => (r.items || []).length || null, defaultDir: 'desc' },
      amount: { get: (r) => Number(r.total_amount || 0) || null, defaultDir: 'desc' },
      payment: { get: (r) => debtRank(debtByReceiptId.get(String(r.id))) },
    }),
    [debtByReceiptId],
  )
  const { sort: receiptSort, toggle: toggleReceiptSort, sortedRows: sortedReceipts } = useTableSort<InventoryReceipt, ReceiptSortKey>({
    storageKey: 'storeReceipts.listSort',
    columns: receiptSortColumns,
    initial: RECEIPT_SORT_INITIAL,
    rows: filteredReceipts,
  })

  const missingInvoiceCount = useMemo(
    () => (data?.receipts || []).filter((r) => r.status !== 'cancelled' && r.kind !== 'posting' && !r.invoice_file_url).length,
    [data],
  )
  const totalReceiptsAmount = useMemo(() => {
    return (data?.receipts || []).reduce((s, r) => s + Number(r.total_amount || 0), 0)
  }, [data?.receipts])

  return (
    <TooltipProvider delayDuration={200}>
    <div className={embedded ? 'space-y-6' : 'app-page-wide space-y-6'}>
      <ReceiptsPageHeader
        embedded={embedded}
        scope={scope}
        onScope={setScope}
        busy={loading || refreshing}
        onRefresh={() => void load(undefined, { soft: true })}
        canCreate={canCreate}
        onNew={() => setFormSheetOpen(true)}
      />

      {/* Сводка: только то, что меняет решения */}
      <StoreStatGrid cols={3}>
        <StoreStat label="Документов" value={(data?.receipts || []).length} loading={loading} />
        <StoreStat label="Сумма приёмок" value={formatMoney(totalReceiptsAmount)} loading={loading} tone={totalReceiptsAmount > 0 ? 'emerald' : 'neutral'} />
        <StoreStat
          label="Без файла накладной"
          value={missingInvoiceCount}
          loading={loading}
          tone={missingInvoiceCount > 0 ? 'amber' : 'neutral'}
          hint={missingInvoiceCount > 0 ? 'Прикрепите скан — пригодится при споре' : 'Все накладные на месте'}
        />
      </StoreStatGrid>

      {error ? (
        <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-300">{error}</div>
      ) : null}
      {success ? (
        <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/10 px-4 py-2.5 text-sm text-emerald-700 dark:text-emerald-300">{success}</div>
      ) : null}

      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative min-w-0 flex-1 sm:max-w-md">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            value={receiptSearch}
            onChange={(e) => setReceiptSearch(e.target.value)}
            placeholder="Поиск по поставщику, товару, накладной..."
            className="h-9 pl-9"
          />
        </div>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button size="sm" variant="outline" className="h-9 gap-1.5">
              <MoreHorizontal className="h-3.5 w-3.5" />
              Действия
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuLabel>Приёмка</DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => setFormSheetOpen(true)}>
              <PackagePlus className="h-4 w-4" />
              Новый документ
            </DropdownMenuItem>
            <DropdownMenuItem
              onClick={form.clearBasics}
            >
              <Trash2 className="h-4 w-4" />
              Очистить форму
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <ReceiptDraftsSection
        drafts={data?.drafts || []}
        canDelete={canDelete}
        onApply={form.applyDraft}
        onDelete={(id) => void form.deleteDraft(id)}
      />

      {/* Документы */}
      <StoreSection title="Документы приёмки" subtitle={`${filteredReceipts.length} шт`} flush>
        {loading && filteredReceipts.length === 0 ? (
          <>
            <div className="space-y-3 p-3 sm:hidden">
              {Array.from({ length: 4 }).map((_, idx) => (
                <div key={idx} className="space-y-2 p-2">
                  <Skeleton className="h-4 w-36" />
                  <Skeleton className="h-3 w-24" />
                  <Skeleton className="h-8 w-full" />
                </div>
              ))}
            </div>
            <div className="hidden sm:block">
              <StoreDataTableSkeleton columns={7} />
            </div>
          </>
        ) : (
          <ReceiptsList
            receipts={sortedReceipts}
            debtByReceiptId={debtByReceiptId}
            sort={receiptSort}
            onSort={toggleReceiptSort}
            refreshing={refreshing}
            searching={Boolean(receiptSearch)}
            canCreate={canCreate}
            onCreate={() => setFormSheetOpen(true)}
            onOpen={(receipt) => {
              setSelectedReceipt(receipt)
              setReceiptDetailsOpen(true)
            }}
          />
        )}
      </StoreSection>

      <ReceiptFormModal form={form} data={data} error={error} />

      <ReceiptCancelModal
        target={cancelTarget}
        reason={cancelReason}
        busy={cancelBusy}
        onReason={setCancelReason}
        onClose={() => setCancelTarget(null)}
        onConfirm={() => void confirmCancelReceipt()}
      />

      <ReceiptDetailsModal
        open={receiptDetailsOpen}
        receipt={selectedReceipt}
        canCancel={canCancel}
        onClose={() => setReceiptDetailsOpen(false)}
        onCancel={cancelReceipt}
      />
    </div>
    </TooltipProvider>
  )
}
