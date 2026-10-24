import { useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, Ban, ClipboardList, LayoutGrid, Loader2, MessageSquare, Minus, Plus, RefreshCw, Search, ShoppingCart, Trash2, Warehouse, X } from 'lucide-react'

import * as api from '@/lib/api'
import * as offline from '@/lib/offline'
import { clearDraft, getDraft, setDraft, type RequestDraftItem } from '@/lib/request-draft'
import { toastError, toastSuccess } from '@/lib/toast'
import { formatShiftLabel } from '@/lib/receipt-html'
import { resolveRuntimeShift } from '@/lib/shift-runtime'
import type { AppConfig, BootstrapData, OperatorSession, PointInventoryRequestContext, PointInventoryRequestRow, PointInventorySaleContext } from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'
import { categoryLook } from '../ui/category-look'
import { getCachedSalesContext } from '@/lib/cache'

type Props = {
  config: AppConfig
  bootstrap: BootstrapData
  session: OperatorSession
  onLogout: () => void
  onSwitchToShift?: () => void
  onSwitchToSale?: () => void
  onSwitchToReturn?: () => void
  onSwitchToHistory?: () => void
  onSwitchToScanner?: () => void
  onSwitchToArena?: () => void
  onOpenCabinet?: () => void
}

type CartItem = {
  item_id: string
  name: string
  barcode: string
  unit: string
  warehouse_qty: number
  qty: number
  comment: string
}

/** Цвета статусов — через переменные темы. */
const STATUS_META: Record<string, { label: string; color: string }> = {
  new: { label: 'Новая', color: 'var(--blue)' },
  approved_full: { label: 'Одобрена', color: 'var(--green)' },
  approved_partial: { label: 'Одобрена частично', color: 'var(--green)' },
  issued: { label: 'Выдана', color: 'var(--orange)' },
  received: { label: 'Получена', color: 'var(--muted)' },
  rejected: { label: 'Отклонена', color: 'var(--red)' },
  disputed: { label: 'Спор', color: 'var(--orange)' },
}

function statusMeta(status: string) {
  return STATUS_META[status] || { label: status, color: 'var(--muted)' }
}

function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function isNetworkError(err: unknown): boolean {
  const message = (err as { message?: string } | null)?.message || ''
  return (
    message.includes('Failed to fetch') ||
    message.includes('NetworkError') ||
    message.includes('Превышено время ожидания') ||
    message.includes('fetch failed') ||
    !navigator.onLine
  )
}

