'use client'

/**
 * Экраны ниши «Детейлинг» на общих адресах: /dashboard, /income, /expenses.
 *
 * Если все действующие точки организации — детейлинг, вместо клубного экрана
 * (смены, операторы, зоны) показываются эти: машины в боксах, оплаты за машины,
 * долги клиентов, материалы. Цифры — /api/admin/detailing/money.
 */

import Link from 'next/link'
import { useMemo, useState, type ReactNode } from 'react'
import {
  ArrowDownToLine,
  ArrowRight,
  ArrowUpFromLine,
  CarFront,
  Plus,
  TrendingDown,
  TrendingUp,
} from 'lucide-react'

import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { Skeleton } from '@/components/skeleton'
import { toast } from '@/components/ui/use-toast'
import { StoreEmpty, StoreSection, StoreStat, StoreStatGrid, plural, storeCard } from '@/components/store/store-ui'
import { useApiCache } from '@/lib/client/use-api-cache'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { formatPhone } from '@/lib/core/format'
import { GarageToday } from '@/components/detailing/garage-today'
import { ExpenseModal, TakePaymentModal } from '@/components/detailing/detailing-actions'
import { type DetailingCompany, DetailingHeader, Plate, WaButton, dayLabel, isoLocal, money, postDetailing, shiftIso, useDetailingCompanyId, useOrgName, waText } from '@/components/detailing/detailing-shared'

/* ── Ниша организации ─────────────────────────────────────────────────── */

/** 'detailing' — все действующие точки организации детейлинги; null — обычный экран. */
export function useOrgNiche(): { niche: 'detailing' | null; ready: boolean } {
  const { data, loading } = useApiCache<{ orgIndustries?: string[] }>('/api/auth/session-role', { ttl: 5 * 60_000 })
  const industries = Array.isArray(data?.orgIndustries) ? data!.orgIndustries! : []
  const niche = industries.length > 0 && industries.every((i) => i === 'detailing') ? 'detailing' : null
  return { niche, ready: !!data || !loading }
}

/* ── Данные ──────────────────────────────────────────────────────────── */

type Method = 'cash' | 'kaspi' | 'card'
export type MoneyBooking = {
  id: string
  company_id: string
  start_date: string
  days: number
  status: string
  price_total: number
  materials_total: number
  customer: { id: string; name: string; phone: string | null } | null
  car: { plate: string; model: string | null } | null
  services: Array<{ name: string; price: number; materials?: number }>
  paid: number
  due: number
  master?: string | null
}
export type MoneyData = {
  companies: DetailingCompany[]
  empty?: boolean
  from: string
  to: string
  today: string
  payments: Array<{ id: string; paid_on: string; amount: number; method: Method; kind: 'prepayment' | 'payment'; booking: MoneyBooking | null }>
  otherIncome: Array<{ id: string; date: string; comment: string | null; cash: number; kaspi: number; card: number }>
  expenses: Array<{ id: string; date: string; category: string; comment: string | null; cash: number; cashless: number; amount: number; payout?: boolean }>
  bookings: MoneyBooking[]
  materialsUsed: number
  load: { boxDaysBusy: number; boxDaysTotal: number; boxes: number }
  debts: MoneyBooking[]
  masters?: { month: string; accrued: number; paid: number; due: number; count: number }
  live: Record<'inBox' | 'arriving' | 'handOver' | 'overdue' | 'requests' | 'upcoming', MoneyBooking[]>
}

export const METHOD_LABEL: Record<Method, string> = { cash: 'Наличные', kaspi: 'Kaspi', card: 'Карта' }
const METHOD_COLOR: Record<Method, string> = { cash: 'bg-orange-400', kaspi: 'bg-blue-600', card: 'bg-violet-500' }

export type Period = 'week' | 'month' | 'prev'

function periodRange(p: Period) {
  const now = new Date()
  const today = isoLocal(now)
  if (p === 'week') {
    const shift = (now.getDay() + 6) % 7
    return { from: shiftIso(today, -shift), to: today, label: 'эта неделя' }
  }
  if (p === 'prev') {
    const first = new Date(now.getFullYear(), now.getMonth() - 1, 1)
    const last = new Date(now.getFullYear(), now.getMonth(), 0)
    return { from: isoLocal(first), to: isoLocal(last), label: 'прошлый месяц' }
  }
  return { from: `${today.slice(0, 7)}-01`, to: today, label: 'этот месяц' }
}

export function useMoney(period: Period, companyId: string) {
  const range = periodRange(period)
  const params = new URLSearchParams({ from: range.from, to: range.to, today: isoLocal(new Date()) })
  if (companyId) params.set('company_id', companyId)
  const res = useApiCache<MoneyData>(`/api/admin/detailing/money?${params}`, { ttl: 30_000 })
  return { ...res, range }
}

