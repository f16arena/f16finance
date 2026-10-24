import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import * as api from '@/lib/api'
import * as offline from '@/lib/offline'
import { getCachedSalesContext, saveSalesContextCache } from '@/lib/cache'
import { beep, type SaleReceiptPreview } from '@/lib/receipt-html'
import { resolveRuntimeShift } from '@/lib/shift-runtime'
import { toastError, toastSuccess } from '@/lib/toast'
import { useCashlessLabels } from '@/lib/use-cashless-labels'
import { useSyncWatcher } from '@/lib/use-sync-watcher'
import { localRef, parseMoney } from '@/lib/utils'
import type { AppConfig, BootstrapData, OperatorSession, PointInventorySaleContext, PointInventorySaleItem } from '@/types'

/**
 * Логика продажи для кассы v2 — без разметки.
 *
 * Повторяет проверенный путь старого экрана (pages/InventorySalesPageMinimal):
 * тот же запрос продажи, оптимистичный чек, офлайн-очередь при обрыве сети,
 * экран покупателя, кэш витрины. Меняется только то, что видит оператор.
 */

export type CartLine = {
  id: string
  item_id: string | null
  name: string
  unit?: string | null
  quantity: number
  unit_price: number
  comment?: string | null
}

export type PayMethod = 'cash' | 'kaspi' | 'mixed'

const UNIVERSAL_PREFIX = 'universal:'

