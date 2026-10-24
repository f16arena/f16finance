import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AlertTriangle, CheckCircle2, KeyRound, Package, Plus, RefreshCw, ScanBarcode, Search, Trash2, X } from 'lucide-react'

import { formatMoney, localRef } from '@/lib/utils'
import { toastError, toastSuccess } from '@/lib/toast'
import * as api from '@/lib/api'
import { getPendingCount, queueCreateDebt, queueDeleteDebt, syncQueue } from '@/lib/offline'
import { getCachedProducts, saveProductsCache } from '@/lib/cache'
import { formatShiftLabel } from '@/lib/receipt-html'
import { resolveRuntimeShift } from '@/lib/shift-runtime'
import type { AppConfig, BootstrapData, DebtItem, OperatorBasic, OperatorSession, Product } from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'

type Props = {
  config: AppConfig
  bootstrap: BootstrapData
  session: OperatorSession
  isOffline?: boolean
  onLogout: () => void
  onSwitchToShift?: () => void
  onSwitchToSale?: () => void
  onSwitchToReturn?: () => void
  onSwitchToHistory?: () => void
  onSwitchToRequest?: () => void
  onSwitchToArena?: () => void
  onOpenCabinet?: () => void
}

/** Подпись в списке должников: роль не дублируем, если уже есть в ФИО. */
function debtorSelectDisplay(op: OperatorBasic): string {
  const primary = (op.short_name || op.full_name || op.name || '').trim() || '—'
  const role = (op.role_label || '').trim()
  if (!role) return primary
  if (primary.toLowerCase().includes(role.toLowerCase())) return primary
  return `${primary} · ${role}`
}

function debtDebtorPayload(selectedId: string, list: OperatorBasic[]): { operator_id: string | null; client_name: string | null } {
  if (selectedId.startsWith('staff:') || selectedId.startsWith('orgmember:')) {
    const row = list.find((o) => o.id === selectedId)
    const clientName = (row?.short_name || row?.full_name || row?.name || '').trim()
    return { operator_id: null, client_name: clientName || null }
  }
  return { operator_id: selectedId?.trim() || null, client_name: null }
}

function playBeep(type: 'success' | 'error') {
  try {
    const ctx = new AudioContext()
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    if (type === 'success') {
      osc.frequency.value = 880
      gain.gain.setValueAtTime(0.2, ctx.currentTime)
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.12)
      osc.start(ctx.currentTime)
      osc.stop(ctx.currentTime + 0.12)
    } else {
      osc.type = 'sawtooth'
      osc.frequency.value = 180
      gain.gain.setValueAtTime(0.3, ctx.currentTime)
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.4)
      osc.start(ctx.currentTime)
      osc.stop(ctx.currentTime + 0.4)
    }
  } catch {
    /* звук недоступен */
  }
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

