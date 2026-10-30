'use client'

import Link from 'next/link'
import { useEffect, useMemo, useState } from 'react'
import { CheckCircle2, Circle, Loader2, MinusCircle, Plus, Sparkles, Trash2, XCircle } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { CopyText } from '@/components/ui/copy-text'
import { Input } from '@/components/ui/input'
import { MoneyInput } from '@/components/ui/money-input'
import { NativeSelect } from '@/components/ui/native-select'
import { PositionSelect, usePointPositions } from '@/components/admin/position-select'
import { transliterate } from '@/lib/core/company-code'
import { listIndustries } from '@/lib/core/industries'

type CompanyRole = 'operator' | 'senior_operator' | 'senior_cashier'

type NewPerson = { key: string; name: string; username: string; usernameTouched: boolean; role: CompanyRole; positionId: string | null }
type ExistingPick = { id: string; name: string; role: CompanyRole; positionId: string | null }
type ExistingOperator = { id: string; name: string; is_active?: boolean }

type StepStatus = 'pending' | 'running' | 'done' | 'skipped' | 'failed'
type Step = { key: string; label: string; status: StepStatus; note?: string }

type Login = { name: string; username: string; password: string }
type Result = {
  companyId: string
  companyName: string
  token: string | null
  logins: Login[]
}

const MODE_OPTIONS = [
  { value: 'cash-desk', label: 'Касса с продажами', hint: 'Пробивает продажи, смена закрывается Z-отчётом' },
  { value: 'shift-report', label: 'Сменный отчёт', hint: 'Оператор в конце смены вносит выручку' },
  { value: 'universal', label: 'Всё вместе', hint: 'И продажи, и сменный отчёт' },
]

function suggestUsername(name: string) {
  const base = transliterate(name.trim().split(/\s+/)[0] || '')
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '')
    .slice(0, 12)
  if (!base) return ''
  return `${base}${Math.floor(100 + Math.random() * 900)}`
}

function todayISO() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function postJson(url: string, body: unknown, method = 'POST') {
  const response = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const data = await response.json().catch(() => ({}))
  return { ok: response.ok, status: response.status, data: data as any }
}

// 402/403 на необязательном шаге — нет модуля в тарифе или права, а не сбой.
function isNotAllowed(status: number) {
  return status === 402 || status === 403
}

function SectionTitle(props: { n: number; title: string; hint: string }) {
  return (
    <div className="mb-4 flex items-start gap-3">
      <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-500/15 text-sm font-semibold text-blue-600 dark:text-blue-300">
        {props.n}
      </span>
      <div>
        <h2 className="text-base font-semibold text-foreground">{props.title}</h2>
        <p className="text-sm text-muted-foreground">{props.hint}</p>
      </div>
    </div>
  )
}

function StepIcon({ status }: { status: StepStatus }) {
  if (status === 'running') return <Loader2 className="h-4 w-4 animate-spin text-blue-500" />
  if (status === 'done') return <CheckCircle2 className="h-4 w-4 text-emerald-500" />
  if (status === 'failed') return <XCircle className="h-4 w-4 text-rose-500" />
  if (status === 'skipped') return <MinusCircle className="h-4 w-4 text-amber-500" />
  return <Circle className="h-4 w-4 text-muted-foreground" />
}

/**
 * Мастер новой точки: точка → касса → люди → ставки за один проход.
 *
 * Шаги идут через обычные роуты (те же права, лимиты тарифа и аудит, что и на
 * отдельных страницах). Точка создаётся первой; если дальше что-то упало,
 * она остаётся, а на экране видно, какой шаг доделать руками и где.
 */