export function useSaleEngine(params: { config: AppConfig; bootstrap: BootstrapData; session: OperatorSession }) {
  const { config, bootstrap, session } = params
  const runtimeShift = useMemo(() => resolveRuntimeShift(), [])
  const isNightAfterMidnight = runtimeShift.shift === 'night' && new Date().getHours() < 12
  const cashLabels = useCashlessLabels(session)

  const [context, setContext] = useState<PointInventorySaleContext | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [cart, setCart] = useState<CartLine[]>([])
  const [discountPercent, setDiscountPercent] = useState(0)
  const [comment, setComment] = useState('')
  const [saving, setSaving] = useState(false)
  const [lastReceipt, setLastReceipt] = useState<SaleReceiptPreview | null>(null)
  const [receiptSettings, setReceiptSettings] = useState<any>(null)
  const savingRef = useRef(false)

  const operatorName =
    session.operator?.full_name?.trim() ||
    session.operator?.name?.trim() ||
    session.operator?.short_name?.trim() ||
    session.operator?.username?.trim() ||
    bootstrap?.operatorName?.trim() ||
    'Оператор'

  // ── Витрина ────────────────────────────────────────────────────────────
  const load = useCallback(
    async (silent: boolean) => {
      if (!silent) setLoading(true)
      setError(null)
      try {
        const data = await api.getPointInventorySales(config, session)
        setContext(data)
        void saveSalesContextCache(data)
      } catch (err: any) {
        if (!silent) setError(err?.message || 'Не удалось загрузить витрину')
      } finally {
        if (!silent) setLoading(false)
      }
    },
    [config, session],
  )

  useEffect(() => {
    let cancelled = false
    void (async () => {
      // Сначала кэш — касса показывает товары сразу, даже без сети.
      const cached = await getCachedSalesContext<PointInventorySaleContext>().catch(() => null)
      if (cached && !cancelled) {
        setContext(cached)
        setLoading(false)
      }
      if (!cancelled) void load(Boolean(cached))
    })()
    return () => {
      cancelled = true
    }
  }, [load])

  useEffect(() => {
    let cancelled = false
    void api
      .getPointReceiptSettings(config, session.company.id)
      .then((s) => !cancelled && setReceiptSettings(s))
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [config, session.company.id])

  // Цены и остатки меняются на сайте — проверяем раз в 30 с, как старый экран.
  const sync = useSyncWatcher({ config, watch: ['catalogVersion', 'balancesVersion'], onSyncNeeded: () => void load(true) })

  const items = context?.items || []
  const itemsById = useMemo(() => new Map(items.map((i) => [i.id, i])), [items])

  // ── Корзина ────────────────────────────────────────────────────────────
  const addItem = useCallback(
    (item: PointInventorySaleItem) => {
      const available = Number(item.display_qty || 0)
      if (available <= 0) {
        beep('error')
        toastError(`Нет на витрине: ${item.name}`)
        return false
      }
      let ok = true
      setCart((prev) => {
        const existing = prev.find((l) => l.item_id === item.id)
        if (existing) {
          if (existing.quantity + 1 > available) {
            ok = false
            return prev
          }
          return prev.map((l) => (l.item_id === item.id ? { ...l, quantity: l.quantity + 1 } : l))
        }
        return [...prev, { id: item.id, item_id: item.id, name: item.name, unit: item.unit, quantity: 1, unit_price: Number(item.sale_price || 0) }]
      })
      if (!ok) {
        beep('error')
        toastError(`На витрине только ${available} ${item.unit || 'шт'}: ${item.name}`)
      }
      return ok
    },
    [],
  )

  const addUniversal = useCallback((name: string, price: number) => {
    const clean = name.trim()
    if (!clean || !(price > 0)) return
    setCart((prev) => [
      ...prev,
      { id: `${UNIVERSAL_PREFIX}${localRef()}`, item_id: null, name: clean, quantity: 1, unit_price: Math.round(price) },
    ])
  }, [])

  const changeQty = useCallback(
    (lineId: string, quantity: number) => {
      setCart((prev) =>
        prev.flatMap((l) => {
          if (l.id !== lineId) return [l]
          if (quantity <= 0) return []
          const available = l.item_id ? Number(itemsById.get(l.item_id)?.display_qty || 0) : Infinity
          if (quantity > available) {
            beep('error')
            toastError(`На витрине только ${available} ${l.unit || 'шт'}`)
            return [l]
          }
          return [{ ...l, quantity }]
        }),
      )
    },
    [itemsById],
  )

  const clear = useCallback(() => {
    setCart([])
    setDiscountPercent(0)
    setComment('')
  }, [])

  /** Поиск/сканер: точный штрихкод добавляет товар сразу. */
  const scan = useCallback(
    (raw: string) => {
      const q = raw.trim()
      if (!q) return false
      const byBarcode = items.find((it) => it.barcode === q)
      if (byBarcode) return addItem(byBarcode)
      return false
    },
    [items, addItem],
  )

  // ── Суммы ──────────────────────────────────────────────────────────────
  const subtotal = cart.reduce((s, l) => s + l.quantity * l.unit_price, 0)
  const discountAmount = Math.round(((subtotal * Math.max(0, Math.min(99, discountPercent))) / 100) * 100) / 100
  const total = Math.max(0, subtotal - discountAmount)
  const pieces = cart.reduce((s, l) => s + l.quantity, 0)

  // Экран покупателя видит корзину вживую (тот же канал, что у старого экрана).
  useEffect(() => {
    try {
      window.electron.customerDisplay.push({
        kind: 'update',
        state: {
          companyName: session.company?.name || null,
          operatorName,
          cart: cart.map((l) => ({
            id: l.id,
            name: l.name,
            quantity: l.quantity,
            unit_price: l.unit_price,
            comment: l.comment || null,
            image_url: (l.item_id ? itemsById.get(l.item_id)?.image_url : null) || null,
          })),
          lastAddedId: cart[cart.length - 1]?.id || null,
          subtotal,
          discount: discountAmount,
          total,
          paymentMethod: 'cash',
          customer: null,
          playlist: [],
          reviewUrl: receiptSettings?.review_url?.trim() || null,
        },
      })
    } catch {
      /* экрана покупателя нет */
    }
  }, [cart, subtotal, discountAmount, total, session.company?.name, operatorName, itemsById, receiptSettings])

  // ── Оплата ─────────────────────────────────────────────────────────────
  const pay = useCallback(
    async (method: PayMethod, mixedCash = 0) => {
      if (savingRef.current) return false
      if (cart.length === 0) {
        toastError('Корзина пуста')
        return false
      }
      if (total <= 0) {
        toastError('Сумма должна быть больше 0')
        return false
      }
      const cashAmount = method === 'cash' ? total : method === 'mixed' ? Math.max(0, Math.min(total, mixedCash)) : 0
      const kaspiAmount = method === 'kaspi' ? total : method === 'mixed' ? Math.max(0, total - cashAmount) : 0
      if (method === 'mixed' && (cashAmount <= 0 || kaspiAmount <= 0)) {
        toastError('Для смешанной оплаты нужны обе суммы')
        return false
      }

      savingRef.current = true
      setSaving(true)
      const ref = localRef()
      const salePayload = {
        sale_date: new Date().toISOString().slice(0, 10),
        shift: runtimeShift.shift,
        payment_method: method,
        cash_amount: cashAmount,
        kaspi_amount: kaspiAmount,
        kaspi_before_midnight_amount: runtimeShift.shift === 'night' && isNightAfterMidnight ? 0 : kaspiAmount,
        kaspi_after_midnight_amount: runtimeShift.shift === 'night' && isNightAfterMidnight ? kaspiAmount : 0,
        comment: comment.trim() || null,
        local_ref: ref,
        items: cart.map((l) => ({
          item_id: l.item_id,
          universal_name: l.item_id ? null : l.name,
          quantity: l.quantity,
          unit_price: l.unit_price,
          comment: l.comment || null,
        })),
        customer_id: null,
        loyalty_points_spent: 0,
        discount_amount: discountAmount,
        loyalty_discount_amount: 0,
      }

      const now = new Date()
      setLastReceipt({
        saleId: ref,
        saleDate: now.toLocaleDateString('ru-RU'),
        saleTime: now.toLocaleTimeString('ru-RU'),
        shift: runtimeShift.shift,
        paymentMethod: method,
        cashAmount,
        kaspiAmount,
        totalAmount: total,
        subtotal,
        discountAmount,
        loyaltyDiscountAmount: 0,
        customer: null,
        comment: comment.trim() || null,
        operatorName,
        companyName: session.company?.name || '',
        locationName: context?.location?.name || '',
        receiptSettings,
        lines: cart.map((l) => ({ name: l.name, quantity: l.quantity, unit_price: l.unit_price, total: l.quantity * l.unit_price, unit: l.unit || null })),
      } as SaleReceiptPreview)

      const snapshot = { cart: [...cart], discountPercent, comment }
      try {
        window.electron.customerDisplay.push({
          kind: 'paid',
          total,
          paymentLabel: method === 'cash' ? 'Оплата наличными' : method === 'kaspi' ? `Оплата ${cashLabels.providerName}` : 'Смешанная оплата',
          lines: snapshot.cart.map((l) => ({
            name: l.name,
            quantity: l.quantity,
            unit_price: l.unit_price,
            image_url: (l.item_id ? itemsById.get(l.item_id)?.image_url : null) || null,
          })),
          loyalty: null,
          reviewUrl: receiptSettings?.review_url?.trim() || null,
        })
      } catch {
        /* нет экрана покупателя */
      }
      clear()
      savingRef.current = false
      setSaving(false)

      const restore = () => {
        setCart(snapshot.cart)
        setDiscountPercent(snapshot.discountPercent)
        setComment(snapshot.comment)
        setLastReceipt(null)
      }

      try {
        const result = await api.createPointInventorySale(config, session, salePayload as any)
        const receiptUrl = (result as any)?.receipt_url || null
        let qrDataUrl: string | null = null
        if (receiptUrl) {
          try {
            const QRCode = (await import('qrcode')).default
            qrDataUrl = await QRCode.toDataURL(String(receiptUrl), { margin: 1, width: 240 })
          } catch {
            /* QR не обязателен */
          }
        }
        setLastReceipt((prev) => (prev && prev.saleId === ref ? { ...prev, saleId: result?.sale_id || ref, onlineUrl: receiptUrl, qrDataUrl } : prev))
        toastSuccess('Продажа сохранена')
        beep('ok')
        void load(true)
        return true
      } catch (err: any) {
        const network =
          /Failed to fetch|NetworkError|Превышено время ожидания|fetch failed/.test(String(err?.message || '')) || !navigator.onLine
        if (network) {
          try {
            await offline.queueInventorySale(salePayload, session, session.company.id)
            toastSuccess('Нет интернета — продажа в очереди, отправится сама')
            beep('ok')
            return true
          } catch (queueErr: any) {
            beep('error')
            toastError('Не удалось сохранить даже локально: ' + (queueErr?.message || 'ошибка'))
            restore()
            return false
          }
        }
        beep('error')
        toastError(
          err?.payload?.error === 'point-shift-no-open'
            ? 'Смена не открыта — откройте её на вкладке «Смена»'
            : err?.message || 'Не удалось провести продажу',
        )
        restore()
        return false
      }
    },
    [cart, total, subtotal, discountAmount, discountPercent, comment, runtimeShift.shift, isNightAfterMidnight, operatorName, session, context, receiptSettings, cashLabels.providerName, itemsById, clear, config, load],
  )

  return {
    loading,
    error,
    reload: () => load(false),
    sync,
    items,
    itemsById,
    location: context?.location || null,
    cart,
    addItem,
    addUniversal,
    changeQty,
    clear,
    scan,
    discountPercent,
    setDiscountPercent,
    comment,
    setComment,
    subtotal,
    discountAmount,
    total,
    pieces,
    saving,
    pay,
    lastReceipt,
    closeReceipt: () => setLastReceipt(null),
    cashLabels,
    operatorName,
    runtimeShift,
    parseMoney,
  }
}