/** Шторка снизу поверх экрана, под ней затемнение. */
function Sheet(props: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="absolute inset-0 z-20 flex items-end justify-center" style={{ background: 'rgba(0,0,0,0.25)' }} onClick={props.onClose}>
      <div
        className="sheet-in glass mb-3 w-[min(460px,calc(100%-24px))] rounded-[24px] p-5"
        style={{ boxShadow: 'var(--shadow-lift)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-[18px] font-bold">{props.title}</h3>
          <button type="button" onClick={props.onClose} className="press fill grid h-11 w-11 place-items-center rounded-full" aria-label="Закрыть">
            <X className="h-4 w-4" />
          </button>
        </div>
        {props.children}
      </div>
    </div>
  )
}

export default function RequestScreenV2({
  config,
  session,
  onLogout,
  onSwitchToShift,
  onSwitchToSale,
  onSwitchToReturn,
  onSwitchToHistory,
  onSwitchToScanner,
  onSwitchToArena,
  onOpenCabinet,
}: Props) {
  const operatorId = session.operator.operator_id

  const [context, setContext] = useState<PointInventoryRequestContext | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [comment, setComment] = useState('')
  const [search, setSearch] = useState('')
  const [cart, setCart] = useState<CartItem[]>([])
  const [viewMode, setViewMode] = useState<'create' | 'history'>('create')
  const [receivingId, setReceivingId] = useState<string | null>(null)
  const [cancelingId, setCancelingId] = useState<string | null>(null)
  const [cancelTarget, setCancelTarget] = useState<PointInventoryRequestRow | null>(null)
  const [category, setCategory] = useState('all')
  const [openComment, setOpenComment] = useState<string | null>(null)
  // Остаток на витрине — из кэша экрана продажи (без лишних запросов): что
  // заканчивается на витрине, то и заказывают со склада.
  const [showcase, setShowcase] = useState<Map<string, number>>(new Map())
  useEffect(() => {
    void getCachedSalesContext<PointInventorySaleContext>()
      .then((ctx) => {
        if (ctx?.items) setShowcase(new Map(ctx.items.map((i) => [i.id, Number(i.display_qty || 0)])))
      })
      .catch(() => {})
  }, [])
  const searchRef = useRef<HTMLInputElement>(null)

  // Черновик per-кассир: позиции восстанавливаем после загрузки каталога (нужны актуальные остатки).
  const draftItemsRef = useRef<RequestDraftItem[]>([])
  const hydratedRef = useRef(false)

  useEffect(() => {
    const draft = getDraft(operatorId)
    if (draft) {
      draftItemsRef.current = draft.items
      if (draft.comment) setComment(draft.comment)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!hydratedRef.current) return
    setDraft(operatorId, {
      comment,
      items: cart.map((c) => ({ item_id: c.item_id, qty: c.qty, comment: c.comment })),
    })
  }, [cart, comment, operatorId])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const data = await api.getPointInventoryRequests(config, session)
      setContext(data)

      const itemMap = new Map((data.items || []).map((i) => [i.id, i]))
      if (!hydratedRef.current) {
        hydratedRef.current = true
        const restored: CartItem[] = []
        for (const d of draftItemsRef.current) {
          const item = itemMap.get(d.item_id)
          if (!item) continue
          const max = Number(item.warehouse_qty || 0)
          const qty = Math.min(d.qty, max)
          if (qty <= 0) continue
          restored.push({
            item_id: item.id,
            name: item.name,
            barcode: item.barcode || '',
            unit: item.unit || 'шт',
            warehouse_qty: max,
            qty,
            comment: d.comment || '',
          })
        }
        draftItemsRef.current = []
        if (restored.length > 0) setCart(restored)
      } else {
        setCart((prev) =>
          prev
            .map((c) => {
              const max = Number(itemMap.get(c.item_id)?.warehouse_qty || 0)
              return { ...c, warehouse_qty: max, qty: Math.min(c.qty, max) }
            })
            .filter((c) => c.qty > 0),
        )
      }
    } catch (err: any) {
      setContext(null)
      setError(err?.message || 'Не удалось загрузить данные')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const LOW_SHOWCASE = 5
  const categories = useMemo(() => {
    const names = new Set<string>()
    for (const it of context?.items || []) if (Number(it.warehouse_qty || 0) > 0) names.add(it.category?.name || 'Без категории')
    return ['all', ...Array.from(names).sort((a, b) => a.localeCompare(b, 'ru'))]
  }, [context?.items])

  const filteredItems = useMemo(() => {
    const q = search.trim().toLowerCase()
    const all = (context?.items || [])
      .filter((item) => Number(item.warehouse_qty || 0) > 0)
      .filter((item) => category === 'all' || (item.category?.name || 'Без категории') === category)
    const found = q ? all.filter((i) => i.name.toLowerCase().includes(q) || (i.barcode || '').toLowerCase().includes(q)) : all
    // Что заканчивается на витрине — первым.
    return [...found].sort((a, b) => {
      const la = showcase.has(a.id) && (showcase.get(a.id) || 0) <= LOW_SHOWCASE ? 0 : 1
      const lb = showcase.has(b.id) && (showcase.get(b.id) || 0) <= LOW_SHOWCASE ? 0 : 1
      return la !== lb ? la - lb : a.name.localeCompare(b.name, 'ru')
    })
  }, [context?.items, search, category, showcase])

  function addToCart(itemId: string) {
    const item = (context?.items || []).find((i) => i.id === itemId)
    if (!item) return
    const max = Number(item.warehouse_qty || 0)
    if (max <= 0) return
    setCart((prev) => {
      const existing = prev.find((c) => c.item_id === itemId)
      if (existing) {
        if (existing.qty >= max) {
          toastError(`«${item.name}»: на складе только ${max} ${item.unit || 'шт'}`)
          return prev
        }
        return prev.map((c) => (c.item_id === itemId ? { ...c, qty: Math.min(c.qty + 1, max) } : c))
      }
      return [
        ...prev,
        { item_id: item.id, name: item.name, barcode: item.barcode || '', unit: item.unit || 'шт', warehouse_qty: max, qty: 1, comment: '' },
      ]
    })
  }

  function handleSearchEnter() {
    const q = search.trim()
    if (!q) return
    const all = (context?.items || []).filter((item) => Number(item.warehouse_qty || 0) > 0)
    const exactBarcode = all.find((item) => String(item.barcode || '').trim() === q)
    if (exactBarcode) {
      addToCart(exactBarcode.id)
      setSearch('')
      return
    }
    const query = q.toLowerCase()
    const oneMatch = all.filter((item) => {
      const name = String(item.name || '').toLowerCase()
      const barcode = String(item.barcode || '').toLowerCase()
      return name.includes(query) || barcode.includes(query)
    })
    if (oneMatch.length === 1) {
      addToCart(oneMatch[0].id)
      setSearch('')
    }
  }

  function setCartQty(itemId: string, qty: number) {
    setCart((prev) => {
      const line = prev.find((c) => c.item_id === itemId)
      if (!line) return prev
      const clamped = Math.round(Math.min(Math.max(0, qty), line.warehouse_qty) * 1000) / 1000
      if (clamped === 0) return prev.filter((c) => c.item_id !== itemId)
      return prev.map((c) => (c.item_id === itemId ? { ...c, qty: clamped } : c))
    })
  }

  function setCartComment(itemId: string, value: string) {
    setCart((prev) => prev.map((c) => (c.item_id === itemId ? { ...c, comment: value } : c)))
  }

  function removeFromCart(itemId: string) {
    setCart((prev) => prev.filter((c) => c.item_id !== itemId))
  }

  function clearForm() {
    setCart([])
    setComment('')
    setSearch('')
    clearDraft()
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (saving) return
    const items = cart.filter((c) => c.qty > 0).map((c) => ({ item_id: c.item_id, requested_qty: c.qty, comment: c.comment.trim() || null }))

    if (items.length === 0) {
      toastError('Добавьте хотя бы один товар в заявку')
      return
    }

    const payload = { comment: comment.trim() || null, items }

    setSaving(true)
    try {
      await api.createPointInventoryRequest(config, session, payload)
      toastSuccess('Заявка отправлена на склад')
      clearForm()
      await load()
      setViewMode('history')
    } catch (err: any) {
      if (isNetworkError(err)) {
        try {
          await offline.queueInventoryRequest(payload, session, session.company.id)
          toastSuccess('Нет интернета — заявка в очереди, отправится сама')
          clearForm()
        } catch (queueErr: any) {
          toastError('Не удалось сохранить даже локально: ' + (queueErr?.message || 'unknown'))
        }
      } else {
        toastError(err?.message || 'Не удалось отправить заявку')
        if (String(err?.message || '').includes('Недостаточно на складе')) void load()
      }
    } finally {
      setSaving(false)
    }
  }

  async function handleReceive(requestId: string) {
    setReceivingId(requestId)
    try {
      await api.receivePointInventoryRequest(config, session, requestId)
      toastSuccess('Товар принят на витрину')
      await load()
    } catch (err: any) {
      toastError(err?.message || 'Не удалось подтвердить получение')
    } finally {
      setReceivingId(null)
    }
  }

  async function handleCancelConfirmed() {
    const target = cancelTarget
    if (!target) return
    setCancelTarget(null)
    setCancelingId(target.id)
    try {
      await api.cancelPointInventoryRequest(config, session, target.id)
      toastSuccess('Заявка отменена')
      await load()
    } catch (err: any) {
      toastError(err?.message || 'Не удалось отменить заявку')
      void load()
    } finally {
      setCancelingId(null)
    }
  }

  const requests = context?.requests || []
  const cartCount = cart.length
  const hasDraftNow = cart.length > 0 || comment.trim().length > 0
  const operatorName = session.operator.full_name || session.operator.name || session.operator.username
  const nav: V2Nav = {
    shift: onSwitchToShift,
    sale: onSwitchToSale,
    return: onSwitchToReturn,
    history: onSwitchToHistory,
    scanner: onSwitchToScanner,
    arena: onSwitchToArena,
    cabinet: onOpenCabinet,
  }

  return (
    <V2Shell
      active="request"
      nav={nav}
      companyName={session.company.name}
      operatorName={operatorName}
      shiftLabel={formatShiftLabel(resolveRuntimeShift().shift)}
      onLogout={onLogout}
    >
      <div className="relative flex h-full min-h-0 flex-col gap-3 p-4">
        <div className="flex items-center gap-3">
          <div className="fill flex shrink-0 items-center gap-1 rounded-[14px] p-1">
            {(['create', 'history'] as const).map((mode) => (
              <button
                key={mode}
                type="button"
                onClick={() => setViewMode(mode)}
                className={`press h-11 rounded-[11px] px-4 text-[14px] font-medium ${viewMode === mode ? 'pill-active' : 'ink-2'}`}
              >
                {mode === 'create'
                  ? `Заявка${cartCount > 0 ? ` · ${cartCount}` : ''}${cartCount === 0 && hasDraftNow ? ' · черновик' : ''}`
                  : `История заявок${requests.length > 0 ? ` · ${requests.length}` : ''}`}
              </button>
            ))}
          </div>
          <h2 className="ml-2 hidden text-[18px] font-bold lg:block">Заявки на склад</h2>
          <button
            type="button"
            onClick={() => void load()}
            disabled={loading}
            className="press surface ml-auto flex h-11 items-center gap-2 rounded-[14px] px-4 text-[14px] font-medium"
          >
            <RefreshCw className={`t-blue h-4 w-4 ${loading ? 'animate-spin' : ''}`} /> Обновить
          </button>
        </div>

        {viewMode === 'history' ? (
          <section className="min-h-0 flex-1 overflow-y-auto">
            {loading && requests.length === 0 ? (
              <div className="flex h-40 items-center justify-center">
                <Loader2 className="muted h-6 w-6 animate-spin" />
              </div>
            ) : requests.length === 0 ? (
              <div className="muted flex h-full flex-col items-center justify-center gap-2">
                <ClipboardList className="h-12 w-12 opacity-30" />
                <p className="text-[15px]">Заявок ещё нет</p>
                <p className="text-[13px]">Соберите заявку на вкладке «Заявка» — она появится здесь</p>
              </div>
            ) : (
              <div className="grid gap-3 pr-1 xl:grid-cols-2">
                {requests.map((request) => {
                  const meta = statusMeta(request.status)
                  return (
                    <div key={request.id} className="surface slide-in flex flex-col rounded-[20px] p-4">
                      <div className="flex items-start justify-between gap-2">
                        <div>
                          <p className="text-[15px] font-semibold">{formatDateTime(request.created_at)}</p>
                          <p className="muted text-[13px]">#{request.id.slice(-6)}</p>
                        </div>
                        <span
                          className="shrink-0 rounded-full px-3 py-1 text-[13px] font-semibold"
                          style={{ color: meta.color, background: `color-mix(in srgb, ${meta.color} 15%, transparent)` }}
                        >
                          {meta.label}
                        </span>
                      </div>

                      <div className="mt-3 space-y-1.5">
                        {(request.items || []).map((item) => {
                          const changed = item.approved_qty !== null && Number(item.approved_qty) !== Number(item.requested_qty)
                          return (
                            <div key={item.id} className="flex items-center justify-between gap-2 text-[14px]">
                              <span className="muted truncate">{item.item?.name || 'Товар'}</span>
                              <span className="shrink-0 font-semibold">
                                × {item.requested_qty}
                                {changed ? <span style={{ color: 'var(--orange)' }}> → {item.approved_qty}</span> : null}
                              </span>
                            </div>
                          )
                        })}
                      </div>

                      {request.comment ? <p className="muted mt-2 text-[13px]">Комментарий: {request.comment}</p> : null}
                      {request.decision_comment ? (
                        <p className="surface-2 muted mt-2 rounded-[12px] px-3 py-2 text-[13px]">Склад: {request.decision_comment}</p>
                      ) : null}

                      <div className="mt-auto pt-3">
                        {request.status === 'new' ? (
                          <button
                            type="button"
                            disabled={cancelingId === request.id}
                            onClick={() => setCancelTarget(request)}
                            className="press fill t-red flex h-12 w-full items-center justify-center gap-2 rounded-[14px] text-[15px] font-semibold"
                          >
                            {cancelingId === request.id ? <Loader2 className="h-4 w-4 animate-spin" /> : <Ban className="h-4 w-4" />}
                            Отменить заявку
                          </button>
                        ) : null}
                        {request.status === 'issued' ? (
                          <button
                            type="button"
                            disabled={receivingId === request.id}
                            onClick={() => void handleReceive(request.id)}
                            className="press btn-green flex h-12 w-full items-center justify-center gap-2 rounded-[14px] text-[16px] font-semibold"
                          >
                            {receivingId === request.id ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                            Получил товар
                          </button>
                        ) : null}
                        {request.status === 'received' && request.received_at ? (
                          <p className="t-green text-[13px] font-medium">Принято: {formatDateTime(request.received_at)}</p>
                        ) : null}
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </section>
        ) : (
          <div className="grid min-h-0 flex-1 grid-cols-[minmax(0,1fr)_clamp(320px,30vw,420px)] gap-4">
            {/* ── Склад ─────────────────────────────────────────────── */}
            <section className="flex min-h-0 flex-col gap-3">
              <label className="surface flex h-12 items-center gap-2.5 rounded-[14px] px-4">
                <Search className="muted h-[18px] w-[18px]" />
                <input
                  ref={searchRef}
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      handleSearchEnter()
                    }
                  }}
                  placeholder="Поиск или штрихкод"
                  autoFocus
                  className="placeholder-muted h-full flex-1 bg-transparent text-[16px] outline-none"
                />
                {search ? (
                  <button type="button" onClick={() => setSearch('')} className="fill grid h-6 w-6 place-items-center rounded-full" aria-label="Очистить поиск">
                    <X className="h-3.5 w-3.5" />
                  </button>
                ) : null}
              </label>

              <div className="flex gap-2 overflow-x-auto pb-1">
                {categories.map((c) => {
                  const look = c === 'all' ? { icon: LayoutGrid, color: '#8e8e93' } : categoryLook(c)
                  const Icon = look.icon
                  const active = c === category
                  return (
                    <button
                      key={c}
                      type="button"
                      onClick={() => setCategory(c)}
                      className={`press flex h-11 shrink-0 items-center gap-2 rounded-full px-4 text-[15px] font-medium ${active ? 'surface' : 'fill'}`}
                      style={active ? { boxShadow: `inset 0 0 0 2px ${look.color}` } : undefined}
                    >
                      <Icon className="h-[18px] w-[18px]" style={{ color: look.color }} />
                      {c === 'all' ? 'Все' : c}
                    </button>
                  )
                })}
              </div>

              <div className="min-h-0 flex-1 overflow-y-auto pr-1">
                {loading ? (
                  <div className="muted flex h-full items-center justify-center gap-2">
                    <Loader2 className="h-6 w-6 animate-spin" /> Загружаем склад…
                  </div>
                ) : error ? (
                  <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
                    <p className="t-red text-[16px]">{error}</p>
                    <button type="button" onClick={() => void load()} className="press btn-blue h-11 rounded-[14px] px-5 text-[15px] font-semibold">
                      Повторить
                    </button>
                  </div>
                ) : filteredItems.length === 0 ? (
                  <div className="muted flex h-full flex-col items-center justify-center gap-2">
                    <Warehouse className="h-8 w-8" />
                    <p className="text-[16px]">{search ? `Ничего не нашлось по «${search}»` : 'На складе пусто'}</p>
                  </div>
                ) : (
                  <div className="grid grid-cols-[repeat(auto-fill,minmax(184px,1fr))] gap-3">
                    {filteredItems.map((item) => {
                      const inCart = cart.find((c) => c.item_id === item.id)
                      const stock = Number(item.warehouse_qty || 0)
                      const onShowcase = showcase.get(item.id)
                      const low = onShowcase !== undefined && onShowcase <= LOW_SHOWCASE
                      const look = categoryLook(item.category?.name)
                      const Icon = look.icon
                      return (
                        <button
                          key={item.id}
                          type="button"
                          onClick={() => addToCart(item.id)}
                          disabled={!!inCart && inCart.qty >= stock}
                          className="press lift surface relative flex min-h-[132px] flex-col rounded-[22px] p-4 text-left"
                          style={inCart ? { boxShadow: '0 0 0 2px var(--blue), var(--shadow)' } : undefined}
                        >
                          <div className="flex items-start justify-between">
                            <div className="grid h-11 w-11 place-items-center rounded-[13px]" style={{ background: `color-mix(in srgb, ${look.color} 14%, transparent)` }}>
                              <Icon className="h-[22px] w-[22px]" style={{ color: look.color }} />
                            </div>
                            {inCart ? (
                              <span key={inCart.qty} className="badge pop grid h-7 min-w-7 place-items-center rounded-full px-2 text-[14px] font-semibold">
                                {inCart.qty}
                              </span>
                            ) : low ? (
                              <span className="flex items-center gap-1 rounded-full px-2 py-0.5 text-[12px] font-semibold text-white" style={{ background: 'var(--orange)' }}>
                                <AlertTriangle className="h-3 w-3" /> Мало
                              </span>
                            ) : null}
                          </div>
                          <span className="mt-3 line-clamp-2 text-[15px] font-medium leading-snug">{item.name}</span>
                          <span className="mt-auto flex flex-wrap items-center justify-between gap-x-3 gap-y-0.5 pt-2 text-[13px]">
                            <span className="muted">
                              Склад <b className="ink">{stock}</b> {item.unit || 'шт'}
                            </span>
                            {onShowcase !== undefined ? (
                              <span style={{ color: low ? 'var(--orange)' : 'var(--muted)' }}>
                                Витрина <b>{onShowcase}</b>
                              </span>
                            ) : null}
                          </span>
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            </section>

            {/* ── Заявка ────────────────────────────────────────────── */}
            <aside className="glass relative flex min-h-0 flex-col rounded-[28px] p-4">
              <form onSubmit={handleSubmit} className="flex min-h-0 flex-1 flex-col">
                <div className="flex items-center justify-between px-1">
                  <h2 className="text-[22px] font-bold tracking-tight">Заявка на склад</h2>
                  <button type="button" onClick={clearForm} disabled={!hasDraftNow} className="press t-red flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[14px] font-medium">
                    <Trash2 className="h-4 w-4" /> Очистить
                  </button>
                </div>
                {hasDraftNow ? <p className="muted px-1 text-[13px]">Черновик сохраняется сам — можно уйти и вернуться</p> : null}

                <div className="mt-3 min-h-0 flex-1 space-y-2 overflow-auto">
                  {cart.length === 0 ? (
                    <div className="muted flex h-full flex-col items-center justify-center gap-3 text-center">
                      <div className="fill grid h-16 w-16 place-items-center rounded-full">
                        <ShoppingCart className="h-7 w-7" />
                      </div>
                      <p className="max-w-[260px] text-[15px] leading-relaxed">Нажмите на товар слева. Сверху — то, что заканчивается на витрине.</p>
                    </div>
                  ) : null}
                  {cart.map((c) => (
                    <div key={c.item_id} className="slide-in surface rounded-[18px] p-3">
                      <div className="flex items-center gap-3">
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-[15px] font-medium">{c.name}</div>
                          <div className="text-[13px]" style={{ color: c.qty >= c.warehouse_qty ? 'var(--orange)' : 'var(--muted)' }}>
                            на складе {c.warehouse_qty} {c.unit}
                            {c.qty >= c.warehouse_qty ? ' · это максимум' : ''}
                          </div>
                        </div>
                        <div className="fill flex items-center rounded-full p-1">
                          <button type="button" onClick={() => setCartQty(c.item_id, c.qty - 1)} className="press surface grid h-10 w-10 place-items-center rounded-full" aria-label="Меньше">
                            <Minus className="h-4 w-4" />
                          </button>
                          <input
                            value={c.qty}
                            onChange={(e) => setCartQty(c.item_id, Number(e.target.value.replace(/[^\d.]/g, '')) || 0)}
                            inputMode="decimal"
                            className="w-11 bg-transparent text-center text-[16px] font-semibold outline-none"
                            aria-label="Количество"
                          />
                          <button
                            type="button"
                            onClick={() => setCartQty(c.item_id, c.qty + 1)}
                            disabled={c.qty >= c.warehouse_qty}
                            className="press surface grid h-10 w-10 place-items-center rounded-full"
                            aria-label="Больше"
                          >
                            <Plus className="h-4 w-4" />
                          </button>
                        </div>
                      </div>
                      <div className="mt-2 flex items-center gap-2">
                        {openComment === c.item_id || c.comment ? (
                          <input
                            value={c.comment}
                            onChange={(e) => setCartComment(c.item_id, e.target.value)}
                            autoFocus={openComment === c.item_id && !c.comment}
                            placeholder="Комментарий к позиции"
                            className="fill placeholder-muted h-10 flex-1 rounded-[12px] px-3 text-[14px] outline-none"
                          />
                        ) : (
                          <button type="button" onClick={() => setOpenComment(c.item_id)} className="press muted flex h-9 items-center gap-1.5 rounded-full px-2 text-[13px]">
                            <MessageSquare className="h-4 w-4" /> Комментарий
                          </button>
                        )}
                        <button type="button" onClick={() => removeFromCart(c.item_id)} className="press fill ml-auto grid h-9 w-9 place-items-center rounded-full" aria-label="Убрать позицию">
                          <X className="h-4 w-4" />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>

                <label className="mt-3 block">
                  <span className="muted px-1 text-[13px]">Комментарий к заявке</span>
                  <textarea
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    placeholder="Что нужно и почему"
                    rows={2}
                    className="surface placeholder-muted mt-1 w-full resize-none rounded-[16px] px-4 py-3 text-[15px] outline-none"
                  />
                </label>

                <div className="mt-3 flex items-end justify-between px-1">
                  <div className="muted text-[14px]">Позиций</div>
                  <div className="text-[34px] font-bold leading-none tracking-tight">
                    {cartCount}
                    <span className="muted ml-2 text-[16px] font-medium">· {cart.reduce((sum, c) => sum + c.qty, 0)} шт</span>
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={saving || loading || cart.length === 0}
                  className="press btn-green mt-4 flex h-[64px] w-full items-center justify-center gap-2 rounded-[20px] text-[17px] font-semibold"
                >
                  {saving ? <Loader2 className="h-5 w-5 animate-spin" /> : <ClipboardList className="h-5 w-5" />}
                  Отправить на склад
                </button>
              </form>
            </aside>
          </div>
        )}

        {cancelTarget ? (
          <Sheet title="Отменить заявку?" onClose={() => setCancelTarget(null)}>
            <p className="muted mb-4 text-[14px]">
              Заявка от {formatDateTime(cancelTarget.created_at)} ({(cancelTarget.items || []).length} поз.) будет отменена. Отменить можно только пока склад её не рассмотрел.
            </p>
            <div className="grid grid-cols-2 gap-2">
              <button type="button" onClick={() => setCancelTarget(null)} className="press fill h-12 rounded-[16px] text-[15px] font-semibold">
                Назад
              </button>
              <button
                type="button"
                onClick={() => void handleCancelConfirmed()}
                className="press flex h-12 items-center justify-center gap-2 rounded-[16px] text-[15px] font-semibold text-white"
                style={{ background: 'var(--red)' }}
              >
                <Ban className="h-4 w-4" /> Отменить
              </button>
            </div>
          </Sheet>
        ) : null}
      </div>
    </V2Shell>
  )
}
