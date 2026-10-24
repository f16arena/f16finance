import { useEffect, useMemo, useState } from 'react'
import {
  AlertTriangle,
  CalendarDays,
  CheckCircle2,
  CheckSquare,
  CreditCard,
  FileText,
  MessageSquare,
  RefreshCw,
  Search,
  ShieldCheck,
  UserCircle2,
} from 'lucide-react'

import * as api from '@/lib/api'
import { formatDate, formatMoney, todayISO } from '@/lib/utils'
import { formatShiftLabel } from '@/lib/receipt-html'
import { resolveRuntimeShift } from '@/lib/shift-runtime'
import type {
  AppConfig,
  BootstrapData,
  DebtItem,
  OperatorSession,
  OperatorTask,
  OperatorTaskComment,
  PointKnowledgeArticle,
  PointKnowledgeContext,
  WorkReturnTo,
} from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'

type Props = {
  config: AppConfig
  bootstrap: BootstrapData
  session: OperatorSession
  returnTo: WorkReturnTo | 'checklists'
  onBackToWork: () => void
  onLogout: () => void
  onSwitchToShift?: () => void
  onSwitchToSale?: () => void
  onSwitchToReturn?: () => void
  onSwitchToHistory?: () => void
  onSwitchToScanner?: () => void
  onSwitchToRequest?: () => void
  onSwitchToArena?: () => void
}

type CabinetTab = 'knowledge' | 'shifts' | 'tasks' | 'debts' | 'profile'
type TaskResponse = 'accept' | 'need_info' | 'blocked' | 'already_done' | 'complete'
type SectionKey = 'shifts' | 'debts' | 'tasks' | 'knowledge'

type ShiftRow = {
  id: string
  date: string
  shift: string
  company_name: string | null
  cash: number
  kaspi: number
  kaspi_online: number
  total: number
}

const TABS: { id: CabinetTab; label: string; icon: typeof CalendarDays }[] = [
  { id: 'knowledge', label: 'Правила и FAQ', icon: FileText },
  { id: 'shifts', label: 'Мои смены', icon: CalendarDays },
  { id: 'tasks', label: 'Мои задачи', icon: CheckSquare },
  { id: 'debts', label: 'Мои долги', icon: CreditCard },
  { id: 'profile', label: 'Профиль', icon: UserCircle2 },
]

const SEVERITY_LABELS: Record<string, string> = { info: 'Инфо', normal: 'Обычно', warning: 'Важно', critical: 'Критично' }

function taskStatusLabel(status: string) {
  switch (status) {
    case 'done':
      return 'Готово'
    case 'in_progress':
      return 'В работе'
    case 'review':
      return 'На проверке'
    case 'todo':
      return 'К выполнению'
    case 'archived':
      return 'Архив'
    default:
      return 'Бэклог'
  }
}

function taskPriorityLabel(priority: string) {
  switch (priority) {
    case 'critical':
      return 'Критично'
    case 'high':
      return 'Высокий'
    case 'medium':
      return 'Средний'
    default:
      return 'Низкий'
  }
}

function money(value: number) {
  return formatMoney(value)
}

/** Цветная «таблетка» с оттенком от переменной темы. */
function Chip({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <span className="inline-flex shrink-0 rounded-full px-3 py-1 text-[13px] font-semibold" style={{ color, background: `color-mix(in srgb, ${color} 15%, transparent)` }}>
      {children}
    </span>
  )
}

function Notice({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <div className="rounded-[14px] px-4 py-3 text-[14px]" style={{ color, background: `color-mix(in srgb, ${color} 12%, transparent)` }}>
      {children}
    </div>
  )
}

function SectionError({ message }: { message?: string }) {
  if (!message) return null
  return <Notice color="var(--orange)">{message}</Notice>
}

function normalizeShiftRows(rows: unknown[], fallbackCompanyName: string): ShiftRow[] {
  return (rows || []).map((row: any) => {
    const cash = Number(row.cash_amount ?? row.cash ?? 0)
    const kaspi = Number(row.kaspi_amount ?? row.kaspi_pos ?? row.kaspi ?? 0)
    const kaspiOnline = Number(row.online_amount ?? row.kaspi_online ?? 0)
    return {
      id: String(row.id),
      date: String(row.date),
      shift: String(row.shift || 'day'),
      company_name: row.company_name || fallbackCompanyName,
      cash,
      kaspi,
      kaspi_online: kaspiOnline,
      total: Number(row.total ?? cash + kaspi + kaspiOnline),
    }
  })
}

