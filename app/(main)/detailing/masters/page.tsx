'use client'

/**
 * Мастера детейлинга: кто работает и сколько кому платить.
 * Оплата собирается тумблерами — оклад, бонус, процент с работы (цена минус
 * материалы). Мастер в программу не заходит; владелец видит «к выплате» и платит
 * одной кнопкой — выплата сама ложится в расходы статьёй «Зарплата».
 */

import { useEffect, useState } from 'react'
import { Loader2, Plus, Trash2 } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { confirmDialog } from '@/components/ui/confirm-dialog'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/components/ui/use-toast'
import { Skeleton } from '@/components/skeleton'
import { StoreEmpty, plural, storeCard } from '@/components/store/store-ui'
import { useApiCache } from '@/lib/client/use-api-cache'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { PAY_METHODS, type PayMethod, Pills, refreshDetailing } from '@/components/detailing/detailing-actions'
import { type DetailingCompany, CompanyPicker, dayLabel, isoLocal, money, postDetailing, useDetailingCompanyId } from '@/components/detailing/detailing-shared'

type Terms = { salary_on: boolean; salary_amount: number; bonus_on: boolean; bonus_amount: number; percent_on: boolean; percent: number; note: string | null }
type MasterPay = {
  id: string
  name: string
  terms: Terms
  jobs: number
  base: number
  salaryPart: number
  bonusPart: number
  percentPart: number
  accrued: number
  paid: number
  due: number
  payouts: Array<{ id: string; amount: number; method: PayMethod; paid_on: string }>
}
type MastersData = { companies: DetailingCompany[]; company: DetailingCompany | null; month: string; masters: MasterPay[] }

const API = '/api/admin/detailing/masters'
const MONTHS_PREP = ['январе', 'феврале', 'марте', 'апреле', 'мае', 'июне', 'июле', 'августе', 'сентябре', 'октябре', 'ноябре', 'декабре']
const MONTHS_ACC = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']

const initials = (name: string) => name.split(/\s+/).map((p) => p[0]).join('').slice(0, 2).toUpperCase()
const digits = (v: string) => v.replace(/[^\d]/g, '')

function monthOf(offset: number) {
  const now = new Date()
  return isoLocal(new Date(now.getFullYear(), now.getMonth() + offset, 1))
}

