/**
 * История продаж точки: онлайн GET /api/point/sales-history (7 дней, серверный q),
 * офлайн-фолбэк — локальная очередь + кэш последних серверных продаж этой кассы.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import * as api from '@/lib/api'
import type { PointSalesHistorySale } from '@/lib/api'
import { getCachedSalesContext } from '@/lib/cache'
import { getQueueItems } from '@/lib/offline'
import type { SaleReceiptPreview } from '@/lib/receipt-html'
import { toastError } from '@/lib/toast'
import { useCashlessLabels } from '@/lib/use-cashless-labels'
import type { AppConfig, OperatorSession, PointInventorySaleContext, PointReceiptSettings } from '@/types'

/** Продажа в списке: серверная или локальная (из офлайн-очереди) */
export type HistorySale = PointSalesHistorySale & {
  /** true — продажа ещё в очереди на отправку (офлайн) */
  pending?: boolean
}

function isSameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

export function formatSoldAt(soldAt: string | null, saleDate: string): string {
  const d = soldAt ? new Date(soldAt) : null
  if (!d || Number.isNaN(d.getTime())) return saleDate || '—'
  const time = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
  const today = new Date()
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (isSameDay(d, today)) return `сегодня ${time}`
  if (isSameDay(d, yesterday)) return `вчера ${time}`
  return `${d.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })} ${time}`
}

function operatorNameFromSession(operator: Record<string, unknown> | null | undefined): string | null {
  if (!operator) return null
  const value =
    (operator.full_name as string) ||
    (operator.name as string) ||
    (operator.short_name as string) ||
    (operator.username as string) ||
    ''
  return value.trim() || null
}

/** Продажи из офлайн-очереди (ещё не отправлены на сервер) */
async function loadLocalSales(): Promise<HistorySale[]> {
  const result: HistorySale[] = []

  const cachedContext = await getCachedSalesContext<PointInventorySaleContext>().catch(() => null)
  const itemsById = new Map((cachedContext?.items || []).map((item) => [item.id, item]))

  // 1) Очередь неотправленных продаж этой кассы
  try {
    const queue = await getQueueItems()
    for (const entry of queue) {
      if (entry.type !== 'inventory_sale') continue
      const p = entry.payload || {}
      const cash = Number(p.cash_amount || 0)
      const kaspi = Number(p.kaspi_amount || 0)
      const rawItems = Array.isArray(p.items) ? (p.items as Array<Record<string, unknown>>) : []
      const lines = rawItems.map((line) => {
        const itemId = (line.item_id as string) || null
        const catalogItem = itemId ? itemsById.get(itemId) : null
        const quantity = Number(line.quantity || 0)
        const unitPrice = Number(line.unit_price || 0)
        return {
          name: String((line.universal_name as string) || catalogItem?.name || 'Товар'),
          unit: String(catalogItem?.unit || 'шт'),
          quantity,
          unit_price: unitPrice,
          total_price: Math.round((quantity * unitPrice + Number.EPSILON) * 100) / 100,
        }
      })
      result.push({
        id: String(p.local_ref || entry.local_ref || `local-${entry.id}`),
        sold_at: entry.created_at || null,
        sale_date: String(p.sale_date || ''),
        shift: String(p.shift || 'day'),
        payment_method: String(p.payment_method || 'cash'),
        cash_amount: cash,
        kaspi_amount: kaspi,
        card_amount: 0,
        online_amount: 0,
        total_amount: cash + kaspi,
        discount_amount: Number(p.discount_amount || 0),
        comment: (p.comment as string) || null,
        operator_name: operatorNameFromSession((p._session as any)?.operator || null),
        items: lines,
        pending: true,
      })
    }
  } catch {
    /* очередь недоступна — покажем что есть из кэша */
  }

  // 2) Кэш последних серверных продаж (последний удачный ответ inventory-sales)
  for (const sale of cachedContext?.sales || []) {
    result.push({
      id: String(sale.id),
      sold_at: sale.sold_at || null,
      sale_date: sale.sale_date,
      shift: sale.shift,
      payment_method: sale.payment_method,
      cash_amount: Number(sale.cash_amount || 0),
      kaspi_amount: Number(sale.kaspi_amount || 0),
      card_amount: 0,
      online_amount: 0,
      total_amount: Number(sale.total_amount || 0),
      discount_amount: 0,
      comment: sale.comment || null,
      operator_name: null,
      items: (sale.items || []).map((line) => ({
        name: String((line as any).universal_name || line.item?.name || 'Товар'),
        unit: 'шт',
        quantity: Number(line.quantity || 0),
        unit_price: Number(line.unit_price || 0),
        total_price: Number(line.total_price || 0),
      })),
    })
  }

  result.sort((a, b) => new Date(b.sold_at || 0).getTime() - new Date(a.sold_at || 0).getTime())
  return result
}

