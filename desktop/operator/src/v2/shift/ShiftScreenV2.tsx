import { useState, useEffect, useCallback, useRef, type ReactNode } from 'react'
import {
  AlertTriangle,
  CalendarDays,
  CheckCircle2,
  ClipboardList,
  Clock,
  CreditCard,
  Moon,
  Printer,
  RefreshCw,
  Send,
  ShoppingBag,
  ReceiptText,
  RotateCcw,
  SplitSquareVertical,
  Sun,
  UserRound,
  WifiOff,
  X,
} from 'lucide-react'
import { useCashlessLabels } from '@/lib/use-cashless-labels'
import { formatMoney, parseMoney, todayISO, localRef } from '@/lib/utils'
import { toastSuccess, toastError } from '@/lib/toast'
import * as api from '@/lib/api'
import { buildShiftReportHtml, printReceiptFromIframe } from '@/lib/receipt-html'
import { syncQueue, getPendingCount, queueClosePointShift, queueShiftReport, openQueueScreen } from '@/lib/offline'
import { clearParkedCarts } from '@/lib/parked-carts'
import type { OpenShiftInfo } from '@/lib/api'
import type {
  AppConfig,
  BootstrapData,
  DailyKaspiReport,
  OperatorSession,
  PointChecklistRun,
  PointChecklistTemplate,
  PointKnowledgeContext,
  PointInventorySaleShiftSummary,
  ShiftForm,
} from '@/types'
import { V2Shell, type V2Nav } from '../ui/V2Shell'

interface Props {
  config: AppConfig
  bootstrap: BootstrapData
  session: OperatorSession
  isOffline?: boolean
  openShift?: OpenShiftInfo | null
  onOpenShiftChange?: (shift: OpenShiftInfo | null) => void
  onLogout: () => void
  onSwitchToSale?: () => void
  onSwitchToReturn?: () => void
  onSwitchToScanner?: () => void
  onSwitchToRequest?: () => void
  onSwitchToArena?: () => void
  onOpenChecklists?: () => void
  onOpenSalesHistory?: () => void
  onOpenCabinet?: () => void
}

// Тот же ключ, что у классической страницы: черновик общий.
const DRAFT_KEY = 'shift_draft_v2'

const emptyForm = (): ShiftForm => ({
  date: todayISO(),
  operator_id: '',
  shift: 'day',
  cash: '',
  coins: '',
  kaspi_pos: '',
  kaspi_before_midnight: '',
  kaspi_online: '',
  debts: '',
  start: '',
  wipon: '',
  comment: '',
})

function isBlockingChecklistDue(template: PointChecklistTemplate, runs: PointChecklistRun[]) {
  if (!template.blocks_shift || !template.is_active || template.schedule_type === 'onboarding') return false
  const completedRuns = runs
    .filter((run) => run.template_id === template.id && run.status === 'completed' && run.completed_at)
    .sort((a, b) => String(b.completed_at).localeCompare(String(a.completed_at)))

  if (completedRuns.length === 0) return true
  if (template.schedule_type !== 'periodic') return false

  const recurrenceMs = Number(template.recurrence_minutes || 0) * 60_000
  if (recurrenceMs <= 0) return false
  const lastCompletedAt = new Date(String(completedRuns[0].completed_at)).getTime()
  return !Number.isFinite(lastCompletedAt) || Date.now() - lastCompletedAt >= recurrenceMs
}

/** Имя проекта (узла): не путать с юр. точкой `session.company`, если названия разные. */
function workspaceSubtitle(bootstrap: BootstrapData, session: OperatorSession): string {
  const dn = (bootstrap.device.name || '').trim()
  const cn = (session.company.name || '').trim()
  if (dn && cn && dn !== cn) return `Проект: ${dn}`
  if (dn) return dn
  return bootstrap.device.point_mode
}

const money = (v: number) => formatMoney(v)

/* ── UI-кирпичики ─────────────────────────────────────────────────────── */

function Sheet(props: { title: string; onClose?: () => void; wide?: boolean; children: ReactNode }) {
  return (
    <div className="absolute inset-0 z-30 flex items-end justify-center p-3" style={{ background: 'rgba(0,0,0,0.32)' }}>
      <div
        className={`sheet-in glass w-full rounded-[24px] p-5 ${props.wide ? 'max-w-[520px]' : 'max-w-[460px]'}`}
        style={{ boxShadow: 'var(--shadow-lift)' }}
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-[18px] font-bold">{props.title}</h3>
          {props.onClose ? (
            <button type="button" onClick={props.onClose} className="press fill grid h-9 w-9 place-items-center rounded-full" aria-label="Закрыть">
              <X className="h-4 w-4" />
            </button>
          ) : null}
        </div>
        {props.children}
      </div>
    </div>
  )
}

function MoneyField(props: {
  label: string
  value: string
  onChange: (v: string) => void
  disabled?: boolean
  note?: string
  autoFocus?: boolean
}) {
  return (
    <label className="block">
      <span className="muted text-[13px]">{props.label}</span>
      <div className="surface-2 mt-1 flex h-14 items-center rounded-[14px] px-4">
        <input
          value={props.value}
          onChange={(e) => props.onChange(e.target.value.replace(/[^\d.]/g, ''))}
          inputMode="decimal"
          autoFocus={props.autoFocus}
          disabled={props.disabled}
          className="placeholder-muted h-full min-w-0 flex-1 bg-transparent text-[22px] font-semibold outline-none disabled:opacity-50"
          placeholder="0"
        />
        <span className="muted text-[18px]">₸</span>
      </div>
      {props.note ? <span className="muted mt-1 block text-[12px]">{props.note}</span> : null}
    </label>
  )
}

function Segmented<T extends string>(props: {
  value: T
  onChange: (v: T) => void
  options: Array<{ id: T; label: string; icon?: ReactNode }>
  disabled?: boolean
}) {
  return (
    <div className="fill flex items-center gap-1 rounded-[14px] p-1">
      {props.options.map((o) => (
        <button
          key={o.id}
          type="button"
          disabled={props.disabled}
          onClick={() => props.onChange(o.id)}
          className={`press flex h-11 flex-1 items-center justify-center gap-2 rounded-[11px] px-4 text-[14px] font-medium ${props.value === o.id ? 'pill-active' : 'ink-2'}`}
        >
          {o.icon}
          {o.label}
        </button>
      ))}
    </div>
  )
}

