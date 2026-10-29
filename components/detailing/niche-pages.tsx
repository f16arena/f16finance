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
  AlertTriangle,
  ArrowRight,
  CalendarDays,
  CarFront,
  Clock,
  LayoutDashboard,
  Plus,
  TrendingDown,
  TrendingUp,
  Wallet,
} from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { Skeleton } from '@/components/skeleton'
import { StoreEmpty, StoreSection, StoreStat, StoreStatGrid, storeCard } from '@/components/store/store-ui'
import { useApiCache } from '@/lib/client/use-api-cache'
import { formatPhone } from '@/lib/core/format'
import { type DetailingCompany, Plate, dayLabel, isoLocal, money, shiftIso } from '@/components/detailing/detailing-shared'

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
type MoneyBooking = {
  id: string
  company_id: string
  start_date: string
  days: number
  status: string
  price_total: number
  materials_total: number
  customer: { id: string; name: string; phone: string | null } | null
  car: { plate: string; model: string | null } | null
  services: Array<{ name: string; price: number }>
  paid: number
  due: number
  master?: string | null
}
type MoneyData = {
  companies: DetailingCompany[]
  empty?: boolean
  from: string
  to: string
  today: string
  payments: Array<{ id: string; paid_on: string; amount: number; method: Method; kind: 'prepayment' | 'payment'; booking: MoneyBooking | null }>
  otherIncome: Array<{ id: string; date: string; comment: string | null; cash: number; kaspi: number; card: number }>
  expenses: Array<{ id: string; date: string; category: string; comment: string | null; cash: number; cashless: number; amount: number }>
  bookings: MoneyBooking[]
  materialsUsed: number
  load: { boxDaysBusy: number; boxDaysTotal: number; boxes: number }
  debts: MoneyBooking[]
  live: Record<'inBox' | 'arriving' | 'handOver' | 'overdue' | 'requests' | 'upcoming', MoneyBooking[]>
}

const METHOD_LABEL: Record<Method, string> = { cash: 'Наличные', kaspi: 'Kaspi', card: 'Карта' }
const METHOD_COLOR: Record<Method, string> = { cash: 'bg-orange-400', kaspi: 'bg-blue-600', card: 'bg-violet-500' }

type Period = 'week' | 'month' | 'prev'

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

function useMoney(period: Period, companyId: string) {
  const range = periodRange(period)
  const params = new URLSearchParams({ from: range.from, to: range.to, today: isoLocal(new Date()) })
  if (companyId) params.set('company_id', companyId)
  const res = useApiCache<MoneyData>(`/api/admin/detailing/money?${params}`, { ttl: 30_000 })
  return { ...res, range }
}