export function useHistoryEngine(args: { config: AppConfig; session: OperatorSession }) {
  const { config, session } = args
  const cashLabels = useCashlessLabels(session)
  const [sales, setSales] = useState<HistorySale[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [offlineMode, setOfflineMode] = useState(false)
  const [query, setQuery] = useState('')
  const [selectedSale, setSelectedSale] = useState<HistorySale | null>(null)
  const [printPreview, setPrintPreview] = useState<SaleReceiptPreview | null>(null)
  const [receiptSettings, setReceiptSettings] = useState<PointReceiptSettings | null>(null)
  const searchTimeout = useRef<ReturnType<typeof setTimeout> | null>(null)
  const loadNonce = useRef(0)

  const operatorName = session.operator.full_name || session.operator.name || session.operator.username

  // Реквизиты ККМ для чека-копии (getPointReceiptSettings сам кэширует офлайн)
  useEffect(() => {
    let cancelled = false
    void (async () => {
      const settings = await api.getPointReceiptSettings(config, session.company.id)
      if (!cancelled) setReceiptSettings(settings)
    })()
    return () => {
      cancelled = true
    }
  }, [config, session.company.id])

  const load = useCallback(
    async (q: string) => {
      const nonce = ++loadNonce.current
      setLoading(true)
      try {
        const data = await api.getPointSalesHistory(config, { days: 7, q, limit: 100 }, session.company.id)
        if (nonce !== loadNonce.current) return
        setSales(data)
        setOfflineMode(false)
      } catch {
        // Сервер недоступен — офлайн-фолбэк: очередь + кэш этой кассы
        const local = await loadLocalSales()
        if (nonce !== loadNonce.current) return
        const trimmed = q.trim()
        const qNum = Number(trimmed.replace(/\s/g, '').replace(',', '.'))
        const filtered = trimmed
          ? local.filter((sale) =>
              Number.isFinite(qNum) && qNum > 0
                ? Math.abs(sale.total_amount - qNum) < 0.005
                : (sale.comment || '').toLowerCase().includes(trimmed.toLowerCase()),
            )
          : local
        setSales(filtered)
        setOfflineMode(true)
      } finally {
        if (nonce === loadNonce.current) setLoading(false)
      }
    },
    [config, session.company.id],
  )

  // Первичная загрузка
  useEffect(() => {
    void load('')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Поиск с debounce (серверный q)
  useEffect(() => {
    if (searchTimeout.current) clearTimeout(searchTimeout.current)
    searchTimeout.current = setTimeout(() => {
      void load(query)
    }, 400)
    return () => {
      if (searchTimeout.current) clearTimeout(searchTimeout.current)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query])

  const totalShown = useMemo(() => (sales || []).reduce((sum, sale) => sum + Number(sale.total_amount || 0), 0), [sales])

  function paymentLabel(method: string) {
    if (method === 'cash') return 'Наличные'
    if (method === 'kaspi') return cashLabels.providerName
    return 'Смешанная'
  }

  function handlePrintCopy(sale: HistorySale) {
    if (!sale.items || sale.items.length === 0) {
      toastError('У этого чека нет позиций — печать копии недоступна')
      return
    }
    const soldAt = sale.sold_at ? new Date(sale.sold_at) : null
    const validDate = soldAt && !Number.isNaN(soldAt.getTime()) ? soldAt : null
    const subtotal = sale.items.reduce((sum, line) => sum + Number(line.total_price || 0), 0)
    const preview: SaleReceiptPreview = {
      saleId: sale.id,
      saleDate: validDate ? validDate.toLocaleDateString('ru-RU') : sale.sale_date,
      saleTime: validDate ? validDate.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '',
      shift: sale.shift,
      paymentMethod: (sale.payment_method as 'cash' | 'kaspi' | 'mixed') || 'cash',
      cashAmount: Number(sale.cash_amount || 0),
      kaspiAmount: Number(sale.kaspi_amount || 0),
      totalAmount: Number(sale.total_amount || 0),
      subtotal,
      discountAmount: Number(sale.discount_amount || 0),
      loyaltyDiscountAmount: 0,
      customer: null,
      comment: sale.comment,
      operatorName: sale.operator_name || '—',
      companyName: session.company.name,
      locationName: '',
      receiptSettings,
      isCopy: true,
      lines: sale.items.map((line) => ({
        name: line.name,
        quantity: line.quantity,
        unit_price: line.unit_price,
        total: line.total_price,
        unit: line.unit || null,
      })),
    }
    setPrintPreview(preview)
  }

  return {
    sales: sales || [],
    initialLoading: loading && sales === null,
    loading,
    offlineMode,
    query,
    setQuery,
    selectedSale,
    setSelectedSale,
    printPreview,
    setPrintPreview,
    totalShown,
    operatorName,
    providerName: cashLabels.providerName,
    paymentLabel,
    handlePrintCopy,
    reload: () => load(query),
  }
}