export default function CabinetScreenV2({
  config,
  bootstrap,
  session,
  returnTo,
  onBackToWork,
  onLogout,
  onSwitchToShift,
  onSwitchToSale,
  onSwitchToReturn,
  onSwitchToHistory,
  onSwitchToScanner,
  onSwitchToRequest,
  onSwitchToArena,
}: Props) {
  const CACHE_KEY = `cabinet_cache_${session.operator.operator_id}`

  const [activeTab, setActiveTab] = useState<CabinetTab>('knowledge')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [isOffline, setIsOffline] = useState(false)
  const [sectionErrors, setSectionErrors] = useState<Partial<Record<SectionKey, string>>>({})
  const [shifts, setShifts] = useState<ShiftRow[]>([])
  const [debts, setDebts] = useState<DebtItem[]>([])
  const [tasks, setTasks] = useState<OperatorTask[]>([])
  const [taskComments, setTaskComments] = useState<OperatorTaskComment[]>([])
  const [taskNotes, setTaskNotes] = useState<Record<string, string>>({})
  const [taskActionLoading, setTaskActionLoading] = useState<string | null>(null)
  const [knowledge, setKnowledge] = useState<PointKnowledgeContext | null>(null)
  const [knowledgeQuery, setKnowledgeQuery] = useState('')
  const [confirmingArticleId, setConfirmingArticleId] = useState<string | null>(null)
  const [from, setFrom] = useState(() => {
    const d = new Date()
    d.setDate(d.getDate() - 30)
    return d.toISOString().slice(0, 10)
  })
  const [to, setTo] = useState(todayISO)

  async function load() {
    setLoading(true)
    setError(null)
    setSectionErrors({})

    const [cabinetResult, tasksResult, knowledgeResult] = await Promise.allSettled([
      api.getPointOperatorCabinet(config, session),
      api.getPointOperatorTasks(config, session),
      api.getPointKnowledge(config, session),
    ])

    const nextErrors: Partial<Record<SectionKey, string>> = {}

    if (cabinetResult.status === 'fulfilled') {
      setShifts(normalizeShiftRows(cabinetResult.value.shifts || [], session.company.name))
      setDebts(cabinetResult.value.debts || [])
      setIsOffline(false)
    } else {
      try {
        const cached = localStorage.getItem(CACHE_KEY)
        if (cached) {
          const { shifts: cs, debts: cd } = JSON.parse(cached)
          setShifts(normalizeShiftRows(cs || [], session.company.name))
          setDebts(cd || [])
          setIsOffline(true)
        } else {
          setShifts([])
          setDebts([])
        }
      } catch {
        setShifts([])
        setDebts([])
      }
      const message = cabinetResult.reason instanceof Error ? cabinetResult.reason.message : 'Не удалось загрузить данные кабинета'
      nextErrors.shifts = message
      nextErrors.debts = message
    }

    if (tasksResult.status === 'fulfilled') {
      setTasks(tasksResult.value.tasks || [])
      setTaskComments(tasksResult.value.comments || [])
    } else {
      try {
        const cached = localStorage.getItem(CACHE_KEY)
        if (cached) {
          const parsed = JSON.parse(cached)
          setTasks(parsed.tasks || [])
          setTaskComments(parsed.taskComments || [])
        } else {
          setTasks([])
          setTaskComments([])
        }
      } catch {
        setTasks([])
        setTaskComments([])
      }
      nextErrors.tasks = tasksResult.reason instanceof Error ? tasksResult.reason.message : 'Не удалось загрузить задачи'
    }

    if (knowledgeResult.status === 'fulfilled') {
      setKnowledge(knowledgeResult.value)
    } else {
      setKnowledge(null)
      nextErrors.knowledge = knowledgeResult.reason instanceof Error ? knowledgeResult.reason.message : 'Не удалось загрузить правила и FAQ'
    }

    if (cabinetResult.status === 'fulfilled' && tasksResult.status === 'fulfilled') {
      try {
        localStorage.setItem(
          CACHE_KEY,
          JSON.stringify({
            shifts: normalizeShiftRows(cabinetResult.value.shifts || [], session.company.name),
            debts: cabinetResult.value.debts || [],
            tasks: tasksResult.value.tasks || [],
            taskComments: tasksResult.value.comments || [],
            savedAt: Date.now(),
          }),
        )
      } catch {
        /* хранилище переполнено */
      }
    }

    setSectionErrors(nextErrors)
    if (Object.keys(nextErrors).length >= 4) {
      setError('Не удалось загрузить личный кабинет. Проверьте сеть и попробуйте обновить.')
    }
    setLoading(false)
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const filteredShifts = useMemo(
    () => shifts.filter((row) => row.date >= from && row.date <= to).sort((a, b) => b.date.localeCompare(a.date)),
    [from, shifts, to],
  )
  const filteredDebts = useMemo(
    () =>
      debts.filter((item) => {
        const debtDate = item.week_start || item.created_at.slice(0, 10)
        return debtDate >= from && debtDate <= to
      }),
    [debts, from, to],
  )

  const debtsByWeek = useMemo(() => {
    const map = new Map<string, DebtItem[]>()
    for (const item of filteredDebts) {
      const week = item.week_start || item.created_at.slice(0, 10)
      if (!map.has(week)) map.set(week, [])
      map.get(week)!.push(item)
    }
    return [...map.entries()].sort((a, b) => b[0].localeCompare(a[0]))
  }, [filteredDebts])

  const totalShiftRevenue = filteredShifts.reduce((sum, row) => sum + row.total, 0)
  const totalDebt = filteredDebts.filter((item) => item.status === 'active').reduce((sum, row) => sum + row.total_amount, 0)
  const activeTasks = tasks.filter((task) => !['done', 'archived'].includes(task.status)).length
  const taskCommentsByTask = useMemo(() => {
    const map = new Map<string, OperatorTaskComment[]>()
    for (const comment of taskComments) {
      const list = map.get(comment.task_id) || []
      list.push(comment)
      map.set(comment.task_id, list)
    }
    return map
  }, [taskComments])
  const pendingConfirmations = knowledge?.pending_confirmations || []
  const pendingConfirmationIds = useMemo(() => new Set(pendingConfirmations.map((article) => article.id)), [pendingConfirmations])
  const knowledgeArticles = useMemo(() => {
    const query = knowledgeQuery.trim().toLowerCase()
    const articles = knowledge?.articles || []
    if (!query) return articles
    return articles.filter((article) => {
      const haystack = [article.title, article.summary, article.content, article.category?.title, ...(article.tags || [])]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      return haystack.includes(query)
    })
  }, [knowledge?.articles, knowledgeQuery])
  const visibleKnowledgeArticles = useMemo(
    () => knowledgeArticles.filter((article) => !pendingConfirmationIds.has(article.id)),
    [knowledgeArticles, pendingConfirmationIds],
  )
  const profileName = session.operator.full_name || session.operator.name || session.operator.username

  // Фолбэк на onBackToWork — на случай, если App передал только returnTo (старый контракт).
  const goShift = onSwitchToShift ?? (returnTo === 'shift' ? onBackToWork : undefined)
  const goSale = onSwitchToSale ?? (returnTo === 'sale' ? onBackToWork : undefined)
  const goReturn = onSwitchToReturn ?? (returnTo === 'return' ? onBackToWork : undefined)
  const goScanner = onSwitchToScanner ?? (returnTo === 'scanner' ? onBackToWork : undefined)
  const nav: V2Nav = {
    shift: goShift,
    sale: goSale,
    return: goReturn,
    history: onSwitchToHistory,
    scanner: goScanner,
    request: onSwitchToRequest,
    arena: onSwitchToArena,
  }

  async function handleConfirmArticle(articleId: string) {
    setConfirmingArticleId(articleId)
    setError(null)
    try {
      await api.confirmPointKnowledgeArticle(config, session, articleId)
      await load()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Не удалось подтвердить ознакомление')
    } finally {
      setConfirmingArticleId(null)
    }
  }

  async function handleTaskResponse(taskId: string, response: TaskResponse) {
    setTaskActionLoading(`${taskId}:${response}`)
    setError(null)
    try {
      const result = await api.respondPointOperatorTask(config, session, taskId, response, taskNotes[taskId] || null)
      setTasks((current) =>
        current.map((task) =>
          task.id === taskId
            ? { ...task, status: result.status, completed_at: result.status === 'done' ? new Date().toISOString() : task.completed_at }
            : task,
        ),
      )
      setTaskNotes((current) => ({ ...current, [taskId]: '' }))
      await load()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Не удалось обновить задачу')
    } finally {
      setTaskActionLoading(null)
    }
  }

  async function handleTaskComment(taskId: string) {
    const content = String(taskNotes[taskId] || '').trim()
    if (!content) return
    setTaskActionLoading(`${taskId}:comment`)
    setError(null)
    try {
      await api.addPointOperatorTaskComment(config, session, taskId, content)
      setTaskNotes((current) => ({ ...current, [taskId]: '' }))
      await load()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Не удалось добавить комментарий')
    } finally {
      setTaskActionLoading(null)
    }
  }

  const dateInput = 'fill h-11 rounded-[12px] px-3 text-[14px] outline-none'

  return (
    <V2Shell
      active="cabinet"
      nav={nav}
      companyName={session.company.name}
      operatorName={profileName}
      shiftLabel={formatShiftLabel(resolveRuntimeShift().shift)}
      onLogout={onLogout}
    >
      <div className="grid h-full min-h-0 grid-cols-[clamp(190px,17vw,230px)_minmax(0,1fr)] gap-4 p-4">
        {/* ── Разделы ─────────────────────────────────────────────── */}
        <aside className="surface flex min-h-0 flex-col gap-1 overflow-y-auto rounded-[24px] p-3">
          <div className="px-3 pb-2 pt-1">
            <p className="text-[17px] font-bold">Личный кабинет</p>
            <p className="muted text-[13px]">{isOffline ? 'Данные из кеша' : profileName}</p>
          </div>
          {TABS.map((tab) => {
            const Icon = tab.icon
            const active = activeTab === tab.id
            return (
              <button
                key={tab.id}
                type="button"
                onClick={() => setActiveTab(tab.id)}
                className={`press flex h-12 items-center gap-3 rounded-[14px] px-3 text-left text-[15px] font-medium ${active ? 'pill-active' : 'ink-2'}`}
                style={active ? undefined : { background: 'transparent' }}
              >
                <Icon className="h-[18px] w-[18px] shrink-0" />
                <span className="flex-1">{tab.label}</span>
                {tab.id === 'knowledge' && pendingConfirmations.length > 0 ? (
                  <span className="badge rounded-full px-2 py-0.5 text-[12px] font-semibold">{pendingConfirmations.length}</span>
                ) : null}
              </button>
            )
          })}
          <div className="mt-auto flex flex-col gap-2 pt-3">
            {returnTo === 'checklists' ? (
              <button type="button" onClick={onBackToWork} className="press fill h-11 rounded-[14px] text-[14px] font-medium">
                К чек-листам
              </button>
            ) : null}
            <button type="button" onClick={() => void load()} disabled={loading} className="press fill flex h-11 items-center justify-center gap-2 rounded-[14px] text-[14px] font-medium">
              <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} /> Обновить
            </button>
          </div>
        </aside>

        {/* ── Содержимое ──────────────────────────────────────────── */}
        <main className="min-h-0 overflow-y-auto pr-1">
          <div className="space-y-4">
            <div className="grid grid-cols-3 gap-3">
              <div className="surface rounded-[20px] p-4">
                <div className="muted text-[13px]">Смен за период</div>
                <div className="mt-1 text-[26px] font-bold">{filteredShifts.length}</div>
                <div className="muted text-[13px]">{money(totalShiftRevenue)}</div>
              </div>
              <div className="surface rounded-[20px] p-4">
                <div className="muted text-[13px]">Активные задачи</div>
                <div className="mt-1 text-[26px] font-bold">{activeTasks}</div>
                <div className="muted text-[13px]">Всего задач: {tasks.length}</div>
              </div>
              <div className="surface rounded-[20px] p-4">
                <div className="muted text-[13px]">Активный долг</div>
                <div className="mt-1 text-[26px] font-bold">{money(totalDebt)}</div>
                <div className="muted text-[13px]">Позиции: {filteredDebts.length}</div>
              </div>
            </div>

            {pendingConfirmations.length > 0 ? (
              <Notice color="var(--orange)">
                <div className="flex items-center gap-2 font-semibold">
                  <AlertTriangle className="h-4 w-4" />
                  Есть материалы, которые нужно прочитать и подтвердить: {pendingConfirmations.length}
                </div>
                <div className="mt-1 text-[13px] opacity-80">Откройте вкладку «Правила и FAQ», прочитайте новые правила и нажмите «Ознакомлен».</div>
              </Notice>
            ) : null}

            {error ? <Notice color="var(--red)">{error}</Notice> : null}

            {activeTab !== 'profile' && activeTab !== 'knowledge' ? (
              <div className="flex flex-wrap items-end gap-3">
                <label className="flex flex-col gap-1">
                  <span className="muted text-[13px]">С</span>
                  <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className={dateInput} />
                </label>
                <label className="flex flex-col gap-1">
                  <span className="muted text-[13px]">По</span>
                  <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className={dateInput} />
                </label>
              </div>
            ) : null}

            {loading ? (
              <div className="flex h-40 items-center justify-center">
                <RefreshCw className="muted h-6 w-6 animate-spin" />
              </div>
            ) : null}

            {/* Правила и FAQ */}
            {!loading && activeTab === 'knowledge' ? (
              <section className="surface space-y-4 rounded-[24px] p-5">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h2 className="flex items-center gap-2 text-[18px] font-bold">
                      <ShieldCheck className="t-blue h-5 w-5" /> Правила, FAQ и подтверждения
                    </h2>
                    <p className="muted mt-1 text-[13px]">Здесь правила смены, ответы на частые проблемы, штрафы, бонусы и материалы для обучения.</p>
                  </div>
                  <Chip color={pendingConfirmations.length ? 'var(--red)' : 'var(--green)'}>
                    {pendingConfirmations.length ? `Нужно подтвердить: ${pendingConfirmations.length}` : 'Всё подтверждено'}
                  </Chip>
                </div>

                <SectionError message={sectionErrors.knowledge} />

                <div className="fill flex h-12 items-center gap-2 rounded-[14px] px-4">
                  <Search className="muted h-4 w-4 shrink-0" />
                  <input
                    value={knowledgeQuery}
                    onChange={(e) => setKnowledgeQuery(e.target.value)}
                    placeholder="Поиск: безналичный, штраф, закрытие смены, клиент, техника"
                    className="placeholder-muted w-full bg-transparent text-[15px] outline-none"
                  />
                </div>

                {pendingConfirmations.length > 0 ? (
                  <div className="space-y-3">
                    <div className="text-[15px] font-semibold">Обязательные материалы</div>
                    {pendingConfirmations.map((article) => (
                      <KnowledgeArticleCard
                        key={article.id}
                        article={article}
                        required
                        confirming={confirmingArticleId === article.id}
                        onConfirm={() => void handleConfirmArticle(article.id)}
                        onExplain={(question) => api.explainPointKnowledgeArticle(config, session, article.id, question)}
                      />
                    ))}
                  </div>
                ) : null}

                <div className="space-y-3">
                  {visibleKnowledgeArticles.map((article) => (
                    <KnowledgeArticleCard
                      key={article.id}
                      article={article}
                      required={pendingConfirmationIds.has(article.id)}
                      confirming={confirmingArticleId === article.id}
                      onExplain={(question) => api.explainPointKnowledgeArticle(config, session, article.id, question)}
                      onConfirm={article.requires_confirmation ? () => void handleConfirmArticle(article.id) : undefined}
                    />
                  ))}
                  {visibleKnowledgeArticles.length === 0 && pendingConfirmations.length === 0 ? (
                    <div className="surface-2 muted rounded-[16px] p-5 text-[14px]">Материалов пока нет или ничего не найдено по запросу.</div>
                  ) : null}
                </div>
              </section>
            ) : null}

            {/* Смены */}
            {!loading && activeTab === 'shifts' ? (
              <section className="space-y-3">
                <h2 className="text-[18px] font-bold">Мои смены</h2>
                <SectionError message={sectionErrors.shifts} />
                {filteredShifts.length === 0 ? (
                  <div className="muted text-[14px]">За выбранный период смен нет.</div>
                ) : (
                  filteredShifts.map((shift) => (
                    <div key={shift.id} className="surface flex items-center justify-between gap-3 rounded-[18px] p-4">
                      <div>
                        <div className="text-[15px] font-semibold">
                          {formatDate(shift.date)} · {shift.shift === 'day' ? 'День' : 'Ночь'}
                        </div>
                        <div className="muted text-[13px]">{shift.company_name || session.company.name}</div>
                      </div>
                      <div className="text-right">
                        <div className="text-[16px] font-bold">{money(shift.total)}</div>
                        <div className="muted text-[13px]">
                          Нал {money(shift.cash)} · Безналичный {money(shift.kaspi + shift.kaspi_online)}
                        </div>
                      </div>
                    </div>
                  ))
                )}
              </section>
            ) : null}

            {/* Задачи */}
            {!loading && activeTab === 'tasks' ? (
              <section className="space-y-3">
                <h2 className="text-[18px] font-bold">Мои задачи</h2>
                <SectionError message={sectionErrors.tasks} />
                {tasks.length === 0 ? (
                  <div className="muted text-[14px]">Сейчас задач нет.</div>
                ) : (
                  tasks.map((task) => {
                    const comments = taskCommentsByTask.get(task.id) || []
                    const isClosed = ['done', 'archived'].includes(task.status)
                    const busy = !!taskActionLoading
                    const actions: Array<{ response: TaskResponse; label: string; color: string; show: boolean }> = [
                      { response: 'accept', label: 'В работу', color: 'var(--green)', show: task.status !== 'in_progress' },
                      { response: 'need_info', label: 'Нужны детали', color: 'var(--orange)', show: true },
                      { response: 'blocked', label: 'Не могу', color: 'var(--red)', show: true },
                      { response: 'already_done', label: 'Уже сделано', color: 'var(--blue)', show: true },
                    ]
                    return (
                      <div key={task.id} className="surface rounded-[20px] p-4">
                        <div className="flex items-start justify-between gap-3">
                          <div className="min-w-0 space-y-1">
                            <div className="text-[16px] font-semibold">
                              #{task.task_number} · {task.title}
                            </div>
                            <div className="muted text-[13px]">
                              {task.company_name || 'Без точки'}
                              {task.due_date ? ` · дедлайн ${formatDate(task.due_date)}` : ''}
                            </div>
                            {task.description ? <div className="muted pt-1 text-[14px]">{task.description}</div> : null}
                          </div>
                          <div className="flex flex-col items-end gap-1.5">
                            <Chip color={task.status === 'done' ? 'var(--green)' : task.status === 'in_progress' ? 'var(--orange)' : 'var(--muted)'}>
                              {taskStatusLabel(task.status)}
                            </Chip>
                            <div className="muted text-[13px]">{taskPriorityLabel(task.priority)}</div>
                          </div>
                        </div>

                        {comments.length > 0 ? (
                          <div className="mt-3 space-y-2 pt-3" style={{ borderTop: '1px solid var(--line)' }}>
                            {comments.slice(-3).map((comment) => (
                              <div key={`${comment.task_id}:${comment.created_at}:${comment.content.slice(0, 12)}`} className="surface-2 rounded-[12px] px-3 py-2">
                                <div className="muted flex items-center gap-2 text-[13px]">
                                  <MessageSquare className="h-3.5 w-3.5" />
                                  {comment.author_name} · {formatDate(comment.created_at)}
                                </div>
                                <div className="mt-1 text-[14px]">{comment.content}</div>
                              </div>
                            ))}
                          </div>
                        ) : null}

                        {!isClosed ? (
                          <div className="mt-3 space-y-2 pt-3" style={{ borderTop: '1px solid var(--line)' }}>
                            <textarea
                              value={taskNotes[task.id] || ''}
                              onChange={(e) => setTaskNotes((current) => ({ ...current, [task.id]: e.target.value }))}
                              placeholder="Комментарий к задаче"
                              rows={2}
                              className="fill placeholder-muted w-full resize-none rounded-[14px] px-4 py-3 text-[14px] outline-none"
                            />
                            <div className="flex flex-wrap gap-2">
                              {actions
                                .filter((a) => a.show)
                                .map((a) => (
                                  <button
                                    key={a.response}
                                    type="button"
                                    disabled={busy}
                                    onClick={() => void handleTaskResponse(task.id, a.response)}
                                    className="press flex h-11 items-center gap-2 rounded-[14px] px-4 text-[14px] font-semibold"
                                    style={{ color: a.color, background: `color-mix(in srgb, ${a.color} 14%, transparent)` }}
                                  >
                                    {taskActionLoading === `${task.id}:${a.response}` ? <RefreshCw className="h-4 w-4 animate-spin" /> : null}
                                    {a.label}
                                  </button>
                                ))}
                              <button
                                type="button"
                                disabled={busy}
                                onClick={() => void handleTaskResponse(task.id, 'complete')}
                                className="press btn-green flex h-11 items-center gap-2 rounded-[14px] px-5 text-[14px] font-semibold"
                              >
                                {taskActionLoading === `${task.id}:complete` ? <RefreshCw className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
                                Готово
                              </button>
                            </div>
                            <button
                              type="button"
                              disabled={busy || !String(taskNotes[task.id] || '').trim()}
                              onClick={() => void handleTaskComment(task.id)}
                              className="press muted flex h-11 items-center gap-2 px-1 text-[14px] font-medium"
                            >
                              {taskActionLoading === `${task.id}:comment` ? <RefreshCw className="h-4 w-4 animate-spin" /> : <MessageSquare className="h-4 w-4" />}
                              Добавить комментарий
                            </button>
                          </div>
                        ) : null}
                      </div>
                    )
                  })
                )}
              </section>
            ) : null}

            {/* Долги */}
            {!loading && activeTab === 'debts' ? (
              <section className="space-y-3">
                <h2 className="text-[18px] font-bold">Мои долги</h2>
                <SectionError message={sectionErrors.debts} />
                <Notice color="var(--orange)">
                  Здесь только просмотр долгов. Оператор не закрывает долг вручную: удержание и погашение делает руководитель через зарплату/админку.
                </Notice>
                {debtsByWeek.length === 0 ? (
                  <div className="muted text-[14px]">За выбранный период долгов нет.</div>
                ) : (
                  debtsByWeek.map(([week, items]) => (
                    <div key={week} className="space-y-2">
                      <div className="flex items-center justify-between px-1">
                        <p className="muted text-[13px] font-semibold uppercase tracking-wide">Неделя с {formatDate(week)}</p>
                        <p className="muted text-[13px]">{money(items.filter((i) => i.status === 'active').reduce((s, i) => s + i.total_amount, 0))}</p>
                      </div>
                      {items.map((item) => (
                        <div key={item.id} className="surface flex items-start justify-between gap-3 rounded-[18px] p-4">
                          <div className="min-w-0">
                            <div className="text-[15px] font-semibold">{item.item_name}</div>
                            <div className="muted text-[13px]">
                              {formatDate(item.created_at.slice(0, 10))} · {item.quantity} шт. × {money(item.unit_price)}
                              {item.company_name ? ` · ${item.company_name}` : null}
                            </div>
                            {item.comment ? <div className="muted pt-1 text-[14px]">{item.comment}</div> : null}
                          </div>
                          <div className="flex flex-col items-end gap-1.5">
                            <div className="text-[16px] font-bold">{money(item.total_amount)}</div>
                            <Chip color={item.status === 'active' ? 'var(--red)' : 'var(--muted)'}>{item.status === 'active' ? 'Активен' : 'Закрыт'}</Chip>
                          </div>
                        </div>
                      ))}
                    </div>
                  ))
                )}
              </section>
            ) : null}

            {/* Профиль */}
            {!loading && activeTab === 'profile' ? (
              <section className="space-y-3">
                <h2 className="text-[18px] font-bold">Профиль</h2>
                <div className="grid grid-cols-2 gap-3">
                  <div className="surface rounded-[20px] p-4">
                    <div className="muted text-[13px]">Оператор</div>
                    <div className="mt-1 text-[16px] font-semibold">{profileName}</div>
                    <div className="muted mt-1 text-[13px]">@{session.operator.username}</div>
                  </div>
                  <div className="surface rounded-[20px] p-4">
                    <div className="muted text-[13px]">Роль</div>
                    <div className="mt-1 text-[16px] font-semibold">{session.operator.role_in_company || 'Оператор'}</div>
                    <div className="muted mt-1 text-[13px]">{session.operator.is_primary ? 'Основная точка' : 'Доп. точка'}</div>
                  </div>
                  <div className="surface rounded-[20px] p-4">
                    <div className="muted text-[13px]">Точка</div>
                    <div className="mt-1 text-[16px] font-semibold">{session.company.name}</div>
                    <div className="muted mt-1 text-[13px]">{bootstrap.device.name || bootstrap.device.point_mode}</div>
                  </div>
                  <div className="surface rounded-[20px] p-4">
                    <div className="muted text-[13px]">Telegram</div>
                    <div className="mt-1 text-[16px] font-semibold">{session.operator.telegram_chat_id || 'Не привязан'}</div>
                    <div className="muted mt-1 text-[13px]">Нужен для уведомлений о долгах и отчётах</div>
                  </div>
                </div>
              </section>
            ) : null}
          </div>
        </main>
      </div>
    </V2Shell>
  )
}

function KnowledgeArticleCard({
  article,
  required,
  confirming,
  onConfirm,
  onExplain,
}: {
  article: PointKnowledgeArticle
  required?: boolean
  confirming?: boolean
  onConfirm?: () => void
  onExplain?: (question: string) => Promise<string>
}) {
  const severityLabel = SEVERITY_LABELS[article.severity] || article.severity
  const isDanger = article.severity === 'critical' || article.severity === 'warning'
  const [explainOpen, setExplainOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState<string | null>(null)
  const [explaining, setExplaining] = useState(false)
  const [explainError, setExplainError] = useState<string | null>(null)

  async function ask() {
    if (!onExplain) return
    setExplaining(true)
    setExplainError(null)
    try {
      setAnswer(await onExplain(question.trim()))
    } catch (error) {
      setExplainError(error instanceof Error ? error.message : 'Не удалось получить объяснение')
    } finally {
      setExplaining(false)
    }
  }

  return (
    <article
      className="surface-2 rounded-[18px] p-4"
      style={required ? { boxShadow: 'inset 0 0 0 1.5px color-mix(in srgb, var(--orange) 55%, transparent)' } : undefined}
    >
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Chip color={isDanger ? 'var(--red)' : 'var(--muted)'}>{severityLabel}</Chip>
            {article.category?.title ? <Chip color="var(--muted)">{article.category.title}</Chip> : null}
            {article.requires_confirmation ? <Chip color="var(--muted)">нужно подтверждение</Chip> : null}
            {article.version ? <Chip color="var(--muted)">v{article.version}</Chip> : null}
          </div>
          <h3 className="mt-3 break-words text-[17px] font-semibold">{article.title}</h3>
          {article.summary ? <p className="muted mt-2 break-words text-[14px] leading-6">{article.summary}</p> : null}
          {article.content ? (
            <div className="surface mt-3 max-h-48 overflow-auto rounded-[14px] p-3 text-[14px] leading-6" dangerouslySetInnerHTML={{ __html: article.content }} />
          ) : null}
          {onExplain ? (
            <div className="mt-3">
              {!explainOpen ? (
                <button type="button" onClick={() => setExplainOpen(true)} className="press t-blue h-11 text-[14px] font-medium">
                  Не понял — объясни проще
                </button>
              ) : (
                <div className="surface rounded-[16px] p-3">
                  <div className="text-[13px] font-semibold">Объяснение по правилу</div>
                  <input
                    value={question}
                    onChange={(e) => setQuestion(e.target.value)}
                    placeholder="Что именно непонятно? Можно оставить пустым"
                    className="fill placeholder-muted mt-2 h-11 w-full rounded-[12px] px-3 text-[14px] outline-none"
                  />
                  <div className="mt-2 flex flex-wrap gap-2">
                    <button type="button" onClick={() => void ask()} disabled={explaining} className="press btn-blue h-11 rounded-[12px] px-4 text-[14px] font-semibold">
                      {explaining ? 'Объясняю…' : 'Объяснить'}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setExplainOpen(false)
                        setAnswer(null)
                        setExplainError(null)
                        setQuestion('')
                      }}
                      className="press fill h-11 rounded-[12px] px-4 text-[14px] font-medium"
                    >
                      Закрыть
                    </button>
                  </div>
                  {explainError ? <p className="t-red mt-2 text-[13px]">{explainError}</p> : null}
                  {answer ? <p className="mt-2 whitespace-pre-wrap text-[14px] leading-6">{answer}</p> : null}
                  <p className="muted mt-2 text-[13px]">Ответ собран по тексту правила. Если ответа в нём нет — спроси руководителя.</p>
                </div>
              )}
            </div>
          ) : null}
          {article.tags?.length ? (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {article.tags.map((tag) => (
                <span key={tag} className="fill muted rounded-full px-2.5 py-1 text-[13px]">
                  #{tag}
                </span>
              ))}
            </div>
          ) : null}
        </div>
        {onConfirm ? (
          <button type="button" onClick={onConfirm} disabled={confirming} className="press btn-green flex h-11 shrink-0 items-center gap-2 rounded-[14px] px-4 text-[14px] font-semibold">
            {confirming ? <RefreshCw className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
            Ознакомлен
          </button>
        ) : null}
      </div>
    </article>
  )
}