export function PeriodTabs(props: { value: Period; onChange: (p: Period) => void; companies?: DetailingCompany[]; companyId: string; onCompany: (id: string) => void }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      {(['week', 'month', 'prev'] as Period[]).map((p) => (
        <Button key={p} size="sm" variant={props.value === p ? 'default' : 'outline'} onClick={() => props.onChange(p)}>
          {p === 'week' ? 'Неделя' : p === 'month' ? 'Месяц' : 'Прошлый месяц'}
        </Button>
      ))}
      {props.companies && props.companies.length > 1 ? (
        <NativeSelect value={props.companyId} onChange={(e) => props.onCompany(e.target.value)} className="h-9 min-w-[160px]" aria-label="Точка">
          <option value="">Все точки</option>
          {props.companies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
        </NativeSelect>
      ) : null}
    </div>
  )
}

export function Loading({ header }: { header: ReactNode }) {
  return (
    <div className="space-y-4">
      {header}
      <StoreStatGrid cols={4}>{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-[104px] rounded-2xl" />)}</StoreStatGrid>
      <Skeleton className="h-[320px] w-full rounded-2xl" />
    </div>
  )
}

export function Failed({ header, error }: { header: ReactNode; error: string }) {
  return <div className="space-y-4">{header}<div className={`${storeCard} p-6 text-sm text-rose-600`}>{error}</div></div>
}

export const carTitle = (b: MoneyBooking | null) => (b ? b.car?.model || b.customer?.name || 'Машина' : 'Машина')
export const shortDate = (iso: string) => `${dayLabel(iso).day} ${dayLabel(iso).month.slice(0, 3)}`

export function totals(d: MoneyData) {
  const by: Record<Method, number> = { cash: 0, kaspi: 0, card: 0 }
  for (const p of d.payments) by[p.method] += p.amount
  for (const r of d.otherIncome) {
    by.cash += r.cash
    by.kaspi += r.kaspi
    by.card += r.card
  }
  const income = by.cash + by.kaspi + by.card
  const prepay = d.payments.filter((p) => p.kind === 'prepayment').reduce((s, p) => s + p.amount, 0)
  const expense = d.expenses.reduce((s, e) => s + e.amount, 0)
  const debt = d.debts.reduce((s, b) => s + b.due, 0)
  const jobs = d.bookings.filter((b) => b.status === 'done' || b.status === 'in_box')
  const avg = jobs.length ? jobs.reduce((s, b) => s + b.price_total, 0) / jobs.length : 0
  const load = d.load.boxDaysTotal ? Math.round((d.load.boxDaysBusy / d.load.boxDaysTotal) * 100) : 0
  return { by, income, prepay, expense, debt, jobs, avg, load, profit: income - expense }
}

/**
 * Сколько осталось владельцу за период — одна формула для «Сегодня» и «Денег»:
 * клиенты заплатили − материалы в работах − начислено мастерам − прочие расходы.
 *
 * Закуп материалов (статьи «Химия», «Плёнка», «Материалы») из прочих расходов
 * вынут: материалы уже посчитаны по работам, дважды их не вычитаем. Выплаты
 * мастерам тоже вынуты — вместо них стоит начисленное.
 */
export const isMaterialsCategory = (c: string) => /материал|пл[её]нк|хими|керамик|расходник/i.test(c)

export function earned(d: MoneyData) {
  const t = totals(d)
  const materials = d.materialsUsed
  const masters = d.masters?.accrued || 0
  const other = d.expenses.filter((e) => !e.payout && !isMaterialsCategory(e.category)).reduce((s, e) => s + e.amount, 0)
  const bought = d.expenses.filter((e) => isMaterialsCategory(e.category)).reduce((s, e) => s + e.amount, 0)
  return { income: t.income, materials, masters, other, bought, left: t.income - materials - masters - other }
}

export function BookingLine({ b, right, wa }: { b: MoneyBooking; right?: ReactNode; wa?: string }) {
  return (
    <div className="flex items-center gap-2 pr-4 transition hover:bg-slate-50 dark:hover:bg-white/[0.03]">
      <Link href="/detailing" className="flex min-w-0 flex-1 items-center gap-3 py-2.5 pl-4">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-500 dark:bg-white/5"><CarFront className="h-4 w-4" /></span>
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2 truncate text-sm font-medium">{carTitle(b)} <Plate plate={b.car?.plate} /></span>
          <span className="block truncate text-[11px] text-muted-foreground">{b.services.map((s) => s.name).join(', ')}{b.master ? ` · ${b.master}` : ''}</span>
        </span>
        {right ? <span className="shrink-0 text-right text-sm">{right}</span> : null}
      </Link>
      {wa ? <WaButton phone={b.customer?.phone} text={wa} /> : null}
    </div>
  )
}

/* ── Сегодня ──────────────────────────────────────────────────────────── */

const WEEKDAY_FULL = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота']

function ActionTile(props: { onClick?: () => void; href?: string; icon: ReactNode; chip: string; title: string; hint: string }) {
  const cls = `${storeCard} flex min-w-0 flex-col items-center gap-1.5 p-3 text-center transition hover:border-blue-400 hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 sm:min-h-[112px] sm:items-start sm:p-4 sm:text-left`
  const body = (
    <>
      <span className={`grid h-10 w-10 place-items-center rounded-xl ${props.chip}`}>{props.icon}</span>
      <b className="text-[13px] font-extrabold leading-tight sm:text-base">{props.title}</b>
      <span className="hidden text-[12.5px] text-muted-foreground sm:block">{props.hint}</span>
    </>
  )
  return props.href ? <Link href={props.href} className={cls}>{body}</Link> : <button type="button" onClick={props.onClick} className={cls}>{body}</button>
}

export function DetailingDashboard() {
  const { can } = useCapabilities()
  const [companyId] = useDetailingCompanyId()
  const orgName = useOrgName()
  const { data, loading, error, refresh } = useMoney('month', '')
  const [modal, setModal] = useState<'pay' | 'spend' | null>(null)
  const [version, setVersion] = useState(0)

  const now = new Date()
  const title = (
    <header>
      <h1 className="font-[family-name:var(--font-manrope)] text-[26px] font-extrabold leading-tight text-[#0b1f3b] dark:text-white">Сегодня</h1>
      <p className="mt-1 text-sm text-muted-foreground">
        {WEEKDAY_FULL[now.getDay()]}, {dayLabel(isoLocal(now)).day} {dayLabel(isoLocal(now)).month}
      </p>
    </header>
  )
  if (loading && !data) return <Loading header={title} />
  if (error && !data) return <Failed header={title} error={error} />
  if (!data || data.empty) return <div className="space-y-4">{title}<div className={storeCard}><StoreEmpty title="Детейлинг ещё не настроен" hint="Обновите страницу через минуту. Если не помогло — напишите в поддержку." /></div></div>

  const t = totals(data)
  const { live, today } = data
  const seeMoney = can('detailing-money.view')
  const canBook = can('detailing-bookings.create')
  const canPay = can('detailing-bookings.take_payment')
  const canSpend = can('expenses.create')

  // Сегодняшние деньги: оплаты за машины, прочие доходы и расходы — одной лентой.
  const feed = [
    ...data.payments.filter((p) => p.paid_on === today).map((p) => ({ key: `p${p.id}`, in: true, what: `${carTitle(p.booking)} — ${p.kind === 'prepayment' ? 'предоплата' : 'оплата'}`, how: METHOD_LABEL[p.method], amount: p.amount })),
    ...data.otherIncome.filter((r) => r.date === today).map((r) => ({ key: `i${r.id}`, in: true, what: r.comment || 'Прочий доход', how: '', amount: r.cash + r.kaspi + r.card })),
    ...data.expenses.filter((e) => e.date === today).map((e) => ({ key: `e${e.id}`, in: false, what: e.comment ? `${e.category}: ${e.comment}` : e.category, how: e.cash > 0 ? 'наличные' : 'безнал', amount: e.amount })),
  ]
  const spends = feed.filter((f) => !f.in)
  const cameIn = feed.filter((f) => f.in).reduce((s, f) => s + f.amount, 0)
  const wentOut = spends.reduce((s, f) => s + f.amount, 0)
  const cashIn =
    data.payments.filter((p) => p.paid_on === today && p.method === 'cash').reduce((s, p) => s + p.amount, 0) +
    data.otherIncome.filter((r) => r.date === today).reduce((s, r) => s + r.cash, 0)
  // «Осталось вам» за месяц — та же формула, что на странице «Деньги».
  const month = earned(data)

  // Кто может заплатить: стоит в боксе, записан, или уехал с долгом.
  const payable = new Map<string, MoneyBooking>()
  for (const b of [...live.inBox, ...live.arriving, ...live.upcoming, ...data.debts]) if (b.due > 0) payable.set(b.id, b)

  const done = async () => {
    setModal(null)
    setVersion((v) => v + 1)
    await refresh()
  }

  const strip = [
    { label: 'Пришло сегодня', value: money(cameIn), hint: cameIn ? `наличные ${money(cashIn)} · безнал ${money(cameIn - cashIn)}` : 'оплат пока не было', tone: '' },
    { label: 'Ушло сегодня', value: money(wentOut), hint: wentOut ? `${spends.length} ${plural(spends.length, ['расход', 'расхода', 'расходов'])}` : 'расходов не было', tone: '' },
    seeMoney
      ? { label: 'Осталось за день', value: money(cameIn - wentOut), hint: `за месяц вам осталось ${money(month.left)}`, tone: cameIn - wentOut < 0 ? 'text-rose-300' : 'text-emerald-300' }
      : { label: 'Машин сегодня', value: `${live.inBox.length + live.arriving.length}`, hint: `выдать ${live.handOver.length} · приедет ${live.arriving.length}`, tone: '' },
    { label: 'Ещё должны', value: money(t.debt), hint: t.debt ? `${data.debts.length} ${plural(data.debts.length, ['машина выдана', 'машины выданы', 'машин выдано'])} в долг` : 'долгов нет', tone: t.debt ? 'text-rose-300' : '' },
  ]

  const calm = !(live.overdue.length || live.requests.length || live.arriving.length || live.handOver.length || data.debts.length || live.upcoming.length)

  return (
    <div className="space-y-4">
      {title}

      <div className="grid grid-cols-3 gap-2 sm:gap-3">
        {canBook ? <ActionTile href="/detailing?new=1" icon={<Plus className="h-5 w-5" />} chip="bg-blue-600 text-white" title="Записать машину" hint="Телефон, номер, услуга — и всё" /> : null}
        {canPay ? <ActionTile onClick={() => setModal('pay')} icon={<ArrowDownToLine className="h-5 w-5" />} chip="bg-emerald-500/15 text-emerald-700 dark:text-emerald-300" title="Принять деньги" hint="Выберите машину — сумма подставится" /> : null}
        {canSpend ? <ActionTile onClick={() => setModal('spend')} icon={<ArrowUpFromLine className="h-5 w-5" />} chip="bg-rose-500/15 text-rose-700 dark:text-rose-300" title="Записать расход" hint="Сумма и на что. Больше ничего" /> : null}
      </div>

      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-white/10 lg:grid-cols-4">
        {strip.map((s) => (
          <div key={s.label} className="bg-[#0b1f3b] px-4 py-4 text-white dark:bg-[#030b18]">
            <div className="text-[11px] font-bold uppercase tracking-wider text-slate-400">{s.label}</div>
            <div className={`mt-0.5 font-[family-name:var(--font-manrope)] text-2xl font-extrabold tabular-nums ${s.tone}`}>{s.value}</div>
            <div className="truncate text-xs text-slate-400">{s.hint}</div>
          </div>
        ))}
      </div>

      <GarageToday key={version} onChanged={refresh} />

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
        <StoreSection title="На что обратить внимание" flush action={<Link href="/detailing" className="text-xs font-medium text-blue-600 hover:underline">Вся неделя</Link>}>
          {calm ? <StoreEmpty title="Всё спокойно" hint="Выдавать сегодня некого, долгов и заявок нет." /> : (
            <div className="divide-y divide-border">
              {live.overdue.map((b) => <BookingLine key={`o${b.id}`} b={b} right={<span className="font-medium text-rose-600">стоит дольше записи</span>} />)}
              {live.requests.map((b) => <BookingLine key={`r${b.id}`} b={b} right={<span className="font-medium text-blue-600">заявка — подтвердить</span>} />)}
              {live.arriving.map((b) => <BookingLine key={`a${b.id}`} b={b} wa={waText.confirm(b, orgName)} right={<span className="font-medium text-blue-600">приедет сегодня</span>} />)}
              {live.handOver.map((b) => <BookingLine key={`h${b.id}`} b={b} wa={waText.ready(b, orgName)} right={b.due > 0 ? <span>выдать, взять <b className="text-rose-600">{money(b.due)}</b></span> : <span className="text-emerald-600">выдать, оплачено</span>} />)}
              {data.debts.slice(0, 5).map((b) => <BookingLine key={`d${b.id}`} b={b} wa={waText.debt(b, orgName)} right={<span>должен <b className="text-rose-600">{money(b.due)}</b></span>} />)}
              {live.upcoming.slice(0, 4).map((b) => <BookingLine key={`u${b.id}`} b={b} right={<span className="text-muted-foreground">{shortDate(b.start_date)}</span>} />)}
            </div>
          )}
        </StoreSection>

        <StoreSection title="Деньги за сегодня" subtitle="всё, что записали" flush>
          {feed.length ? (
            <div className="divide-y divide-border">
              {feed.map((f) => (
                <div key={f.key} className="flex items-center gap-3 px-4 py-2.5">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{f.what}</span>
                    {f.how ? <span className="block text-[11px] text-muted-foreground">{f.how}</span> : null}
                  </span>
                  <b className={`shrink-0 tabular-nums ${f.in ? 'text-emerald-600' : 'text-rose-600'}`}>{f.in ? '+' : '−'}{money(f.amount)}</b>
                </div>
              ))}
            </div>
          ) : <StoreEmpty title="Сегодня денег ещё не было" hint="Оплата и расход появятся здесь сразу, как их запишут." />}
        </StoreSection>
      </div>

      <RemindersCard orgName={orgName} />

      {modal === 'pay' ? <TakePaymentModal cars={[...payable.values()]} onClose={() => setModal(null)} onSaved={done} /> : null}
      {modal === 'spend' ? <ExpenseModal companyId={companyId || data.companies[0]?.id || ''} onClose={() => setModal(null)} onSaved={done} /> : null}
    </div>
  )
}

/* ── Пора напомнить ───────────────────────────────────────────────────── */

type Reminder = { id: string; service: string; months: number; done: string; due: string; overdue: boolean; customer: { id: string; name: string; phone: string | null } | null; car: { plate: string; model: string | null } | null }

function RemindersCard({ orgName }: { orgName: string }) {
  const [companyId] = useDetailingCompanyId()
  const url = `/api/admin/detailing/clients?reminders=1${companyId ? `&company_id=${companyId}` : ''}`
  const { data, refresh } = useApiCache<{ reminders: Reminder[] }>(url, { ttl: 60_000 })
  const list = data?.reminders || []
  // Блок появляется, только когда есть кому писать.
  if (!list.length) return null

  const done = async (id: string) => {
    try {
      await postDetailing('/api/admin/detailing/clients', { action: 'reminder.done', id, company_id: companyId || undefined })
      await refresh()
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
    }
  }

  return (
    <StoreSection title="Пора напомнить" subtitle="давно делали — самое время позвать снова" flush>
      <div className="divide-y divide-border">
        {list.map((r) => (
          <div key={r.id} className="flex flex-wrap items-center gap-3 px-4 py-2.5">
            <span className="min-w-0 flex-1">
              <span className="flex items-center gap-2 truncate text-sm font-medium">{r.customer?.name || 'Клиент'} · {r.car?.model || 'машина'} <Plate plate={r.car?.plate} /></span>
              <span className="block truncate text-[11px] text-muted-foreground">{r.service} · делали {shortDate(r.done)} {r.done.slice(0, 4)}</span>
            </span>
            <WaButton phone={r.customer?.phone} text={waText.remind({ customer: r.customer, car: r.car, service: r.service, months: r.months }, orgName)} label="Написать" />
            <button type="button" onClick={() => done(r.id)} className="h-8 rounded-lg border border-border px-2.5 text-xs font-semibold hover:border-slate-400">Написал</button>
          </div>
        ))}
      </div>
    </StoreSection>
  )
}

/* ── Доходы ───────────────────────────────────────────────────────────── */

export function DetailingIncome() {
  const [period, setPeriod] = useState<Period>('month')
  const [companyId, setCompanyId] = useState('')
  const [method, setMethod] = useState<Method | 'all'>('all')
  const orgName = useOrgName()
  const { data, loading, error, range } = useMoney(period, companyId)

  const header = (
    <DetailingHeader
      title="Оплаты"
      subtitle={<><Link href="/detailing/money" className="font-medium text-blue-600 hover:underline">← Деньги</Link> · оплаты за машины, {range.label}</>}
    >
      <PeriodTabs value={period} onChange={setPeriod} companies={data?.companies} companyId={companyId} onCompany={setCompanyId} />
    </DetailingHeader>
  )
  const rows = useMemo(() => {
    if (!data || data.empty) return []
    const list = [
      ...data.payments.map((p) => ({ key: p.id, date: p.paid_on, amount: p.amount, method: p.method, kind: p.kind as string, booking: p.booking, comment: null as string | null })),
      ...data.otherIncome.flatMap((r) =>
        (['cash', 'kaspi', 'card'] as Method[])
          .filter((m) => r[m] > 0)
          .map((m) => ({ key: `${r.id}-${m}`, date: r.date, amount: r[m], method: m, kind: 'other', booking: null, comment: r.comment })),
      ),
    ]
    return list.filter((r) => method === 'all' || r.method === method).sort((a, b) => b.date.localeCompare(a.date) || b.amount - a.amount)
  }, [data, method])

  if (loading && !data) return <Loading header={header} />
  if (error && !data) return <Failed header={header} error={error} />
  if (!data || data.empty) return <div className="space-y-4">{header}<div className={storeCard}><StoreEmpty title="Нет точки-детейлинга" /></div></div>

  const t = totals(data)
  const bySvc = new Map<string, number>()
  for (const b of data.bookings) if (b.status !== 'no_show') for (const s of b.services) bySvc.set(s.name, (bySvc.get(s.name) || 0) + s.price)
  const svc = [...bySvc.entries()].sort((a, b) => b[1] - a[1])
  const maxSvc = Math.max(1, ...svc.map((s) => s[1]))

  return (
    <div className="space-y-4">
      {header}
      <StoreStatGrid cols={4}>
        <StoreStat label="Принято" value={money(t.income)} hint="все оплаты, вместе с предоплатами" tone="emerald" icon={<TrendingUp className="h-4 w-4" />} />
        <StoreStat label="Из них предоплаты" value={money(t.prepay)} hint="за машины до выдачи" />
        <StoreStat label="Должны клиенты" value={money(t.debt)} hint={`${data.debts.length} машин выданы без полной оплаты`} tone={t.debt > 0 ? 'rose' : 'neutral'} />
        <StoreStat label="Средний чек" value={money(t.avg)} hint={`${t.jobs.length} машин`} />
      </StoreStatGrid>

      <StoreSection title="Как платили" subtitle="Оплата попадает сюда сама, когда её принимают в карточке машины" flush>
        <div className="space-y-3 px-4 py-3">
          <div className="flex h-3 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5">
            {(['cash', 'kaspi', 'card'] as Method[]).map((m) => <span key={m} className={METHOD_COLOR[m]} style={{ width: `${t.income ? (t.by[m] / t.income) * 100 : 0}%` }} />)}
          </div>
          <div className="flex flex-wrap gap-2">
            <Button size="xs" variant={method === 'all' ? 'default' : 'outline'} onClick={() => setMethod('all')}>Все</Button>
            {(['cash', 'kaspi', 'card'] as Method[]).map((m) => (
              <Button key={m} size="xs" variant={method === m ? 'default' : 'outline'} onClick={() => setMethod(m)}>
                <span className={`h-2 w-2 rounded-full ${METHOD_COLOR[m]}`} />{METHOD_LABEL[m]} · {money(t.by[m])}
              </Button>
            ))}
          </div>
        </div>
        {rows.length ? (
          <div className="overflow-x-auto border-t border-border">
            <table className="w-full min-w-[820px] text-sm">
              <thead>
                <tr className="border-b border-border text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                  <th className="px-4 py-2 font-medium">День</th>
                  <th className="px-2 py-2 font-medium">Машина</th>
                  <th className="px-2 py-2 font-medium">Клиент</th>
                  <th className="px-2 py-2 font-medium">За что</th>
                  <th className="px-2 py-2 font-medium">Мастер</th>
                  <th className="px-2 py-2 font-medium">Вид</th>
                  <th className="px-2 py-2 font-medium">Способ</th>
                  <th className="px-4 py-2 text-right font-medium">Сумма</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.key} className="border-b border-border last:border-0">
                    <td className="whitespace-nowrap px-4 py-2.5 text-muted-foreground">{shortDate(r.date)}</td>
                    {r.booking ? (
                      <>
                        <td className="px-2 py-2.5"><div className="font-medium">{carTitle(r.booking)}</div><Plate plate={r.booking.car?.plate} /></td>
                        <td className="px-2 py-2.5">{r.booking.customer?.name}<div className="text-xs text-muted-foreground">{formatPhone(r.booking.customer?.phone || null)}</div></td>
                        <td className="px-2 py-2.5 text-muted-foreground">{r.booking.services.map((s) => s.name).join(', ')}</td>
                        <td className="px-2 py-2.5">{r.booking.master || '—'}</td>
                      </>
                    ) : (
                      <td className="px-2 py-2.5 text-muted-foreground" colSpan={4}>{r.comment || 'Прочий доход'}</td>
                    )}
                    <td className="px-2 py-2.5">
                      <span className={`rounded-md px-2 py-0.5 text-xs font-medium ${r.kind === 'prepayment' ? 'bg-blue-500/10 text-blue-700 dark:text-blue-200' : r.kind === 'other' ? 'bg-slate-100 text-slate-600 dark:bg-white/5 dark:text-slate-300' : 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-200'}`}>
                        {r.kind === 'prepayment' ? 'предоплата' : r.kind === 'other' ? 'прочее' : 'оплата'}
                      </span>
                    </td>
                    <td className="px-2 py-2.5">{METHOD_LABEL[r.method]}</td>
                    <td className="px-4 py-2.5 text-right font-semibold tabular-nums">{money(r.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <StoreEmpty title="Оплат за этот период нет" hint="Примите оплату в карточке машины на неделе." />}
      </StoreSection>

      <div className="grid gap-4 lg:grid-cols-2">
        <StoreSection title="Выручка по услугам" subtitle="по записям периода">
          {svc.length ? (
            <div className="space-y-2">
              {svc.map(([name, sum]) => (
                <div key={name} className="grid grid-cols-[minmax(0,140px)_1fr_auto] items-center gap-3 text-sm">
                  <span className="truncate">{name}</span>
                  <span className="h-2.5 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5"><span className="block h-full rounded-full bg-blue-600" style={{ width: `${(sum / maxSvc) * 100}%` }} /></span>
                  <span className="tabular-nums text-muted-foreground">{money(sum)}</span>
                </div>
              ))}
            </div>
          ) : <p className="text-sm text-muted-foreground">Записей в периоде нет.</p>}
        </StoreSection>
        <StoreSection title="Кто сколько должен" subtitle="выданы без полной оплаты" flush>
          {data.debts.length ? (
            <div className="divide-y divide-border">
              {data.debts.map((b) => (
                <BookingLine key={b.id} b={b} wa={waText.debt(b, orgName)} right={<><div className="font-semibold text-rose-600 tabular-nums">{money(b.due)}</div><div className="text-[11px] text-muted-foreground">{b.customer?.name}</div></>} />
              ))}
            </div>
          ) : <StoreEmpty title="Долгов нет" hint="Все выданные машины оплачены полностью." />}
        </StoreSection>
      </div>
    </div>
  )
}

/* ── Расходы ──────────────────────────────────────────────────────────── */

export function DetailingExpenses() {
  const [period, setPeriod] = useState<Period>('month')
  const [companyId, setCompanyId] = useState('')
  const [category, setCategory] = useState<string>('all')
  const [adding, setAdding] = useState(false)
  const [savedCompanyId] = useDetailingCompanyId()
  const { data, loading, error, range, refresh } = useMoney(period, companyId)

  const header = (
    <DetailingHeader
      title="Расходы"
      subtitle={<><Link href="/detailing/money" className="font-medium text-blue-600 hover:underline">← Деньги</Link> · {range.label}</>}
    >
      <PeriodTabs value={period} onChange={setPeriod} companies={data?.companies} companyId={companyId} onCompany={setCompanyId} />
      <Button size="sm" onClick={() => setAdding(true)}><Plus className="h-4 w-4" />Расход</Button>
    </DetailingHeader>
  )
  if (loading && !data) return <Loading header={header} />
  if (error && !data) return <Failed header={header} error={error} />
  if (!data || data.empty) return <div className="space-y-4">{header}<div className={storeCard}><StoreEmpty title="Нет точки-детейлинга" /></div></div>

  const t = totals(data)
  const cats = new Map<string, number>()
  for (const e of data.expenses) cats.set(e.category, (cats.get(e.category) || 0) + e.amount)
  const catList = [...cats.entries()].sort((a, b) => b[1] - a[1])
  const maxCat = Math.max(1, ...catList.map((c) => c[1]))
  const bought = earned(data).bought
  const list = data.expenses.filter((e) => category === 'all' || e.category === category)

  return (
    <div className="space-y-4">
      {header}
      <StoreStatGrid cols={4}>
        <StoreStat label="Расходы" value={money(t.expense)} hint={`${data.expenses.length} записей в журнале`} tone="rose" icon={<TrendingDown className="h-4 w-4" />} />
        <StoreStat label="Материалы ушли в работы" value={money(data.materialsUsed)} hint="по прайсу и правкам в записях" />
        <StoreStat label="Принято денег" value={money(t.income)} hint="оплаты за машины" tone="emerald" href="/income" />
        <StoreStat label="Выплачено мастерам" value={money(data.expenses.filter((e) => e.payout).reduce((s, e) => s + e.amount, 0))} hint="сколько осталось вам — в «Деньгах»" href="/detailing/money" />
      </StoreStatGrid>

      <div className="grid gap-4 lg:grid-cols-2">
        <StoreSection title="Материалы: купили и потратили">
          <div className="space-y-2 text-sm">
            <div className="flex justify-between"><span>Куплено за период</span><b className="tabular-nums">{money(bought)}</b></div>
            <div className="flex justify-between"><span>Ушло в работы по записям</span><b className="tabular-nums">{money(data.materialsUsed)}</b></div>
            <div className="h-2.5 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5"><div className="h-full rounded-full bg-blue-600" style={{ width: `${bought ? Math.min(100, (data.materialsUsed / bought) * 100) : 0}%` }} /></div>
            <div className="flex justify-between"><span>Должно остаться на полке</span><b className="tabular-nums">{money(Math.max(0, bought - data.materialsUsed))}</b></div>
            <p className="text-xs text-muted-foreground">
              Закуп — расходы со статьёй «Материалы», «Плёнка», «Химия». Это те же материалы, что вычитаются перед процентом мастера. Если на полке заметно меньше — где-то перерасход.
            </p>
          </div>
        </StoreSection>
        <StoreSection title="По статьям">
          {catList.length ? (
            <div className="space-y-2">
              {catList.map(([name, sum]) => (
                <div key={name} className="grid grid-cols-[minmax(0,140px)_1fr_auto] items-center gap-3 text-sm">
                  <span className="truncate">{name}</span>
                  <span className="h-2.5 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5"><span className="block h-full rounded-full bg-rose-500/70" style={{ width: `${(sum / maxCat) * 100}%` }} /></span>
                  <span className="tabular-nums text-muted-foreground">{money(sum)}</span>
                </div>
              ))}
            </div>
          ) : <p className="text-sm text-muted-foreground">Расходов в периоде нет.</p>}
        </StoreSection>
      </div>

      <StoreSection
        title="Журнал расходов"
        flush
        action={catList.length > 1 ? (
          <NativeSelect value={category} onChange={(e) => setCategory(e.target.value)} className="h-8" aria-label="Статья">
            <option value="all">Все статьи</option>
            {catList.map(([c]) => <option key={c} value={c}>{c}</option>)}
          </NativeSelect>
        ) : null}
      >
        {list.length ? (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[620px] text-sm">
              <thead>
                <tr className="border-b border-border text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                  <th className="px-4 py-2 font-medium">День</th>
                  <th className="px-2 py-2 font-medium">Статья</th>
                  <th className="px-2 py-2 font-medium">Что купили / за что</th>
                  <th className="px-2 py-2 text-right font-medium">Наличные</th>
                  <th className="px-2 py-2 text-right font-medium">Безнал</th>
                  <th className="px-4 py-2 text-right font-medium">Сумма</th>
                </tr>
              </thead>
              <tbody>
                {list.map((e) => (
                  <tr key={e.id} className="border-b border-border last:border-0">
                    <td className="whitespace-nowrap px-4 py-2.5 text-muted-foreground">{shortDate(e.date)}</td>
                    <td className="px-2 py-2.5"><span className="rounded-md bg-slate-100 px-2 py-0.5 text-xs dark:bg-white/5">{e.category}</span></td>
                    <td className="px-2 py-2.5">{e.comment || '—'}</td>
                    <td className="px-2 py-2.5 text-right tabular-nums text-muted-foreground">{e.cash ? money(e.cash) : '—'}</td>
                    <td className="px-2 py-2.5 text-right tabular-nums text-muted-foreground">{e.cashless ? money(e.cashless) : '—'}</td>
                    <td className="px-4 py-2.5 text-right font-semibold tabular-nums">{money(e.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <StoreEmpty
            title="Расходов нет"
            hint="Внесите закуп плёнки и химии со статьёй «Материалы», аренду, рекламу."
            action={<button type="button" onClick={() => setAdding(true)} className="inline-flex items-center gap-1 text-sm font-medium text-blue-600 hover:underline">Добавить расход <ArrowRight className="h-4 w-4" /></button>}
          />
        )}
      </StoreSection>
      <p className="text-xs text-muted-foreground">Выплаты мастерам попадают сюда сами — статьёй «Зарплата», когда вы платите в разделе «Мастера».</p>
      {adding ? <ExpenseModal companyId={companyId || savedCompanyId || data.companies[0]?.id || ''} onClose={() => setAdding(false)} onSaved={async () => { setAdding(false); await refresh() }} /> : null}
    </div>
  )
}

/** Обёртка страницы: пока ниша неизвестна — скелетон, потом нужный экран. */
export function NicheSwitch(props: { detailing: ReactNode; fallback: ReactNode }) {
  const { niche, ready } = useOrgNiche()
  if (!ready) return <div className="space-y-4"><Skeleton className="h-[120px] w-full rounded-2xl" /><Skeleton className="h-[320px] w-full rounded-2xl" /></div>
  return <>{niche === 'detailing' ? props.detailing : props.fallback}</>
}