export default function DetailingMastersPage() {
  const { can } = useCapabilities()
  const [companyId, setCompanyId] = useDetailingCompanyId()
  const [offset, setOffset] = useState(0)
  const month = monthOf(offset)
  const params = new URLSearchParams({ month })
  if (companyId) params.set('company_id', companyId)
  const { data, loading, error, refresh } = useApiCache<MastersData>(`${API}?${params}`, { ttl: 30_000 })
  useEffect(() => {
    if (data?.company && data.company.id !== companyId) setCompanyId(data.company.id)
  }, [data?.company, companyId, setCompanyId])

  const [adding, setAdding] = useState(false)
  const [paying, setPaying] = useState<MasterPay | null>(null)
  const canEdit = can('detailing-masters.edit')
  const canPay = can('detailing-masters.pay')

  const reload = async () => {
    refreshDetailing()
    await refresh()
  }
  const run = async (body: Record<string, unknown>, done?: string) => {
    try {
      await postDetailing(API, { company_id: data?.company?.id, ...body })
      if (done) toast({ title: done })
      await reload()
      return true
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
      return false
    }
  }

  const monthIndex = Number(month.slice(5, 7)) - 1
  const header = (
    <header className="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="font-[family-name:var(--font-manrope)] text-[26px] font-extrabold leading-tight text-[#0b1f3b] dark:text-white">Мастера</h1>
        <p className="mt-1 text-sm text-muted-foreground">Сколько платить каждому. Включите, из чего складывается оплата.</p>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {data ? <CompanyPicker companies={data.companies} value={data.company?.id || ''} onChange={setCompanyId} /> : null}
        <div className="flex rounded-xl border border-border bg-white p-[3px] dark:bg-slate-900/60">
          {[0, -1].map((o) => (
            <button
              key={o}
              type="button"
              onClick={() => setOffset(o)}
              aria-pressed={offset === o}
              className={`rounded-[9px] px-3 py-1.5 text-[13px] font-bold capitalize transition ${offset === o ? 'bg-[#0b1f3b] text-white dark:bg-white dark:text-[#0b1f3b]' : 'text-muted-foreground hover:text-foreground'}`}
            >
              {MONTHS_ACC[Number(monthOf(o).slice(5, 7)) - 1]}
            </button>
          ))}
        </div>
        {can('detailing-masters.create') && data?.company ? <Button size="sm" onClick={() => setAdding(true)}><Plus className="h-4 w-4" />Мастер</Button> : null}
      </div>
    </header>
  )

  if (loading && !data) return <div className="space-y-4">{header}<div className="grid gap-3.5 lg:grid-cols-2"><Skeleton className="h-[300px] rounded-2xl" /><Skeleton className="h-[300px] rounded-2xl" /></div></div>
  if (error && !data) return <div className="space-y-4">{header}<div className={`${storeCard} p-6 text-sm text-rose-600`}>{error}</div></div>
  if (!data?.company) return <div className="space-y-4">{header}<div className={storeCard}><StoreEmpty title="Детейлинг ещё не настроен" /></div></div>

  const totalDue = data.masters.reduce((s, m) => s + m.due, 0)

  return (
    <div className="space-y-4">
      {header}

      {data.masters.length ? (
        <>
          <p className="text-sm text-muted-foreground">
            В {MONTHS_PREP[monthIndex]} к выплате всем: <b className="tabular-nums text-foreground">{money(totalDue)}</b>. Процент считается с выданных машин — с цены минус материалы.
          </p>
          <div className="grid grid-cols-1 items-start gap-3.5 lg:grid-cols-2">
            {data.masters.map((m) => (
              <MasterCard
                key={m.id}
                master={m}
                monthLabel={MONTHS_PREP[monthIndex]}
                canEdit={canEdit}
                canPay={canPay}
                canRemove={can('detailing-masters.delete')}
                onSave={(terms) => run({ action: 'terms', operator_id: m.id, ...terms })}
                onPay={() => setPaying(m)}
                onUndo={(payoutId) => run({ action: 'unpayout', operator_id: m.id, payout_id: payoutId }, 'Выплата отменена, расход убран')}
                onRemove={async () => {
                  const ok = await confirmDialog({
                    title: `Убрать мастера ${m.name}?`,
                    description: 'Он пропадёт из списка и из выбора в записи. Его выданные машины и выплаты останутся в истории.',
                    confirmLabel: 'Убрать',
                    destructive: true,
                  })
                  if (ok) await run({ action: 'remove', operator_id: m.id }, 'Мастер убран')
                }}
              />
            ))}
          </div>
        </>
      ) : (
        <div className={storeCard}>
          <StoreEmpty
            title="Мастеров пока нет"
            hint="Добавьте тех, кто работает в боксах. Вход в программу им не нужен — достаточно имени."
            action={<Button size="sm" onClick={() => setAdding(true)}><Plus className="h-4 w-4" />Добавить мастера</Button>}
          />
        </div>
      )}

      {adding ? <AddMasterModal onClose={() => setAdding(false)} onSave={async (name, note) => {
        try {
          const res = await postDetailing<{ id: string }>(API, { company_id: data.company!.id, action: 'add', name })
          if (note) await postDetailing(API, { company_id: data.company!.id, action: 'terms', operator_id: res.id, percent_on: true, percent: 30, note })
          toast({ title: `${name} добавлен`, description: 'По умолчанию — 30% с работы. Поменяйте, если у вас иначе.' })
          setAdding(false)
          await reload()
        } catch (e: any) {
          toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
        }
      }} /> : null}

      {paying ? <PayoutModal master={paying} monthLabel={MONTHS_ACC[monthIndex]} onClose={() => setPaying(null)} onPay={async (amount, method) => {
        const ok = await run({ action: 'payout', operator_id: paying.id, month, amount, method, paid_on: isoLocal(new Date()) }, `Выплачено ${money(amount)} · ушло в расходы`)
        if (ok) setPaying(null)
      }} /> : null}
    </div>
  )
}

/* ── Карточка мастера ─────────────────────────────────────────────────── */

function MasterCard(props: {
  master: MasterPay
  monthLabel: string
  canEdit: boolean
  canPay: boolean
  canRemove: boolean
  onSave: (terms: Terms) => Promise<boolean>
  onPay: () => void
  onUndo: (payoutId: string) => void
  onRemove: () => void
}) {
  const m = props.master
  const [terms, setTerms] = useState<Terms>(m.terms)
  useEffect(() => setTerms(m.terms), [m.terms])

  // Тумблер сохраняется сразу, число — когда из поля вышли.
  const save = (patch: Partial<Terms>) => {
    const next = { ...terms, ...patch }
    setTerms(next)
    void props.onSave(next)
  }

  const rows: Array<{ on: 'salary_on' | 'bonus_on' | 'percent_on'; value: 'salary_amount' | 'bonus_amount' | 'percent'; title: string; hint?: string; part: number; suffix: string }> = [
    { on: 'salary_on', value: 'salary_amount', title: 'Оклад в месяц', part: m.salaryPart, suffix: '₸' },
    { on: 'bonus_on', value: 'bonus_amount', title: 'Бонус за месяц', part: m.bonusPart, suffix: '₸' },
    { on: 'percent_on', value: 'percent', title: 'Процент с работы', hint: `цена минус материалы · в ${props.monthLabel} с ${money(m.base)}`, part: m.percentPart, suffix: '%' },
  ]

  return (
    <section className={`${storeCard} flex min-w-0 flex-col gap-3 p-4`}>
      <div className="flex items-center gap-3">
        <span className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-[#0b1f3b] text-sm font-extrabold text-white dark:bg-white/10">{initials(m.name)}</span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-base font-bold">{m.name}</div>
          <div className="truncate text-[12.5px] text-muted-foreground">
            {terms.note ? `${terms.note} · ` : ''}{m.jobs} {plural(m.jobs, ['машина', 'машины', 'машин'])} в {props.monthLabel}
          </div>
        </div>
        <div className="shrink-0 text-right">
          <div className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">к выплате</div>
          <div className="text-lg font-extrabold tabular-nums">{money(m.due)}</div>
        </div>
      </div>

      <div>
        {rows.map((r) => {
          const on = terms[r.on]
          return (
            <div key={r.on} className="flex flex-wrap items-center justify-between gap-x-2.5 gap-y-1.5 border-t border-border py-2.5">
              <div className="min-w-0">
                <div className="font-semibold">{r.title}</div>
                {r.hint ? <div className="truncate text-[12.5px] text-muted-foreground">{r.hint}</div> : null}
              </div>
              <div className="flex shrink-0 items-center gap-2.5">
                <div className={`relative ${on ? '' : 'opacity-40'}`}>
                  <Input
                    value={r.suffix === '%' ? String(terms.percent) : terms[r.value] ? Number(terms[r.value]).toLocaleString('ru-RU') : ''}
                    onChange={(e) => {
                      const raw = digits(e.target.value)
                      setTerms({ ...terms, [r.value]: r.suffix === '%' ? Math.min(100, Number(raw) || 0) : Number(raw.slice(0, 8)) || 0 })
                    }}
                    onBlur={() => terms[r.value] !== m.terms[r.value] && void props.onSave(terms)}
                    disabled={!on || !props.canEdit}
                    inputMode="numeric"
                    placeholder="0"
                    aria-label={r.title}
                    className="h-9 w-[104px] pr-7 text-right font-mono font-bold tabular-nums"
                  />
                  <span className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-xs text-muted-foreground">{r.suffix}</span>
                </div>
                <Switch checked={on} disabled={!props.canEdit} onCheckedChange={(v) => save({ [r.on]: v } as Partial<Terms>)} aria-label={r.title} />
              </div>
            </div>
          )
        })}
      </div>

      <div className="rounded-xl bg-slate-50 px-3 py-2.5 text-[13px] dark:bg-white/[0.03]">
        <div className="flex justify-between gap-3"><span className="text-muted-foreground">Начислено</span><b className="tabular-nums">{money(m.accrued)}</b></div>
        {m.payouts.map((p) => (
          <div key={p.id} className="flex items-center justify-between gap-3 text-muted-foreground">
            <span>
              Выплачено {dayLabel(p.paid_on).day} {dayLabel(p.paid_on).month} · {PAY_METHODS.find((x) => x.id === p.method)?.label}
              {props.canPay ? <> · <button type="button" onClick={() => props.onUndo(p.id)} className="underline-offset-2 hover:text-rose-600 hover:underline">отменить</button></> : null}
            </span>
            <span className="tabular-nums">−{money(p.amount)}</span>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-2">
        {props.canPay ? (
          <Button className="flex-1" disabled={m.due <= 0} onClick={props.onPay}>
            {m.due > 0 ? `Выплатить ${money(m.due)}` : m.accrued > 0 ? 'Выплачено полностью' : 'Пока нечего платить'}
          </Button>
        ) : <span className="flex-1" />}
        {props.canRemove ? (
          <Button variant="outline" size="icon" onClick={props.onRemove} aria-label={`Убрать мастера ${m.name}`}><Trash2 className="h-4 w-4" /></Button>
        ) : null}
      </div>
    </section>
  )
}

/* ── Окна ─────────────────────────────────────────────────────────────── */

function AddMasterModal(props: { onClose: () => void; onSave: (name: string, note: string) => Promise<void> }) {
  const [name, setName] = useState('')
  const [note, setNote] = useState('')
  const [saving, setSaving] = useState(false)
  const ready = name.trim().length >= 2
  const submit = async () => {
    setSaving(true)
    await props.onSave(name.trim(), note.trim())
    setSaving(false)
  }
  return (
    <AppModal
      open
      onClose={props.onClose}
      title="Новый мастер"
      description="Вход в программу ему не нужен — работы на него ставите вы."
      maxWidth="max-w-md"
      footer={<Button className="h-11 w-full" disabled={!ready || saving} onClick={submit}>{saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Добавить</Button>}
    >
      <div className="space-y-3">
        <label className="block space-y-1.5 text-sm"><span className="text-muted-foreground">Имя</span><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Ерлан" autoFocus maxLength={80} /></label>
        <label className="block space-y-1.5 text-sm"><span className="text-muted-foreground">Чем занимается — по желанию</span><Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="плёнка, керамика" maxLength={120} /></label>
      </div>
    </AppModal>
  )
}

function PayoutModal(props: { master: MasterPay; monthLabel: string; onClose: () => void; onPay: (amount: number, method: PayMethod) => Promise<void> }) {
  const m = props.master
  const [amount, setAmount] = useState(String(Math.round(m.due)))
  const [method, setMethod] = useState<PayMethod>('kaspi')
  const [saving, setSaving] = useState(false)
  const value = Number(amount) || 0
  const submit = async () => {
    setSaving(true)
    await props.onPay(value, method)
    setSaving(false)
  }
  return (
    <AppModal
      open
      onClose={props.onClose}
      title={`Выплатить: ${m.name}`}
      description={`За ${props.monthLabel}. Начислено ${money(m.accrued)}, уже выплачено ${money(m.paid)}.`}
      maxWidth="max-w-md"
      footer={<Button className="h-11 w-full" disabled={saving || value <= 0 || value > m.due} onClick={submit}>{saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Выплатить {value > 0 ? money(value) : ''}</Button>}
    >
      <div className="space-y-4">
        <div className="space-y-1.5">
          <div className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">Сумма</div>
          <div className="relative">
            <Input value={amount ? Number(amount).toLocaleString('ru-RU') : ''} onChange={(e) => setAmount(digits(e.target.value).slice(0, 9))} inputMode="numeric" aria-label="Сумма" className="h-12 pr-9 text-lg font-bold tabular-nums" />
            <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground">₸</span>
          </div>
          {value > m.due ? <p className="text-xs text-rose-600">Больше, чем к выплате ({money(m.due)})</p> : null}
        </div>
        <div className="space-y-1.5">
          <div className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">Чем платите</div>
          <Pills label="Способ" value={method} onChange={setMethod} options={PAY_METHODS} />
        </div>
        <p className="text-xs text-muted-foreground">Выплата сама запишется в расходы статьёй «Зарплата». Можно платить частями.</p>
      </div>
    </AppModal>
  )
}
