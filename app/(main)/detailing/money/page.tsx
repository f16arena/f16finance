'use client'

/**
 * Деньги детейлинга: «сколько заработали» лесенкой за месяц.
 * Клиенты заплатили − материалы − мастерам − расходы = осталось вам.
 * Цифры и формула — из niche-pages (earned), те же, что на «Сегодня».
 */

import Link from 'next/link'
import { useState } from 'react'
import { ArrowRight, Plus } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { StoreEmpty, StoreSection, plural, storeCard } from '@/components/store/store-ui'
import { ExpenseModal } from '@/components/detailing/detailing-actions'
import { money, useDetailingCompanyId } from '@/components/detailing/detailing-shared'
import { Failed, Loading, METHOD_LABEL, type Period, carTitle, earned, shortDate, totals, useMoney } from '@/components/detailing/niche-pages'

const MONTHS = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']

function Step(props: { title: string; hint: string; amount: number; minus?: boolean; from: number; width: number; bar: string; total?: boolean; href?: string }) {
  // Ноль — не убыток и не прибыль: без цвета.
  const amountCls = !props.amount ? 'text-muted-foreground' : props.total ? (props.amount < 0 ? 'text-rose-600' : 'text-emerald-600') : props.minus ? 'text-rose-600' : ''
  return (
    <div className={`grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1.5 py-2.5 md:grid-cols-[minmax(0,1fr)_150px_minmax(0,1.6fr)] ${props.total ? 'mt-1.5 border-t-2 border-[#0b1f3b] pt-3.5 dark:border-white/60' : ''}`}>
      <div className="min-w-0">
        <div className="font-bold">{props.title}</div>
        <div className="truncate text-[12.5px] text-muted-foreground">
          {props.hint}
          {props.href ? <> · <Link href={props.href} className="font-medium text-blue-600 hover:underline">открыть</Link></> : null}
        </div>
      </div>
      <b className={`text-right tabular-nums ${props.total ? 'font-[family-name:var(--font-manrope)] text-[22px]' : ''} ${amountCls}`}>
        {props.minus && props.amount > 0 ? '− ' : ''}{money(props.amount)}
      </b>
      <div className="relative col-span-2 h-[22px] overflow-hidden rounded-md bg-slate-100 md:col-span-1 dark:bg-white/5" aria-hidden>
        <span className={`absolute inset-y-0 rounded-md ${props.bar}`} style={{ left: `${props.from}%`, width: `${props.width}%` }} />
      </div>
    </div>
  )
}

