'use client'

import { Suspense, useEffect, useMemo, useState, type ReactNode } from 'react'
import Link from 'next/link'
import { AlertCircle, CheckCircle2, ClipboardList, History, Loader2, MoreHorizontal, PackageCheck, Pencil, RefreshCw, Search, XCircle } from 'lucide-react'
import { useCapabilities } from '@/lib/client/use-capabilities'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { AppModal } from '@/components/ui/app-modal'
import { NativeSelect } from '@/components/ui/native-select'
import { StoreChip, StoreEmpty, StoreStat, StoreStatGrid, plural, storeCard } from '@/components/store/store-ui'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { DatePicker } from '@/components/ui/date-picker'
import { Textarea } from '@/components/ui/textarea'
import { StorePanelSkeleton } from '@/components/store/store-panel-skeleton'
import { useDebouncedValue, useUrlState } from '@/lib/hooks/use-url-state'
import { isAbortError } from '@/lib/is-abort-error'
import { useStoreApiUrl } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'
import { invalidateStoreCaches } from '@/lib/client/store-cache'
import { toast } from '@/hooks/use-toast'
import { confirmDialog } from '@/components/ui/confirm-dialog'
import { Label } from '@/components/ui/label'

type InventoryLocation = {
  id: string
  name: string
  code: string | null
  location_type: 'warehouse' | 'point_display'
  company?: { id: string; name: string; code: string | null } | null
}

type InventoryRequestItem = {
  id: string
  requested_qty: number
  approved_qty: number | null
  comment: string | null
  available_qty?: number
  enough_for_requested?: boolean
  item?: { id: string; name: string; barcode: string } | null
}

type InventoryRequest = {
  id: string
  status: string
  comment: string | null
  decision_comment: string | null
  created_at: string
  approved_at: string | null
  issued_at: string | null
  received_at: string | null
  created_by: string | null
  approved_by: string | null
  issued_by: string | null
  received_by: string | null
  received_qty_confirmed: number | null
  created_by_staff?: { id: string; full_name: string | null; role: string | null } | null
  approved_by_staff?: { id: string; full_name: string | null; role: string | null } | null
  issued_by_staff?: { id: string; full_name: string | null; role: string | null } | null
  received_by_staff?: { id: string; full_name: string | null; role: string | null } | null
  company?: { id: string; name: string; code: string | null } | null
  source_location?: InventoryLocation | null
  target_location?: InventoryLocation | null
  items?: InventoryRequestItem[]
}

type InventoryResponse = {
  ok: boolean
  data?: {
    requests: InventoryRequest[]
  }
  error?: string
}

type DecisionDraft = {
  decisionComment: string
  quantities: Record<string, string>
}

function firstOrSelf<T>(value: T | T[] | null | undefined): T | null {
  if (Array.isArray(value)) return (value[0] as T) || null
  return value ?? null
}

function asArray<T>(value: T[] | T | null | undefined): T[] {
  if (Array.isArray(value)) return value
  if (value == null) return []
  return [value]
}

function normalizeRequest(raw: any): InventoryRequest {
  return {
    id: String(raw?.id || ''),
    status: String(raw?.status || 'new'),
    comment: raw?.comment || null,
    decision_comment: raw?.decision_comment || null,
    created_at: raw?.created_at || null,
    approved_at: raw?.approved_at || null,
    issued_at: raw?.issued_at || null,
    received_at: raw?.received_at || null,
    created_by: raw?.created_by ? String(raw.created_by) : null,
    approved_by: raw?.approved_by ? String(raw.approved_by) : null,
    issued_by: raw?.issued_by ? String(raw.issued_by) : null,
    received_by: raw?.received_by ? String(raw.received_by) : null,
    received_qty_confirmed: raw?.received_qty_confirmed == null ? null : Number(raw.received_qty_confirmed || 0),
    created_by_staff: firstOrSelf(raw?.created_by_staff),
    approved_by_staff: firstOrSelf(raw?.approved_by_staff),
    issued_by_staff: firstOrSelf(raw?.issued_by_staff),
    received_by_staff: firstOrSelf(raw?.received_by_staff),
    company: firstOrSelf(raw?.company),
    source_location: firstOrSelf(raw?.source_location),
    target_location: firstOrSelf(raw?.target_location),
    items: asArray(raw?.items).map((item: any) => ({
      id: String(item?.id || ''),
      requested_qty: Number(item?.requested_qty || 0),
      approved_qty: item?.approved_qty == null ? null : Number(item.approved_qty || 0),
      comment: item?.comment || null,
      available_qty: item?.available_qty == null ? undefined : Number(item.available_qty || 0),
      enough_for_requested: typeof item?.enough_for_requested === 'boolean' ? item.enough_for_requested : undefined,
      item: firstOrSelf(item?.item),
    })),
  }
}

function actorLabel(
  staff: { full_name: string | null; role: string | null } | null | undefined,
  fallbackId: string | null | undefined,
) {
  if (staff?.full_name) return staff.full_name
  if (fallbackId) return `ID ${fallbackId.slice(0, 8)}`
  return '—'
}

function requestTimeline(request: InventoryRequest) {
  return [
    { key: 'created', label: 'Создана', at: request.created_at, by: actorLabel(request.created_by_staff, request.created_by) },
    { key: 'approved', label: 'Одобрена', at: request.approved_at, by: actorLabel(request.approved_by_staff, request.approved_by) },
    { key: 'issued', label: 'Выдана', at: request.issued_at, by: actorLabel(request.issued_by_staff, request.issued_by) },
    { key: 'received', label: 'Получена', at: request.received_at, by: actorLabel(request.received_by_staff, request.received_by) },
  ]
}