export default function DebtsScreenV2({
  config,
  bootstrap,
  session,
  isOffline: initOffline,
  onLogout,
  onSwitchToShift,
  onSwitchToSale,
  onSwitchToReturn,
  onSwitchToHistory,
  onSwitchToRequest,
  onSwitchToArena,
  onOpenCabinet,
}: Props) {
  const [products, setProducts] = useState<Product[]>([])
  const [debts, setDebts] = useState<DebtItem[]>([])
  const [allOperators, setAllOperators] = useState<OperatorBasic[]>([])
  const [loading, setLoading] = useState(true)
  const [offline, setOffline] = useState(initOffline ?? false)

  const [operatorId, setOperatorId] = useState(session.operator.operator_id)
  const [quantity, setQuantity] = useState('1')
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [flashMsg, setFlashMsg] = useState<{ type: 'ok' | 'err'; text: string } | null>(null)

  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null)
  const [debtFilterOpId, setDebtFilterOpId] = useState('all')

  const [adminTarget, setAdminTarget] = useState<{ id: string; item_name: string; total_amount: number } | null>(null)
  const [adminToken, setAdminTokenInput] = useState('')
  const [adminLoading, setAdminLoading] = useState(false)
  const [adminError, setAdminError] = useState<string | null>(null)

  const [scannedBarcode, setScannedBarcode] = useState('')
  const [foundProduct, setFoundProduct] = useState<Product | null>(null)
  const barcodeInputRef = useRef<HTMLInputElement>(null)

  const [nameSearch, setNameSearch] = useState('')
  const [showNameSearch, setShowNameSearch] = useState(false)

  const [, setPendingCount] = useState(0)
  const flashTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const operatorName = session.operator.full_name || session.operator.name || session.operator.username
  const nav: V2Nav = {
    shift: onSwitchToShift,
    sale: onSwitchToSale,
    return: onSwitchToReturn,
    history: onSwitchToHistory,
    request: onSwitchToRequest,
    arena: onSwitchToArena,
    cabinet: onOpenCabinet,
  }

  const loadAll = useCallback(async () => {
    setLoading(true)
    try {
      const [prodsResult, opsResult, debtsResult] = await Promise.allSettled([
        api.getProducts(config, session.company.id),
        api.getAllOperators(config, session.company.id),
        api.getDebts(config, session.company.id),
      ])

      if (prodsResult.status === 'fulfilled') {
        saveProductsCache(prodsResult.value).catch(() => null)
        setProducts(prodsResult.value.filter((p) => p.is_active))
        setOffline(false)
      } else {
        const cached = await getCachedProducts()
        setProducts(cached.filter((p) => p.is_active))
        setOffline(true)
      }

      const sessionOpEntry: OperatorBasic = {
        id: session.operator.operator_id,
        name: session.operator.name || session.operator.username,
        short_name: session.operator.short_name || null,
        full_name: session.operator.full_name || null,
        role_label: null,
      }
      if (opsResult.status === 'fulfilled') {
        const ops = opsResult.value
        const alreadyIn = ops.some((o) => o.id === sessionOpEntry.id)
        setAllOperators(alreadyIn ? ops : [sessionOpEntry, ...ops])
      } else {
        const fallback = bootstrap.operators.map(
          (o): OperatorBasic => ({ id: o.id, name: o.name, short_name: o.short_name, full_name: o.full_name, role_label: null }),
        )
        if (!fallback.some((o) => o.id === sessionOpEntry.id)) fallback.unshift(sessionOpEntry)
        setAllOperators(fallback)
      }

      setDebts(debtsResult.status === 'fulfilled' ? debtsResult.value : [])
      setPendingCount(await getPendingCount())
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    void loadAll()
  }, [loadAll])

  useEffect(() => {
    barcodeInputRef.current?.focus()
  }, [foundProduct])

  const doSync = useCallback(async () => {
    const { synced, failed } = await syncQueue(config)
    setPendingCount(await getPendingCount())
    if (synced > 0) {
      toastSuccess(`Синхронизировано: ${synced} ${synced === 1 ? 'запись' : 'записей'}`)
      await loadAll()
    }
    if (failed > 0) toastError(`Не удалось синхронизировать: ${failed}`)
  }, [config, loadAll])

  useEffect(() => {
    const i = setInterval(async () => {
      const count = await getPendingCount()
      if (count > 0) void doSync()
    }, 60000)
    return () => clearInterval(i)
  }, [doSync])

  useEffect(() => {
    const handleOnline = () => void doSync()
    window.addEventListener('online', handleOnline)
    return () => window.removeEventListener('online', handleOnline)
  }, [doSync])

  function flash(type: 'ok' | 'err', text: string) {
    setFlashMsg({ type, text })
    if (flashTimer.current) clearTimeout(flashTimer.current)
    flashTimer.current = setTimeout(() => setFlashMsg(null), 3000)
  }

  function handleBarcodeKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key !== 'Enter') return
    e.preventDefault()
    const code = scannedBarcode.trim()
    if (!code) return
    const product = products.find((p) => p.barcode === code)
    if (product) {
      setFoundProduct(product)
      setQuantity('1')
      setComment('')
      flash('ok', `Найден: ${product.name} — ${formatMoney(product.price)}`)
      playBeep('success')
    } else {
      flash('err', `Штрихкод не найден: ${code}`)
      playBeep('error')
    }
  }

  async function handleAddDebt(e: React.FormEvent) {
    e.preventDefault()
    if (!foundProduct) return
    const qty = Math.max(1, parseInt(quantity) || 1)
    const total = foundProduct.price * qty
    const ref = localRef()

    setSubmitting(true)
    try {
      const debtor = debtDebtorPayload(operatorId, allOperators)
      await api.createDebt(
        config,
        {
          operator_id: debtor.operator_id,
          client_name: debtor.client_name,
          item_name: foundProduct.name,
          item_id: foundProduct.id,
          barcode: foundProduct.barcode,
          quantity: qty,
          unit_price: foundProduct.price,
          total_amount: total,
          comment: comment || null,
          local_ref: ref,
          created_by_operator_id: session.operator.operator_id,
        },
        session.company.id,
      )

      flash('ok', `Долг: ${foundProduct.name} × ${qty} = ${formatMoney(total)}`)
      playBeep('success')
      setFoundProduct(null)
      setScannedBarcode('')
      setQuantity('1')
      setComment('')
      barcodeInputRef.current?.focus()
      api.getDebts(config, session.company.id).then(setDebts).catch(() => {})
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Ошибка отправки долга'
      const canQueueOffline =
        message === 'Failed to fetch' || message.includes('fetch') || message.includes('NetworkError') || message.includes('Load failed')

      if (!canQueueOffline) {
        const errorMap: Record<string, string> = {
          'inventory-debt-item-not-found': 'Товар не найден в инвентаре — добавьте его на сайте Orda',
          'inventory-debt-quantity-invalid': 'Неверное количество',
          'inventory-debt-location-not-found': 'Склад точки не настроен — обратитесь к администратору',
          'inventory-insufficient-stock': 'На витрине точки нет столько товара — переместите со склада в Orda или уменьшите количество',
          'debt-report-disabled-for-device': 'Долги для этой точки отключены — включите «Долги и сканер» в настройках проекта Orda (или для компании)',
          'operator-not-found': 'Оператор не найден или неактивен',
          'debt-delete-window-expired': 'Удалить можно только в течение 15 минут',
        }
        const friendlyMsg =
          errorMap[message] || (message.includes('inventory_balances_quantity_check') ? errorMap['inventory-insufficient-stock'] : message)
        flash('err', friendlyMsg)
        return
      }

      const debtorQ = debtDebtorPayload(operatorId, allOperators)
      await queueCreateDebt(
        {
          operator_id: debtorQ.operator_id,
          client_name: debtorQ.client_name,
          item_name: foundProduct.name,
          item_id: foundProduct.id,
          barcode: foundProduct.barcode,
          quantity: qty,
          unit_price: foundProduct.price,
          total_amount: total,
          comment: comment || null,
          local_ref: ref,
          created_by_operator_id: session.operator.operator_id,
        },
        session.company.id,
      )
      setPendingCount(await getPendingCount())
      flash('ok', 'Сохранено в очередь (нет сети)')
      setFoundProduct(null)
      setScannedBarcode('')
    } finally {
      setSubmitting(false)
    }
  }

  async function handleDeleteConfirmed(itemId: string) {
    setDeleteConfirm(null)
    try {
      await api.deleteDebt(config, itemId, session.company.id, session.operator.operator_id)
    } catch {
      await queueDeleteDebt(itemId, session.company.id, session.operator.operator_id)
      setPendingCount(await getPendingCount())
    }
    setDebts((prev) => prev.filter((d) => d.id !== itemId))
  }

  async function handleAdminDelete() {
    if (!adminTarget) return
    setAdminLoading(true)
    setAdminError(null)
    try {
      await api.deleteDebt(config, adminTarget.id, session.company.id, null, adminToken)
      setDebts((prev) => prev.filter((d) => d.id !== adminTarget.id))
      setAdminTarget(null)
      setAdminTokenInput('')
      toastSuccess('Долг удалён (инвентарь возвращён)')
    } catch (err: any) {
      setAdminError(err?.message === 'admin-token-required' ? 'Неверный токен' : err?.message || 'Ошибка')
    } finally {
      setAdminLoading(false)
    }
  }

  async function handleAdminPay() {
    if (!adminTarget) return
    setAdminLoading(true)
    setAdminError(null)
    try {
      await api.adminPayDebt(config, adminTarget.id, adminToken, session.company.id)
      setDebts((prev) => prev.filter((d) => d.id !== adminTarget.id))
      setAdminTarget(null)
      setAdminTokenInput('')
      toastSuccess('Долг отмечен оплаченным')
    } catch (err: any) {
      setAdminError(err?.message === 'admin-token-required' ? 'Неверный токен' : err?.message || 'Ошибка')
    } finally {
      setAdminLoading(false)
    }
  }

  const totalDebt = debts.reduce((s, d) => s + d.total_amount, 0)

  const debtOperators = useMemo(() => {
    const map = new Map<string, string>()
    for (const d of debts) if (d.operator_id && d.debtor_name) map.set(d.operator_id, d.debtor_name)
    return [...map.entries()]
  }, [debts])

  const filteredDebts = debtFilterOpId === 'all' ? debts : debts.filter((d) => d.operator_id === debtFilterOpId)

  const nameSearchResults =
    nameSearch.trim().length >= 2 ? products.filter((p) => p.name.toLowerCase().includes(nameSearch.toLowerCase())).slice(0, 8) : []

  function selectProductFromSearch(product: Product) {
    setFoundProduct(product)
    setQuantity('1')
    setComment('')
    setNameSearch('')
    setShowNameSearch(false)
    flash('ok', `Найден: ${product.name} — ${formatMoney(product.price)}`)
    playBeep('success')
  }

  const deleteDebt = deleteConfirm ? debts.find((d) => d.id === deleteConfirm) : null

  return (
    <V2Shell
      active="scanner"
      nav={nav}
      companyName={session.company.name}
      operatorName={operatorName}
      shiftLabel={formatShiftLabel(resolveRuntimeShift().shift)}
      onLogout={onLogout}
    >
      <div className="relative grid h-full min-h-0 grid-cols-[clamp(300px,29vw,400px)_minmax(0,1fr)] gap-4 p-4">
        {/* ── Сканер ───────────────────────────────────────────────── */}
        <aside className="surface flex min-h-0 flex-col gap-4 overflow-y-auto rounded-[24px] p-5">
          <div className="flex items-center justify-between">
            <h2 className="flex items-center gap-2 text-[18px] font-bold">
              <ScanBarcode className="t-blue h-5 w-5" /> Сканер
            </h2>
            {offline ? <span className="muted text-[13px]">кеш товаров</span> : null}
          </div>

          <div className="surface-2 rounded-[20px] p-4">
            <div className="mb-3 flex flex-col items-center gap-1 text-center">
              <div className="fill grid h-14 w-14 place-items-center rounded-full">
                <ScanBarcode className="t-blue h-7 w-7" />
              </div>
              <p className="text-[15px] font-semibold">Наведите сканер</p>
              <p className="muted text-[13px]">или введите код вручную и нажмите Enter</p>
            </div>
            <input
              ref={barcodeInputRef}
              value={scannedBarcode}
              onChange={(e) => {
                setScannedBarcode(e.target.value)
                setFoundProduct(null)
              }}
              onKeyDown={handleBarcodeKeyDown}
              placeholder="Штрихкод"
              autoFocus
              className="fill placeholder-muted h-12 w-full rounded-[14px] px-4 text-center font-mono text-[18px] tracking-widest outline-none"
            />
            <button
              type="button"
              onClick={() => {
                setShowNameSearch(!showNameSearch)
                setNameSearch('')
              }}
              className="press t-blue mt-2 flex h-11 w-full items-center justify-center gap-2 rounded-[14px] text-[14px] font-medium"
            >
              <Search className="h-4 w-4" /> {showNameSearch ? 'Скрыть поиск' : 'Найти по названию'}
            </button>
          </div>

          {showNameSearch ? (
            <div className="flex flex-col gap-2">
              <input
                value={nameSearch}
                onChange={(e) => setNameSearch(e.target.value)}
                placeholder="Название товара"
                autoFocus
                className="fill placeholder-muted h-12 w-full rounded-[14px] px-4 text-[15px] outline-none"
              />
              {nameSearchResults.length > 0 ? (
                <div className="surface-2 overflow-hidden rounded-[16px]">
                  {nameSearchResults.map((p) => (
                    <button
                      key={p.id}
                      type="button"
                      onClick={() => selectProductFromSearch(p)}
                      className="press flex min-h-[48px] w-full items-center justify-between gap-3 px-4 text-left text-[15px]"
                    >
                      <span className="truncate">{p.name}</span>
                      <span className="muted shrink-0 text-[13px]">{formatMoney(p.price)}</span>
                    </button>
                  ))}
                </div>
              ) : null}
              {nameSearch.trim().length >= 2 && nameSearchResults.length === 0 ? <p className="muted text-[13px]">Ничего не найдено</p> : null}
            </div>
          ) : null}

          {flashMsg ? (
            <p
              className="slide-in flex items-center gap-2 rounded-[14px] px-3.5 py-3 text-[14px] font-medium"
              style={{
                background: `color-mix(in srgb, var(${flashMsg.type === 'ok' ? '--green' : '--red'}) 14%, transparent)`,
                color: flashMsg.type === 'ok' ? 'var(--green)' : 'var(--red)',
              }}
            >
              {flashMsg.type === 'ok' ? <CheckCircle2 className="h-4 w-4 shrink-0" /> : <AlertTriangle className="h-4 w-4 shrink-0" />}
              {flashMsg.text}
            </p>
          ) : null}

          {foundProduct ? (
            <form onSubmit={handleAddDebt} className="slide-in flex flex-col gap-3">
              <div className="surface-2 rounded-[16px] p-4">
                <div className="t-green mb-1 flex items-center gap-1.5 text-[12px] font-semibold uppercase tracking-wide">
                  <CheckCircle2 className="h-4 w-4" /> Товар найден
                </div>
                <p className="text-[17px] font-bold">{foundProduct.name}</p>
                <p className="muted text-[13px]">
                  {foundProduct.barcode} · <span className="ink font-semibold">{formatMoney(foundProduct.price)}</span> / шт.
                </p>
              </div>

              <label className="flex flex-col gap-1.5">
                <span className="muted text-[13px]">Должник</span>
                <select
                  value={operatorId}
                  onChange={(e) => setOperatorId(e.target.value)}
                  className="fill h-12 w-full rounded-[14px] px-3 text-[15px] outline-none"
                >
                  {allOperators.map((op) => (
                    <option key={op.id} value={op.id}>
                      {debtorSelectDisplay(op)}
                    </option>
                  ))}
                </select>
              </label>

              <div className="flex gap-3">
                <label className="flex flex-1 flex-col gap-1.5">
                  <span className="muted text-[13px]">Количество</span>
                  <input
                    type="number"
                    min="1"
                    value={quantity}
                    onChange={(e) => setQuantity(e.target.value)}
                    disabled={submitting}
                    className="fill h-12 w-full rounded-[14px] px-4 text-[16px] outline-none"
                  />
                </label>
                <div className="flex flex-1 flex-col gap-1.5">
                  <span className="muted text-[13px]">Сумма</span>
                  <div className="surface-2 flex h-12 items-center rounded-[14px] px-4 text-[16px] font-semibold">
                    {formatMoney(foundProduct.price * (parseInt(quantity) || 1))}
                  </div>
                </div>
              </div>

              <label className="flex flex-col gap-1.5">
                <span className="muted text-[13px]">Комментарий</span>
                <input
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder="Необязательно"
                  disabled={submitting}
                  className="fill placeholder-muted h-12 w-full rounded-[14px] px-4 text-[15px] outline-none"
                />
              </label>

              <button type="submit" disabled={submitting} className="press btn-green flex h-14 items-center justify-center gap-2 rounded-[18px] text-[17px] font-semibold">
                {submitting ? <RefreshCw className="h-5 w-5 animate-spin" /> : <Plus className="h-5 w-5" />}
                Добавить долг
              </button>
            </form>
          ) : null}
        </aside>

        {/* ── Список долгов ─────────────────────────────────────────── */}
        <main className="flex min-h-0 flex-col gap-3">
          <div className="flex items-center gap-3">
            <div className="min-w-0 flex-1">
              <h2 className="text-[20px] font-bold">Долги за неделю</h2>
              <p className="muted text-[14px]">
                {filteredDebts.length} {filteredDebts.length === 1 ? 'позиция' : 'позиций'}
                {totalDebt > 0 ? (
                  <>
                    {' · '}
                    <span className="t-red font-semibold">{formatMoney(totalDebt)}</span>
                  </>
                ) : null}
              </p>
            </div>
            {debtOperators.length > 1 ? (
              <select
                value={debtFilterOpId}
                onChange={(e) => setDebtFilterOpId(e.target.value)}
                className="fill h-11 max-w-[260px] rounded-[14px] px-3 text-[14px] outline-none"
              >
                <option value="all">Все операторы ({debts.length})</option>
                {debtOperators.map(([id, name]) => (
                  <option key={id} value={id}>
                    {name} ({debts.filter((d) => d.operator_id === id).length})
                  </option>
                ))}
              </select>
            ) : null}
            <button
              type="button"
              onClick={() => api.getDebts(config, session.company.id).then(setDebts).catch(() => {})}
              className="press surface flex h-11 items-center gap-2 rounded-[14px] px-4 text-[14px] font-medium"
            >
              <RefreshCw className="t-blue h-4 w-4" /> Обновить
            </button>
          </div>

          {loading ? (
            <div className="flex flex-1 items-center justify-center">
              <RefreshCw className="muted h-6 w-6 animate-spin" />
            </div>
          ) : debts.length === 0 ? (
            <div className="muted flex flex-1 flex-col items-center justify-center gap-2">
              <Package className="h-12 w-12 opacity-30" />
              <p className="text-[15px]">Долгов нет</p>
            </div>
          ) : (
            <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto pr-1">
              {filteredDebts.map((debt) => {
                const own = debt.operator_id === session.operator.operator_id || debt.created_by_operator_id === session.operator.operator_id
                return (
                  <div key={debt.id} className="surface slide-in flex items-center gap-4 rounded-[18px] px-4 py-3">
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[16px] font-semibold">{debt.item_name}</p>
                      <p className="muted truncate text-[13px]">
                        {debt.debtor_name} · {debt.quantity} шт.
                        {debt.comment ? <> · {debt.comment}</> : null}
                      </p>
                    </div>
                    <span className="t-red shrink-0 text-[17px] font-bold">{formatMoney(debt.total_amount)}</span>
                    {own ? (
                      <button
                        type="button"
                        onClick={() => setDeleteConfirm(debt.id)}
                        className="press fill grid h-11 w-11 shrink-0 place-items-center rounded-full"
                        aria-label="Удалить долг"
                      >
                        <Trash2 className="t-red h-[18px] w-[18px]" />
                      </button>
                    ) : null}
                    <button
                      type="button"
                      onClick={() => {
                        setAdminTarget({ id: debt.id, item_name: debt.item_name, total_amount: debt.total_amount })
                        setAdminTokenInput('')
                        setAdminError(null)
                      }}
                      className="press fill grid h-11 w-11 shrink-0 place-items-center rounded-full"
                      title="Администратор: удалить или оплатить"
                      aria-label="Действие администратора"
                    >
                      <KeyRound className="muted h-[18px] w-[18px]" />
                    </button>
                  </div>
                )
              })}
            </div>
          )}
        </main>

        {/* ── Шторки ────────────────────────────────────────────────── */}
        {adminTarget ? (
          <Sheet title="Действие администратора" onClose={() => setAdminTarget(null)}>
            <p className="muted mb-3 text-[14px]">
              {adminTarget.item_name} — <span className="ink font-semibold">{formatMoney(adminTarget.total_amount)}</span>
            </p>
            <input
              type="password"
              value={adminToken}
              onChange={(e) => {
                setAdminTokenInput(e.target.value)
                setAdminError(null)
              }}
              placeholder="Токен администратора"
              autoFocus
              className="fill placeholder-muted h-12 w-full rounded-[14px] px-4 text-[16px] outline-none"
            />
            {adminError ? <p className="t-red mt-2 text-[13px]">{adminError}</p> : null}
            <div className="mt-4 flex flex-col gap-2">
              <button
                type="button"
                disabled={!adminToken || adminLoading}
                onClick={handleAdminPay}
                className="press btn-green flex h-12 items-center justify-center gap-2 rounded-[16px] text-[15px] font-semibold"
              >
                <CheckCircle2 className="h-4 w-4" /> Оплатил долг (без возврата)
              </button>
              <button
                type="button"
                disabled={!adminToken || adminLoading}
                onClick={handleAdminDelete}
                className="press fill t-red flex h-12 items-center justify-center gap-2 rounded-[16px] text-[15px] font-semibold"
              >
                <Trash2 className="h-4 w-4" /> Удалить (вернуть инвентарь)
              </button>
              <button type="button" disabled={adminLoading} onClick={() => setAdminTarget(null)} className="press h-11 rounded-[16px] text-[15px] font-medium">
                Отмена
              </button>
            </div>
          </Sheet>
        ) : null}

        {deleteConfirm ? (
          <Sheet title="Удалить долг?" onClose={() => setDeleteConfirm(null)}>
            <p className="mb-4 text-[15px]">
              <strong>{deleteDebt?.item_name}</strong>
              {deleteDebt ? (
                <span className="muted block text-[13px]">
                  {deleteDebt.debtor_name} · {formatMoney(deleteDebt.total_amount)}
                </span>
              ) : null}
            </p>
            <div className="flex gap-2">
              <button type="button" onClick={() => setDeleteConfirm(null)} className="press fill h-12 flex-1 rounded-[16px] text-[15px] font-semibold">
                Отмена
              </button>
              <button
                type="button"
                onClick={() => handleDeleteConfirmed(deleteConfirm)}
                className="press h-12 flex-1 rounded-[16px] text-[15px] font-semibold text-white"
                style={{ background: 'var(--red)' }}
              >
                Удалить
              </button>
            </div>
          </Sheet>
        ) : null}
      </div>
    </V2Shell>
  )
}
