import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

import * as api from '@/lib/api'
import * as offline from '@/lib/offline'
import { getCachedSalesContext, saveSalesContextCache } from '@/lib/cache'
import { deleteParkedCart, loadParkedCarts, saveParkedCart } from '@/lib/parked-carts'
import { beep, type SaleReceiptPreview } from '@/lib/receipt-html'
import { resolveRuntimeShift } from '@/lib/shift-runtime'
import { toastError, toastSuccess } from '@/lib/toast'
import { useCashlessLabels } from '@/lib/use-cashless-labels'
import { useSyncWatcher } from '@/lib/use-sync-watcher'
import { localRef, parseMoney } from '@/lib/utils'
import type {
  AppConfig,
  BootstrapData,
  Customer,
  LoyaltyConfig,
  OperatorSession,
  ParkedCart,
  PointInventorySaleContext,
  PointInventorySaleItem,
  PointInventorySaleRow,
  PointService,
  PointServiceSession,
  PointServiceVariant,
} from '@/types'

/**
 * Логика продажи для кассы v2 — без разметки.
 *
 * Повторяет проверенный путь старого экрана (pages/InventorySalesPageMinimal):
 * тот же запрос продажи, оптимистичный чек, офлайн-очередь при обрыве сети,
 * экран покупателя, кэш витрины, бонусы, промокод, отложенные корзины,
 * исправление оплаты. Меняется только то, что видит оператор.
 */

export type CartLine = {
  id: string
  item_id: string | null
  name: string
  unit?: string | null
  quantity: number
  unit_price: number
  comment?: string | null
  /** Строка-услуга: цену и название подтверждает сервер. */
  service_variant_id?: string | null
  /** Минуты таймера на единицу (для экрана покупателя и подписи). */
  duration_minutes?: number | null
  /** Продление идущего таймера. */
  extend_session_id?: string | null
}

export type PayMethod = 'cash' | 'kaspi' | 'mixed'

const UNIVERSAL_PREFIX = 'universal:'
const SERVICE_PREFIX = 'service:'

