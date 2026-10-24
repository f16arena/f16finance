import { useEffect, useMemo, useState } from 'react'

import * as api from '@/lib/api'
import type { SaleReceiptPreview } from '@/lib/receipt-html'
import { resolveRuntimeShift } from '@/lib/shift-runtime'
import { toastError, toastSuccess } from '@/lib/toast'
import { localRef } from '@/lib/utils'
import type { AppConfig, OperatorSession, PointInventoryReturnContext, PointReceiptSettings } from '@/types'

export type ReturnLine = {
  item_id: string
  quantity: number
  unit_price: number
}

export function roundMoney(value: number) {
  return Math.round((value + Number.EPSILON) * 100) / 100
}

export function roundQty(value: number) {
  return Math.round((value + Number.EPSILON) * 1000) / 1000
}

export function paymentBadge(paymentMethod: string) {
  if (paymentMethod === 'cash') return 'Наличные'
  if (paymentMethod === 'kaspi') return 'Безналичный'
  return 'Смешанная'
}

export function useReturnsEngine(args: { config: AppConfig; session: OperatorSession }) {
  const { config, session } = args
  const runtimeShift = useMemo(() => resolveRuntimeShift(), [])
  const [context, setContext] = useState<PointInventoryReturnContext | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedSaleId, setSelectedSaleId] = useState<string>('')
  const [search, setSearch] = useState('')
  const [comment, setComment] = useState('')
  const [cart, setCart] = useState<ReturnLine[]>([])
  const [receiptSettings, setReceiptSettings] = useState<PointReceiptSettings | null>(null)
  const [lastReceipt, setLastReceipt] = useState<SaleReceiptPreview | null>(null)

  const operatorName = session.operator.full_name || session.operator.name || session.operator.username

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

  async function load(preserveSaleId?: string) {
    setLoading(true)
    setError(null)
    try {
      const data = await api.getPointInventoryReturns(config, session)
      setContext(data)
      const shiftSales = data.sales || []
      const nextSaleId =
        preserveSaleId && shiftSales.some((sale) => sale.id === preserveSaleId)
          ? preserveSaleId
          : shiftSales[0]?.id || ''
      setSelectedSaleId(nextSaleId)
      setCart([])
    } catch (err: any) {
      setContext(null)
      setError(err?.message || 'Не удалось загрузить возвраты точки')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const currentShiftSales = useMemo(() => {
    const sales = context?.sales || []
    return [...sales].sort((a, b) => {
      const aCurrent = a.sale_date === runtimeShift.date && a.shift === runtimeShift.shift ? 1 : 0
      const bCurrent = b.sale_date === runtimeShift.date && b.shift === runtimeShift.shift ? 1 : 0
      if (aCurrent !== bCurrent) return bCurrent - aCurrent
      return String(b.sold_at || '').localeCompare(String(a.sold_at || ''))
    })
  }, [context?.sales, runtimeShift])

  const selectedSale = useMemo(
    () => currentShiftSales.find((sale) => sale.id === selectedSaleId) || null,
    [currentShiftSales, selectedSaleId],
  )

  const saleItems = useMemo(() => {
    const query = search.trim().toLowerCase()
    const items = Array.isArray(selectedSale?.items) ? selectedSale.items : []
    if (!query) return items
    return items.filter((line) => {
      const name = String(line.item?.name || (line as any).universal_name || '').toLowerCase()
      const barcode = String(line.item?.barcode || '').toLowerCase()
      return name.includes(query) || barcode.includes(query)
    })
  }, [search, selectedSale])

  const cartDetailed = useMemo(() => {
    const saleItemsById = new Map((Array.isArray(selectedSale?.items) ? selectedSale.items : []).map((line) => [line.id, line]))
    return cart
      .map((line) => {
        const saleLine = saleItemsById.get(line.item_id)
        if (!saleLine) return null
        return { ...line, saleLine, total: roundMoney(line.quantity * line.unit_price) }
      })
      .filter((line): line is NonNullable<typeof line> => !!line)
  }, [cart, selectedSale])

  const cartTotal = useMemo(() => cartDetailed.reduce((sum, line) => sum + line.total, 0), [cartDetailed])

  function getReturnableQty(saleLineId: string) {
    const saleLine = (selectedSale?.items || []).find((line) => line.id === saleLineId)
    return roundQty(Number(saleLine?.returnable_qty || 0))
  }

  function addToCart(saleLineId: string) {
    const saleLine = (selectedSale?.items || []).find((line) => line.id === saleLineId)
    if (!saleLine) return
    const maxQty = getReturnableQty(saleLineId)
    if (maxQty <= 0) {
      toastError('По этой позиции уже нечего возвращать')
      return
    }
    setCart((current) => {
      const existing = current.find((line) => line.item_id === saleLineId)
      if (!existing) {
        return [...current, { item_id: saleLineId, quantity: Math.min(1, maxQty), unit_price: Number(saleLine.unit_price || 0) }]
      }
      const nextQty = Math.min(maxQty, roundQty(existing.quantity + 1))
      return current.map((line) => (line.item_id === saleLineId ? { ...line, quantity: nextQty } : line))
    })
  }

  function changeQty(saleLineId: string, nextQty: number) {
    const maxQty = getReturnableQty(saleLineId)
    if (nextQty <= 0) {
      setCart((current) => current.filter((line) => line.item_id !== saleLineId))
      return
    }
    setCart((current) =>
      current.map((line) => (line.item_id === saleLineId ? { ...line, quantity: Math.min(maxQty, roundQty(nextQty)) } : line)),
    )
  }

  function buildRefundAmounts() {
    if (!selectedSale || cartTotal <= 0) {
      return {
        paymentMethod: 'cash' as string,
        cashAmount: 0,
        kaspiAmount: 0,
        kaspiBeforeMidnightAmount: 0,
        kaspiAfterMidnightAmount: 0,
      }
    }
    const saleTotal = Number(selectedSale.total_amount || 0)
    const paymentMethod = selectedSale.payment_method as string

    if (paymentMethod === 'cash') {
      return {
        paymentMethod,
        cashAmount: roundMoney(cartTotal),
        kaspiAmount: 0,
        kaspiBeforeMidnightAmount: 0,
        kaspiAfterMidnightAmount: 0,
      }
    }

    if (paymentMethod === 'kaspi') {
      const kaspiAmount = roundMoney(cartTotal)
      const beforeRatio =
        saleTotal > 0 && Number(selectedSale.kaspi_amount || 0) > 0
          ? Number(selectedSale.kaspi_before_midnight_amount || 0) / Number(selectedSale.kaspi_amount || 0)
          : 0
      const kaspiBeforeMidnightAmount = roundMoney(kaspiAmount * beforeRatio)
      const kaspiAfterMidnightAmount = roundMoney(kaspiAmount - kaspiBeforeMidnightAmount)
      return { paymentMethod, cashAmount: 0, kaspiAmount, kaspiBeforeMidnightAmount, kaspiAfterMidnightAmount }
    }

    const cashRatio = saleTotal > 0 ? Number(selectedSale.cash_amount || 0) / saleTotal : 0
    const cashAmount = roundMoney(cartTotal * cashRatio)
    const kaspiAmount = roundMoney(cartTotal - cashAmount)
    const beforeRatio =
      Number(selectedSale.kaspi_amount || 0) > 0
        ? Number(selectedSale.kaspi_before_midnight_amount || 0) / Number(selectedSale.kaspi_amount || 0)
        : 0
    const kaspiBeforeMidnightAmount = roundMoney(kaspiAmount * beforeRatio)
    const kaspiAfterMidnightAmount = roundMoney(kaspiAmount - kaspiBeforeMidnightAmount)
    return { paymentMethod, cashAmount, kaspiAmount, kaspiBeforeMidnightAmount, kaspiAfterMidnightAmount }
  }

  async function submit() {
    if (!selectedSale) {
      toastError('Сначала выберите продажу')
      return
    }
    if (cartDetailed.length === 0) {
      toastError('Добавьте хотя бы одну проданную позицию в возврат')
      return
    }

    const refund = buildRefundAmounts()

    setSaving(true)
    try {
      const returnRef = localRef()
      const result = await api.createPointInventoryReturn(config, session, {
        sale_id: selectedSale.id,
        return_date: runtimeShift.date,
        shift: runtimeShift.shift,
        payment_method: refund.paymentMethod as any,
        cash_amount: refund.cashAmount,
        kaspi_amount: refund.kaspiAmount,
        kaspi_before_midnight_amount: refund.kaspiBeforeMidnightAmount,
        kaspi_after_midnight_amount: refund.kaspiAfterMidnightAmount,
        comment: comment.trim() || null,
        local_ref: returnRef,
        items: cartDetailed.map((line) => ({
          // Универсальная позиция чека: item_id = null + universal_name
          item_id: line.saleLine.item?.id ? String(line.saleLine.item.id) : null,
          universal_name: line.saleLine.item?.id ? null : (line.saleLine as any).universal_name || null,
          quantity: line.quantity,
          unit_price: line.unit_price,
        })),
      })

      // Preview чека возврата согласно приказу №626 — отдельный фискальный чек
      // «возврат прихода» со ссылкой на оригинальный чек.
      const nowTs = new Date()
      const originalSoldAt = selectedSale.sold_at ? new Date(selectedSale.sold_at) : null
      const returnPreview: SaleReceiptPreview = {
        saleId: (result as any)?.return_id || returnRef,
        saleDate: nowTs.toLocaleDateString('ru-RU'),
        saleTime: nowTs.toLocaleTimeString('ru-RU'),
        shift: runtimeShift.shift,
        paymentMethod: refund.paymentMethod as any,
        cashAmount: refund.cashAmount,
        kaspiAmount: refund.kaspiAmount,
        totalAmount: refund.cashAmount + refund.kaspiAmount,
        subtotal: refund.cashAmount + refund.kaspiAmount,
        discountAmount: 0,
        loyaltyDiscountAmount: 0,
        customer: null,
        comment: comment.trim() || null,
        operatorName,
        companyName: session.company?.name || '',
        locationName: (context as any)?.location?.name || '',
        receiptSettings,
        isReturn: true,
        originalSaleId: selectedSale.id,
        originalSaleDate: originalSoldAt ? originalSoldAt.toLocaleDateString('ru-RU') : null,
        originalSaleTime: originalSoldAt ? originalSoldAt.toLocaleTimeString('ru-RU') : null,
        refundReason: comment.trim() || null,
        lines: cartDetailed.map((line) => ({
          name: line.saleLine.item?.name || (line.saleLine as any).universal_name || 'Товар',
          quantity: line.quantity,
          unit_price: line.unit_price,
          total: line.quantity * line.unit_price,
          unit: (line.saleLine.item as { unit?: string | null } | null)?.unit || null,
        })),
      }
      setLastReceipt(returnPreview)

      toastSuccess('Возврат оформлен — чек ниже')
      setComment('')
      await load(selectedSale.id)
    } catch (err: any) {
      toastError(err?.message || 'Не удалось провести возврат')
    } finally {
      setSaving(false)
    }
  }

  /** Выбор другой продажи; при непустой корзине просит подтверждение. */
  function selectSale(id: string) {
    if (cart.length > 0 && !window.confirm('В корзине есть товары для возврата. Переключить продажу и очистить корзину?')) return
    setSelectedSaleId(id)
    setCart([])
    setSearch('')
  }

  return {
    runtimeShift,
    loading,
    saving,
    error,
    currentShiftSales,
    selectedSale,
    selectedSaleId,
    search,
    setSearch,
    comment,
    setComment,
    saleItems,
    cart,
    setCart,
    cartDetailed,
    cartTotal,
    refund: buildRefundAmounts(),
    lastReceipt,
    setLastReceipt,
    operatorName,
    load,
    getReturnableQty,
    addToCart,
    changeQty,
    selectSale,
    submit,
  }
}
