import { useEffect, useMemo, useRef, useState } from 'react'
import { Ban, ClipboardList, Loader2, Minus, Plus, RefreshCw, Search, ShoppingCart, Trash2, X } from 'lucide-react'

import * as api from '@/lib/api'
import * as offline from '@/lib/offline'
import { clearDraft, getDraft, setDraft, type RequestDraftItem } from '@/lib/request-draft'
import { toastError, toastSuccess } from '@/lib/toast'
import { formatShiftLabel } from '@/lib/receipt-html'
import { resolveRuntimeShift } from '@/lib/shift-runtime'
import type { AppConfig, BootstrapData, OperatorSession, PointInventoryRequestContext, PointInventoryRequestRow } from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'

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

  const filteredItems = useMemo(() => {
    const q = search.trim().toLowerCase()
    const all = (context?.items || []).filter((item) => Number(item.warehouse_qty || 0) > 0)
    if (!q) return all
    return all.filter((i) => i.name.toLowerCase().includes(q) || (i.barcode || '').toLowerCase().includes(q))
  }, [context?.items, search])

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
          <div className="grid min-h-0 flex-1 grid-cols-[1fr_420px] gap-4">
            {/* Каталог склада */}
            <section className="flex min-h-0 flex-col gap-3">
              <div className="relative">
                <Search className="muted pointer-events-none absolute left-4 top-1/2 h-[18px] w-[18px] -translate-y-1/2" />
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
                  placeholder="Поиск по названию или штрихкоду"
                  autoFocus
                  className="fill placeholder-muted h-12 w-full rounded-[16px] pl-11 pr-11 text-[15px] outline-none"
                />
                {search ? (
                  <button type="button" onClick={() => setSearch('')} className="press absolute right-1 top-1/2 grid h-11 w-11 -translate-y-1/2 place-items-center" aria-label="Очистить поиск">
                    <X className="muted h-4 w-4" />
                  </button>
                ) : null}
              </div>

              <div className="surface min-h-0 flex-1 overflow-y-auto rounded-[22px]">
                {loading ? (
                  <div className="flex h-40 items-center justify-center">
                    <Loader2 className="muted h-6 w-6 animate-spin" />
                  </div>
                ) : error ? (
                  <div className="t-red m-3 rounded-[14px] px-4 py-3 text-[14px]" style={{ background: 'color-mix(in srgb, var(--red) 12%, transparent)' }}>
                    {error}
                  </div>
                ) : filteredItems.length === 0 ? (
                  <div className="muted flex h-40 flex-col items-center justify-center gap-2">
                    <Search className="h-8 w-8 opacity-30" />
                    <p className="text-[15px]">{search ? 'Ничего не найдено' : 'Каталог пуст'}</p>
                  </div>
                ) : (
                  <div>
                    {filteredItems.map((item) => {
                      const inCart = cart.find((c) => c.item_id === item.id)
                      const max = Number(item.warehouse_qty || 0)
                      const atMax = !!inCart && inCart.qty >= max
                      return (
                        <button
                          key={item.id}
                          type="button"
                          onClick={() => addToCart(item.id)}
                          className="press flex min-h-[64px] w-full items-center justify-between gap-3 px-4 py-3 text-left"
                          style={inCart ? { background: 'color-mix(in srgb, var(--blue) 8%, transparent)' } : undefined}
                        >
                          <div className="min-w-0">
                            <p className={`truncate text-[15px] font-semibold ${inCart ? 't-blue' : ''}`}>{item.name}</p>
                            <p className="muted mt-0.5 text-[13px]">
                              {item.barcode || '—'} · склад: <span className="ink font-semibold">{max}</span> {item.unit || 'шт'}
                              {atMax ? <span className="ml-1.5 font-semibold" style={{ color: 'var(--orange)' }}>максимум</span> : null}
                            </p>
                          </div>
                          <div
                            key={inCart?.qty ?? 0}
                            className={`grid h-11 w-11 shrink-0 place-items-center rounded-full ${inCart ? 'badge pop' : 'fill'}`}
                          >
                            {inCart ? <span className="text-[15px] font-bold">{inCart.qty}</span> : <Plus className="muted h-5 w-5" />}
                          </div>
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            </section>

            {/* Панель заявки */}
            <aside className="surface flex min-h-0 flex-col rounded-[24px]">
              <form onSubmit={handleSubmit} className="flex min-h-0 flex-1 flex-col">
                <div className="flex shrink-0 items-center justify-between px-5 pb-2 pt-4">
                  <div className="flex items-center gap-2 text-[17px] font-bold">
                    <ShoppingCart className="t-blue h-5 w-5" /> Заявка
                    {cartCount > 0 ? <span className="badge rounded-full px-2.5 py-0.5 text-[12px] font-semibold">{cartCount} поз.</span> : null}
                  </div>
                  {hasDraftNow ? (
                    <button type="button" onClick={clearForm} className="press t-red flex h-11 items-center gap-1.5 px-2 text-[14px] font-medium">
                      <Trash2 className="h-4 w-4" /> Очистить
                    </button>
                  ) : null}
                </div>

                {cart.length === 0 ? (
                  <div className="muted flex flex-1 flex-col items-center justify-center gap-2 py-8">
                    <ShoppingCart className="h-8 w-8 opacity-30" />
                    <p className="text-[15px]">Нажмите на товар слева</p>
                  </div>
                ) : (
                  <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3">
                    {cart.map((c) => (
                      <div key={c.item_id} className="surface-2 slide-in space-y-2 rounded-[16px] p-3">
                        <div className="flex items-start justify-between gap-2">
                          <p className="min-w-0 truncate text-[15px] font-semibold leading-tight">{c.name}</p>
                          <button type="button" onClick={() => removeFromCart(c.item_id)} className="press -mr-1 -mt-1 grid h-11 w-11 shrink-0 place-items-center" aria-label="Убрать позицию">
                            <X className="muted h-4 w-4" />
                          </button>
                        </div>
                        <div className="flex items-center gap-2">
                          <button type="button" onClick={() => setCartQty(c.item_id, c.qty - 1)} className="press fill grid h-11 w-11 place-items-center rounded-full" aria-label="Меньше">
                            <Minus className="h-4 w-4" />
                          </button>
                          <input
                            type="number"
                            value={c.qty}
                            onChange={(e) => setCartQty(c.item_id, Number(e.target.value) || 0)}
                            min={0}
                            max={c.warehouse_qty}
                            className="fill h-11 w-16 rounded-[12px] px-1 text-center text-[16px] font-semibold outline-none"
                          />
                          <button
                            type="button"
                            onClick={() => setCartQty(c.item_id, c.qty + 1)}
                            disabled={c.qty >= c.warehouse_qty}
                            className="press fill grid h-11 w-11 place-items-center rounded-full"
                            aria-label="Больше"
                          >
                            <Plus className="h-4 w-4" />
                          </button>
                          <span className="muted text-[13px]">{c.unit}</span>
                          <span className="ml-auto shrink-0 text-[13px]" style={{ color: c.qty >= c.warehouse_qty ? 'var(--orange)' : 'var(--muted)' }}>
                            макс {c.warehouse_qty}
                          </span>
                        </div>
                        <input
                          value={c.comment}
                          onChange={(e) => setCartComment(c.item_id, e.target.value)}
                          placeholder="Комментарий (необязательно)"
                          className="fill placeholder-muted h-11 w-full rounded-[12px] px-3 text-[14px] outline-none"
                        />
                      </div>
                    ))}
                  </div>
                )}

                <div className="shrink-0 space-y-3 p-4">
                  <label className="block">
                    <span className="muted text-[13px]">Комментарий к заявке</span>
                    <textarea
                      value={comment}
                      onChange={(e) => setComment(e.target.value)}
                      placeholder="Что нужно и почему"
                      rows={2}
                      className="fill placeholder-muted mt-1.5 w-full resize-none rounded-[14px] px-4 py-3 text-[15px] outline-none"
                    />
                  </label>
                  <button
                    type="submit"
                    disabled={saving || loading || cart.length === 0}
                    className="press btn-green flex h-14 w-full items-center justify-center gap-2 rounded-[18px] text-[17px] font-semibold"
                  >
                    {saving ? <Loader2 className="h-5 w-5 animate-spin" /> : <ClipboardList className="h-5 w-5" />}
                    {cartCount > 0 ? `Отправить заявку · ${cartCount} поз.` : 'Отправить заявку'}
                  </button>
                </div>
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
