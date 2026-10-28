'use client'

import { useEffect, useMemo, useState } from 'react'
import { ChevronRight, ClipboardList, Loader2, MessageCircle, Plus, Trash2, Truck } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { AppModal } from '@/components/ui/app-modal'
import { NativeSelect } from '@/components/ui/native-select'
import { Skeleton } from '@/components/skeleton'
import { StoreChip, StoreEmpty, StoreRow, plural, storeCard } from '@/components/store/store-ui'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { toast } from '@/hooks/use-toast'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { useStoreApiUrl } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'
import { invalidateStoreCaches } from '@/lib/client/store-cache'

type OrderStatus = 'draft' | 'sent' | 'received' | 'cancelled'

type SupplierLite = {
  id: string
  name: string
  organization_name: string | null
  sales_rep_name: string | null
  sales_rep_phone: string | null
}

type OrderRow = {
  id: string
  supplier_id: string
  status: OrderStatus
  is_auto: boolean
  comment: string | null
  sent_at: string | null
  received_at: string | null
  cancelled_at: string | null
  created_at: string
  item_count: number
  supplier: SupplierLite | null
}

type OrderItem = {
  id: string
  item_id: string
  current_qty: number
  threshold: number | null
  suggested_qty: number
  comment: string | null
  item: { id: string; name: string; barcode: string; unit: string | null } | null
}

type OrderDetail = {
  id: string
  status: OrderStatus
  is_auto: boolean
  comment: string | null
  sent_at: string | null
  received_at: string | null
  cancelled_at: string | null
  cancel_reason: string | null
  created_at: string
  supplier: (SupplierLite & { bin_iin: string | null; phone: string | null; lead_time_days: number | null }) | null
  items: OrderItem[]
}

type CatalogItem = { id: string; name: string; barcode: string; unit?: string | null }

type DraftLine = { item_id: string; qty: string }

const STATUS_META: Record<OrderStatus, { label: string; tone: 'neutral' | 'sky' | 'emerald' | 'rose' }> = {
  draft: { label: 'Черновик', tone: 'neutral' },
  sent: { label: 'Отправлена', tone: 'sky' },
  received: { label: 'Получена', tone: 'emerald' },
  cancelled: { label: 'Отменена', tone: 'rose' },
}

const fmtDate = (value: string | null | undefined) => {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleDateString('ru-RU')
  } catch {
    return String(value)
  }
}