export function useSaleEngine(params: { config: AppConfig; bootstrap: BootstrapData; session: OperatorSession }) {
  const { config, bootstrap, session } = params
  const runtimeShift = useMemo(() => resolveRuntimeShift(), [])
  const isNightAfterMidnight = runtimeShift.shift === 'night' && new Date().getHours() < 12
  const cashLabels = useCashlessLabels(session)

  const [context, setContext] = useState<PointInventorySaleContext | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [cart, setCart] = useState<CartLine[]>([])
  const [manualDiscount, setManualDiscount] = useState(0)
  const [promo, setPromo] = useState<{ code: string; percent: number } | null>(null)
  const [customer, setCustomerState] = useState<Customer | null>(null)
  const [loyaltyConfig, setLoyaltyConfig] = useState<LoyaltyConfig | null>(null)
  const [pointsToSpend, setPointsToSpend] = useState(0)
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
        if ((data as any).loyalty_config) setLoyaltyConfig((data as any).loyalty_config)
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
  const addItem = useCallback((item: PointInventorySaleItem) => {
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
  }, [])

  const addUniversal = useCallback((name: string, price: number) => {
    const clean = name.trim()
    if (!clean || !(price > 0)) return
    setCart((prev) => [...prev, { id: `${UNIVERSAL_PREFIX}${localRef()}`, item_id: null, name: clean, quantity: 1, unit_price: Math.round(price) }])
  }, [])

  /** Услуга: без остатка, количество не ограничено. Продление — отдельной строкой. */
  const addService = useCallback((service: PointService, variant: PointServiceVariant, extend?: PointServiceSession | null) => {
    const lineId = `${SERVICE_PREFIX}${variant.id}${extend ? `:${extend.id}` : ''}`
    setCart((prev) => {
      const existing = prev.find((l) => l.id === lineId)
      if (existing) return prev.map((l) => (l.id === lineId ? { ...l, quantity: l.quantity + 1 } : l))
      return [
        ...prev,
        {
          id: lineId,
          item_id: null,
          name: `${service.name} · ${variant.name}${extend ? ' (продление)' : ''}`,
          quantity: 1,
          unit_price: Number(variant.price || 0),
          service_variant_id: variant.id,
          duration_minutes: variant.duration_minutes,
          extend_session_id: extend?.id || null,
        },
      ]
    })
    beep('ok')
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

  const setCustomer = useCallback((next: Customer | null) => {
    setCustomerState(next)
    setPointsToSpend(0)
  }, [])

  const clear = useCallback(() => {
    setCart([])
    setManualDiscount(0)
    setPromo(null)
    setCustomerState(null)
    setPointsToSpend(0)
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

  // ── Услуги и таймеры ───────────────────────────────────────────────────
  const services: PointService[] = context?.services || []
  const [sessions, setSessions] = useState<PointServiceSession[]>([])
  const loadSessions = useCallback(async () => {
    try {
      const data = await api.getServiceSessions(config, session)
      const next = data.sessions || []
      // Тот же список — не трогаем состояние, иначе экран продажи перерисуется зря.
      setSessions((prev) => (JSON.stringify(prev) === JSON.stringify(next) ? prev : next))
    } catch {
      /* старый сервер или нет сети — таймеры просто не видны */
    }
  }, [config, session])

  // Таймеры видят все кассы точки: продажу могли пробить на соседней.
  useEffect(() => {
    void loadSessions()
    const id = window.setInterval(() => void loadSessions(), 30_000)
    return () => window.clearInterval(id)
  }, [loadSessions])

  const finishSession = useCallback(
    async (id: string) => {
      setSessions((prev) => prev.filter((x) => x.id !== id))
      try {
        await api.finishServiceSession(config, session, id)
      } catch (err: any) {
        toastError(err?.message || 'Не удалось завершить таймер')
        void loadSessions()
      }
    },
    [config, session, loadSessions],
  )

  // ── Суммы (формулы старого экрана) ─────────────────────────────────────
  const subtotal = cart.reduce((s, l) => s + l.quantity * l.unit_price, 0)
  const discountPercent = promo ? promo.percent : Math.max(0, Math.min(99, manualDiscount))
  const discountAmount = Math.round(((subtotal * discountPercent) / 100) * 100) / 100
  const afterDiscount = Math.max(0, subtotal - discountAmount)
  const loyaltyActive = !!customer && !!loyaltyConfig?.is_active
  const maxRedeemablePoints = loyaltyActive
    ? Math.min(
        Math.max(0, customer!.loyalty_points || 0),
        Math.floor((afterDiscount * Math.max(0, Math.min(100, loyaltyConfig!.max_redeem_percent_per_purchase || 100))) / 100),
      )
    : 0
  const loyaltyDiscountAmount = loyaltyActive && pointsToSpend > 0 ? Math.min(pointsToSpend, afterDiscount, maxRedeemablePoints) : 0
  const total = Math.max(0, afterDiscount - loyaltyDiscountAmount)
  const pieces = cart.reduce((s, l) => s + l.quantity, 0)
  const pointsEarned = loyaltyActive ? Math.max(0, Math.floor((total / 100) * Number(loyaltyConfig!.points_per_100_tenge || 1))) : 0

  // ── Клиент и промокод ──────────────────────────────────────────────────
  const searchCustomers = useCallback(
    async (q: string) => {
      const result = await api.searchCustomers(config, q.trim())
      if (result?.loyalty_config) setLoyaltyConfig(result.loyalty_config)
      return result?.customers || []
    },
    [config],
  )

  const applyPromo = useCallback(
    async (raw: string) => {
      const code = raw.trim().toUpperCase()
      if (!code) return false
      try {
        const result = await api.validatePromoCode(config, code, subtotal)
        if (result.value > 0) {
          const pct = result.type === 'percent' ? result.value : subtotal > 0 ? (result.value / subtotal) * 100 : 0
          setPromo({ code, percent: Math.min(99, pct) })
          setManualDiscount(0)
          toastSuccess(`Промокод «${code}» применён`)
          return true
        }
        toastError('Промокод недействителен')
      } catch (err: any) {
        toastError(err?.message || 'Промокод недействителен')
      }
      return false
    },
    [config, subtotal],
  )

  const setDiscount = useCallback((percent: number) => {
    setPromo(null)
    setManualDiscount(Math.max(0, Math.min(99, percent)))
  }, [])

  // ── Отложенные корзины (до конца смены, как у старого экрана) ──────────
  const parkedScope = useMemo(
    () => ({ companyId: session.company.id, date: new Date().toISOString().slice(0, 10), shift: runtimeShift.shift }),
    [session.company.id, runtimeShift.shift],
  )
  const [parked, setParked] = useState<ParkedCart[]>(() => loadParkedCarts(parkedScope))

  const park = useCallback(
    (label?: string) => {
      if (cart.length === 0) return
      const name = label?.trim() || `Отложка #${parked.length + 1}`
      const next = saveParkedCart({
        ...parkedScope,
        cart: {
          id: `park-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
          label: name,
          createdAt: new Date().toISOString(),
          items: cart.map((l) => ({
            id: l.id,
            item_id: l.item_id,
            name: l.name,
            unit: l.unit || null,
            quantity: l.quantity,
            unit_price: l.unit_price,
            comment: l.comment || null,
            ...(l.service_variant_id
              ? { service_variant_id: l.service_variant_id, duration_minutes: l.duration_minutes ?? null, extend_session_id: l.extend_session_id || null }
              : {}),
          })),
          customer: customer ? { id: customer.id, name: customer.name, phone: customer.phone } : null,
          comment: comment.trim() || null,
        },
      })
      setParked(next)
      clear()
      toastSuccess(`Отложено: ${name}`)
      beep('ok')
    },
    [cart, parked.length, parkedScope, customer, comment, clear],
  )

  const restoreParked = useCallback(
    (id: string) => {
      const found = parked.find((p) => p.id === id)
      if (!found) return
      setCart(found.items.map((it) => ({ ...it, unit: it.unit || null, comment: it.comment || null })))
      setCustomerState(
        found.customer ? ({ id: found.customer.id, name: found.customer.name, phone: found.customer.phone, card_number: null, loyalty_points: 0, total_spent: 0 } as Customer) : null,
      )
      setPointsToSpend(0)
      setComment(found.comment || '')
      setParked(deleteParkedCart({ ...parkedScope, id }))
      toastSuccess(`Отложка восстановлена: ${found.label}`)
      beep('ok')
    },
    [parked, parkedScope],
  )

  const removeParked = useCallback((id: string) => setParked(deleteParkedCart({ ...parkedScope, id })), [parkedScope])

  // ── История продаж и исправление оплаты ────────────────────────────────
  const sales: PointInventorySaleRow[] = context?.sales || []

  const correctPayment = useCallback(
    async (sale: PointInventorySaleRow, method: PayMethod, cashInput: number, reason: string) => {
      if (!reason.trim()) {
        toastError('Укажите причину исправления')
        return false
      }
      const saleTotal = Number(sale.total_amount || 0)
      const cash = method === 'cash' ? saleTotal : method === 'mixed' ? Math.max(0, Math.min(saleTotal, cashInput)) : 0
      const kaspi = method === 'kaspi' ? saleTotal : method === 'mixed' ? Math.max(0, saleTotal - cash) : 0
      if (method === 'mixed' && (cash <= 0 || kaspi <= 0)) {
        toastError('В смешанной оплате обе суммы должны быть больше 0')
        return false
      }
      try {
        await api.correctPointInventorySalePayment(config, session, {
          sale_id: sale.id,
          payment_method: method,
          cash_amount: cash,
          kaspi_amount: kaspi,
          kaspi_before_midnight_amount: runtimeShift.shift === 'night' && isNightAfterMidnight ? 0 : kaspi,
          kaspi_after_midnight_amount: runtimeShift.shift === 'night' && isNightAfterMidnight ? kaspi : 0,
          reason: reason.trim(),
        })
        toastSuccess('Оплата исправлена')
        void load(true)
        return true
      } catch (err: any) {
        toastError(err?.message || 'Не удалось исправить оплату')
        return false
      }
    },
    [config, session, runtimeShift.shift, isNightAfterMidnight, load],
  )

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
          discount: discountAmount + loyaltyDiscountAmount,
          total,
          paymentMethod: 'cash',
          customer: customer ? { name: customer.name, phone: customer.phone, loyaltyPoints: Number(customer.loyalty_points || 0) } : null,
          playlist: [],
          reviewUrl: receiptSettings?.review_url?.trim() || null,
        },
      })
    } catch {
      /* экрана покупателя нет */
    }
  }, [cart, subtotal, discountAmount, loyaltyDiscountAmount, total, customer, session.company?.name, operatorName, itemsById, receiptSettings])

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
      const spentPoints = loyaltyActive ? Math.round(loyaltyDiscountAmount) : 0
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
          service_variant_id: l.service_variant_id || null,
          extend_session_id: l.extend_session_id || null,
          quantity: l.quantity,
          unit_price: l.unit_price,
          comment: l.comment || null,
        })),
        customer_id: customer?.id || null,
        loyalty_points_spent: customer ? spentPoints : 0,
        discount_amount: discountAmount,
        loyalty_discount_amount: loyaltyDiscountAmount,
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
        loyaltyDiscountAmount,
        customer: customer ? { name: customer.name, phone: customer.phone } : null,
        comment: comment.trim() || null,
        operatorName,
        companyName: session.company?.name || '',
        locationName: context?.location?.name || '',
        receiptSettings,
        lines: cart.map((l) => ({ name: l.name, quantity: l.quantity, unit_price: l.unit_price, total: l.quantity * l.unit_price, unit: l.unit || null })),
      } as SaleReceiptPreview)

      const snapshot = { cart: [...cart], manualDiscount, promo, customer, pointsToSpend, comment }
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
          loyalty: loyaltyActive
            ? {
                earned: pointsEarned,
                spent: spentPoints,
                totalAfter: Math.max(0, Number(customer!.loyalty_points || 0) + pointsEarned - spentPoints),
              }
            : null,
          reviewUrl: receiptSettings?.review_url?.trim() || null,
          timers: serviceTimersForDisplay(snapshot.cart, sessions),
        })
      } catch {
        /* нет экрана покупателя */
      }
      clear()
      savingRef.current = false
      setSaving(false)

      const restore = () => {
        setCart(snapshot.cart)
        setManualDiscount(snapshot.manualDiscount)
        setPromo(snapshot.promo)
        setCustomerState(snapshot.customer)
        setPointsToSpend(snapshot.pointsToSpend)
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
        const started = (result as any)?.service_sessions as PointServiceSession[] | undefined
        if (started?.length) {
          setSessions((prev) => mergeSessions(prev, started))
          void loadSessions()
        }
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
    [
      cart, total, subtotal, discountAmount, loyaltyDiscountAmount, loyaltyActive, pointsEarned, manualDiscount, promo, customer, pointsToSpend, comment,
      runtimeShift.shift, isNightAfterMidnight, operatorName, session, context, receiptSettings, cashLabels.providerName, itemsById, clear, config, load,
      sessions, loadSessions,
    ],
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
    addService,
    services,
    sessions,
    finishSession,
    changeQty,
    clear,
    scan,
    discountPercent,
    manualDiscount,
    setDiscount,
    promo,
    applyPromo,
    clearPromo: () => setPromo(null),
    customer,
    setCustomer,
    searchCustomers,
    loyaltyActive,
    loyaltyConfig,
    maxRedeemablePoints,
    pointsToSpend,
    setPointsToSpend,
    pointsEarned,
    loyaltyDiscountAmount,
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
    parked,
    park,
    restoreParked,
    removeParked,
    sales,
    correctPayment,
    cashLabels,
    operatorName,
    runtimeShift,
    parseMoney,
  }
}

function mergeSessions(prev: PointServiceSession[], next: PointServiceSession[]) {
  const byId = new Map(prev.map((x) => [x.id, x]))
  for (const row of next) byId.set(row.id, row)
  return [...byId.values()].sort((a, b) => a.ends_at.localeCompare(b.ends_at))
}

/**
 * «Ваше время до 15:40» на экране покупателя. Чек показывается до ответа
 * сервера, поэтому время считаем сами: новая игра — от «сейчас», продление —
 * от конца текущего таймера.
 */
function serviceTimersForDisplay(cart: CartLine[], sessions: PointServiceSession[]) {
  const now = Date.now()
  const out: Array<{ title: string; endsAt: string }> = []
  for (const line of cart) {
    const minutes = Number(line.duration_minutes || 0) * line.quantity
    if (!line.service_variant_id || !(minutes > 0)) continue
    const current = line.extend_session_id ? sessions.find((x) => x.id === line.extend_session_id) : null
    const from = current ? Math.max(now, new Date(current.ends_at).getTime()) : now
    out.push({ title: (current?.title || line.name.split(' · ')[0]).trim(), endsAt: new Date(from + minutes * 60_000).toISOString() })
  }
  return out
}