function PeriodTabs(props: { value: Period; onChange: (p: Period) => void; companies?: DetailingCompany[]; companyId: string; onCompany: (id: string) => void }) {
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

function Loading({ header }: { header: ReactNode }) {
  return (
    <div className="space-y-4">
      {header}
      <StoreStatGrid cols={4}>{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-[104px] rounded-2xl" />)}</StoreStatGrid>
      <Skeleton className="h-[320px] w-full rounded-2xl" />
    </div>
  )
}

function Failed({ header, error }: { header: ReactNode; error: string }) {
  return <div className="space-y-4">{header}<div className={`${storeCard} p-6 text-sm text-rose-600`}>{error}</div></div>
}

const carTitle = (b: MoneyBooking | null) => (b ? b.car?.model || b.customer?.name || 'Машина' : 'Машина')
const shortDate = (iso: string) => `${dayLabel(iso).day} ${dayLabel(iso).month.slice(0, 3)}`

function totals(d: MoneyData) {
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

function BookingLine({ b, right }: { b: MoneyBooking; right?: ReactNode }) {
  return (
    <Link href="/detailing" className="flex items-center gap-3 px-4 py-2.5 transition hover:bg-slate-50 dark:hover:bg-white/[0.03]">
      <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-500 dark:bg-white/5"><CarFront className="h-4 w-4" /></span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2 truncate text-sm font-medium">{carTitle(b)} <Plate plate={b.car?.plate} /></span>
        <span className="block truncate text-[11px] text-muted-foreground">{b.services.map((s) => s.name).join(', ')}{b.master ? ` · ${b.master}` : ''}</span>
      </span>
      {right ? <span className="shrink-0 text-right text-sm">{right}</span> : null}
    </Link>
  )
}

/* ── Дашборд ──────────────────────────────────────────────────────────── */

export function DetailingDashboard() {
  const [period, setPeriod] = useState<Period>('month')
  const [companyId, setCompanyId] = useState('')
  const { data, loading, error, range } = useMoney(period, companyId)

  const header = (
    <AdminPageHeader
      title="Дашборд"
      description={`Детейлинг · ${range.label}`}
      icon={<LayoutDashboard className="h-5 w-5" />}
      backHref="/detailing"
      actions={<Link href="/detailing" className="inline-flex items-center gap-1.5 rounded-xl bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700"><CalendarDays className="h-4 w-4" />Запись · неделя</Link>}
      toolbar={<PeriodTabs value={period} onChange={setPeriod} companies={data?.companies} companyId={companyId} onCompany={setCompanyId} />}
    />
  )
  if (loading && !data) return <Loading header={header} />
  if (error && !data) return <Failed header={header} error={error} />
  if (!data || data.empty) return <div className="space-y-4">{header}<div className={storeCard}><StoreEmpty title="Нет точки-детейлинга" hint="Выберите у точки нишу «Детейлинг» в настройках." /></div></div>

  const t = totals(data)
  const { live } = data
  const attention = [
    live.overdue.length ? { tone: 'rose', text: `${live.overdue.length} машин${live.overdue.length === 1 ? 'а' : 'ы'} стоят дольше записи — проверьте, выданы ли` } : null,
    live.requests.length ? { tone: 'amber', text: `${live.requests.length} заявк${live.requests.length === 1 ? 'а' : 'и'} с сайта ждут подтверждения` } : null,
    t.debt > 0 ? { tone: 'rose', text: `Клиенты должны ${money(t.debt)} за выданные машины` } : null,
  ].filter(Boolean) as Array<{ tone: string; text: string }>

  // Выручка по дням периода — столбики.
  const days: string[] = []
  for (let d = data.from; d <= data.to && days.length < 62; d = shiftIso(d, 1)) days.push(d)
  const perDay = new Map<string, number>()
  for (const p of data.payments) perDay.set(p.paid_on, (perDay.get(p.paid_on) || 0) + p.amount)
  for (const r of data.otherIncome) perDay.set(r.date, (perDay.get(r.date) || 0) + r.cash + r.kaspi + r.card)
  const maxDay = Math.max(1, ...days.map((d) => perDay.get(d) || 0))

  return (
    <div className="space-y-4">
      {header}

      <StoreStatGrid cols={4}>
        <StoreStat label="В боксах сейчас" value={`${live.inBox.length}`} hint={`из ${data.load.boxes} боксов`} icon={<CarFront className="h-4 w-4" />} tone="sky" />
        <StoreStat label="Приедут сегодня" value={`${live.arriving.length}`} hint={live.handOver.length ? `выдать сегодня: ${live.handOver.length}` : 'выдач сегодня нет'} icon={<Clock className="h-4 w-4" />} />
        <StoreStat label="Принято денег" value={money(t.income)} hint={`прибыль ${money(t.profit)} после расходов`} icon={<TrendingUp className="h-4 w-4" />} tone="emerald" href="/income" />
        <StoreStat label="Загрузка боксов" value={`${t.load}%`} hint={`${data.load.boxDaysBusy} из ${data.load.boxDaysTotal} бокс-дней`} icon={<CalendarDays className="h-4 w-4" />} />
      </StoreStatGrid>

      {attention.length ? (
        <div className={`${storeCard} divide-y divide-border`}>
          {attention.map((a, i) => (
            <div key={i} className="flex items-center gap-3 px-4 py-2.5 text-sm">
              <AlertTriangle className={`h-4 w-4 shrink-0 ${a.tone === 'rose' ? 'text-rose-500' : 'text-orange-500'}`} />
              {a.text}
            </div>
          ))}
        </div>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-2">
        <StoreSection title="Сейчас в боксах" subtitle={live.inBox.length ? 'нажмите, чтобы открыть неделю' : undefined} flush>
          {live.inBox.length ? (
            <div className="divide-y divide-border">
              {live.inBox.map((b) => <BookingLine key={b.id} b={b} right={b.due > 0 ? <span className="text-muted-foreground">осталось {money(b.due)}</span> : <span className="text-emerald-600">оплачено</span>} />)}
            </div>
          ) : <StoreEmpty title="Боксы свободны" hint="Машины появятся здесь, когда их отметят «В боксе»." />}
        </StoreSection>
        <StoreSection title="Ближайшие записи" flush action={<Link href="/detailing" className="text-xs font-medium text-blue-600 hover:underline">Вся неделя</Link>}>
          {[...live.arriving, ...live.upcoming].length ? (
            <div className="divide-y divide-border">
              {[...live.arriving, ...live.upcoming].slice(0, 8).map((b) => <BookingLine key={b.id} b={b} right={<span className="text-muted-foreground">{b.start_date === data.today ? 'сегодня' : shortDate(b.start_date)}</span>} />)}
            </div>
          ) : <StoreEmpty title="Записей нет" hint="Запишите машину на неделе." />}
        </StoreSection>
      </div>

      <StoreSection title="Выручка по дням" subtitle={`${money(t.income)} за ${range.label}`}>
        <div className="flex h-36 items-end gap-[3px]">
          {days.map((d) => {
            const v = perDay.get(d) || 0
            return (
              <div key={d} className="group relative flex h-full min-w-0 flex-1 flex-col justify-end">
                <div className={`rounded-t ${d === data.today ? 'bg-blue-600' : 'bg-blue-500/40'} ${v ? '' : 'opacity-30'}`} style={{ height: `${Math.max(v ? 4 : 2, (v / maxDay) * 100)}%` }} />
                <span className="pointer-events-none absolute -top-6 left-1/2 hidden -translate-x-1/2 whitespace-nowrap rounded bg-slate-900 px-1.5 py-0.5 text-[10px] text-white group-hover:block">{shortDate(d)}: {money(v)}</span>
              </div>
            )
          })}
        </div>
        <div className="mt-1 flex justify-between text-[10px] text-muted-foreground"><span>{shortDate(data.from)}</span><span>{shortDate(data.to)}</span></div>
      </StoreSection>

      <div className="grid gap-4 lg:grid-cols-3">
        <StoreStat label="Расходы" value={money(t.expense)} hint={`материалы в работах: ${money(data.materialsUsed)}`} icon={<TrendingDown className="h-4 w-4" />} tone="rose" href="/expenses" />
        <StoreStat label="Должны клиенты" value={money(t.debt)} hint={`${data.debts.length} выданных машин без полной оплаты`} icon={<Wallet className="h-4 w-4" />} tone={t.debt > 0 ? 'rose' : 'neutral'} href="/income" />
        <StoreStat label="Средний чек" value={money(t.avg)} hint={`${t.jobs.length} машин в работе и выдано`} icon={<CarFront className="h-4 w-4" />} />
      </div>
    </div>
  )
}

/* ── Доходы ───────────────────────────────────────────────────────────── */

export function DetailingIncome() {
  const [period, setPeriod] = useState<Period>('month')
  const [companyId, setCompanyId] = useState('')
  const [method, setMethod] = useState<Method | 'all'>('all')
  const { data, loading, error, range } = useMoney(period, companyId)

  const header = (
    <AdminPageHeader
      title="Доходы"
      description={`Оплаты за машины · ${range.label}`}
      icon={<TrendingUp className="h-5 w-5" />}
      backHref="/dashboard"
      actions={<Link href="/income/add" className="inline-flex items-center gap-1.5 rounded-xl border border-border px-3 py-2 text-sm font-medium hover:border-slate-400"><Plus className="h-4 w-4" />Прочий доход</Link>}
      toolbar={<PeriodTabs value={period} onChange={setPeriod} companies={data?.companies} companyId={companyId} onCompany={setCompanyId} />}
    />
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
                <BookingLine key={b.id} b={b} right={<><div className="font-semibold text-rose-600 tabular-nums">{money(b.due)}</div><div className="text-[11px] text-muted-foreground">{b.customer?.name}</div></>} />
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
  const { data, loading, error, range } = useMoney(period, companyId)

  const header = (
    <AdminPageHeader
      title="Расходы"
      description={`Детейлинг · ${range.label}`}
      icon={<TrendingDown className="h-5 w-5" />}
      accent="amber"
      backHref="/dashboard"
      actions={<Link href="/expenses/add" className="inline-flex items-center gap-1.5 rounded-xl bg-blue-600 px-3 py-2 text-sm font-semibold text-white hover:bg-blue-700"><Plus className="h-4 w-4" />Расход</Link>}
      toolbar={<PeriodTabs value={period} onChange={setPeriod} companies={data?.companies} companyId={companyId} onCompany={setCompanyId} />}
    />
  )
  if (loading && !data) return <Loading header={header} />
  if (error && !data) return <Failed header={header} error={error} />
  if (!data || data.empty) return <div className="space-y-4">{header}<div className={storeCard}><StoreEmpty title="Нет точки-детейлинга" /></div></div>

  const t = totals(data)
  const cats = new Map<string, number>()
  for (const e of data.expenses) cats.set(e.category, (cats.get(e.category) || 0) + e.amount)
  const catList = [...cats.entries()].sort((a, b) => b[1] - a[1])
  const maxCat = Math.max(1, ...catList.map((c) => c[1]))
  // Закуп материалов — статьи, где в названии есть «материал», «плёнк», «хими».
  const isMaterials = (c: string) => /материал|пл[её]нк|хими|керамик|расходник/i.test(c)
  const bought = data.expenses.filter((e) => isMaterials(e.category)).reduce((s, e) => s + e.amount, 0)
  const list = data.expenses.filter((e) => category === 'all' || e.category === category)

  return (
    <div className="space-y-4">
      {header}
      <StoreStatGrid cols={4}>
        <StoreStat label="Расходы" value={money(t.expense)} hint={`${data.expenses.length} записей в журнале`} tone="rose" icon={<TrendingDown className="h-4 w-4" />} />
        <StoreStat label="Материалы ушли в работы" value={money(data.materialsUsed)} hint="по прайсу и правкам в записях" />
        <StoreStat label="Принято денег" value={money(t.income)} hint="оплаты за машины" tone="emerald" href="/income" />
        <StoreStat label="Прибыль" value={money(t.profit)} hint="принято минус расходы" tone={t.profit < 0 ? 'rose' : 'emerald'} />
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
            action={<Link href="/expenses/add" className="inline-flex items-center gap-1 text-sm font-medium text-blue-600 hover:underline">Добавить расход <ArrowRight className="h-4 w-4" /></Link>}
          />
        )}
      </StoreSection>
      <p className="text-xs text-muted-foreground">Зарплата мастеров (оклад, бонус, процент с цены минус материалы) появится здесь отдельной строкой на следующем этапе.</p>
    </div>
  )
}

/** Обёртка страницы: пока ниша неизвестна — скелетон, потом нужный экран. */
export function NicheSwitch(props: { detailing: ReactNode; fallback: ReactNode }) {
  const { niche, ready } = useOrgNiche()
  if (!ready) return <div className="space-y-4"><Skeleton className="h-[120px] w-full rounded-2xl" /><Skeleton className="h-[320px] w-full rounded-2xl" /></div>
  return <>{niche === 'detailing' ? props.detailing : props.fallback}</>
}