function Card(props: { title?: string; hint?: string; children: ReactNode }) {
  return (
    <section className="surface rounded-[22px] p-5">
      {props.title ? (
        <div className="mb-3">
          <h2 className="text-[17px] font-bold">{props.title}</h2>
          {props.hint ? <p className="muted mt-0.5 text-[13px]">{props.hint}</p> : null}
        </div>
      ) : null}
      {props.children}
    </section>
  )
}

function Stat(props: { label: string; value: string; tone?: 'green' | 'red' | 'blue' }) {
  const cls = props.tone === 'green' ? 't-green' : props.tone === 'red' ? 't-red' : props.tone === 'blue' ? 't-blue' : 'ink'
  return (
    <div className="surface-2 min-w-0 rounded-[16px] px-4 py-3">
      <div className="muted truncate text-[13px]">{props.label}</div>
      <div className={`mt-0.5 truncate text-[18px] font-semibold ${cls}`}>{props.value}</div>
    </div>
  )
}

function Row(props: { label: string; value: number; dim?: boolean; strong?: boolean; negativeRed?: boolean }) {
  return (
    <div className="flex items-baseline justify-between py-1 text-[15px]">
      <span className={props.strong ? 'ink font-semibold' : props.dim ? 'muted' : 'ink-2'}>{props.label}</span>
      <span className={`${props.strong ? 'text-[18px] font-bold' : 'font-medium'} ${props.negativeRed && props.value < 0 ? 't-red' : props.dim ? 'muted' : 'ink'}`}>
        {money(props.value)}
      </span>
    </div>
  )
}

function Notice(props: { tone: 'red' | 'orange' | 'green'; icon: ReactNode; children: ReactNode; action?: ReactNode }) {
  const color = props.tone === 'red' ? 'var(--red)' : props.tone === 'orange' ? 'var(--orange)' : 'var(--green)'
  return (
    <div
      className="flex items-center gap-3 rounded-[16px] px-4 py-3 text-[14px]"
      style={{ background: `color-mix(in srgb, ${color} 14%, transparent)` }}
    >
      <span className="shrink-0" style={{ color }}>{props.icon}</span>
      <div className="ink min-w-0 flex-1">{props.children}</div>
      {props.action}
    </div>
  )
}

function ActionButton(props: { icon: ReactNode; label: string; onClick?: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      onClick={props.onClick}
      disabled={props.disabled}
      className="press fill flex h-12 items-center gap-2.5 rounded-[14px] px-3.5 text-left text-[14px] font-medium"
    >
      {props.icon}
      <span className="truncate">{props.label}</span>
    </button>
  )
}

function ConfirmLine(props: { label: string; value: string }) {
  return (
    <div className="flex justify-between">
      <span className="muted">{props.label}</span>
      <span className="font-medium">{props.value}</span>
    </div>
  )
}

/* ── Экран ────────────────────────────────────────────────────────────── */

