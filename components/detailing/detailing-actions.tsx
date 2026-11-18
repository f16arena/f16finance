'use client'

/**
 * Короткие окна детейлинга — на 2–3 нажатия, без обязательных полей сверх
 * нужного: «Принять деньги» (машина → сумма подставлена → чем заплатил) и
 * «Записать расход» (сумма → на что → чем платил).
 */

import { useState, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { toast } from '@/components/ui/use-toast'
import { invalidateApiCache, useApiCache } from '@/lib/client/use-api-cache'
import { Plate, isoLocal, money, postDetailing } from '@/components/detailing/detailing-shared'

export type PayMethod = 'cash' | 'kaspi' | 'card'
export const PAY_METHODS: Array<{ id: PayMethod; label: string }> = [
  { id: 'kaspi', label: 'Kaspi' },
  { id: 'cash', label: 'Наличные' },
  { id: 'card', label: 'Карта' },
]

/** Статьи, пока сервер не ответил своими. */
const FALLBACK_CATEGORIES = ['Химия', 'Плёнка', 'Материалы', 'Аренда', 'Свет, вода', 'Реклама', 'Другое']

const digits = (v: string) => v.replace(/[^\d]/g, '')
const spaced = (v: string) => (v ? Number(v).toLocaleString('ru-RU') : '')

export function Pills<T extends string>(props: { value: T; onChange: (v: T) => void; options: Array<{ id: T; label: ReactNode }>; label: string }) {
  return (
    <div role="radiogroup" aria-label={props.label} className="flex flex-wrap gap-1.5">
      {props.options.map((o) => {
        const on = props.value === o.id
        return (
          <button
            key={o.id}
            type="button"
            role="radio"
            aria-checked={on}
            onClick={() => props.onChange(o.id)}
            className={`min-h-10 rounded-xl border-[1.5px] px-3 py-2 text-left text-sm font-semibold transition ${
              on ? 'border-blue-600 bg-blue-600/10 text-blue-700 dark:text-blue-200' : 'border-border text-foreground hover:border-slate-400'
            }`}
          >
            {o.label}
          </button>
        )
      })}
    </div>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <div className="text-[11px] font-bold uppercase tracking-wider text-muted-foreground">{label}</div>
      {children}
    </div>
  )
}

function AmountInput(props: { value: string; onChange: (v: string) => void; autoFocus?: boolean }) {
  return (
    <div className="relative">
      <Input
        value={spaced(props.value)}
        onChange={(e) => props.onChange(digits(e.target.value).slice(0, 9))}
        inputMode="numeric"
        autoFocus={props.autoFocus}
        aria-label="Сумма"
        className="h-12 pr-9 text-lg font-bold tabular-nums"
      />
      <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground">₸</span>
    </div>
  )
}

/** Сбросить всё, что детейлинг держит в кэше: гараж, неделю, деньги, мастеров. */
export function refreshDetailing() {
  invalidateApiCache('/api/admin/detailing')
}

/* ── Принять деньги ───────────────────────────────────────────────────── */

export type PayableCar = {
  id: string
  due: number
  status: string
  customer: { name: string; phone: string | null } | null
  car: { plate: string; model: string | null } | null
}

export function TakePaymentModal(props: { cars: PayableCar[]; onClose: () => void; onSaved: () => void }) {
  const cars = props.cars.filter((c) => c.due > 0)
  const [carId, setCarId] = useState(cars[0]?.id || '')
  const car = cars.find((c) => c.id === carId) || null
  const [amount, setAmount] = useState(car ? String(Math.round(car.due)) : '')
  const [method, setMethod] = useState<PayMethod>('kaspi')
  const [saving, setSaving] = useState(false)
  const value = Number(amount) || 0

  const pick = (id: string) => {
    setCarId(id)
    const next = cars.find((c) => c.id === id)
    if (next) setAmount(String(Math.round(next.due)))
  }

  const save = async () => {
    if (!car) return
    setSaving(true)
    try {
      await postDetailing('/api/admin/detailing', { id: car.id, action: 'pay', amount: value, method, paid_on: isoLocal(new Date()) })
      toast({ title: `Принято ${money(value)}`, description: car.car?.model || car.customer?.name || undefined })
      refreshDetailing()
      props.onSaved()
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
      setSaving(false)
    }
  }

  return (
    <AppModal
      open
      onClose={props.onClose}
      title="Принять деньги"
      maxWidth="max-w-lg"
      footer={
        cars.length ? (
          <Button className="h-11 w-full text-[15px]" onClick={save} disabled={saving || !car || value <= 0 || value > (car?.due || 0)}>
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Принять {value > 0 ? money(value) : ''}
          </Button>
        ) : undefined
      }
    >
      {cars.length ? (
        <div className="space-y-4">
          <Field label="За какую машину">
            <Pills
              label="Машина"
              value={carId}
              onChange={pick}
              options={cars.map((c) => ({
                id: c.id,
                label: (
                  <span className="flex flex-col gap-0.5">
                    <span className="flex items-center gap-2">{c.car?.model || c.customer?.name || 'Машина'} <Plate plate={c.car?.plate} /></span>
                    <span className="text-xs font-medium text-muted-foreground">{c.status === 'done' ? 'выдана, должен' : 'осталось'} {money(c.due)}</span>
                  </span>
                ),
              }))}
            />
          </Field>
          <Field label="Сумма">
            <AmountInput value={amount} onChange={setAmount} />
            {car && value > car.due ? <p className="text-xs text-rose-600">Больше, чем осталось ({money(car.due)})</p> : null}
          </Field>
          <Field label="Как заплатил">
            <Pills label="Способ оплаты" value={method} onChange={setMethod} options={PAY_METHODS} />
          </Field>
        </div>
      ) : (
        <p className="py-4 text-sm text-muted-foreground">Сейчас никто ничего не должен: все записанные и выданные машины оплачены.</p>
      )}
    </AppModal>
  )
}

/* ── Записать расход ──────────────────────────────────────────────────── */

export function ExpenseModal(props: { companyId: string; onClose: () => void; onSaved: () => void }) {
  const { data } = useApiCache<{ categories: string[] }>('/api/admin/detailing/expense', { ttl: 5 * 60_000 })
  const categories = data?.categories?.length ? data.categories.filter((c) => c !== 'Зарплата') : FALLBACK_CATEGORIES
  const [amount, setAmount] = useState('')
  const [category, setCategory] = useState(categories[0])
  const [method, setMethod] = useState<PayMethod>('cash')
  const [comment, setComment] = useState('')
  const [saving, setSaving] = useState(false)
  const value = Number(amount) || 0

  const save = async () => {
    setSaving(true)
    try {
      await postDetailing('/api/admin/detailing/expense', { company_id: props.companyId, amount: value, category, method, comment, date: isoLocal(new Date()) })
      toast({ title: `Расход ${money(value)} записан`, description: category })
      refreshDetailing()
      props.onSaved()
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
      setSaving(false)
    }
  }

  return (
    <AppModal
      open
      onClose={props.onClose}
      title="Записать расход"
      maxWidth="max-w-lg"
      footer={
        <Button className="h-11 w-full text-[15px]" onClick={save} disabled={saving || value <= 0}>
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Записать {value > 0 ? money(value) : ''}
        </Button>
      }
    >
      <div className="space-y-4">
        <Field label="Сумма">
          <AmountInput value={amount} onChange={setAmount} autoFocus />
        </Field>
        <Field label="На что">
          <Pills label="Статья" value={category} onChange={setCategory} options={categories.map((c) => ({ id: c, label: c }))} />
        </Field>
        <Field label="Чем платил">
          <Pills label="Способ оплаты" value={method} onChange={setMethod} options={PAY_METHODS} />
        </Field>
        <Field label="Что именно — по желанию">
          <Input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Koch Chemie, 5 литров" maxLength={300} />
        </Field>
        <p className="text-xs text-muted-foreground">Зарплату мастерам сюда не пишите — она выплачивается в разделе «Мастера» и попадает в расходы сама.</p>
      </div>
    </AppModal>
  )
}
