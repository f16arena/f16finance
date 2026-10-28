'use client'

import { useEffect, useMemo, useState, FormEvent, useCallback } from 'react'
import { pointProfile } from '@/lib/core/point-profile'
import { activeZones } from '@/lib/core/revenue-zones'
import { useRouter } from 'next/navigation'
import {
  Calendar,
  Wallet,
  CreditCard,
  Gamepad2,
  Eye,
  Sun,
  Moon,
  Store,
  Building2,
  Save,
  UserCircle2,
  Smartphone,
  Brain,
  Sparkles,
  TrendingUp,
  CheckCircle2,
  AlertCircle,
  Zap,
} from 'lucide-react'

import { Card } from '@/components/ui/card'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { Button } from '@/components/ui/button'
import { MoneyInput } from '@/components/ui/money-input'
import { confirmDialog } from '@/components/ui/confirm-dialog'
import { useUnsavedGuard } from '@/lib/client/use-unsaved-guard'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { DatePicker } from '@/components/ui/date-picker'
import { Skeleton } from '@/components/skeleton'

type Company = {
  id: string
  name: string
  code: string
  industry?: string | null
  revenue_zones?: unknown
}

type Operator = {
  id: string
  name: string
  short_name: string | null
  is_active: boolean
}

type ShiftType = 'day' | 'night'
type ZoneType = 'pc' | 'ps5' | 'vr' | 'ramen' | 'other'

// --- даты без UTC-сдвига ---
const toISODateLocal = (d: Date) => {
  const t = d.getTime() - d.getTimezoneOffset() * 60_000
  return new Date(t).toISOString().slice(0, 10)
}
const getTodayISO = () => toISODateLocal(new Date())

const parseAmount = (v: string) => {
  if (!v) return 0
  const n = Number(v.replace(',', '.').replace(/\s/g, ''))
  return Number.isFinite(n) && n > 0 ? n : 0
}

const formatMoney = (v: number) => v.toLocaleString('ru-RU') + ' ₸'

async function logIncomeAudit(event: {
  entityId: string
  action: string
  payload?: Record<string, unknown>
}) {
  await fetch('/api/admin/audit-event', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      entityType: 'income',
      entityId: event.entityId,
      action: event.action,
      payload: event.payload || null,
    }),
  }).catch(() => null)
}