export default function ShiftScreenV2({
  config,
  bootstrap,
  session,
  isOffline,
  openShift,
  onOpenShiftChange,
  onLogout,
  onSwitchToSale,
  onSwitchToReturn,
  onSwitchToScanner,
  onSwitchToRequest,
  onSwitchToArena,
  onOpenChecklists,
  onOpenSalesHistory,
  onOpenCabinet,
}: Props) {
  const [draftLost, setDraftLost] = useState(false)
  const [form, setForm] = useState<ShiftForm>(() => {
    try {
      const saved = localStorage.getItem(DRAFT_KEY)
      if (saved) {
        const parsed = JSON.parse(saved)
        if ('kaspi_online' in parsed) {
          return {
            ...emptyForm(),
            ...parsed,
            kaspi_before_midnight: typeof parsed.kaspi_before_midnight === 'string' ? parsed.kaspi_before_midnight : '',
          }
        }
      }
    } catch {
      localStorage.removeItem(DRAFT_KEY)
      setTimeout(() => setDraftLost(true), 0)
    }
    localStorage.removeItem(DRAFT_KEY)
    return emptyForm()
  })

  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<'success' | 'queued' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [pendingCount, setPendingCount] = useState(0)
  const [syncing, setSyncing] = useState(false)

  const [confirmDialog, setConfirmDialog] = useState(false)
  const [startCashInput, setStartCashInput] = useState('')
  const [activeOpenShift, setActiveOpenShift] = useState<OpenShiftInfo | null>(openShift || null)
  const [shiftReport, setShiftReport] = useState<any | null>(null)
  const shiftReportIframeRef = useRef<HTMLIFrameElement | null>(null)
  const [openingShift, setOpeningShift] = useState(false)
  const [openingError, setOpeningError] = useState<string | null>(null)
  const [viewMode, setViewMode] = useState<'shift' | 'daily'>('shift')
  const [dailyDate, setDailyDate] = useState(todayISO())
  const [dailyLoading, setDailyLoading] = useState(false)
  const [dailyError, setDailyError] = useState<string | null>(null)
  const [dailyReport, setDailyReport] = useState<DailyKaspiReport | null>(null)
  const [salesSummary, setSalesSummary] = useState<PointInventorySaleShiftSummary | null>(null)
  const [salesSummaryLoading, setSalesSummaryLoading] = useState(false)
  const [knowledgeContext, setKnowledgeContext] = useState<PointKnowledgeContext | null>(null)

  const flags = bootstrap.device.feature_flags
  const hasInventorySale = !!onSwitchToSale
  const hasScanner = !!(flags.debt_report && onSwitchToScanner)
  const kaspiDailySplitEnabled = flags.kaspi_daily_split === true
  const isNightKaspiSplit = kaspiDailySplitEnabled && form.shift === 'night'
  const dueBlockingChecklists = knowledgeContext?.open_shift
    ? (knowledgeContext.checklist_templates || []).filter((template) =>
        isBlockingChecklistDue(template, knowledgeContext.checklist_runs || []),
      )
    : []

  const mergeInventorySalesIntoShift = hasInventorySale
  const wiponLabel = mergeInventorySalesIntoShift ? 'Продажи POS (система)' : 'Wipon (система)'
  const cashLabels = useCashlessLabels(session)
  const kaspiLabel = cashLabels.providerName

  useEffect(() => {
    setActiveOpenShift(openShift || null)
    if (openShift) {
      setForm((current) => ({
        ...current,
        shift: openShift.shift_type === 'night' ? 'night' : 'day',
        start: String(Number(openShift.opening_cash || 0)),
      }))
    }
  }, [openShift?.id, openShift?.opening_cash, openShift?.shift_type]) // eslint-disable-line react-hooks/exhaustive-deps

  const refreshOpenShift = useCallback(async () => {
    try {
      const info = await api.getCurrentPointShift(config, session.company.id)
      setActiveOpenShift(info)
      onOpenShiftChange?.(info)
      if (info) {
        setForm((current) => ({
          ...current,
          shift: info.shift_type === 'night' ? 'night' : 'day',
          start: String(Number(info.opening_cash || 0)),
        }))
      }
    } catch {
      // Временная ошибка сети не должна скрывать уже открытую смену.
    }
  }, [config, onOpenShiftChange, session.company.id])

  useEffect(() => {
    const hasMeaningfulDraft = !!(
      form.cash || form.coins || form.kaspi_pos || form.kaspi_online ||
      form.debts || form.start || form.wipon || form.comment
    )
    if (!hasMeaningfulDraft) {
      const hour = new Date().getHours()
      setForm((current) => ({ ...current, shift: hour >= 8 && hour < 20 ? 'day' : 'night' }))
    }
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    void refreshOpenShift()
  }, [refreshOpenShift])

  useEffect(() => {
    const timeout = setTimeout(() => localStorage.setItem(DRAFT_KEY, JSON.stringify(form)), 500)
    return () => clearTimeout(timeout)
  }, [form])

  useEffect(() => {
    const refresh = async () => setPendingCount(await getPendingCount())
    void refresh()
    const interval = setInterval(refresh, 10000)
    return () => clearInterval(interval)
  }, [])

  const doSync = useCallback(async () => {
    setSyncing(true)
    try {
      const { synced, failed } = await syncQueue(config)
      setPendingCount(await getPendingCount())
      if (synced > 0) toastSuccess(`Синхронизировано: ${synced} ${synced === 1 ? 'отчёт' : 'отчётов'}`)
      if (failed > 0) toastError(`Не удалось синхронизировать: ${failed}`)
    } finally {
      setSyncing(false)
    }
  }, [config])

  useEffect(() => {
    const interval = setInterval(async () => {
      const count = await getPendingCount()
      if (count > 0) void doSync()
    }, 60000)
    return () => clearInterval(interval)
  }, [doSync])

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      if (event.ctrlKey && event.key === 'Enter') {
        document.getElementById('shift-submit-btn')?.click()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  useEffect(() => {
    const handleOnline = () => void doSync()
    window.addEventListener('online', handleOnline)
    return () => window.removeEventListener('online', handleOnline)
  }, [doSync])

  const loadDailyReport = useCallback(async (date: string) => {
    if (!kaspiDailySplitEnabled) return
    setDailyLoading(true)
    setDailyError(null)
    try {
      const data = await api.getPointDailyKaspiReport(config, date, session.company.id)
      setDailyReport(data)
    } catch (err: any) {
      setDailyReport(null)
      setDailyError(err?.message || `Не удалось загрузить суточный ${cashLabels.providerName} отчёт`)
    } finally {
      setDailyLoading(false)
    }
  }, [config, kaspiDailySplitEnabled]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (viewMode === 'daily' && kaspiDailySplitEnabled) void loadDailyReport(dailyDate)
  }, [dailyDate, kaspiDailySplitEnabled, loadDailyReport, viewMode])

  const loadSalesSummary = useCallback(async (date: string, shift: 'day' | 'night') => {
    if (!mergeInventorySalesIntoShift || !activeOpenShift) {
      setSalesSummary(null)
      return
    }
    setSalesSummaryLoading(true)
    try {
      const summary = await api.getPointInventorySaleShiftSummary(
        config,
        date,
        shift,
        session.company.id,
        activeOpenShift?.id || null,
      )
      setSalesSummary(summary)
    } catch {
      setSalesSummary(null)
    } finally {
      setSalesSummaryLoading(false)
    }
  }, [activeOpenShift?.id, config, mergeInventorySalesIntoShift, session.company.id]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (viewMode !== 'shift') return
    if (form.shift !== 'day' && form.shift !== 'night') return
    const timer = setTimeout(() => void loadSalesSummary(form.date, form.shift), 600)
    return () => clearTimeout(timer)
  }, [form.date, form.shift, loadSalesSummary, viewMode])

  const loadKnowledgeContext = useCallback(async () => {
    try {
      const data = await api.getPointKnowledge(config, session)
      setKnowledgeContext(data)
    } catch {
      setKnowledgeContext(null)
    }
  }, [config, session])

  useEffect(() => {
    void loadKnowledgeContext()
    const id = setInterval(() => void loadKnowledgeContext(), 60_000)
    return () => clearInterval(id)
  }, [loadKnowledgeContext])

  const vCash = parseMoney(form.cash)
  const vCoins = parseMoney(form.coins)
  const vKaspi = parseMoney(form.kaspi_pos)
  const vKaspiBeforeMidnight = parseMoney(form.kaspi_before_midnight)
  const autoSalesCash = mergeInventorySalesIntoShift ? salesSummary?.cash_amount || 0 : 0
  const autoSalesKaspiBeforeMidnight = mergeInventorySalesIntoShift ? salesSummary?.kaspi_before_midnight_amount || 0 : 0
  const autoSalesKaspiAfterMidnight = mergeInventorySalesIntoShift ? salesSummary?.kaspi_after_midnight_amount || 0 : 0
  const autoSalesKaspiTotal = mergeInventorySalesIntoShift ? salesSummary?.kaspi_amount || 0 : 0
  const vKaspiTotal = isNightKaspiSplit ? vKaspiBeforeMidnight + vKaspi : vKaspi
  const vDebts = parseMoney(form.debts)
  const vStart = parseMoney(form.start)
  const autoSalesSystemTotal = mergeInventorySalesIntoShift ? salesSummary?.total_amount || 0 : 0
  const vWipon = mergeInventorySalesIntoShift ? autoSalesSystemTotal : parseMoney(form.wipon)

  const fact = vCash + vCoins + vKaspiTotal + vDebts - vStart
  const itog = fact - vWipon

  function setField(key: keyof ShiftForm, value: string) {
    setForm((current) => ({ ...current, [key]: value }))
    setResult(null)
    setError(null)
  }

  function resetForm() {
    localStorage.removeItem(DRAFT_KEY)
    setForm(emptyForm())
    setResult(null)
    setError(null)
  }

  async function handleOpenShift() {
    setOpeningError(null)
    if (!session.operator.operator_id) {
      setOpeningError('Оператор не определён. Перезайдите в программу.')
      return
    }
    if (startCashInput.trim() === '') {
      setOpeningError('Введите старт кассы. Если мелочи нет, укажите 0.')
      return
    }

    const openingCash = parseMoney(startCashInput)
    if (!Number.isFinite(openingCash) || openingCash < 0) {
      setOpeningError('Старт кассы должен быть числом от 0 и выше.')
      return
    }

    setOpeningShift(true)
    try {
      const info = await api.openPointShift(
        config,
        session.operator.operator_id,
        form.shift,
        session.company.id,
        openingCash,
      )
      if (!info) throw new Error('Не удалось открыть смену')

      setActiveOpenShift(info)
      onOpenShiftChange?.(info)
      setField('start', String(openingCash))
      toastSuccess('Смена открыта')
      void loadKnowledgeContext()
    } catch (openError) {
      const payload = (openError as any)?.payload
      const errorCode = payload?.error || ''
      const shiftErrorMessages: Record<string, string> = {
        'point-shift-not-scheduled': payload?.detail || 'Вы не назначены на сегодня по графику. Обратитесь к руководителю.',
        'opening-cash-required': 'Укажите старт кассы перед открытием смены.',
        'point-shift-already-open': 'Смена уже открыта.',
        'point-shift-operator-not-onboarded': 'Оператор не привязан к этой точке.',
        'point-company-required': 'Не удалось определить точку. Перезайдите в программу.',
      }
      const message = shiftErrorMessages[errorCode]
        || (openError instanceof Error ? openError.message : 'Не удалось открыть смену')
      setOpeningError(message)
      toastError(message)
    } finally {
      setOpeningShift(false)
    }
  }

  async function sendOne(formToSend: ShiftForm): Promise<'success' | 'queued'> {
    const ref = localRef()
    try {
      await api.sendShiftReport(config, formToSend, ref, session.company.id, activeOpenShift?.id || null)
      return 'success'
    } catch (sendError) {
      const status = (sendError as Error & { status?: number }).status
      if (status && status < 500) throw sendError
      await queueShiftReport({ ...formToSend, local_ref: ref }, session.company.id)
      return 'queued'
    }
  }

  function buildCloseShiftPayload(fullForm: ShiftForm) {
    return {
      shift_id: activeOpenShift?.id || null,
      closing_cash: vCash + vCoins,
      closing_kaspi: isNightKaspiSplit
        ? parseMoney(fullForm.kaspi_before_midnight) + parseMoney(fullForm.kaspi_pos)
        : parseMoney(fullForm.kaspi_pos),
      kaspi_before_midnight: isNightKaspiSplit ? parseMoney(fullForm.kaspi_before_midnight) : 0,
      kaspi_after_midnight: isNightKaspiSplit ? parseMoney(fullForm.kaspi_pos) : 0,
      closed_by: session.operator.operator_id || null,
      closing_notes: form.comment || null,
    }
  }

  async function closeShiftAfterReport(
    payload: ReturnType<typeof buildCloseShiftPayload>,
    reportResult: 'success' | 'queued',
  ): Promise<'success' | 'queued'> {
    if (reportResult === 'queued') {
      await queueClosePointShift(payload, session.company.id)
      return 'queued'
    }

    try {
      const closingShiftId = activeOpenShift?.id || null
      await api.closePointShift(config, payload, session.company.id)
      if (closingShiftId) {
        try {
          const rep = await api.getPointShiftReport(config, closingShiftId, session.company.id)
          if (rep) setShiftReport(rep)
        } catch { /* отчёт не критичен для закрытия */ }
      }
      return 'success'
    } catch (closeError) {
      const status = (closeError as Error & { status?: number }).status
      if (status && status < 500) throw closeError
      await queueClosePointShift(payload, session.company.id)
      return 'queued'
    }
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    setResult(null)

    if (!activeOpenShift) {
      setError('Сначала откройте смену и зафиксируйте старт кассы.')
      return
    }

    if (dueBlockingChecklists.length > 0) {
      setError(
        `Перед закрытием смены завершите обязательные чек-листы: ${dueBlockingChecklists
          .map((template) => template.title)
          .join(', ')}`,
      )
      onOpenChecklists?.()
      return
    }

    if (fact <= 0) {
      setError('Введите сумму выручки')
      return
    }

    if (!session.operator.operator_id) {
      setError('Выберите оператора')
      return
    }

    if (form.start.trim() === '') {
      setError('Старт кассы должен быть заполнен из открытия смены.')
      return
    }

    if (isNightKaspiSplit && (form.kaspi_before_midnight.trim() === '' || form.kaspi_pos.trim() === '')) {
      setError(`Для ночной смены заполните ${cashLabels.providerName} до 00:00 и после 00:00`)
      return
    }

    setConfirmDialog(true)
  }

  async function handleConfirm() {
    setConfirmDialog(false)
    setSubmitting(true)
    try {
      const fullForm: ShiftForm = {
        ...form,
        operator_id: session.operator.operator_id,
        cash: String(vCash),
        kaspi_pos: String(vKaspi),
        kaspi_before_midnight: isNightKaspiSplit ? String(vKaspiBeforeMidnight) : '',
        wipon: String(vWipon),
      }
      const sendResult = await sendOne(fullForm)
      const closeResult = await closeShiftAfterReport(buildCloseShiftPayload(fullForm), sendResult)
      if (sendResult === 'queued' || closeResult === 'queued') {
        setPendingCount(await getPendingCount())
        setResult('queued')
      }
      clearParkedCarts({ companyId: session.company.id, date: form.date, shift: form.shift })
      resetForm()
      onLogout()
    } catch (confirmError) {
      const message = confirmError instanceof Error ? confirmError.message : 'Не удалось отправить отчёт'
      setError(message)
      toastError(message)
    } finally {
      setSubmitting(false)
    }
  }

  function fillFromSales() {
    const coins = parseMoney(form.coins)
    const cashFromSales = Math.max(0, autoSalesCash - coins)
    setField('cash', cashFromSales > 0 ? String(Math.round(cashFromSales)) : '')
    if (isNightKaspiSplit) {
      setField('kaspi_before_midnight', autoSalesKaspiBeforeMidnight > 0 ? String(Math.round(autoSalesKaspiBeforeMidnight)) : '')
      setField('kaspi_pos', autoSalesKaspiAfterMidnight > 0 ? String(Math.round(autoSalesKaspiAfterMidnight)) : '')
    } else {
      setField('kaspi_pos', autoSalesKaspiTotal > 0 ? String(Math.round(autoSalesKaspiTotal)) : '')
    }
    toastSuccess('Заполнено из продаж' + (coins > 0 ? ` (мелочь ${formatMoney(coins)} вычтена)` : ''))
  }

  const operatorName = session.operator.full_name || session.operator.name || session.operator.username
  const shiftLabel = form.shift === 'day' ? 'Дневная смена' : 'Ночная смена'
  const shiftOptions: Array<{ id: 'day' | 'night'; label: string; icon: ReactNode }> = [
    { id: 'day', label: 'Дневная', icon: <Sun className="h-4 w-4" /> },
    { id: 'night', label: 'Ночная', icon: <Moon className="h-4 w-4" /> },
  ]

  const nav: V2Nav = {
    sale: onSwitchToSale,
    return: onSwitchToReturn,
    history: onOpenSalesHistory,
    scanner: hasScanner ? onSwitchToScanner : undefined,
    request: onSwitchToRequest,
    arena: onSwitchToArena,
    cabinet: onOpenCabinet,
  }

  const openedAt = activeOpenShift?.opened_at
    ? new Date(activeOpenShift.opened_at).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
    : null

  const salesLoading = salesSummaryLoading

  return (
    <V2Shell
      active="shift"
      nav={nav}
      companyName={session.company.name || 'Точка'}
      operatorName={operatorName}
      shiftLabel={shiftLabel}
      onLogout={onLogout}
    >
      <div className="relative h-full min-h-0">
        {!activeOpenShift ? (
          /* ── Открытие смены ───────────────────────────────────────── */
          <div className="grid h-full place-items-center p-4">
            <div className="surface w-full max-w-[460px] rounded-[28px] p-6">
              <h1 className="text-[24px] font-bold">Открытие смены</h1>
              <p className="muted mt-1 text-[14px]">
                Укажите старт кассы перед продажами и закрытием отчёта. Если мелочи нет, введите 0.
              </p>
              <div className="mt-5 space-y-4">
                <div>
                  <span className="muted text-[13px]">Смена</span>
                  <div className="mt-1">
                    <Segmented value={form.shift as 'day' | 'night'} onChange={(v) => setField('shift', v)} options={shiftOptions} disabled={openingShift} />
                  </div>
                </div>
                <MoneyField label="Старт кассы" value={startCashInput} onChange={setStartCashInput} disabled={openingShift} autoFocus />
                {openingError ? (
                  <Notice tone="red" icon={<AlertTriangle className="h-5 w-5" />}>{openingError}</Notice>
                ) : null}
                <div className="flex gap-3">
                  <button type="button" onClick={onLogout} disabled={openingShift} className="press fill h-14 flex-1 rounded-[16px] text-[16px] font-semibold">
                    Выйти
                  </button>
                  <button type="button" onClick={handleOpenShift} disabled={openingShift} className="press btn-green h-14 flex-1 rounded-[16px] text-[16px] font-semibold">
                    {openingShift ? 'Открываю…' : 'Открыть смену'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        ) : (
          <div className="grid h-full min-h-0 grid-cols-[1fr_400px] gap-4 p-4">
            {/* ── Левая колонка ───────────────────────────────────────── */}
            <main className="flex min-h-0 flex-col gap-3 overflow-y-auto pr-1">
              <div className="flex flex-wrap items-center gap-3">
                {kaspiDailySplitEnabled ? (
                  <div className="w-[380px]">
                    <Segmented
                      value={viewMode}
                      onChange={setViewMode}
                      options={[
                        { id: 'shift', label: 'Смена', icon: <ReceiptText className="h-4 w-4" /> },
                        { id: 'daily', label: `Суточный ${cashLabels.providerName}`, icon: <CalendarDays className="h-4 w-4" /> },
                      ]}
                    />
                  </div>
                ) : null}
                <div className="muted flex flex-1 flex-wrap items-center justify-end gap-x-4 gap-y-1 text-[13px]">
                  {openedAt ? (
                    <span className="flex items-center gap-1.5"><Clock className="h-4 w-4" />Открыта с {openedAt}</span>
                  ) : null}
                  <span className="flex items-center gap-1.5"><CalendarDays className="h-4 w-4" />{form.date}</span>
                  <span>{workspaceSubtitle(bootstrap, session)}</span>
                  {isOffline ? <span className="t-red flex items-center gap-1.5"><WifiOff className="h-4 w-4" />Оффлайн</span> : null}
                </div>
              </div>

              {draftLost ? (
                <Notice
                  tone="red"
                  icon={<AlertTriangle className="h-5 w-5" />}
                  action={
                    <button type="button" onClick={() => setDraftLost(false)} className="press fill h-9 rounded-full px-4 text-[13px] font-semibold">
                      Понятно
                    </button>
                  }
                >
                  Черновик повреждён, данные сброшены
                </Notice>
              ) : null}

              {dueBlockingChecklists.length > 0 ? (
                <Notice
                  tone="orange"
                  icon={<AlertTriangle className="h-5 w-5" />}
                  action={
                    onOpenChecklists ? (
                      <button type="button" onClick={onOpenChecklists} className="press fill flex h-11 shrink-0 items-center gap-2 rounded-full px-4 text-[14px] font-semibold">
                        <ClipboardList className="h-4 w-4" />
                        Открыть чек-листы
                      </button>
                    ) : undefined
                  }
                >
                  <div className="font-semibold">Есть обязательные чек-листы</div>
                  <div className="muted text-[13px]">
                    Перед закрытием смены нужно пройти: {dueBlockingChecklists.map((t) => t.title).join(', ')}.
                  </div>
                </Notice>
              ) : null}

              {viewMode === 'daily' && kaspiDailySplitEnabled ? (
                <>
                  <Card
                    title={`${cashLabels.providerName} за календарные сутки`}
                    hint="Сумма собирается из дневной смены выбранной даты, ночной смены этой даты до 00:00 и хвоста прошлой ночной смены после 00:00."
                  >
                    <div className="flex items-end gap-3">
                      <label className="block">
                        <span className="muted text-[13px]">Дата суток</span>
                        <input
                          type="date"
                          value={dailyDate}
                          onChange={(e) => setDailyDate(e.target.value)}
                          className="surface-2 mt-1 block h-14 rounded-[14px] px-4 text-[17px] font-semibold outline-none"
                        />
                      </label>
                      <button
                        type="button"
                        onClick={() => void loadDailyReport(dailyDate)}
                        disabled={dailyLoading}
                        className="press fill flex h-14 items-center gap-2 rounded-[14px] px-5 text-[15px] font-semibold"
                      >
                        <RefreshCw className={`h-4 w-4 ${dailyLoading ? 'animate-spin' : ''}`} />
                        Обновить
                      </button>
                    </div>
                  </Card>

                  <Card title={`Разбивка суточного ${cashLabels.providerName}`}>
                    {dailyError ? <Notice tone="red" icon={<AlertTriangle className="h-5 w-5" />}>{dailyError}</Notice> : null}
                    {!dailyError && dailyLoading ? <div className="muted py-8 text-center text-[14px]">Загружаю…</div> : null}
                    {!dailyError && !dailyLoading && dailyReport ? (
                      <div className="space-y-3">
                        <div className="grid grid-cols-3 gap-3">
                          {dailyReport.parts.map((part) => (
                            <div key={part.key} className="surface-2 rounded-[16px] p-4">
                              <div className="muted text-[13px]">{part.label}</div>
                              <div className="mt-1 text-[22px] font-bold">{money(part.amount)}</div>
                              <div className="muted mt-0.5 text-[13px]">{part.rowCount > 0 ? `Смен: ${part.rowCount}` : 'Нет записей'}</div>
                            </div>
                          ))}
                        </div>
                        {dailyReport.warning ? (
                          <Notice tone="orange" icon={<AlertTriangle className="h-5 w-5" />}>{dailyReport.warning}</Notice>
                        ) : null}
                      </div>
                    ) : null}
                  </Card>

                  <Card title="Формула суток">
                    <div className="ink-2 space-y-1 text-[14px]">
                      <div>Дневная смена даты</div>
                      <div>+ Ночная смена даты до 00:00</div>
                      <div>+ Ночная смена предыдущей даты после 00:00</div>
                    </div>
                    <p className="muted mt-3 text-[12px]">
                      kaspi_amount хранит полный Kaspi по смене, а kaspi_before_midnight нужен только для точной суточной сверки.
                    </p>
                  </Card>
                </>
              ) : (
                <form id="shift-report-form" onSubmit={handleSubmit} className="flex flex-col gap-3">
                  <Card title="Отчёт по смене" hint="Быстрый ввод выручки с живым расчётом факта. Черновик сохраняется автоматически.">
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <span className="muted text-[13px]">Дата</span>
                        <input
                          type="date"
                          value={form.date}
                          onChange={(e) => setField('date', e.target.value)}
                          disabled={submitting}
                          className="surface-2 mt-1 block h-12 w-full rounded-[14px] px-4 text-[16px] font-semibold outline-none"
                        />
                      </div>
                      <div>
                        <span className="muted text-[13px]">Смена</span>
                        <div className="mt-1">
                          <Segmented value={form.shift as 'day' | 'night'} onChange={(v) => setField('shift', v)} options={shiftOptions} disabled={submitting} />
                        </div>
                      </div>
                    </div>
                    <div className="mt-3 grid grid-cols-3 gap-3">
                      <Stat label="Точка" value={session.company.name} />
                      <Stat label="Оператор" value={operatorName} />
                      <Stat
                        label="Режим"
                        value={isNightKaspiSplit ? `${cashLabels.providerName}: до и после 00:00` : 'Стандартный'}
                      />
                    </div>
                  </Card>

                  {mergeInventorySalesIntoShift ? (
                    <Card
                      title="Товарный контур этой смены"
                      hint="Продажи и возвраты автоматически формируют поле «Система». Наличные, мелочь и безнал введите по факту кассы."
                    >
                      <div className="grid grid-cols-5 gap-3">
                        <Stat label="Продаж" value={salesLoading ? '…' : String(salesSummary?.sale_count || 0)} tone="green" />
                        <Stat label="Возвратов" value={salesLoading ? '…' : String(salesSummary?.return_count || 0)} />
                        <Stat label="POS нал" value={salesLoading ? '…' : money(autoSalesCash)} />
                        <Stat
                          label={isNightKaspiSplit ? `${cashLabels.pos} до / после 00:00` : cashLabels.pos}
                          value={
                            salesLoading
                              ? '…'
                              : isNightKaspiSplit
                                ? `${money(autoSalesKaspiBeforeMidnight)} / ${money(autoSalesKaspiAfterMidnight)}`
                                : money(autoSalesKaspiTotal)
                          }
                          tone="blue"
                        />
                        <Stat label="Система" value={salesLoading ? '…' : money(salesSummary?.total_amount || 0)} />
                      </div>
                      {autoSalesCash > 0 || autoSalesKaspiTotal > 0 ? (
                        <div className="surface-2 mt-3 flex items-center gap-3 rounded-[16px] p-3">
                          <div className="min-w-0 flex-1 text-[14px]">
                            <div className="font-semibold">По продажам этой смены</div>
                            <div className="muted">нал {money(autoSalesCash)} · {kaspiLabel} {money(autoSalesKaspiTotal)}</div>
                            <div className="muted mt-0.5 text-[12px]">
                              Сначала введите мелочь — она вычтется из наличных, чтобы в отчёт ушли только купюры.
                            </div>
                          </div>
                          <button
                            type="button"
                            disabled={submitting}
                            onClick={fillFromSales}
                            className="press btn-blue h-12 shrink-0 rounded-[14px] px-5 text-[15px] font-semibold"
                          >
                            Заполнить
                          </button>
                        </div>
                      ) : null}
                    </Card>
                  ) : null}

                  <Card title="Приход" hint="Всё, что формирует факт по кассе на этой смене.">
                    <div className="grid grid-cols-2 gap-3">
                      <MoneyField label="Наличные" value={form.cash} onChange={(v) => setField('cash', v)} disabled={submitting} />
                      <MoneyField label="Мелочь" value={form.coins} onChange={(v) => setField('coins', v)} disabled={submitting} />
                      {isNightKaspiSplit ? (
                        <>
                          <MoneyField
                            label={`${kaspiLabel} до 00:00`}
                            value={form.kaspi_before_midnight}
                            onChange={(v) => setField('kaspi_before_midnight', v)}
                            disabled={submitting}
                          />
                          <MoneyField
                            label={`${kaspiLabel} после 00:00`}
                            value={form.kaspi_pos}
                            onChange={(v) => setField('kaspi_pos', v)}
                            disabled={submitting}
                            note={`в сумме это общий ${cashLabels.providerName} смены`}
                          />
                        </>
                      ) : (
                        <MoneyField label={kaspiLabel} value={form.kaspi_pos} onChange={(v) => setField('kaspi_pos', v)} disabled={submitting} />
                      )}
                      <MoneyField label="Тех. компенс." value={form.debts} onChange={(v) => setField('debts', v)} disabled={submitting} />
                    </div>
                    {isNightKaspiSplit ? (
                      <div className="muted mt-3 flex items-start gap-2 text-[13px]">
                        <SplitSquareVertical className="mt-0.5 h-4 w-4 shrink-0" />
                        Для ночной смены {cashLabels.providerName} делится на две части: до 00:00 и после 00:00. В итоге смены будет учтена общая сумма.
                      </div>
                    ) : null}
                  </Card>

                  <Card title="Вычеты" hint="Эти значения уменьшают итог по смене.">
                    <div className="grid grid-cols-2 gap-3">
                      <MoneyField
                        label="Старт кассы"
                        value={form.start}
                        onChange={(v) => setField('start', v)}
                        disabled={submitting || !!activeOpenShift}
                        note={activeOpenShift ? 'зафиксировано при открытии смены' : undefined}
                      />
                      <MoneyField
                        label={wiponLabel}
                        value={mergeInventorySalesIntoShift ? String(autoSalesSystemTotal) : form.wipon}
                        onChange={(v) => setField('wipon', v)}
                        disabled={submitting || mergeInventorySalesIntoShift}
                        note={mergeInventorySalesIntoShift ? 'собирается из вкладки «Продажа»' : undefined}
                      />
                    </div>
                  </Card>

                  <Card title="Комментарий">
                    <input
                      value={form.comment}
                      onChange={(e) => setField('comment', e.target.value)}
                      placeholder="Например: позднее закрытие, инкассация, корректировка"
                      disabled={submitting}
                      className="surface-2 placeholder-muted h-12 w-full rounded-[14px] px-4 text-[16px] outline-none"
                    />
                  </Card>
                </form>
              )}
            </main>

            {/* ── Правая колонка ─────────────────────────────────────── */}
            <aside className="glass flex min-h-0 flex-col gap-3 overflow-y-auto rounded-[28px] p-4">
              {viewMode === 'daily' && kaspiDailySplitEnabled ? (
                <div className="surface rounded-[22px] p-5">
                  <div className="muted text-[13px]">{cashLabels.providerName} за сутки</div>
                  <div className="mt-1 text-[36px] font-bold leading-tight">{dailyReport ? money(dailyReport.total) : money(0)}</div>
                  <div className="muted mt-0.5 text-[13px]">{dailyDate}</div>
                  <div className={`mt-3 text-[14px] font-semibold ${dailyReport?.isPrecise === false ? 't-red' : 't-green'}`}>
                    {dailyReport?.isPrecise === false ? 'Есть старые неточные смены' : 'Точный расчёт'}
                  </div>
                </div>
              ) : (
                <>
                  <div className="surface rounded-[22px] p-5">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <div className="muted text-[13px]">Итог смены</div>
                        <div className={`mt-1 text-[36px] font-bold leading-tight ${itog < 0 ? 't-red' : 'ink'}`}>
                          {itog > 0 ? '+' : ''}{money(itog)}
                        </div>
                        <div className="muted mt-0.5 text-[13px]">ФАКТ минус {wiponLabel}</div>
                      </div>
                      <span className={`fill rounded-full px-3 py-1 text-[13px] font-semibold ${itog < 0 ? 't-red' : 't-green'}`}>
                        {itog < 0 ? 'Недостача' : 'Готово'}
                      </span>
                    </div>
                    <div className="mt-4 grid grid-cols-2 gap-3">
                      <Stat label="ФАКТ" value={money(fact)} />
                      <Stat label={wiponLabel} value={money(vWipon)} />
                    </div>

                    <button
                      form="shift-report-form"
                      id="shift-submit-btn"
                      type="submit"
                      disabled={submitting}
                      className="press btn-green mt-4 flex h-14 w-full items-center justify-center gap-2 rounded-[16px] text-[17px] font-semibold"
                    >
                      <Send className="h-5 w-5" />
                      {submitting ? 'Закрываю…' : 'Закрыть смену'}
                      {!submitting ? <span className="ml-1 text-[12px] opacity-70">Ctrl+↵</span> : null}
                    </button>

                    <div className="mt-3 space-y-2">
                      {error ? <Notice tone="red" icon={<AlertTriangle className="h-5 w-5" />}>{error}</Notice> : null}
                      {result === 'success' ? <Notice tone="green" icon={<CheckCircle2 className="h-5 w-5" />}>Отчёт отправлен успешно</Notice> : null}
                      {result === 'queued' ? (
                        <Notice tone="orange" icon={<Clock className="h-5 w-5" />}>Нет сети — отчёт сохранён в локальную очередь</Notice>
                      ) : null}
                    </div>
                  </div>

                  <div className="surface rounded-[22px] p-5">
                    <h3 className="mb-2 text-[16px] font-bold">Формула расчёта</h3>
                    <Row label="Наличные" value={vCash} />
                    <Row label="Мелочь" value={vCoins} />
                    <Row label={kaspiLabel} value={vKaspiTotal} />
                    {isNightKaspiSplit ? <Row label="до 00:00" value={vKaspiBeforeMidnight} dim /> : null}
                    {isNightKaspiSplit ? <Row label="после 00:00" value={vKaspi} dim /> : null}
                    <Row label="Тех. компенс." value={vDebts} />
                    <Row label="− Старт кассы" value={-vStart} />
                    <div className="line my-1.5 border-t" />
                    <Row label="ФАКТ" value={fact} />
                    <Row label={`− ${wiponLabel}`} value={-vWipon} />
                    <div className="line my-1.5 border-t" />
                    <Row label="ИТОГ" value={itog} strong negativeRed />
                  </div>
                </>
              )}

              <div className="surface rounded-[22px] p-4">
                <h3 className="mb-2 text-[16px] font-bold">Быстрые действия</h3>
                <div className="grid grid-cols-2 gap-2">
                  <ActionButton
                    icon={<RefreshCw className={`h-4 w-4 shrink-0 ${syncing ? 'animate-spin' : ''}`} />}
                    label="Синхронизировать"
                    onClick={doSync}
                    disabled={syncing}
                  />
                  <ActionButton
                    icon={<Clock className="h-4 w-4 shrink-0" />}
                    label={pendingCount > 0 ? `Очередь: ${pendingCount}` : 'Очередь пуста'}
                    onClick={openQueueScreen}
                  />
                  {onSwitchToSale ? <ActionButton icon={<ShoppingBag className="h-4 w-4 shrink-0" />} label="Продажа" onClick={onSwitchToSale} /> : null}
                  {onSwitchToReturn ? <ActionButton icon={<RotateCcw className="h-4 w-4 shrink-0" />} label="Возврат товара" onClick={onSwitchToReturn} /> : null}
                  {hasScanner ? <ActionButton icon={<CreditCard className="h-4 w-4 shrink-0" />} label="Сканер долгов" onClick={onSwitchToScanner} /> : null}
                  {onSwitchToRequest ? <ActionButton icon={<ClipboardList className="h-4 w-4 shrink-0" />} label="Заявка на склад" onClick={onSwitchToRequest} /> : null}
                  {onOpenChecklists ? <ActionButton icon={<ClipboardList className="h-4 w-4 shrink-0" />} label="Чек-листы смены" onClick={onOpenChecklists} /> : null}
                  {onOpenCabinet ? <ActionButton icon={<UserRound className="h-4 w-4 shrink-0" />} label="Мой кабинет" onClick={onOpenCabinet} /> : null}
                </div>
              </div>
            </aside>
          </div>
        )}

        {/* ── Подтверждение закрытия ─────────────────────────────────── */}
        {confirmDialog ? (
          <Sheet title={itog < 0 ? 'Недостача — подтвердить?' : 'Подтвердить отчёт'} onClose={() => setConfirmDialog(false)}>
            <div className="surface-2 space-y-1 rounded-[16px] p-4 text-[15px]">
              <p className="muted mb-2 text-[13px]">
                {session.operator.short_name || session.operator.name || session.operator.username} · {form.date} · {form.shift === 'day' ? 'Дневная' : 'Ночная'}
              </p>
              {vCash > 0 ? <ConfirmLine label="Наличные" value={money(vCash)} /> : null}
              {vCoins > 0 ? <ConfirmLine label="Мелочь" value={money(vCoins)} /> : null}
              {vKaspiTotal > 0 ? <ConfirmLine label={kaspiLabel} value={money(vKaspiTotal)} /> : null}
              {isNightKaspiSplit && vKaspiBeforeMidnight > 0 ? <ConfirmLine label="до 00:00" value={money(vKaspiBeforeMidnight)} /> : null}
              {isNightKaspiSplit && vKaspi > 0 ? <ConfirmLine label="после 00:00" value={money(vKaspi)} /> : null}
              {vDebts > 0 ? <ConfirmLine label="Тех. компенс." value={money(vDebts)} /> : null}
              {vStart > 0 ? <ConfirmLine label="Старт" value={`−${money(vStart)}`} /> : null}
              {vWipon > 0 ? <ConfirmLine label={wiponLabel} value={`−${money(vWipon)}`} /> : null}
              <div className="line flex justify-between border-t pt-2 text-[17px] font-bold">
                <span>ИТОГ</span>
                <span className={itog < 0 ? 't-red' : 't-green'}>{itog > 0 ? '+' : ''}{money(itog)}</span>
              </div>
            </div>
            <div className="mt-4 flex gap-3">
              <button type="button" onClick={() => setConfirmDialog(false)} className="press fill h-14 flex-1 rounded-[16px] text-[16px] font-semibold">
                Исправить
              </button>
              <button
                type="button"
                onClick={handleConfirm}
                className="press h-14 flex-1 rounded-[16px] text-[16px] font-semibold text-white"
                style={{ background: itog < 0 ? 'var(--red)' : 'var(--green)' }}
              >
                Отправить
              </button>
            </div>
          </Sheet>
        ) : null}

        {/* ── Чековый сменный отчёт (Z-отчёт): превью в iframe + печать ── */}
        {shiftReport ? (
          <Sheet title={`Сменный отчёт · смена №${shiftReport.shiftNumber}`} onClose={() => setShiftReport(null)} wide>
            <div className="surface-2 max-h-[56vh] overflow-auto rounded-[16px] p-3">
              <div className="mx-auto w-full max-w-[360px] overflow-hidden rounded-[10px] bg-white">
                <iframe
                  ref={shiftReportIframeRef}
                  srcDoc={buildShiftReportHtml(shiftReport)}
                  title="Сменный отчёт"
                  className="h-[50vh] w-full"
                />
              </div>
            </div>
            <div className="mt-4 flex gap-3">
              <button type="button" onClick={() => setShiftReport(null)} className="press fill h-14 flex-1 rounded-[16px] text-[16px] font-semibold">
                Закрыть
              </button>
              <button
                type="button"
                onClick={() => printReceiptFromIframe(shiftReportIframeRef.current)}
                className="press btn-green flex h-14 flex-1 items-center justify-center gap-2 rounded-[16px] text-[16px] font-semibold"
              >
                <Printer className="h-5 w-5" />
                Печать
              </button>
            </div>
          </Sheet>
        ) : null}
      </div>
    </V2Shell>
  )
}