function formatDateTime(value: string | null | undefined) {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(parsed)
}

function formatQty(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

function requestStatusLabel(status: string) {
  if (status === 'approved_full') return 'Одобрена полностью'
  if (status === 'approved_partial') return 'Одобрена частично'
  if (status === 'issued') return 'Выдана'
  if (status === 'received') return 'Получена'
  if (status === 'rejected') return 'Отклонена'
  if (status === 'disputed') return 'Спор'
  return 'Новая'
}

function requestStatusClass(status: string) {
  if (status === 'approved_full') return 'border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-200'
  if (status === 'approved_partial') return 'border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-200'
  if (status === 'issued') return 'border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-200'
  if (status === 'received') return 'border-blue-500/30 bg-blue-500/10 text-blue-700 dark:text-blue-200'
  if (status === 'rejected') return 'border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-200'
  if (status === 'disputed') return 'border-orange-500/30 bg-orange-500/10 text-orange-700 dark:text-orange-200'
  return 'border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-200'
}

function RequestStatusBadge({ status }: { status: string }) {
  const label = requestStatusLabel(status)
  const cls = requestStatusClass(status)
  const icon = status === 'approved_full' ? <CheckCircle2 className="h-3 w-3" />
    : status === 'approved_partial' ? <CheckCircle2 className="h-3 w-3" />
    : status === 'issued' ? <PackageCheck className="h-3 w-3" />
    : status === 'received' ? <PackageCheck className="h-3 w-3" />
    : status === 'rejected' ? <XCircle className="h-3 w-3" />
    : <AlertCircle className="h-3 w-3" />
  return (
    <span className={`inline-flex items-center gap-1 rounded-full border px-3 py-1 text-xs font-medium ${cls}`}>
      {icon}{label}
    </span>
  )
}

function createDecisionDraft(request: InventoryRequest): DecisionDraft {
  return {
    decisionComment: '',
    quantities: Object.fromEntries(asArray(request.items).map((item) => [item.id, formatQty(Number(item.requested_qty || 0))])),
  }
}

function requestItemsCount(request: InventoryRequest) {
  return asArray(request.items).reduce((sum, item) => sum + Number(item.requested_qty || 0), 0)
}

function pointNameOf(request: InventoryRequest) {
  return request.company?.name || request.target_location?.company?.name || request.target_location?.name || 'Точка'
}

/** Карточка заявки, ждущей решения: кто просит, что и сколько, быстрые действия. */
function PendingRequestCard(props: {
  request: InventoryRequest
  draft: DecisionDraft
  editing: boolean
  saving: boolean
  selected: boolean
  canApprove: boolean
  canReject: boolean
  onSelect: (checked: boolean) => void
  onToggleEdit: () => void
  onQty: (itemId: string, value: string) => void
  onComment: (value: string) => void
  onDecide: (approved: boolean, full: boolean) => void
}) {
  const { request, draft, editing, saving, selected } = props
  const items = asArray(request.items)
  const disputed = request.status === 'disputed'
  const totalQty = requestItemsCount(request)
  const short = items.filter((i) => i.enough_for_requested === false).length
  return (
    <div className={`${storeCard} overflow-hidden ${disputed ? 'border-rose-500/40 bg-rose-500/[0.03]' : 'border-amber-500/30'}`}>
      <div className="flex flex-wrap items-start gap-3 px-4 py-3">
        <input
          type="checkbox"
          className="mt-1 h-4 w-4 shrink-0 accent-amber-500"
          checked={selected}
          onChange={(event) => props.onSelect(event.target.checked)}
        />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-base font-semibold text-foreground">{pointNameOf(request)}</span>
            {disputed ? <StoreChip tone="rose"><AlertCircle className="h-3 w-3" /> Спор</StoreChip> : <StoreChip tone="amber">Ждёт решения</StoreChip>}
            {short > 0 ? <StoreChip tone="rose">Не хватает на складе: {short}</StoreChip> : null}
          </div>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {actorLabel(request.created_by_staff, request.created_by)} · {formatDateTime(request.created_at)} · из «{request.source_location?.name || 'Склад'}»
          </p>
          <p className="mt-0.5 text-xs tabular-nums text-muted-foreground">
            {items.length} {plural(items.length, ['позиция', 'позиции', 'позиций'])} · {formatQty(totalQty)} ед. запрошено
          </p>
        </div>
      </div>
      {request.comment ? <p className="border-t border-border px-4 py-2 text-sm text-muted-foreground">{request.comment}</p> : null}

      <div className="max-h-[45vh] divide-y divide-border overflow-auto border-t border-border">
        {items.map((item) => {
          const lacks = item.enough_for_requested === false
          return (
            <div key={item.id} className="flex items-center gap-3 px-4 py-2">
              <div className="min-w-0 flex-1">
                <p className="break-words text-sm font-medium text-foreground">{item.item?.name || 'Товар'}</p>
                <p className="break-words text-[11px] text-muted-foreground">
                  {item.item?.barcode ? <span className="font-mono">{item.item.barcode}</span> : null}
                  {item.comment ? <span>{item.item?.barcode ? ' · ' : ''}{item.comment}</span> : null}
                </p>
              </div>
              <div className="shrink-0 text-right text-xs tabular-nums">
                <p className="font-semibold text-foreground">× {formatQty(Number(item.requested_qty || 0))}</p>
                <p className={lacks ? 'text-rose-600 dark:text-rose-300' : 'text-emerald-600 dark:text-emerald-300'}>
                  на складе {formatQty(Number(item.available_qty || 0))}
                </p>
              </div>
              {editing ? (
                <div className="w-20 shrink-0">
                  <Input
                    value={draft.quantities[item.id] ?? formatQty(Number(item.requested_qty || 0))}
                    onChange={(event) => props.onQty(item.id, event.target.value)}
                    className="h-8 text-right"
                    inputMode="decimal"
                    aria-label="Сколько одобрить"
                  />
                </div>
              ) : null}
            </div>
          )
        })}
      </div>

      {editing ? (
        <div className="border-t border-border px-4 py-3">
          <Textarea
            value={draft.decisionComment}
            onChange={(event) => props.onComment(event.target.value)}
            placeholder="Комментарий к решению (необязательно)"
            rows={2}
            className="text-sm"
          />
          <p className="mt-1.5 text-[11px] text-muted-foreground">Впишите, сколько одобрить по каждой позиции, и сохраните.</p>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center justify-end gap-2 border-t border-border bg-slate-50/60 px-4 py-3 dark:bg-white/[0.02]">
        {props.canReject && (
          <Button size="sm" variant="outline" disabled={saving} onClick={() => props.onDecide(false, false)}
            className="gap-1.5 border-rose-500/40 text-rose-700 hover:bg-rose-500/10 dark:text-rose-300">
            <XCircle className="h-3.5 w-3.5" /> Отклонить
          </Button>
        )}
        {props.canApprove && (
          <Button size="sm" variant="outline" disabled={saving} onClick={props.onToggleEdit} className="gap-1.5">
            <Pencil className="h-3.5 w-3.5" /> {editing ? 'Скрыть правку' : 'Изменить'}
          </Button>
        )}
        {props.canApprove && editing && (
          <Button size="sm" disabled={saving} onClick={() => props.onDecide(true, false)} className="gap-1.5">
            {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <PackageCheck className="h-3.5 w-3.5" />}
            Сохранить по количествам
          </Button>
        )}
        {props.canApprove && (
          <Button size="sm" disabled={saving} onClick={() => props.onDecide(true, true)}
            className="gap-1.5 bg-emerald-600 text-white hover:bg-emerald-700">
            {saving && !editing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
            Одобрить всё
          </Button>
        )}
      </div>
    </div>
  )
}

/** Заголовок очереди: общий чекбокс, название, счётчик. */
function QueueHeading(props: { icon: ReactNode; title: string; count: number; hint?: string; checkbox?: ReactNode }) {
  return (
    <div className="space-y-0.5">
      <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
        {props.checkbox}
        {props.icon}
        {props.title}
        <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-slate-600 dark:bg-white/5 dark:text-slate-300">{props.count}</span>
      </div>
      {props.hint ? <p className="text-xs text-muted-foreground">{props.hint}</p> : null}
    </div>
  )
}

/** Строка заявки на этапе «выдать» / «получить». */
function StageRequestRow(props: {
  request: InventoryRequest
  tone: 'amber' | 'sky'
  selectable: boolean
  selected: boolean
  onSelect: (checked: boolean) => void
  detail: ReactNode
  actions: ReactNode
}) {
  const { request } = props
  return (
    <div className="flex flex-wrap items-center gap-3 px-4 py-3">
      {props.selectable && (
        <input
          type="checkbox"
          className={`h-4 w-4 shrink-0 ${props.tone === 'amber' ? 'accent-amber-500' : 'accent-sky-500'}`}
          checked={props.selected}
          onChange={(event) => props.onSelect(event.target.checked)}
        />
      )}
      <div className="min-w-0 flex-1 basis-48">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-semibold text-foreground">{pointNameOf(request)}</span>
          <RequestStatusBadge status={request.status} />
        </div>
        <p className="mt-0.5 text-xs text-muted-foreground">{props.detail}</p>
      </div>
      <div className="flex flex-wrap items-center gap-2">{props.actions}</div>
    </div>
  )
}


function StoreRequestsPageContent({ embedded = false }: { embedded?: boolean }) {
  const { can } = useCapabilities()
  const storeUrl = useStoreApiUrl()
  const canApprove = can('store-requests.approve')
  const canBulkApprove = can('store-requests.bulk_approve')
  const canReject = can('store-requests.reject')
  const canBulkReject = can('store-requests.bulk_reject')
  const canIssue = can('store-requests.issue')
  const canReceive = can('store-requests.receive')
  const canUndecide = can('store-requests.undecide')

  const [requests, setRequests] = useState<InventoryRequest[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [savingId, setSavingId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  // Итоги действий — тостами: карточки вверху страницы не видны у кнопок внизу
  const notifyError = (message: string) => toast({ title: message, variant: 'destructive' })
  const [filters, setFilters] = useUrlState({
    q: '',
    status: 'all',
    actor: '',
    from: '',
    to: '',
  })
  const [searchInput, setSearchInput] = useState(filters.q)
  const debouncedSearch = useDebouncedValue(searchInput, 300)
  const [decisionDrafts, setDecisionDrafts] = useState<Record<string, DecisionDraft>>({})
  const [selectedIds, setSelectedIds] = useState<string[]>([])
  const [bulkSaving, setBulkSaving] = useState(false)
  // Откат одобрения: причина в окне, а не в prompt() — текст не теряется
  const [undecideId, setUndecideId] = useState<string | null>(null)
  const [undecideReason, setUndecideReason] = useState('')
  const [editingIds, setEditingIds] = useState<string[]>([])

  const requestsUrl = storeUrl('/api/admin/inventory/requests')
  // Суффикс: кладём разобранные заявки, а не сырой ответ.
  const requestsCacheKey = `${requestsUrl}#store-requests`

  const applyRequests = (normalizedRequests: InventoryRequest[]) => {
    setRequests(normalizedRequests)
    setSelectedIds((prev) => prev.filter((id) => normalizedRequests.some((request) => request.id === id)))
    setDecisionDrafts((prev) => {
      const next = { ...prev }
      for (const request of normalizedRequests) {
        if (!next[request.id]) next[request.id] = createDecisionDraft(request)
      }
      return next
    })
  }

  const load = async (signal?: AbortSignal, opts?: { soft?: boolean }) => {
    // opts.soft — перезагрузка после решения по заявке: кэш устарел.
    const cached = opts?.soft ? null : readApiCache<InventoryRequest[]>(requestsCacheKey)
    if (cached) applyRequests(cached)
    const soft = Boolean(opts?.soft) || !!cached
    if (soft) {
      setRefreshing(true)
      setLoading(false)
    } else {
      setLoading(true)
    }
    setError(null)
    try {
      const response = await fetch(requestsUrl, { cache: 'no-store', signal })
      const json = (await response.json().catch(() => null)) as InventoryResponse | null
      if (signal?.aborted) return
      if (!response.ok || !json?.ok || !json.data) {
        throw new Error(json?.error || 'Не удалось загрузить заявки магазина')
      }
      const normalizedRequests = asArray(json.data.requests).map(normalizeRequest).filter((request) => request.id)
      writeApiCache(requestsCacheKey, normalizedRequests)
      applyRequests(normalizedRequests)
    } catch (err: any) {
      if (isAbortError(err) || signal?.aborted) return
      setError(err?.message || 'Не удалось загрузить заявки магазина')
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
  }, [storeUrl])

  useEffect(() => {
    setSearchInput(filters.q)
  }, [filters.q])

  useEffect(() => {
    setFilters({ q: debouncedSearch })
  }, [debouncedSearch, setFilters])

  const filteredRequests = useMemo(() => {
    const q = filters.q.trim().toLowerCase()
    return requests.filter((request) => {
      if (filters.status !== 'all' && request.status !== filters.status) return false

      const createdAt = request.created_at ? new Date(request.created_at) : null
      if (filters.from) {
        const from = new Date(`${filters.from}T00:00:00`)
        if (createdAt && createdAt < from) return false
      }
      if (filters.to) {
        const to = new Date(`${filters.to}T23:59:59`)
        if (createdAt && createdAt > to) return false
      }

      const actorQ = filters.actor.trim().toLowerCase()
      if (actorQ) {
        const actorText = [
          actorLabel(request.created_by_staff, request.created_by),
          actorLabel(request.approved_by_staff, request.approved_by),
          actorLabel(request.issued_by_staff, request.issued_by),
          actorLabel(request.received_by_staff, request.received_by),
        ].join(' ').toLowerCase()
        if (!actorText.includes(actorQ)) return false
      }

      if (!q) return true
      const haystack = [
        request.company?.name,
        request.source_location?.name,
        request.target_location?.name,
        request.comment,
        ...asArray(request.items).flatMap((item) => [item.item?.name, item.item?.barcode, item.comment]),
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      return haystack.includes(q)
    })
  }, [requests, filters])

  const pendingRequests = filteredRequests.filter((request) => request.status === 'new' || request.status === 'disputed')
  const disputedRequests = pendingRequests.filter((request) => request.status === 'disputed')
  const newRequests = pendingRequests.filter((request) => request.status !== 'disputed')
  const readyToIssueRequests = filteredRequests.filter((request) =>
    ['approved_full', 'approved_partial'].includes(request.status),
  )
  const issuedAwaitingReceiveRequests = filteredRequests.filter((request) => request.status === 'issued')
  const historyRequests = filteredRequests.filter((request) => ['received', 'rejected'].includes(request.status))

  const stats = useMemo(() => {
    const totalRequested = pendingRequests.reduce((sum, request) => sum + requestItemsCount(request), 0)
    const totalApproved = filteredRequests.reduce(
      (sum, request) => sum + asArray(request.items).reduce((acc, item) => acc + Number(item.approved_qty || 0), 0),
      0,
    )
    return {
      pending: pendingRequests.length,
      toIssue: readyToIssueRequests.length,
      issued: issuedAwaitingReceiveRequests.length,
      history: historyRequests.length,
      totalRequested,
      totalApproved,
    }
  }, [filteredRequests])

  const updateDraft = (requestId: string, patch: Partial<DecisionDraft>) => {
    setDecisionDrafts((prev) => ({
      ...prev,
      [requestId]: {
        ...(prev[requestId] || { decisionComment: '', quantities: {} }),
        ...patch,
      },
    }))
  }

  const updateDraftQty = (requestId: string, requestItemId: string, value: string) => {
    setDecisionDrafts((prev) => ({
      ...prev,
      [requestId]: {
        ...(prev[requestId] || { decisionComment: '', quantities: {} }),
        quantities: {
          ...(prev[requestId]?.quantities || {}),
          [requestItemId]: value,
        },
      },
    }))
  }

  const undecideRequest = async (requestId: string) => {
    if (savingId) return
    setSavingId(requestId)
    try {
      const response = await fetch('/api/admin/inventory/requests', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'undecideRequest', requestId, reason: undecideReason.trim() || null }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Ошибка')
      toast({ title: 'Решение откачено, заявка вернулась в статус «Новая»' })
      setUndecideId(null)
      setUndecideReason('')
      invalidateStoreCaches()
      await load(undefined, { soft: true })
    } catch (err: any) {
      notifyError(err?.message || 'Не удалось откатить решение')
    } finally {
      setSavingId(null)
    }
  }

  const transitionStatus = async (requestId: string, status: 'issued' | 'received') => {
    if (savingId) return
    setSavingId(requestId)
    try {
      const response = await fetch('/api/admin/inventory/requests', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'transitionStatus', requestId, status }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Ошибка')
      toast({ title: status === 'issued' ? 'Заявка отмечена как выданная' : 'Заявка отмечена как полученная' })
      invalidateStoreCaches()
      await load(undefined, { soft: true })
    } catch (err: any) {
      notifyError(err?.message || 'Не удалось изменить статус заявки')
    } finally {
      setSavingId(null)
    }
  }

  const submitDecision = async (request: InventoryRequest, approved: boolean, fullApprove: boolean) => {
    if (savingId) return
    // Отклонение необратимо для заявителя — спрашиваем подтверждение
    if (!approved) {
      const ok = await confirmDialog({
        title: 'Отклонить заявку?',
        description: 'Товар не уйдёт на точку. Заявка получит статус «Отклонена».',
        confirmLabel: 'Отклонить',
        destructive: true,
      })
      if (!ok) return
    }
    setSavingId(request.id)
    try {
      const draft = decisionDrafts[request.id] || createDecisionDraft(request)
      const items = approved
        ? asArray(request.items).map((item) => ({
            request_item_id: item.id,
            approved_qty: Number(
              fullApprove ? item.requested_qty : String(draft.quantities[item.id] ?? item.requested_qty).replace(',', '.'),
            ),
          }))
        : []

      const response = await fetch('/api/admin/inventory/requests', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'decideRequest',
          requestId: request.id,
          approved,
          decision_comment: draft.decisionComment || null,
          items,
        }),
      })

      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) {
        throw new Error(json?.error || 'Не удалось обработать заявку')
      }

      toast({ title: approved ? 'Решение по заявке сохранено' : 'Заявка отклонена' })
      invalidateStoreCaches()
      await load(undefined, { soft: true })
    } catch (err: any) {
      notifyError(err?.message || 'Не удалось обработать заявку')
    } finally {
      setSavingId(null)
    }
  }

  const toggleSelected = (requestId: string, checked: boolean) => {
    setSelectedIds((prev) => (checked ? (prev.includes(requestId) ? prev : [...prev, requestId]) : prev.filter((id) => id !== requestId)))
  }

  const toggleSelectAllPending = (checked: boolean) => {
    const pendingIds = pendingRequests.map((request) => request.id)
    setSelectedIds((prev) => (checked ? Array.from(new Set([...prev, ...pendingIds])) : prev.filter((id) => !pendingIds.includes(id))))
  }

  const toggleSelectAllIn = (list: InventoryRequest[], checked: boolean) => {
    const ids = list.map((request) => request.id)
    setSelectedIds((prev) => (checked ? Array.from(new Set([...prev, ...ids])) : prev.filter((id) => !ids.includes(id))))
  }

  // Каждое массовое действие применяется только к заявкам «своей» секции —
  // из смешанного выбора берём подходящие по статусу
  const bulkIdsFor = (action: 'approve-full' | 'reject' | 'issue' | 'receive') => {
    if (action === 'issue') return selectedIds.filter((id) => readyToIssueRequests.some((r) => r.id === id))
    if (action === 'receive') return selectedIds.filter((id) => issuedAwaitingReceiveRequests.some((r) => r.id === id))
    return selectedIds.filter((id) => pendingRequests.some((r) => r.id === id))
  }

  const BULK_LABEL: Record<string, [string, string]> = {
    'approve-full': ['Одобрено', 'одобрить'],
    reject: ['Отклонено', 'отклонить'],
    issue: ['Выдано', 'выдать'],
    receive: ['Получено', 'получить'],
  }

  const runBulkAction = async (action: 'approve-full' | 'reject' | 'issue' | 'receive') => {
    const ids = bulkIdsFor(action)
    if (!ids.length || bulkSaving) return
    if (action === 'reject') {
      const ok = await confirmDialog({
        title: `Отклонить ${ids.length} заявок?`,
        description: 'Товар не уйдёт на точки. Заявки получат статус «Отклонена».',
        confirmLabel: 'Отклонить',
        destructive: true,
      })
      if (!ok) return
    }
    setBulkSaving(true)
    try {
      const response = await fetch('/api/admin/inventory/requests/bulk', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ requestIds: ids, action }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось выполнить массовое действие')
      const succeeded = Array.isArray(json?.data?.succeeded) ? json.data.succeeded.length : 0
      const failed = Array.isArray(json?.data?.failed) ? json.data.failed.length : 0
      toast({
        title: `${BULK_LABEL[action][0]} ${succeeded} из ${ids.length}${failed ? `, не удалось ${failed}` : ''}`,
        variant: failed ? 'destructive' : undefined,
      })
      setSelectedIds((prev) => prev.filter((id) => !ids.includes(id)))
      invalidateStoreCaches()
      await load(undefined, { soft: true })
    } catch (err: any) {
      notifyError(err?.message || 'Не удалось выполнить массовое действие')
    } finally {
      setBulkSaving(false)
    }
  }

  const exportRequestsCsv = () => {
    const rows = filteredRequests.map((request) => ({
      request_id: request.id,
      status: request.status,
      company: request.company?.name || '',
      created_at: request.created_at || '',
      approved_at: request.approved_at || '',
      issued_at: request.issued_at || '',
      received_at: request.received_at || '',
      created_by: actorLabel(request.created_by_staff, request.created_by),
      approved_by: actorLabel(request.approved_by_staff, request.approved_by),
      issued_by: actorLabel(request.issued_by_staff, request.issued_by),
      items_requested: asArray(request.items).reduce((sum, item) => sum + Number(item.requested_qty || 0), 0),
      items_approved: asArray(request.items).reduce((sum, item) => sum + Number(item.approved_qty || 0), 0),
    }))
    if (rows.length === 0) return
    const headers = Object.keys(rows[0])
    const csv = [headers.join(',')]
      .concat(rows.map((row) => headers.map((h) => `"${String((row as any)[h] ?? '').replace(/"/g, '""')}"`).join(',')))
      .join('\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `requests-${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  const renderPending = (request: InventoryRequest) => (
    <PendingRequestCard
      key={request.id}
      request={request}
      draft={decisionDrafts[request.id] || createDecisionDraft(request)}
      editing={editingIds.includes(request.id)}
      saving={savingId === request.id}
      selected={selectedIds.includes(request.id)}
      canApprove={canApprove}
      canReject={canReject}
      onSelect={(checked) => toggleSelected(request.id, checked)}
      onToggleEdit={() => setEditingIds((prev) => (prev.includes(request.id) ? prev.filter((id) => id !== request.id) : [...prev, request.id]))}
      onQty={(itemId, value) => updateDraftQty(request.id, itemId, value)}
      onComment={(value) => updateDraft(request.id, { decisionComment: value })}
      onDecide={(approved, full) => void submitDecision(request, approved, full)}
    />
  )

  return (
    <>
    <div className={embedded ? 'space-y-6' : 'app-page-wide space-y-6'}>
      {/* Header */}
      {(() => {
        const hdrActions = (
          <>
            <Link href="/store/requests-journal">
              <Button variant="outline" size="sm" className="h-9 gap-1.5">
                <History className="h-3.5 w-3.5" />
                Журнал
              </Button>
            </Link>
            <Button variant="outline" size="sm" onClick={() => void load(undefined, { soft: true })} disabled={loading || refreshing} className="h-9 gap-1.5">
              <RefreshCw className={`h-3.5 w-3.5 ${loading || refreshing ? 'animate-spin' : ''}`} />
              Обновить
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" className="h-9 gap-1.5">
                  <MoreHorizontal className="h-3.5 w-3.5" />
                  Действия
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>Заявки</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {can('store-requests.export') && (
                <DropdownMenuItem onClick={exportRequestsCsv}>
                  <ClipboardList className="h-4 w-4" />
                  Экспорт CSV
                </DropdownMenuItem>
                )}
                <DropdownMenuItem
                  onClick={() => {
                    setSearchInput('')
                    setFilters({ status: 'all', actor: '', from: '', to: '', q: '' })
                  }}
                >
                  <XCircle className="h-4 w-4" />
                  Сбросить фильтры
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        )
        const hdrToolbar = (
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative w-full min-w-0 flex-1 sm:w-auto sm:max-w-md">
              <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                placeholder="Поиск по точке, товару или комментарию"
                className="h-9 pl-9"
              />
            </div>
            <NativeSelect value={filters.status} onChange={(event) => setFilters({ status: event.target.value })} className="h-9 w-full sm:w-[200px]">
              <option value="all">Все статусы</option>
              <option value="new">Новая</option>
              <option value="approved_full">Одобрена полностью</option>
              <option value="approved_partial">Одобрена частично</option>
              <option value="issued">Выдана</option>
              <option value="received">Получена</option>
              <option value="rejected">Отклонена</option>
              <option value="disputed">Спор</option>
            </NativeSelect>
            <Input
              value={filters.actor}
              onChange={(event) => setFilters({ actor: event.target.value })}
              placeholder="Кто создавал/одобрял"
              className="h-9 w-full sm:w-[220px]"
            />
            <DatePicker value={filters.from} onChange={(v) => setFilters({ from: v })} className="w-full sm:w-[150px]" />
            <DatePicker value={filters.to} onChange={(v) => setFilters({ to: v })} className="w-full sm:w-[150px]" />
          </div>
        )
        return embedded ? (
          <div className="flex flex-wrap items-center justify-between gap-3">
            {hdrToolbar}
            <div className="flex flex-wrap items-center gap-2">{hdrActions}</div>
          </div>
        ) : (
          <AdminPageHeader
            title="Заявки точек"
            description="Решения по пополнению витрин со склада"
            icon={<ClipboardList className="h-5 w-5" />}
            accent="emerald"
            backHref="/"
            actions={hdrActions}
            toolbar={hdrToolbar}
          />
        )
      })()}

      {/* Stats */}
      <StoreStatGrid cols={6}>
        <StoreStat label="Ждут решения" value={stats.pending} tone={stats.pending > 0 ? 'amber' : 'neutral'} loading={loading} icon={<AlertCircle className="h-3.5 w-3.5" />} />
        <StoreStat label="Ждут выдачи" value={stats.toIssue} tone={stats.toIssue > 0 ? 'amber' : 'neutral'} hint="Одобрено, не выдано" loading={loading} />
        <StoreStat label="В пути" value={stats.issued} tone={stats.issued > 0 ? 'sky' : 'neutral'} hint="Выдано со склада" loading={loading} />
        <StoreStat label="История" value={stats.history} hint="Получены и отклонены" loading={loading} />
        <StoreStat label="Запрошено" value={formatQty(stats.totalRequested)} hint="ед. в очереди" loading={loading} />
        <StoreStat label="Одобрено" value={formatQty(stats.totalApproved)} tone={stats.totalApproved > 0 ? 'emerald' : 'neutral'} hint="ед. по решениям" loading={loading} />
      </StoreStatGrid>

      {error ? <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-200">{error}</div> : null}
      {refreshing ? (
        <div className="flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-xs text-muted-foreground">
          <Loader2 className="h-3.5 w-3.5 animate-spin" />
          Обновление списка…
        </div>
      ) : null}

      {/* Спорные — отдельно и заметно */}
      {disputedRequests.length > 0 && (
        <div className="space-y-3">
          <QueueHeading
            icon={<AlertCircle className="h-4 w-4 text-rose-600 dark:text-rose-300" />}
            title="Спорные заявки"
            count={disputedRequests.length}
            hint="Точка и склад не сошлись по количеству — нужно ваше решение."
          />
          {disputedRequests.map((request) => renderPending(request))}
        </div>
      )}

      {/* Очередь решений */}
      <div className="space-y-3">
        <QueueHeading
          icon={<ClipboardList className="h-4 w-4 text-amber-600 dark:text-amber-300" />}
          title="Ждут решения"
          count={newRequests.length}
          hint={newRequests.length > 0 ? 'Одобрить всё — как просили. «Изменить» — когда отдаёте не всё.' : undefined}
          checkbox={
            <input
              type="checkbox"
              className="h-4 w-4 accent-amber-500"
              checked={pendingRequests.length > 0 && pendingRequests.every((request) => selectedIds.includes(request.id))}
              onChange={(event) => toggleSelectAllPending(event.target.checked)}
              title="Выбрать все ждущие решения"
            />
          }
        />
        {loading && pendingRequests.length === 0 ? (
          <StorePanelSkeleton cards={3} />
        ) : newRequests.length === 0 ? (
          <div className={storeCard}>
            <StoreEmpty
              icon={<CheckCircle2 className="h-5 w-5" />}
              title="Новых заявок нет"
              hint="Здесь появятся запросы кассиров на пополнение витрин со склада."
            />
          </div>
        ) : (
          newRequests.map((request) => renderPending(request))
        )}
      </div>

      {/* Одобрено — отметить выдачу со склада (статус «Выдана») */}
      <div className="space-y-3">
        <QueueHeading
          icon={<PackageCheck className="h-4 w-4 text-amber-600 dark:text-amber-300" />}
          title="Одобрено — отметьте выдачу"
          count={readyToIssueRequests.length}
          hint="Когда товар реально отдали с подсобки на точку, нажмите «Отметить выданной»."
          checkbox={canIssue ? (
            <input
              type="checkbox"
              className="h-4 w-4 accent-amber-500"
              checked={readyToIssueRequests.length > 0 && readyToIssueRequests.every((request) => selectedIds.includes(request.id))}
              onChange={(event) => toggleSelectAllIn(readyToIssueRequests, event.target.checked)}
              title="Выбрать все для выдачи"
            />
          ) : undefined}
        />
        {readyToIssueRequests.length === 0 ? (
          <div className={`${storeCard} px-4 py-3 text-sm text-muted-foreground`}>Нет заявок в ожидании выдачи. После одобрения они появятся здесь.</div>
        ) : (
          <div className={`${storeCard} divide-y divide-border overflow-hidden`}>
            {readyToIssueRequests.map((request) => {
              const items = asArray(request.items)
              const requested = items.reduce((sum, i) => sum + Number(i.requested_qty || 0), 0)
              const approved = items.reduce((sum, i) => sum + Number(i.approved_qty || 0), 0)
              return (
                <StageRequestRow
                  key={request.id}
                  request={request}
                  tone="amber"
                  selectable={canIssue}
                  selected={selectedIds.includes(request.id)}
                  onSelect={(checked) => toggleSelected(request.id, checked)}
                  detail={<>{request.source_location?.name || 'Склад'} → {request.target_location?.name || 'Витрина'} · к выдаче {formatQty(approved)} из {formatQty(requested)} ед. · {items.length} {plural(items.length, ['позиция', 'позиции', 'позиций'])}</>}
                  actions={
                    <>
                      {canUndecide && (
                        <Button type="button" size="sm" variant="outline"
                          className="gap-1.5 border-rose-500/40 text-rose-700 hover:bg-rose-500/10 dark:text-rose-300"
                          disabled={savingId === request.id}
                          onClick={() => { setUndecideReason(''); setUndecideId(request.id) }}>
                          Откатить
                        </Button>
                      )}
                      {canIssue && (
                        <Button type="button" size="sm" className="gap-1.5 bg-amber-600 text-white hover:bg-amber-700"
                          disabled={savingId === request.id}
                          onClick={() => void transitionStatus(request.id, 'issued')}>
                          {savingId === request.id ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <PackageCheck className="h-3.5 w-3.5" />}
                          Отметить выданной
                        </Button>
                      )}
                    </>
                  }
                />
              )
            })}
          </div>
        )}
      </div>

      {/* Выдано — подтвердить получение на точке */}
      <div className="space-y-3">
        <QueueHeading
          icon={<PackageCheck className="h-4 w-4 text-sky-600 dark:text-sky-300" />}
          title="В пути — отметьте получение"
          count={issuedAwaitingReceiveRequests.length}
          hint="Когда витрина приняла товар, нажмите «Отметить полученной»."
          checkbox={canReceive ? (
            <input
              type="checkbox"
              className="h-4 w-4 accent-sky-500"
              checked={issuedAwaitingReceiveRequests.length > 0 && issuedAwaitingReceiveRequests.every((request) => selectedIds.includes(request.id))}
              onChange={(event) => toggleSelectAllIn(issuedAwaitingReceiveRequests, event.target.checked)}
              title="Выбрать все для получения"
            />
          ) : undefined}
        />
        {issuedAwaitingReceiveRequests.length === 0 ? (
          <div className={`${storeCard} px-4 py-3 text-sm text-muted-foreground`}>Нет заявок в статусе «Выдана».</div>
        ) : (
          <div className={`${storeCard} divide-y divide-border overflow-hidden`}>
            {issuedAwaitingReceiveRequests.map((request) => {
              const items = asArray(request.items)
              const approved = items.reduce((sum, i) => sum + Number(i.approved_qty || 0), 0)
              return (
                <StageRequestRow
                  key={request.id}
                  request={request}
                  tone="sky"
                  selectable={canReceive}
                  selected={selectedIds.includes(request.id)}
                  onSelect={(checked) => toggleSelected(request.id, checked)}
                  detail={<>Выдано {formatDateTime(request.issued_at)} · {formatQty(approved)} ед. · {items.length} {plural(items.length, ['позиция', 'позиции', 'позиций'])}</>}
                  actions={
                    canReceive ? (
                      <Button type="button" size="sm" className="gap-1.5 bg-sky-600 text-white hover:bg-sky-700"
                        disabled={savingId === request.id}
                        onClick={() => void transitionStatus(request.id, 'received')}>
                        {savingId === request.id ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <CheckCircle2 className="h-3.5 w-3.5" />}
                        Отметить полученной
                      </Button>
                    ) : null
                  }
                />
              )
            })}
          </div>
        )}
      </div>

      {selectedIds.length > 0 ? (
        <div className="sticky bottom-4 z-30 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-amber-500/30 bg-white/95 px-4 py-3 shadow-2xl backdrop-blur dark:bg-[#0f172a]/95">
          <div className="text-sm text-foreground">
            Выбрано заявок: <span className="font-semibold tabular-nums">{selectedIds.length}</span>
          </div>
          <div className="flex flex-wrap gap-2">
            {canBulkApprove && bulkIdsFor('approve-full').length > 0 && (
              <Button size="sm" onClick={() => runBulkAction('approve-full')} disabled={bulkSaving} className="gap-2 bg-emerald-600 text-white hover:bg-emerald-700">
                {bulkSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
                Одобрить ({bulkIdsFor('approve-full').length})
              </Button>
            )}
            {canBulkReject && bulkIdsFor('reject').length > 0 && (
              <Button size="sm" variant="destructive" onClick={() => runBulkAction('reject')} disabled={bulkSaving} className="gap-2">
                <XCircle className="h-4 w-4" />
                Отклонить ({bulkIdsFor('reject').length})
              </Button>
            )}
            {canIssue && bulkIdsFor('issue').length > 0 && (
              <Button size="sm" onClick={() => runBulkAction('issue')} disabled={bulkSaving} className="gap-2 bg-amber-600 text-white hover:bg-amber-700">
                {bulkSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <PackageCheck className="h-4 w-4" />}
                Выдать ({bulkIdsFor('issue').length})
              </Button>
            )}
            {canReceive && bulkIdsFor('receive').length > 0 && (
              <Button size="sm" onClick={() => runBulkAction('receive')} disabled={bulkSaving} className="gap-2 bg-sky-600 text-white hover:bg-sky-700">
                {bulkSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
                Получить ({bulkIdsFor('receive').length})
              </Button>
            )}
            <Button size="sm" variant="outline" onClick={() => setSelectedIds([])} disabled={bulkSaving}>
              Снять выбор
            </Button>
          </div>
        </div>
      ) : null}

      {/* Откат одобрения: причина вводится в поле */}
      <AppModal
        open={!!undecideId}
        onClose={() => { if (!savingId) setUndecideId(null) }}
        title="Откатить одобрение заявки?"
        description="Товар вернётся на склад, заявка станет «Новой»."
        tone="danger"
        maxWidth="max-w-md"
        footer={
          <>
            <Button variant="outline" onClick={() => setUndecideId(null)} disabled={!!savingId}>Назад</Button>
            <Button variant="destructive" disabled={!!savingId} onClick={() => undecideId && void undecideRequest(undecideId)}>
              {savingId ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              Откатить
            </Button>
          </>
        }
      >
        <div className="space-y-1.5">
          <Label>Причина (опционально)</Label>
          <Textarea
            rows={3}
            value={undecideReason}
            onChange={(event) => setUndecideReason(event.target.value)}
            placeholder="Например: одобрили не ту точку"
          />
        </div>
      </AppModal>
    </div>
    </>
  )
}

export default function StoreRequestsPage({ embedded = false }: { embedded?: boolean } = {}) {
  return (
    <Suspense fallback={null}>
      <StoreRequestsPageContent embedded={embedded} />
    </Suspense>
  )
}