export default function PurchaseOrdersPage({ embedded = false }: { embedded?: boolean } = {}) {
  const { can } = useCapabilities()
  const storeUrl = useStoreApiUrl()
  const [orders, setOrders] = useState<OrderRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // Ошибки действий — тостами: карточка ошибки остаётся за модалкой и не видна
  const notifyError = (message: string) => toast({ title: message, variant: 'destructive' })
  const [statusFilter, setStatusFilter] = useState<'all' | OrderStatus>('all')

  // create dialog
  const [createOpen, setCreateOpen] = useState(false)
  const [suppliers, setSuppliers] = useState<SupplierLite[]>([])
  const [catalog, setCatalog] = useState<CatalogItem[]>([])
  const [formSupplier, setFormSupplier] = useState('')
  const [formComment, setFormComment] = useState('')
  const [formLines, setFormLines] = useState<DraftLine[]>([{ item_id: '', qty: '' }])
  const [saving, setSaving] = useState(false)

  // detail dialog
  const [detail, setDetail] = useState<OrderDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [statusBusy, setStatusBusy] = useState(false)

  // Причина отмены — своим окном: в prompt() текст пропадал при любой осечке
  const [cancelOpen, setCancelOpen] = useState(false)
  const [cancelReason, setCancelReason] = useState('')

  const ordersUrl = storeUrl(
    statusFilter === 'all'
      ? '/api/admin/store/purchase-orders'
      : `/api/admin/store/purchase-orders?status=${statusFilter}`,
  )
  const ordersCacheKey = `${ordersUrl}#orders`

  const load = async (opts?: { fresh?: boolean }) => {
    // fresh — перезагрузка после смены статуса заявки: кэш устарел.
    const cached = opts?.fresh ? null : readApiCache<OrderRow[]>(ordersCacheKey)
    if (cached) { setOrders(cached); setLoading(false) }
    setError(null)
    try {
      const response = await fetch(ordersUrl, { cache: 'no-store' })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось загрузить заявки')
      const orders = json.data?.orders || []
      writeApiCache(ordersCacheKey, orders)
      setOrders(orders)
    } catch (err: any) {
      setError(err?.message || 'Ошибка загрузки')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, storeUrl])

  // Подгружаем поставщиков и каталог при первом открытии формы создания.
  useEffect(() => {
    if (!createOpen || (suppliers.length > 0 && catalog.length > 0)) return
    void (async () => {
      try {
        // Каталог берём у каталога, а не у приёмки: тот ответ вёз ещё документы
        // приёмок с позициями, локации, черновики и категории расходов — ради
        // одного выпадающего списка товаров.
        const [supRes, catRes] = await Promise.all([
          fetch(storeUrl('/api/admin/store/suppliers'), { cache: 'no-store' }),
          fetch(storeUrl('/api/admin/inventory/catalog?compact=1'), { cache: 'no-store' }),
        ])
        const supJson = await supRes.json().catch(() => null)
        const catJson = await catRes.json().catch(() => null)
        if (supRes.ok && supJson?.ok) setSuppliers(supJson.data?.suppliers || [])
        if (catRes.ok && catJson?.ok && Array.isArray(catJson.data)) {
          setCatalog(catJson.data.map((it: any) => ({
            id: it.id, name: it.name, barcode: it.barcode, unit: it.unit,
          })))
        }
      } catch {
        // ignore
      }
    })()
  }, [createOpen, suppliers.length, catalog.length, storeUrl])

  const openDetail = async (id: string) => {
    setDetailLoading(true)
    setError(null)
    try {
      const response = await fetch(storeUrl(`/api/admin/store/purchase-orders/${id}`), { cache: 'no-store' })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось загрузить заявку')
      setDetail(json.data.order)
    } catch (err: any) {
      notifyError(err?.message || 'Не удалось загрузить заявку')
    } finally {
      setDetailLoading(false)
    }
  }

  const submitCreate = async () => {
    if (saving) return
    if (!formSupplier) {
      notifyError('Выберите поставщика')
      return
    }
    const items = formLines
      .map((l) => ({ item_id: l.item_id, suggested_qty: Number(String(l.qty).replace(',', '.')) }))
      .filter((l) => l.item_id && Number.isFinite(l.suggested_qty) && l.suggested_qty > 0)
    if (items.length === 0) {
      notifyError('Добавьте хотя бы одну позицию с количеством')
      return
    }
    setSaving(true)
    try {
      const response = await fetch('/api/admin/store/purchase-orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ supplier_id: formSupplier, comment: formComment.trim() || null, items }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось создать заявку')
      toast({ title: 'Заявка создана' })
      setCreateOpen(false)
      setFormSupplier('')
      setFormComment('')
      setFormLines([{ item_id: '', qty: '' }])
      invalidateStoreCaches()
      await load({ fresh: true })
    } catch (err: any) {
      notifyError(err?.message || 'Не удалось создать заявку')
    } finally {
      setSaving(false)
    }
  }

  const buildWhatsAppText = (d: OrderDetail) => {
    const supplierName = d.supplier?.organization_name || d.supplier?.name || 'Поставщик'
    const today = new Date().toLocaleDateString('ru-RU')
    const lines = (d.items || []).map((it, i) => {
      const unit = it.item?.unit || 'шт'
      return `${i + 1}. ${it.item?.name || 'Товар'} — ${it.suggested_qty} ${unit}`
    })
    let text = `Заявка на закуп\n${supplierName}\nДата: ${today}\n\n${lines.join('\n')}`
    if (d.comment) text += `\n\nКомментарий: ${d.comment}`
    return text
  }

  const sendWhatsApp = async () => {
    if (!detail || statusBusy) return
    const phone = (detail.supplier?.sales_rep_phone || '').replace(/\D/g, '')
    if (!phone) {
      notifyError('У поставщика не указан WhatsApp торгпреда — заполните в карточке поставщика')
      return
    }
    const url = `https://wa.me/${phone}?text=${encodeURIComponent(buildWhatsAppText(detail))}`
    window.open(url, '_blank', 'noopener,noreferrer')
    // Полу-авто: открыли чат с готовым текстом → помечаем заявку отправленной.
    await changeStatus('sent')
  }

  const changeStatus = async (next: OrderStatus, reason?: string) => {
    if (!detail || statusBusy) return
    setStatusBusy(true)
    try {
      const response = await fetch(`/api/admin/store/purchase-orders/${detail.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: next, cancel_reason: reason || null }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось обновить статус')
      toast({ title: `Статус: ${STATUS_META[next].label}` })
      setCancelOpen(false)
      setCancelReason('')
      setDetail(null)
      invalidateStoreCaches()
      await load({ fresh: true })
    } catch (err: any) {
      notifyError(err?.message || 'Не удалось обновить статус')
    } finally {
      setStatusBusy(false)
    }
  }

  const counts = useMemo(() => {
    const c: Record<string, number> = { all: orders.length, draft: 0, sent: 0, received: 0, cancelled: 0 }
    for (const o of orders) c[o.status] = (c[o.status] || 0) + 1
    return c
  }, [orders])

  return (
    <div className={embedded ? 'space-y-4' : 'app-page max-w-[1600px] space-y-4'}>
      {(() => {
        const hdrActions = (
          <Button onClick={() => setCreateOpen(true)} className="bg-blue-600 text-white hover:bg-blue-700">
            <Plus className="mr-1 h-4 w-4" /> Создать заявку
          </Button>
        )
        const hdrToolbar = (
          <div className="flex max-w-full flex-wrap gap-1 rounded-2xl border border-border bg-white p-1 dark:bg-slate-950/50">
            {(['all', 'draft', 'sent', 'received', 'cancelled'] as const).map((s) => (
              <button
                key={s}
                onClick={() => setStatusFilter(s)}
                className={`rounded-xl px-3 py-1.5 text-sm font-medium transition ${
                  statusFilter === s ? 'bg-surface-hover text-foreground shadow-sm ring-1 ring-slate-200 dark:ring-white/10' : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                {s === 'all' ? 'Все' : STATUS_META[s].label} <span className="tabular-nums text-muted-foreground">{counts[s] || 0}</span>
              </button>
            ))}
          </div>
        )
        return embedded ? (
          <div className="flex flex-wrap items-center justify-between gap-3">
            {hdrToolbar}
            <div className="flex flex-wrap items-center gap-2">{hdrActions}</div>
          </div>
        ) : (
          <AdminPageHeader
            title="Заявки поставщикам"
            description="Заказы на закуп товара: черновик, отправка поставщику, получение"
            icon={<ClipboardList className="h-5 w-5" />}
            accent="emerald"
            backHref="/"
            actions={hdrActions}
            toolbar={hdrToolbar}
          />
        )
      })()}

      {error ? <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-200">{error}</div> : null}

      <div className={`${storeCard} overflow-hidden`}>
        {loading ? (
          <div className="divide-y divide-border">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 px-4 py-3">
                <Skeleton className="h-9 w-9 rounded-lg" />
                <div className="flex-1 space-y-1.5"><Skeleton className="h-4 w-44" /><Skeleton className="h-3 w-60" /></div>
              </div>
            ))}
          </div>
        ) : orders.length === 0 ? (
          <StoreEmpty
            icon={<Truck className="h-5 w-5" />}
            title="Заявок поставщикам нет"
            hint="Создайте заявку: выберите поставщика, добавьте товары и количество — потом отправьте в WhatsApp."
            action={<Button size="sm" onClick={() => setCreateOpen(true)} className="bg-emerald-600 text-white hover:bg-emerald-700"><Plus className="mr-1 h-4 w-4" /> Создать заявку</Button>}
          />
        ) : (
          <div className="divide-y divide-border">
            {orders.map((o) => (
              <StoreRow
                key={o.id}
                icon={Truck}
                tone={STATUS_META[o.status].tone}
                onClick={() => void openDetail(o.id)}
                title={
                  <span className="flex min-w-0 items-center gap-2">
                    <span className="truncate">{o.supplier?.organization_name || o.supplier?.name || 'Поставщик'}</span>
                    <StoreChip tone={STATUS_META[o.status].tone}>{STATUS_META[o.status].label}</StoreChip>
                    {o.is_auto ? <StoreChip tone="violet">авто</StoreChip> : null}
                  </span>
                }
                subtitle={`${o.item_count} ${plural(o.item_count, ['позиция', 'позиции', 'позиций'])} · создана ${fmtDate(o.created_at)}${o.sent_at ? ` · отправлена ${fmtDate(o.sent_at)}` : ''}`}
                right={<ChevronRight className="h-4 w-4 text-slate-400" />}
              />
            ))}
          </div>
        )}
      </div>

      {/* Создание заявки */}
      <AppModal
        open={createOpen}
        onClose={() => { if (!saving) setCreateOpen(false) }}
        title="Новая заявка поставщику"
        description="Что и сколько заказать"
        icon={<Truck className="h-4 w-4" />}
        maxWidth="max-w-2xl"
        footer={
          <>
            <Button variant="outline" onClick={() => setCreateOpen(false)} disabled={saving}>Отмена</Button>
            {can('store-purchase-orders.create') && (
              <Button onClick={submitCreate} disabled={saving} className="bg-blue-600 text-white hover:bg-blue-700">
                {saving ? <><Loader2 className="mr-1 h-4 w-4 animate-spin" />Создаю...</> : 'Создать заявку'}
              </Button>
            )}
          </>
        }
      >
        <div className="space-y-3">
          <div className="space-y-1.5">
            <Label>Поставщик</Label>
            <NativeSelect value={formSupplier} onChange={(e) => setFormSupplier(e.target.value)}>
              <option value="">Выберите поставщика</option>
              {suppliers.map((s) => (
                <option key={s.id} value={s.id}>{s.organization_name || s.name}</option>
              ))}
            </NativeSelect>
          </div>

          <div className="space-y-2">
            <Label>Позиции</Label>
            {formLines.map((line, index) => (
              <div key={index} className="grid grid-cols-[minmax(0,1fr)_80px_auto] gap-2 sm:grid-cols-[minmax(0,1fr)_120px_auto]">
                <NativeSelect
                  value={line.item_id}
                  onChange={(e) => setFormLines((cur) => cur.map((l, i) => i === index ? { ...l, item_id: e.target.value } : l))}
                >
                  <option value="">Выберите товар</option>
                  {catalog.map((it) => (
                    <option key={it.id} value={it.id}>{it.name} · {it.barcode}</option>
                  ))}
                </NativeSelect>
                <Input
                  inputMode="decimal"
                  placeholder="Кол-во"
                  value={line.qty}
                  onChange={(e) => setFormLines((cur) => cur.map((l, i) => i === index ? { ...l, qty: e.target.value } : l))}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label="Убрать позицию"
                  onClick={() => setFormLines((cur) => cur.length === 1 ? cur : cur.filter((_, i) => i !== index))}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" onClick={() => setFormLines((cur) => [...cur, { item_id: '', qty: '' }])}>
              <Plus className="mr-1 h-4 w-4" /> Добавить позицию
            </Button>
          </div>

          <div className="space-y-1.5">
            <Label>Комментарий (опц.)</Label>
            <Input value={formComment} onChange={(e) => setFormComment(e.target.value)} placeholder="Например: срочно, до пятницы" />
          </div>
        </div>
      </AppModal>

      {/* Детали заявки */}
      <AppModal
        open={!!detail}
        onClose={() => { if (!statusBusy) setDetail(null) }}
        title={detail ? (detail.supplier?.organization_name || detail.supplier?.name || 'Заявка') : ''}
        description={detail ? `создана ${fmtDate(detail.created_at)}` : undefined}
        icon={<Truck className="h-4 w-4" />}
        maxWidth="max-w-2xl"
        footer={detail ? (
          <>
            {(detail.status === 'draft' || detail.status === 'sent') && can('store-purchase-orders.cancel') ? (
              <Button
                variant="outline"
                className="border-rose-500/40 text-rose-700 hover:bg-rose-500/10 dark:text-rose-200"
                disabled={statusBusy}
                onClick={() => { setCancelReason(''); setCancelOpen(true) }}
              >
                Отменить
              </Button>
            ) : null}
            {detail.status === 'draft' && can('store-purchase-orders.send') ? (
              <>
                <Button variant="outline" onClick={() => void changeStatus('sent')} disabled={statusBusy}>
                  Просто отметить «Отправлена»
                </Button>
                <Button className="bg-blue-600 text-white hover:bg-blue-700" onClick={() => void sendWhatsApp()} disabled={statusBusy}>
                  {statusBusy ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <MessageCircle className="mr-1 h-4 w-4" />}
                  Отправить в WhatsApp
                </Button>
              </>
            ) : null}
            {detail.status === 'sent' && can('store-purchase-orders.edit') ? (
              <Button onClick={() => void changeStatus('received')} disabled={statusBusy} className="bg-blue-600 text-white hover:bg-blue-700">
                {statusBusy ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : null}
                Отметить «Получена»
              </Button>
            ) : null}
          </>
        ) : undefined}
      >
        {detail ? (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <StoreChip tone={STATUS_META[detail.status].tone}>{STATUS_META[detail.status].label}</StoreChip>
              {detail.is_auto ? <StoreChip tone="violet">авто</StoreChip> : null}
            </div>

            {detail.supplier ? (
              <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                {detail.supplier.sales_rep_name ? <span>Торгпред: {detail.supplier.sales_rep_name}</span> : null}
                {detail.supplier.sales_rep_phone ? <span>WhatsApp: {detail.supplier.sales_rep_phone}</span> : null}
                {detail.supplier.lead_time_days != null ? <span>Срок поставки: {detail.supplier.lead_time_days} дн</span> : null}
              </div>
            ) : null}

            {detail.comment ? <p className="text-sm text-muted-foreground">Комментарий: {detail.comment}</p> : null}
            {detail.cancel_reason ? <p className="text-sm text-rose-600 dark:text-rose-300">Причина отмены: {detail.cancel_reason}</p> : null}

            <div className="divide-y divide-border overflow-hidden rounded-xl border border-border">
              {(detail.items || []).map((it) => (
                <div key={it.id} className="flex items-center gap-3 px-3 py-2">
                  <div className="min-w-0 flex-1">
                    <p className="break-words text-sm text-foreground">{it.item?.name || 'Товар'}</p>
                    <p className="font-mono text-[10px] text-muted-foreground">{it.item?.barcode || '—'}</p>
                  </div>
                  <div className="shrink-0 text-right text-xs tabular-nums">
                    <p className="text-muted-foreground">был {it.current_qty} {it.item?.unit || 'шт'}</p>
                    <p className="text-sm font-semibold text-foreground">заказать {it.suggested_qty} {it.item?.unit || 'шт'}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ) : null}
      </AppModal>

      {/* Отмена заявки: причина в поле, а не в prompt() */}
      <AppModal
        open={cancelOpen}
        onClose={() => { if (!statusBusy) setCancelOpen(false) }}
        title="Отменить заявку?"
        description="Поставщику она не уйдёт, статус станет «Отменена»."
        tone="danger"
        maxWidth="max-w-md"
        footer={
          <>
            <Button variant="outline" onClick={() => setCancelOpen(false)} disabled={statusBusy}>Назад</Button>
            <Button
              variant="destructive"
              disabled={statusBusy}
              onClick={() => void changeStatus('cancelled', cancelReason.trim() || undefined)}
            >
              {statusBusy ? <><Loader2 className="mr-1 h-4 w-4 animate-spin" />Отменяю...</> : 'Отменить заявку'}
            </Button>
          </>
        }
      >
        <div className="space-y-1.5">
          <Label>Причина отмены (опц.)</Label>
          <Textarea
            rows={3}
            value={cancelReason}
            onChange={(e) => setCancelReason(e.target.value)}
            placeholder="Например: поставщик поднял цену"
          />
        </div>
      </AppModal>

      {detailLoading ? (
        <div className="fixed inset-0 z-[90] flex items-center justify-center bg-black/30">
          <Loader2 className="h-6 w-6 animate-spin text-blue-300" />
        </div>
      ) : null}
    </div>
  )
}