export default function DetailingMoneyPage() {
  const [period, setPeriod] = useState<Period>('month')
  const [adding, setAdding] = useState(false)
  const [companyId] = useDetailingCompanyId()
  const { data, loading, error, range, refresh } = useMoney(period, '')

  const header = (
    <header className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="font-[family-name:var(--font-manrope)] text-[26px] font-extrabold leading-tight text-[#0b1f3b] dark:text-white">Деньги</h1>
        <p className="mt-1 text-sm text-muted-foreground">Оплаты из записей попадают сюда сами.</p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <div className="flex rounded-xl border border-border bg-white p-[3px] dark:bg-slate-900/60">
          {(['month', 'prev'] as Period[]).map((p) => (
            <button
              key={p}
              type="button"
              onClick={() => setPeriod(p)}
              aria-pressed={period === p}
              className={`rounded-[9px] px-3 py-1.5 text-[13px] font-bold transition ${period === p ? 'bg-[#0b1f3b] text-white dark:bg-white dark:text-[#0b1f3b]' : 'text-muted-foreground hover:text-foreground'}`}
            >
              {p === 'month' ? 'Этот месяц' : 'Прошлый'}
            </button>
          ))}
        </div>
        <Button size="sm" onClick={() => setAdding(true)}><Plus className="h-4 w-4" />Расход</Button>
      </div>
    </header>
  )
  if (loading && !data) return <Loading header={header} />
  if (error && !data) return <Failed header={header} error={error} />
  if (!data || data.empty) return <div className="space-y-4">{header}<div className={storeCard}><StoreEmpty title="Детейлинг ещё не настроен" /></div></div>

  const t = totals(data)
  const e = earned(data)
  const monthName = MONTHS[Number(data.from.slice(5, 7)) - 1]
  // Лесенка: каждая ступень отъедает кусок справа от того, что осталось.
  const pct = (v: number) => (e.income > 0 ? Math.min(100, Math.max(0, (v / e.income) * 100)) : 0)
  const afterMaterials = e.income - e.materials
  const afterMasters = afterMaterials - e.masters
  const cars = t.jobs.length

  // Последние движения: оплаты и расходы вперемешку, свежие сверху.
  const moves = [
    ...data.payments.map((p) => ({ key: `p${p.id}`, date: p.paid_on, what: carTitle(p.booking), how: METHOD_LABEL[p.method], amount: p.amount, in: true })),
    ...data.otherIncome.map((r) => ({ key: `i${r.id}`, date: r.date, what: r.comment || 'Прочий доход', how: '', amount: r.cash + r.kaspi + r.card, in: true })),
    ...data.expenses.map((x) => ({ key: `e${x.id}`, date: x.date, what: x.comment ? `${x.category}: ${x.comment}` : x.category, how: x.cash > 0 ? 'Наличные' : 'Безнал', amount: x.amount, in: false })),
  ].sort((a, b) => b.date.localeCompare(a.date)).slice(0, 10)

  // Что выгоднее: сколько остаётся с услуги после материалов.
  const bySvc = new Map<string, { price: number; materials: number }>()
  for (const b of data.bookings) {
    if (b.status === 'no_show') continue
    for (const s of b.services) {
      const row = bySvc.get(s.name) || { price: 0, materials: 0 }
      row.price += s.price
      row.materials += s.materials || 0
      bySvc.set(s.name, row)
    }
  }
  const margins = [...bySvc.entries()]
    .filter(([, v]) => v.price > 0)
    .map(([name, v]) => ({ name, margin: Math.round(((v.price - v.materials) / v.price) * 100), price: v.price }))
    .sort((a, b) => b.margin - a.margin)

  return (
    <div className="space-y-4">
      {header}

      <StoreSection
        title={`Сколько заработали · ${monthName}`}
        subtitle={cars ? `${cars} ${plural(cars, ['машина', 'машины', 'машин'])} · средний чек ${money(t.avg)}` : `${range.label}: машин пока не было`}
      >
        <Step title="Клиенты заплатили" hint={`все оплаты, вместе с предоплатами${t.debt > 0 ? ` · ещё должны ${money(t.debt)}` : ''}`} amount={e.income} from={0} width={e.income > 0 ? 100 : 0} bar="bg-[#0b1f3b] dark:bg-slate-200" href="/income" />
        <Step title="Материалы" hint="плёнка, керамика, химия — по записям" amount={e.materials} minus from={pct(afterMaterials)} width={pct(e.materials)} bar="bg-blue-600" />
        <Step title="Мастерам" hint={data.masters?.count ? `начислено: оклад, бонус, процент · выплачено ${money(data.masters.paid)}` : 'мастеров пока нет'} amount={e.masters} minus from={pct(afterMasters)} width={pct(e.masters)} bar="bg-blue-600" href="/detailing/masters" />
        <Step title="Расходы" hint="аренда, свет, реклама и прочее" amount={e.other} minus from={pct(e.left)} width={pct(e.other)} bar="bg-rose-500" href="/expenses" />
        <Step title="Осталось вам" hint={e.left < 0 ? 'расходов больше, чем пришло денег' : 'после материалов, мастеров и расходов'} amount={e.left} total from={0} width={pct(e.left)} bar="bg-emerald-500" />
        <p className="mt-3 text-xs text-muted-foreground">
          Закуп материалов ({money(e.bought)} за период) в «Расходы» здесь не входит: материалы уже вычтены по каждой машине, дважды не считаем.
        </p>
      </StoreSection>

      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
        <StoreSection
          title="Последние движения"
          flush
          action={
            <span className="flex gap-3 text-xs font-medium">
              <Link href="/income" className="text-blue-600 hover:underline">Все оплаты</Link>
              <Link href="/expenses" className="text-blue-600 hover:underline">Все расходы</Link>
            </span>
          }
        >
          {moves.length ? (
            <div className="divide-y divide-border">
              {moves.map((m) => (
                <div key={m.key} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                  <span className="w-14 shrink-0 text-xs text-muted-foreground">{m.date === data.today ? 'сегодня' : shortDate(m.date)}</span>
                  <span className="min-w-0 flex-1 truncate font-medium">{m.what}</span>
                  <span className="hidden shrink-0 text-xs text-muted-foreground sm:block">{m.how}</span>
                  <b className={`shrink-0 tabular-nums ${m.in ? 'text-emerald-600' : 'text-rose-600'}`}>{m.in ? '+' : '−'}{money(m.amount)}</b>
                </div>
              ))}
            </div>
          ) : (
            <StoreEmpty
              title="Движений нет"
              hint="Примите оплату за машину или запишите расход."
              action={<button type="button" onClick={() => setAdding(true)} className="inline-flex items-center gap-1 text-sm font-medium text-blue-600 hover:underline">Записать расход <ArrowRight className="h-4 w-4" /></button>}
            />
          )}
        </StoreSection>

        <StoreSection title="Что выгоднее" subtitle="остаётся с услуги после материалов">
          {margins.length ? (
            <div className="space-y-2">
              {margins.map((m) => (
                <div key={m.name} className="grid grid-cols-[minmax(0,1fr)_70px_40px] items-center gap-2.5 text-sm">
                  <span className="truncate">{m.name}</span>
                  <span className="h-1.5 overflow-hidden rounded-full bg-slate-100 dark:bg-white/5"><span className="block h-full rounded-full bg-[#0b1f3b] dark:bg-slate-200" style={{ width: `${m.margin}%` }} /></span>
                  <b className="text-right tabular-nums">{m.margin}%</b>
                </div>
              ))}
            </div>
          ) : <p className="text-sm text-muted-foreground">Появится после первых записей.</p>}
        </StoreSection>
      </div>

      {adding ? <ExpenseModal companyId={companyId || data.companies[0]?.id || ''} onClose={() => setAdding(false)} onSaved={async () => { setAdding(false); await refresh() }} /> : null}
    </div>
  )
}