export default function AddIncomePage() {
  const { can } = useCapabilities()
  const router = useRouter()

  // Встроенный режим (внутри модалки на странице доходов через iframe ?embedded=1):
  // прячем шапку и при сохранении шлём сообщение родителю вместо редиректа.
  const [embedded, setEmbedded] = useState(false)
  useEffect(() => {
    setEmbedded(new URLSearchParams(window.location.search).get('embedded') === '1')
  }, [])

  const [date, setDate] = useState(getTodayISO())

  const [companies, setCompanies] = useState<Company[]>([])
  const [operators, setOperators] = useState<Operator[]>([])
  const [companyId, setCompanyId] = useState('')
  const [operatorId, setOperatorId] = useState('')

  const [loadingMeta, setLoadingMeta] = useState(true)
  const [shift, setShift] = useState<ShiftType>('day')

  // Обычные компании
  const [cash, setCash] = useState('')
  const [kaspi, setKaspi] = useState('')
  const [online, setOnline] = useState('')
  const [card, setCard] = useState('')

  // Точка с зонами (PS5, VR, бильярд…): суммы по каждой включённой зоне
  const [zoneAmounts, setZoneAmounts] = useState<Record<string, { cash: string; cashless: string }>>({})

  const [comment, setComment] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showSuccess, setShowSuccess] = useState(false)

  // ---- загрузка справочников ----
  useEffect(() => {
    const load = async () => {
      setLoadingMeta(true)
      setError(null)

      const [compRes, opRes] = await Promise.all([
        fetch('/api/admin/companies?open=1', { cache: 'no-store' }),
        fetch('/api/admin/operators?active_only=true', { cache: 'no-store' }),
      ])

      if (!compRes.ok || !opRes.ok) {
        setError('Не удалось загрузить компании/операторов')
        setCompanies([])
        setOperators([])
      } else {
        const [companiesBody, operatorsBody] = await Promise.all([compRes.json(), opRes.json()])
        const loadedCompanies = companiesBody?.data || []
        const loadedOperators = operatorsBody?.data || []
        setCompanies(loadedCompanies)
        setOperators(loadedOperators)
        if (loadedCompanies?.length) setCompanyId(loadedCompanies[0].id)
      }

      setLoadingMeta(false)
    }

    load()
  }, [])

  const selectedCompany = useMemo(() => companies.find((c) => c.id === companyId) || null, [companies, companyId])

  // Поля формы — по нише точки (клуб, PS-зал, магазин), а не по кодам F16.
  const profile = pointProfile(selectedCompany)
  const showOnline = profile.showOnline
  // Зоны — настройка точки (Настройки → Компании → «Зоны»). Есть зоны — приход по зонам.
  const zones = useMemo(() => activeZones(selectedCompany), [selectedCompany])
  const isExtra = zones.length > 0
  const zoneTotal = (key: string) => parseAmount(zoneAmounts[key]?.cash || '') + parseAmount(zoneAmounts[key]?.cashless || '')
  const setZoneAmount = (key: string, field: 'cash' | 'cashless', value: string) =>
    setZoneAmounts((prev) => ({ ...prev, [key]: { ...(prev[key] || { cash: '', cashless: '' }), [field]: value } }))

  const getZone = useCallback((): ZoneType => {
    if (profile.incomeZone === 'pc' || profile.incomeZone === 'ramen') return profile.incomeZone
    return 'other'
  }, [profile.incomeZone])

  // ---- умный сброс полей при смене компании ----
  useEffect(() => {
    setError(null)
    setCash('')
    setKaspi('')
    setCard('')
    setOnline('')
    setZoneAmounts({})
  }, [companyId])

  // ---- расчет предварительной суммы ----
  const previewTotal = useMemo(() => {
    if (isExtra) {
      return zones.reduce((sum, z) => sum + parseAmount(zoneAmounts[z.key]?.cash || '') + parseAmount(zoneAmounts[z.key]?.cashless || ''), 0)
    }
    return parseAmount(cash) + parseAmount(kaspi) + parseAmount(online) + parseAmount(card)
  }, [isExtra, zones, zoneAmounts, cash, kaspi, online, card])

  // Защита несохранённой формы: есть введённые суммы или комментарий и запись ещё не сохранена
  const isDirty = !showSuccess && (previewTotal > 0 || comment.trim().length > 0)
  const guardClose = useUnsavedGuard(isDirty)

  // ---- валидация ----
  const validation = useMemo(() => {
    if (!companyId) return { ok: false, msg: 'Выберите компанию' }
    if (!operatorId) return { ok: false, msg: 'Выберите оператора смены' }
    if (!date) return { ok: false, msg: 'Выберите дату' }
    if (!operators.length) return { ok: false, msg: 'Нет активных операторов' }

    if (isExtra) {
      if (previewTotal <= 0) return { ok: false, msg: `Укажите сумму хотя бы по одной зоне: ${zones.map((z) => z.label).join(', ')}` }
      return { ok: true, msg: '' }
    }

    const c = parseAmount(cash)
    const k = parseAmount(kaspi)
    const o = showOnline ? parseAmount(online) : 0
    const cd = parseAmount(card)
    if (c <= 0 && k <= 0 && o <= 0 && cd <= 0) return { ok: false, msg: 'Введите сумму дохода' }

    return { ok: true, msg: '' }
  }, [companyId, operatorId, date, operators.length, isExtra, zones, previewTotal, cash, kaspi, online, card, showOnline])

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    if (saving) return

    setError(null)
    setSaving(true)

    try {
      if (!validation.ok) throw new Error(validation.msg)

      if (isExtra) {
        const baseComment = comment.trim()
        // Одна запись на зону с суммой; подпись зоны — в комментарии (как было у PS5/VR).
        const rows: any[] = zones
          .filter((z) => zoneTotal(z.key) > 0)
          .map((z) => ({
            date,
            company_id: companyId,
            operator_id: operatorId,
            shift,
            zone: z.key,
            cash_amount: parseAmount(zoneAmounts[z.key]?.cash || ''),
            kaspi_amount: parseAmount(zoneAmounts[z.key]?.cashless || ''),
            online_amount: 0,
            card_amount: 0,
            comment: baseComment ? `${baseComment} • ${z.label}` : z.label,
            is_virtual: true,
          }))

        const response = await fetch('/api/admin/incomes', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            action: 'createIncomeBatch',
            payload: rows,
          }),
        })
        const json = await response.json().catch(() => null)
        if (!response.ok) {
          if (json?.error === 'forbidden' && json?.capability) {
            throw new Error(`У тебя нет права «${json.capability}». Попроси владельца включить его на странице /access → Руководитель`)
          }
          throw new Error(json?.message || json?.error || 'Не удалось сохранить доход')
        }
        await logIncomeAudit({
          entityId: `batch:${(json?.data || []).map((item: { id: string }) => item.id).join(',') || `${date}:${companyId}`}`,
          action: 'create-batch',
          payload: {
            date,
            company_id: companyId,
            operator_id: operatorId,
            shift,
            rows_count: rows.length,
            total_amount: rows.reduce(
              (sum, item) =>
                sum +
                Number(item.cash_amount || 0) +
                Number(item.kaspi_amount || 0) +
                Number(item.online_amount || 0) +
                Number(item.card_amount || 0),
              0,
            ),
          },
        })
      } else {
        const payload = {
          date,
          company_id: companyId,
          operator_id: operatorId,
          shift,
          zone: getZone(),
          cash_amount: parseAmount(cash),
          kaspi_amount: parseAmount(kaspi),
          online_amount: showOnline ? parseAmount(online) : 0,
          card_amount: parseAmount(card),
          comment: comment.trim() || null,
          is_virtual: false,
        }

        const submit = async (force: boolean) =>
          fetch('/api/admin/incomes', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              action: 'createIncome',
              payload,
              force,
            }),
          })

        let response = await submit(false)
        let json = await response.json().catch(() => null)

        if (response.status === 409 && json?.duplicate) {
          const d = json.duplicate
          const ok = await confirmDialog({
            title: 'Похожий доход уже есть',
            description:
              `Уже есть доход за ${d.date}, смена "${d.shift === 'day' ? 'день' : 'ночь'}" на ${formatMoney(d.total)}` +
              (d.comment ? ` (${d.comment})` : '') +
              '. Всё равно создать дубль?',
            confirmLabel: 'Создать дубль',
            cancelLabel: 'Отмена',
            destructive: true,
          })
          if (!ok) {
            setSaving(false)
            return
          }
          response = await submit(true)
          json = await response.json().catch(() => null)
        }

        if (!response.ok) {
          if (json?.error === 'forbidden' && json?.capability) {
            throw new Error(`У тебя нет права «${json.capability}». Попроси владельца включить его на странице /access → Руководитель`)
          }
          throw new Error(json?.message || json?.error || 'Не удалось сохранить доход')
        }
        await logIncomeAudit({
          entityId: String(json?.data?.id || `${date}:${companyId}`),
          action: 'create',
          payload: {
            ...payload,
            total_amount:
              Number(payload.cash_amount || 0) +
              Number(payload.kaspi_amount || 0) +
              Number(payload.online_amount || 0) +
              Number(payload.card_amount || 0),
          },
        })
      }

      setShowSuccess(true)
      if (embedded) {
        if (typeof window !== 'undefined' && window.parent && window.parent !== window) {
          window.parent.postMessage({ type: 'income-wizard-created' }, window.location.origin)
        }
        return
      }
      setTimeout(() => router.push('/income'), 800)
    } catch (err: any) {
      await logIncomeAudit({
        entityId: `${date}:${companyId || 'no-company'}`,
        action: 'create-failed',
        payload: {
          message: err?.message || 'Ошибка при сохранении',
          company_id: companyId || null,
          operator_id: operatorId || null,
          shift,
        },
      })
      setError(err?.message || 'Ошибка при сохранении')
      setSaving(false)
    }
  }

  const CompanyCard = ({ c }: { c: Company }) => {
    const active = c.id === companyId
    let Icon = Building2
    let color = 'from-gray-500 to-gray-600'
    
    const kind = pointProfile(c).kind
    if (kind === 'ps_club') {
      Icon = Gamepad2
      color = 'from-blue-500 to-blue-500'
    } else if (kind === 'shop' || kind === 'food') {
      Icon = Store
      color = 'from-orange-500 to-red-500'
    } else if (kind === 'club') {
      Icon = Eye
      color = 'from-blue-500 to-cyan-500'
    }

    return (
      <div
        onClick={() => setCompanyId(c.id)}
        className={`relative cursor-pointer rounded-xl border p-4 flex flex-col items-center justify-center gap-3 transition-all duration-300 ${
          active
            ? `bg-gradient-to-br ${color} border-transparent text-white shadow-lg shadow-purple-500/25 scale-105`
            : 'bg-white dark:bg-gray-800/50 border-slate-200 dark:border-gray-700 text-slate-500 dark:text-gray-400 hover:bg-slate-100 dark:hover:bg-gray-700/50 hover:border-slate-300 dark:hover:border-gray-600'
        }`}
      >
        <div className={`p-3 rounded-xl ${active ? 'bg-white/20' : 'bg-slate-100 dark:bg-gray-700/50'}`}>
          <Icon className={`w-6 h-6 ${active ? 'text-white' : ''}`} />
        </div>
        <span className="text-xs font-semibold text-center">{c.name}</span>
        {active && (
          <div className="absolute -top-2 -right-2 w-6 h-6 bg-green-500 rounded-full flex items-center justify-center shadow-lg">
            <CheckCircle2 className="w-4 h-4 text-white" />
          </div>
        )}
      </div>
    )
  }

  if (showSuccess) {
    return (
      <>
          <div className="text-center">
            <div className="relative mb-6">
              <div className="w-24 h-24 bg-green-500/20 rounded-full flex items-center justify-center mx-auto animate-pulse">
                <CheckCircle2 className="w-12 h-12 text-green-400" />
              </div>
              <Sparkles className="w-6 h-6 text-yellow-400 absolute top-0 right-1/3 animate-bounce" />
            </div>
            <h2 className="text-2xl font-bold text-foreground mb-2">Успешно сохранено!</h2>
            <p className="text-slate-500 dark:text-gray-400">Перенаправляем в журнал доходов...</p>
          </div>
      </>
    )
  }

  return (
    <>
        <div className={embedded ? 'p-4 sm:p-6 max-w-4xl mx-auto' : 'app-page-tight max-w-4xl'}>
          {!embedded && (
            <AdminPageHeader
              title="Новая запись дохода"
              description="AI-помощник внесения выручки"
              icon={<Brain className="h-5 w-5" />}
              accent="emerald"
              onBack={() => guardClose(() => router.push('/income'))}
              className="mb-6"
            />
          )}

          {error && (
            <div className="mb-6 p-4 bg-red-500/10 border border-red-500/30 text-red-400 rounded-xl text-sm flex items-center gap-3">
              <AlertCircle className="w-5 h-5" />
              {error}
            </div>
          )}

          {!error && !validation.ok && (
            <div className="mb-6 p-4 bg-yellow-500/10 border border-yellow-500/30 text-yellow-400 rounded-xl text-sm flex items-center gap-3">
              <Zap className="w-5 h-5" />
              {validation.msg}
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-6">
            {/* 1. Настройки смены */}
            <Card className="p-6 border-0 bg-white dark:bg-gray-800/50 backdrop-blur-sm">
              <div className="flex items-center gap-3 mb-6">
                <div className="p-2 bg-blue-500/20 rounded-xl">
                  <Calendar className="w-5 h-5 text-blue-400" />
                </div>
                <h3 className="text-sm font-semibold text-foreground uppercase tracking-wider">Настройки смены</h3>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                <div>
                  <label className="text-xs text-gray-500 uppercase mb-2 block">Дата</label>
                  <DatePicker value={date} onChange={setDate} className="w-full" />
                </div>

                <div>
                  <label className="text-xs text-gray-500 uppercase mb-2 block">Смена</label>
                  <div className="grid grid-cols-2 bg-white dark:bg-gray-900 p-1 rounded-xl border border-slate-200 dark:border-gray-700">
                    <button
                      type="button"
                      onClick={() => setShift('day')}
                      className={`flex items-center justify-center gap-2 py-3 rounded-lg text-sm font-medium transition-all ${
                        shift === 'day'
                          ? 'bg-gradient-to-r from-amber-500 to-blue-600 text-white shadow-lg'
                          : 'text-slate-500 dark:text-gray-400 hover:text-slate-900 dark:hover:text-white'
                      }`}
                    >
                      <Sun className="w-4 h-4" /> День
                    </button>
                    <button
                      type="button"
                      onClick={() => setShift('night')}
                      className={`flex items-center justify-center gap-2 py-3 rounded-lg text-sm font-medium transition-all ${
                        shift === 'night'
                          ? 'bg-gradient-to-r from-blue-500 to-blue-500 text-white shadow-lg'
                          : 'text-slate-500 dark:text-gray-400 hover:text-slate-900 dark:hover:text-white'
                      }`}
                    >
                      <Moon className="w-4 h-4" /> Ночь
                    </button>
                  </div>
                </div>
              </div>

              {/* Компания */}
              <div className="mt-6">
                <label className="text-xs text-gray-500 uppercase mb-3 block">Точка (Компания)</label>
                {loadingMeta ? (
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
                    {Array.from({ length: 4 }).map((_, i) => (
                      <Skeleton key={i} className="h-16 rounded-xl" />
                    ))}
                  </div>
                ) : (
                  <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
                    {companies.map((c) => (
                      <CompanyCard key={c.id} c={c} />
                    ))}
                  </div>
                )}
              </div>

              {/* Оператор */}
              <div className="mt-6">
                <label className="text-xs text-gray-500 uppercase mb-3 block">Оператор смены</label>
                {loadingMeta ? (
                  <div className="flex flex-wrap gap-2">
                    {Array.from({ length: 4 }).map((_, i) => (
                      <Skeleton key={i} className="h-10 w-32 rounded-xl" />
                    ))}
                  </div>
                ) : operators.length === 0 ? (
                  <p className="text-xs text-yellow-500">Операторов нет. Добавьте их в разделе «Операторы».</p>
                ) : (
                  <div className="flex flex-wrap gap-2">
                    {operators.map((op) => {
                      const active = op.id === operatorId
                      return (
                        <button
                          key={op.id}
                          type="button"
                          onClick={() => setOperatorId(op.id)}
                          className={`px-4 py-2.5 rounded-xl text-sm font-medium border flex items-center gap-2 transition-all ${
                            active
                              ? 'bg-gradient-to-r from-blue-500 to-green-500 text-white border-transparent shadow-lg shadow-green-500/25'
                              : 'bg-white dark:bg-gray-900 border-slate-200 dark:border-gray-700 text-slate-500 dark:text-gray-400 hover:border-slate-300 dark:hover:border-gray-600 hover:text-slate-900 dark:hover:text-white'
                          }`}
                        >
                          <UserCircle2 className="w-4 h-4" />
                          {op.short_name || op.name}
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>
            </Card>

            {/* 2. Суммы */}
            <Card className="p-6 border-0 bg-white dark:bg-gray-800/50 backdrop-blur-sm relative overflow-hidden">
              <div className="absolute -right-20 -top-20 opacity-[0.03] pointer-events-none">
                {isExtra ? <Gamepad2 className="w-64 h-64" /> : <Wallet className="w-64 h-64" />}
              </div>

              <div className="flex items-center gap-3 mb-6">
                <div className={`p-2 rounded-xl ${isExtra ? 'bg-pink-500/20' : 'bg-green-500/20'}`}>
                  {isExtra ? <Gamepad2 className="w-5 h-5 text-pink-400" /> : <Wallet className="w-5 h-5 text-green-400" />}
                </div>
                <div>
                  <h3 className="text-sm font-semibold text-foreground uppercase tracking-wider">
                    {isExtra ? 'Выручка по зонам' : 'Суммы выручки'}
                  </h3>
                  {previewTotal > 0 && (
                    <p className="text-xs text-slate-500 dark:text-gray-400 mt-1">
                      Предварительная сумма: <span className="text-purple-400 font-bold">{formatMoney(previewTotal)}</span>
                    </p>
                  )}
                </div>
              </div>

              {isExtra ? (
                <div className="space-y-6">
                  {zones.map((z, i) => (
                    <div
                      key={z.key}
                      className={`rounded-2xl border p-5 ${
                        i % 2 === 0
                          ? 'border-blue-500/20 bg-gradient-to-br from-blue-50 to-slate-50 dark:from-blue-900/20 dark:to-gray-900/50'
                          : 'border-cyan-500/20 bg-gradient-to-br from-cyan-50 to-slate-50 dark:from-cyan-900/20 dark:to-gray-900/50'
                      }`}
                    >
                      <div className="flex items-center justify-between gap-3 mb-4">
                        <div className="flex items-center gap-3">
                          <div className={`p-2 rounded-xl ${i % 2 === 0 ? 'bg-purple-500/20' : 'bg-cyan-500/20'}`}>
                            <Gamepad2 className={`w-5 h-5 ${i % 2 === 0 ? 'text-purple-400' : 'text-cyan-400'}`} />
                          </div>
                          <span className="font-semibold text-foreground">{z.label}</span>
                        </div>
                        {zoneTotal(z.key) > 0 && <span className="text-sm text-muted-foreground">{formatMoney(zoneTotal(z.key))}</span>}
                      </div>

                      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                        <div className="space-y-2">
                          <label className="text-xs text-slate-500 dark:text-gray-400 flex items-center gap-2">
                            <Wallet className="w-4 h-4 text-amber-400" /> Наличные
                          </label>
                          <div className="relative">
                            <MoneyInput
                              placeholder="0"
                              value={zoneAmounts[z.key]?.cash || ''}
                              onValueChange={(v) => setZoneAmount(z.key, 'cash', v)}
                              className="h-auto w-full text-lg md:text-lg bg-white dark:bg-gray-900 border border-slate-200 dark:border-gray-700 rounded-xl py-4 px-4 text-foreground focus:border-amber-500 focus:ring-2 focus:ring-amber-500/20 outline-none transition-all"
                            />
                            <span className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 text-sm">₸</span>
                          </div>
                        </div>

                        <div className="space-y-2">
                          <label className="text-xs text-slate-500 dark:text-gray-400 flex items-center gap-2">
                            <CreditCard className="w-4 h-4 text-blue-400" /> Безналичный QR / POS
                          </label>
                          <div className="relative">
                            <MoneyInput
                              placeholder="0"
                              value={zoneAmounts[z.key]?.cashless || ''}
                              onValueChange={(v) => setZoneAmount(z.key, 'cashless', v)}
                              className="h-auto w-full text-lg md:text-lg bg-white dark:bg-gray-900 border border-slate-200 dark:border-gray-700 rounded-xl py-4 px-4 text-foreground focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none transition-all"
                            />
                            <span className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 text-sm">₸</span>
                          </div>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                  <div className="space-y-2">
                    <label className="text-xs text-slate-500 dark:text-gray-400 flex items-center gap-2">
                      <Wallet className="w-4 h-4 text-amber-400" /> Наличные (Cash)
                    </label>
                    <div className="relative">
                      <MoneyInput
                        placeholder="0"
                        value={cash}
                        onValueChange={setCash}
                        className="h-auto w-full text-lg md:text-lg bg-white dark:bg-gray-900 border border-slate-200 dark:border-gray-700 rounded-xl py-4 px-4 text-foreground focus:border-amber-500 focus:ring-2 focus:ring-amber-500/20 outline-none transition-all"
                      />
                      <span className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 text-sm">₸</span>
                    </div>
                  </div>

                  <div className="space-y-2">
                    <label className="text-xs text-slate-500 dark:text-gray-400 flex items-center gap-2">
                      <CreditCard className="w-4 h-4 text-blue-400" /> Безналичный POS / переводы
                    </label>
                    <div className="relative">
                      <MoneyInput
                        placeholder="0"
                        value={kaspi}
                        onValueChange={setKaspi}
                        className="h-auto w-full text-lg md:text-lg bg-white dark:bg-gray-900 border border-slate-200 dark:border-gray-700 rounded-xl py-4 px-4 text-foreground focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none transition-all"
                      />
                      <span className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 text-sm">₸</span>
                    </div>
                  </div>

                  {showOnline && (
                    <div className="sm:col-span-2 space-y-2">
                      <label className="text-xs text-slate-500 dark:text-gray-400 flex items-center gap-2">
                        <Smartphone className="w-4 h-4 text-pink-400" /> Безналичный Online (Senet)
                      </label>
                      <div className="relative">
                        <MoneyInput
                          placeholder="0"
                          value={online}
                          onValueChange={setOnline}
                          className="h-auto w-full text-lg md:text-lg bg-white dark:bg-gray-900 border border-slate-200 dark:border-gray-700 rounded-xl py-4 px-4 text-foreground focus:border-pink-500 focus:ring-2 focus:ring-pink-500/20 outline-none transition-all"
                        />
                        <span className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 text-sm">₸</span>
                      </div>
                    </div>
                  )}

                  <div className="sm:col-span-2 space-y-2">
                    <label className="text-xs text-slate-500 dark:text-gray-400 flex items-center gap-2">
                      <CreditCard className="w-4 h-4 text-purple-400" /> Карта (если используется)
                    </label>
                    <div className="relative">
                      <MoneyInput
                        placeholder="0"
                        value={card}
                        onValueChange={setCard}
                        className="h-auto w-full bg-white dark:bg-gray-900 border border-slate-200 dark:border-gray-700 rounded-xl py-3 px-4 text-foreground focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none transition-all"
                      />
                      <span className="absolute right-4 top-1/2 -translate-y-1/2 text-gray-500 text-sm">₸</span>
                    </div>
                  </div>
                </div>
              )}

              {/* Комментарий */}
              <div className="mt-6 space-y-2">
                <label className="text-xs text-gray-500 uppercase">Комментарий (необязательно)</label>
                <textarea
                  rows={3}
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  className="w-full bg-white dark:bg-gray-900 border border-slate-200 dark:border-gray-700 rounded-xl py-3 px-4 text-foreground focus:border-blue-500 focus:ring-2 focus:ring-blue-500/20 outline-none transition-all resize-none"
                  placeholder="Например: предоплата за бронь, акция, скидка..."
                />
              </div>
            </Card>

            {/* Итоговая карточка */}
            {previewTotal > 0 && (
              <Card className="p-4 sm:p-6 border-0 bg-gradient-to-br from-blue-50 via-white to-blue-50 dark:from-blue-900/30 dark:via-gray-900 dark:to-blue-900/30 backdrop-blur-sm">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex items-center gap-3">
                    <div className="p-3 bg-purple-500/20 rounded-xl">
                      <TrendingUp className="w-6 h-6 text-purple-400" />
                    </div>
                    <div>
                      <p className="text-sm text-slate-500 dark:text-gray-400">Итого к сохранению</p>
                      <p className="text-2xl sm:text-3xl font-bold bg-gradient-to-r from-blue-400 to-blue-400 bg-clip-text text-transparent">
                        {formatMoney(previewTotal)}
                      </p>
                    </div>
                  </div>
                  <div className="text-right">
                    <p className="text-xs text-gray-500">{isExtra ? `По зонам: ${zones.filter((z) => zoneTotal(z.key) > 0).map((z) => z.label).join(' + ') || '—'}` : '1 запись'}</p>
                    <p className="text-xs text-slate-500 dark:text-gray-400">{date} • {shift === 'day' ? 'День' : 'Ночь'}</p>
                  </div>
                </div>
              </Card>
            )}

            {/* Кнопки */}
            <div className="flex gap-4 pt-4">
              <Button
                type="button"
                variant="outline"
                onClick={() => guardClose(() => router.push('/income'))}
                className="flex-1 h-14 border-slate-200 dark:border-gray-700 bg-white dark:bg-gray-800/50 hover:bg-slate-100 dark:hover:bg-gray-700 text-slate-700 dark:text-gray-300 rounded-xl"
              >
                Отмена
              </Button>

              {can('income.create') && (
              <Button
                type="submit"
                disabled={saving || !validation.ok}
                className="flex-[2] h-14 bg-gradient-to-r from-blue-600 to-blue-600 hover:from-blue-500 hover:to-blue-500 text-white text-base font-semibold rounded-xl shadow-lg shadow-blue-500/25 disabled:opacity-50"
              >
                {saving ? (
                  <span className="flex items-center gap-2">
                    <div className="w-5 h-5 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    Сохранение...
                  </span>
                ) : (
                  <span className="flex items-center gap-2">
                    <Save className="w-5 h-5" /> 
                    Сохранить доход
                    {previewTotal > 0 && ` (${formatMoney(previewTotal)})`}
                  </span>
                )}
              </Button>
              )}
            </div>
          </form>
        </div>
    </>
  )
}