export default function NewPointWizardPage() {
  const industries = useMemo(() => listIndustries(), [])
  // Мастер создаёт новую точку — подходят только должности «на всех точках».
  const positions = usePointPositions()

  const [name, setName] = useState('')
  const [industry, setIndustry] = useState('')
  // ?industry=detailing — пришли со стартовой страницы ниши, ниша уже выбрана.
  useEffect(() => {
    const preset = new URLSearchParams(window.location.search).get('industry')
    if (preset) setIndustry(preset)
  }, [])
  const [address, setAddress] = useState('')
  const [excludeFromTotals, setExcludeFromTotals] = useState(false)
  const [storeEnabled, setStoreEnabled] = useState(false)

  const [createDevice, setCreateDevice] = useState(true)
  const [pointMode, setPointMode] = useState('cash-desk')
  const [chatId, setChatId] = useState('')

  const [people, setPeople] = useState<NewPerson[]>([])
  const [existing, setExisting] = useState<ExistingOperator[]>([])
  const [picked, setPicked] = useState<ExistingPick[]>([])

  const [setRates, setSetRates] = useState(true)
  const [dayRate, setDayRate] = useState('8000')
  const [nightRate, setNightRate] = useState('8000')

  const [running, setRunning] = useState(false)
  const [steps, setSteps] = useState<Step[]>([])
  const [result, setResult] = useState<Result | null>(null)
  const [fatal, setFatal] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    fetch('/api/admin/operators', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : null))
      .then((json) => {
        if (cancelled || !json) return
        const rows = (Array.isArray(json.data) ? json.data : []) as ExistingOperator[]
        setExisting(rows.filter((row) => row.is_active !== false))
      })
      .catch(() => null)
    return () => {
      cancelled = true
    }
  }, [])

  // Для магазина и общепита касса с продажами и магазин — разумное начало.
  useEffect(() => {
    if (industry === 'shop' || industry === 'food') {
      setStoreEnabled(true)
      setPointMode('cash-desk')
    }
  }, [industry])

  function addPerson() {
    setPeople((list) => [...list, { key: crypto.randomUUID(), name: '', username: '', usernameTouched: false, role: list.length === 0 ? 'senior_operator' : 'operator', positionId: null }])
  }

  function updatePerson(key: string, patch: Partial<NewPerson>) {
    setPeople((list) =>
      list.map((p) => {
        if (p.key !== key) return p
        const next = { ...p, ...patch }
        // Логин подсказываем, пока его не правили руками.
        if (patch.name !== undefined && !p.usernameTouched) next.username = suggestUsername(patch.name)
        return next
      }),
    )
  }

  function togglePicked(op: ExistingOperator) {
    setPicked((list) => (list.some((p) => p.id === op.id) ? list.filter((p) => p.id !== op.id) : [...list, { id: op.id, name: op.name, role: 'operator', positionId: null }]))
  }

  const invalidPerson = people.find((p) => !p.name.trim() || p.username.trim().length < 3)
  const canSubmit = name.trim().length > 0 && !invalidPerson && !running

  function setStep(key: string, patch: Partial<Step>) {
    setSteps((list) => list.map((s) => (s.key === key ? { ...s, ...patch } : s)))
  }

  async function run() {
    if (!canSubmit) return
    setRunning(true)
    setFatal(null)
    setResult(null)

    const plan: Step[] = [
      { key: 'company', label: `Точка «${name.trim()}»`, status: 'pending' },
      ...(storeEnabled ? [{ key: 'store', label: 'Магазин: склад и витрина', status: 'pending' as StepStatus }] : []),
      ...(createDevice ? [{ key: 'device', label: 'Касса и токен', status: 'pending' as StepStatus }] : []),
      ...people.map((p) => ({ key: `new-${p.key}`, label: `${p.name.trim()} — логин ${p.username.trim()}`, status: 'pending' as StepStatus })),
      ...picked.map((p) => ({ key: `old-${p.id}`, label: `${p.name} — на эту точку`, status: 'pending' as StepStatus })),
      ...(setRates ? [{ key: 'rates', label: 'Ставки за смену', status: 'pending' as StepStatus }] : []),
    ]
    setSteps(plan)

    // 1. Точка — без неё дальше нельзя.
    setStep('company', { status: 'running' })
    const companyRes = await postJson('/api/admin/companies', {
      name: name.trim(),
      industry: industry || null,
      address: address.trim() || null,
      excludeFromTotals,
    })
    if (!companyRes.ok || !companyRes.data?.company?.id) {
      setStep('company', { status: 'failed', note: companyRes.data?.error || 'Не удалось создать точку' })
      setFatal(companyRes.data?.error || 'Не удалось создать точку')
      setRunning(false)
      return
    }
    const company = companyRes.data.company as { id: string; name: string; code: string }
    setStep('company', { status: 'done', note: `код ${company.code}` })

    const logins: Login[] = []
    let token: string | null = null

    // 2. Магазин: флаг + свои склад и витрина. Список включённых шлётся целиком.
    if (storeEnabled) {
      setStep('store', { status: 'running' })
      const config = await fetch('/api/admin/store/config', { cache: 'no-store' }).then((r) => r.json().catch(() => null)).catch(() => null)
      const current: string[] = Array.isArray(config?.data?.store_enabled_ids) ? config.data.store_enabled_ids : []
      const storeRes = await postJson('/api/admin/store/config', { store_enabled_ids: Array.from(new Set([...current, company.id])) }, 'PUT')
      if (storeRes.ok) setStep('store', { status: 'done' })
      else setStep('store', {
        status: isNotAllowed(storeRes.status) ? 'skipped' : 'failed',
        note: isNotAllowed(storeRes.status) ? 'нет модуля магазина в тарифе' : storeRes.data?.error || 'ошибка',
      })
    }

    // 3. Касса.
    if (createDevice) {
      setStep('device', { status: 'running' })
      const deviceRes = await postJson('/api/admin/point-devices', {
        action: 'createProject',
        payload: {
          name: company.name,
          point_mode: pointMode,
          shift_report_chat_id: chatId.trim() || null,
          company_assignments: [{ company_id: company.id }],
        },
      })
      if (deviceRes.ok) {
        token = deviceRes.data?.data?.project_token || null
        setStep('device', { status: 'done' })
      } else {
        setStep('device', {
          status: isNotAllowed(deviceRes.status) ? 'skipped' : 'failed',
          note: deviceRes.data?.error || 'ошибка',
        })
      }
    }

    // 4. Новые люди: карточка → логин → назначение на точку.
    for (const person of people) {
      const key = `new-${person.key}`
      setStep(key, { status: 'running' })
      const opRes = await postJson('/api/admin/operators', { action: 'createOperator', payload: { name: person.name.trim() } })
      const operatorId = opRes.data?.data?.id
      if (!opRes.ok || !operatorId) {
        setStep(key, { status: 'failed', note: opRes.data?.error || 'не создан' })
        continue
      }
      const username = person.username.trim().toLowerCase()
      const accRes = await postJson('/api/admin/create-operator-account', {
        operatorId,
        username,
        email: `${username}@operator.local`,
        name: person.name.trim(),
      })
      const assignRes = await postJson('/api/admin/operator-company-assignments', {
        action: 'saveAssignments',
        operatorId,
        assignments: [{ company_id: company.id, role_in_company: person.role, position_id: person.positionId, is_primary: true, is_active: true }],
      })
      if (accRes.ok && accRes.data?.password) logins.push({ name: person.name.trim(), username: accRes.data.username, password: accRes.data.password })
      const problems = [
        !accRes.ok ? `логин: ${accRes.data?.error || 'ошибка'}` : null,
        !assignRes.ok ? `точка: ${assignRes.data?.error || 'ошибка'}` : null,
      ].filter(Boolean)
      setStep(key, problems.length ? { status: 'failed', note: problems.join('; ') } : { status: 'done' })
    }

    // 5. Уже работающие люди: назначения заменяются набором целиком — дописываем к текущим.
    for (const pick of picked) {
      const key = `old-${pick.id}`
      setStep(key, { status: 'running' })
      const currentRes = await fetch(`/api/admin/operator-company-assignments?operatorId=${encodeURIComponent(pick.id)}`, { cache: 'no-store' })
        .then((r) => r.json().catch(() => null))
        .catch(() => null)
      const current = (Array.isArray(currentRes?.data) ? currentRes.data : []) as any[]
      const activeCount = current.filter((row) => row.is_active !== false).length
      if (activeCount >= 2) {
        setStep(key, { status: 'skipped', note: 'уже на двух точках — это максимум' })
        continue
      }
      const assignRes = await postJson('/api/admin/operator-company-assignments', {
        action: 'saveAssignments',
        operatorId: pick.id,
        assignments: [
          ...current.map((row) => ({
            id: row.id,
            company_id: row.company_id,
            role_in_company: row.role_in_company,
            is_primary: row.is_primary,
            is_active: row.is_active,
            notes: row.notes,
          })),
          { company_id: company.id, role_in_company: pick.role, position_id: pick.positionId, is_primary: activeCount === 0, is_active: true },
        ],
      })
      setStep(key, assignRes.ok ? { status: 'done' } : { status: 'failed', note: assignRes.data?.error || 'ошибка' })
    }

    // 6. Ставки: без правила зарплата молча считает 8 000 за смену и без бонусов.
    if (setRates) {
      setStep('rates', { status: 'running' })
      const notes: string[] = []
      let skipped = false
      for (const [shiftType, value] of [['day', dayRate], ['night', nightRate]] as const) {
        const rateRes = await postJson('/api/admin/salary-rules', {
          action: 'createRule',
          payload: {
            company_code: company.code,
            shift_type: shiftType,
            base_per_shift: Number(value || 0),
            senior_operator_bonus: 0,
            senior_cashier_bonus: 0,
            is_active: true,
            effective_from: todayISO(),
          },
        })
        if (!rateRes.ok) {
          if (isNotAllowed(rateRes.status)) skipped = true
          notes.push(rateRes.data?.error || 'ошибка')
        }
      }
      if (skipped) setStep('rates', { status: 'skipped', note: 'нет модуля зарплаты или права' })
      else setStep('rates', notes.length ? { status: 'failed', note: notes.join('; ') } : { status: 'done' })
    }

    setResult({ companyId: company.id, companyName: company.name, token, logins })
    setRunning(false)
  }

  if (result) {
    const failed = steps.filter((s) => s.status === 'failed' || s.status === 'skipped')
    return (
      <div className="app-page space-y-5">
        <AdminPageHeader title="Точка готова" description={result.companyName} icon={<Sparkles className="h-5 w-5" />} backHref="/settings" />

        <Card className="p-5">
          <h2 className="mb-3 text-base font-semibold text-foreground">Что сделано</h2>
          <StepList steps={steps} />
          {failed.length > 0 && (
            <p className="mt-3 text-sm text-amber-600 dark:text-amber-300">
              Не всё получилось — точка создана, недоделанное можно закончить на страницах ниже.
            </p>
          )}
        </Card>

        {result.token && (
          <Card className="p-5">
            <h2 className="text-base font-semibold text-foreground">Токен кассы</h2>
            <p className="mb-3 text-sm text-muted-foreground">
              Вставьте в программу кассы при первом запуске. Посмотреть или сменить его можно в «Кассах и устройствах».
            </p>
            <CopyText value={result.token} className="block break-all rounded-lg bg-slate-100 px-3 py-2 font-mono text-xs dark:bg-white/5" />
          </Card>
        )}

        {result.logins.length > 0 && (
          <Card className="p-5">
            <h2 className="text-base font-semibold text-foreground">Логины сотрудников</h2>
            <p className="mb-3 text-sm text-rose-600 dark:text-rose-300">
              Пароли показываются один раз. Запишите или передайте сразу — потом только сброс.
            </p>
            <div className="space-y-2">
              {result.logins.map((login) => (
                <div key={login.username} className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-border px-3 py-2 text-sm">
                  <span className="min-w-32 font-medium text-foreground">{login.name}</span>
                  <span className="text-muted-foreground">логин</span>
                  <CopyText value={login.username} className="font-mono" />
                  <span className="text-muted-foreground">пароль</span>
                  <CopyText value={login.password} className="font-mono" />
                </div>
              ))}
            </div>
          </Card>
        )}

        <Card className="p-5">
          <h2 className="mb-3 text-base font-semibold text-foreground">Дальше</h2>
          <div className="flex flex-wrap gap-2">
            <Button asChild variant="outline"><Link href="/point-devices">Кассы и устройства</Link></Button>
            <Button asChild variant="outline"><Link href="/structure">Люди по точкам</Link></Button>
            <Button asChild variant="outline"><Link href="/salary/rules">Правила зарплаты</Link></Button>
            {storeEnabled && <Button asChild variant="outline"><Link href="/store/stock">Товары и остатки</Link></Button>}
            <Button asChild variant="outline"><Link href="/settings/payment-provider">Безналичная оплата</Link></Button>
            <Button onClick={() => window.location.reload()}>Ещё одна точка</Button>
          </div>
        </Card>
      </div>
    )
  }

  return (
    <div className="app-page space-y-5">
      <AdminPageHeader
        title="Новая точка"
        description="Точка, касса, люди и ставки — за один раз"
        icon={<Sparkles className="h-5 w-5" />}
        backHref="/settings"
      />

      <Card className="p-5">
        <SectionTitle n={1} title="Точка" hint="Как она называется и чем занимается" />
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Название *</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Например, «Кофейня на Абая»" />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Чем занимается</span>
            <NativeSelect value={industry} onChange={(e) => setIndustry(e.target.value)}>
              <option value="">Не выбрано</option>
              {industries.map((item) => (
                <option key={item.code} value={item.code}>{item.label}</option>
              ))}
            </NativeSelect>
          </label>
          <label className="space-y-1 text-sm sm:col-span-2">
            <span className="text-muted-foreground">Адрес</span>
            <Input value={address} onChange={(e) => setAddress(e.target.value)} placeholder="Необязательно" />
          </label>
        </div>
        <div className="mt-3 space-y-2 text-sm">
          <label className="flex items-start gap-2">
            <input type="checkbox" className="mt-1" checked={storeEnabled} onChange={(e) => setStoreEnabled(e.target.checked)} />
            <span>
              <span className="text-foreground">Продаёт товары</span>
              <span className="block text-xs text-muted-foreground">Своя витрина и склад, остатки, приходы</span>
            </span>
          </label>
          <label className="flex items-start gap-2">
            <input type="checkbox" className="mt-1" checked={excludeFromTotals} onChange={(e) => setExcludeFromTotals(e.target.checked)} />
            <span>
              <span className="text-foreground">Не в общих итогах</span>
              <span className="block text-xs text-muted-foreground">Выручка точки видна отдельно и не складывается с остальными</span>
            </span>
          </label>
        </div>
      </Card>

      <Card className="p-5">
        <SectionTitle n={2} title="Касса" hint="Программа, в которой работает оператор" />
        <label className="mb-3 flex items-center gap-2 text-sm">
          <input type="checkbox" checked={createDevice} onChange={(e) => setCreateDevice(e.target.checked)} />
          <span className="text-foreground">Завести кассу сейчас</span>
        </label>
        {createDevice && (
          <div className="space-y-3">
            <div className="grid gap-2 sm:grid-cols-3">
              {MODE_OPTIONS.map((mode) => (
                <label
                  key={mode.value}
                  className={`cursor-pointer rounded-xl border px-3 py-2.5 text-sm ${
                    pointMode === mode.value ? 'border-blue-500/50 bg-blue-500/10' : 'border-border hover:bg-slate-50 dark:hover:bg-white/5'
                  }`}
                >
                  <input type="radio" name="point-mode" className="sr-only" checked={pointMode === mode.value} onChange={() => setPointMode(mode.value)} />
                  <span className="block font-medium text-foreground">{mode.label}</span>
                  <span className="block text-xs text-muted-foreground">{mode.hint}</span>
                </label>
              ))}
            </div>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">Telegram-чат для отчётов смены</span>
              <Input value={chatId} onChange={(e) => setChatId(e.target.value)} placeholder="Например, -1001234567890 (можно позже)" inputMode="numeric" />
            </label>
          </div>
        )}
      </Card>

      <Card className="p-5">
        <SectionTitle n={3} title="Люди" hint="Кто будет работать на точке. Каждому новому — логин для кассы" />
        <div className="space-y-2">
          {people.map((person) => (
            <div key={person.key} className="grid gap-2 rounded-xl border border-border p-3 sm:grid-cols-[1fr_10rem_11rem_auto] sm:items-center">
              <Input value={person.name} onChange={(e) => updatePerson(person.key, { name: e.target.value })} placeholder="Имя и фамилия" />
              <Input value={person.username} onChange={(e) => updatePerson(person.key, { username: e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g, ''), usernameTouched: true })} placeholder="логин" className="font-mono" />
              <PositionSelect
                companyId=""
                role={person.role}
                positionId={person.positionId}
                positions={positions}
                onChange={(next) => updatePerson(person.key, { role: next.role, positionId: next.position_id })}
              />
              <Button variant="ghost" size="icon" onClick={() => setPeople((list) => list.filter((p) => p.key !== person.key))} aria-label="Убрать">
                <Trash2 className="h-4 w-4" />
              </Button>
            </div>
          ))}
          <Button variant="outline" onClick={addPerson}>
            <Plus className="mr-1 h-4 w-4" /> Новый сотрудник
          </Button>
        </div>

        {existing.length > 0 && (
          <div className="mt-4">
            <p className="mb-2 text-sm text-muted-foreground">Или поставить тех, кто уже работает (человек может быть максимум на двух точках):</p>
            <div className="flex flex-wrap gap-2">
              {existing.map((op) => {
                const pick = picked.find((p) => p.id === op.id)
                return (
                  <span key={op.id} className={`inline-flex items-center gap-1 rounded-full border px-3 py-1 text-sm ${pick ? 'border-blue-500/50 bg-blue-500/10' : 'border-border'}`}>
                    <button type="button" onClick={() => togglePicked(op)} className="text-foreground">{op.name}</button>
                    {pick && (
                      <PositionSelect
                        companyId=""
                        role={pick.role}
                        positionId={pick.positionId}
                        positions={positions}
                        onChange={(next) => setPicked((list) => list.map((p) => (p.id === op.id ? { ...p, role: next.role, positionId: next.position_id } : p)))}
                        className="h-7 border-0 bg-transparent px-1 text-xs text-muted-foreground shadow-none"
                      />
                    )}
                  </span>
                )
              })}
            </div>
          </div>
        )}
      </Card>

      <Card className="p-5">
        <SectionTitle n={4} title="Ставка за смену" hint="Сколько оператор получает за смену. Бонусы и пороги — потом в правилах зарплаты" />
        <label className="mb-3 flex items-center gap-2 text-sm">
          <input type="checkbox" checked={setRates} onChange={(e) => setSetRates(e.target.checked)} />
          <span className="text-foreground">Задать ставки сейчас</span>
        </label>
        {setRates && (
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">Дневная смена, ₸</span>
              <MoneyInput value={dayRate} onValueChange={setDayRate} />
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">Ночная смена, ₸</span>
              <MoneyInput value={nightRate} onValueChange={setNightRate} />
            </label>
          </div>
        )}
      </Card>

      {steps.length > 0 && (
        <Card className="p-5">
          <StepList steps={steps} />
          {fatal && <p className="mt-3 text-sm text-rose-600 dark:text-rose-300">{fatal}</p>}
        </Card>
      )}

      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={run} disabled={!canSubmit} size="lg">
          {running ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Sparkles className="mr-2 h-4 w-4" />}
          Создать точку
        </Button>
        {invalidPerson && <span className="text-sm text-muted-foreground">У каждого нового сотрудника нужны имя и логин от 3 символов</span>}
      </div>
    </div>
  )
}

function StepList({ steps }: { steps: Step[] }) {
  return (
    <ul className="space-y-1.5">
      {steps.map((step) => (
        <li key={step.key} className="flex items-start gap-2 text-sm">
          <span className="mt-0.5"><StepIcon status={step.status} /></span>
          <span className="text-foreground">
            {step.label}
            {step.note && <span className="ml-2 text-xs text-muted-foreground">— {step.note}</span>}
          </span>
        </li>
      ))}
    </ul>
  )
}
