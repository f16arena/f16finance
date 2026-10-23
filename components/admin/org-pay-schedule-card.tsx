'use client'

import { useEffect, useState } from 'react'
import { CalendarClock, Save } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { toast } from '@/components/ui/use-toast'
import {
  DEFAULT_BIWEEKLY_ANCHOR,
  DEFAULT_OPERATOR_PAY_SCHEDULE,
  DEFAULT_STAFF_PAY_SCHEDULE,
  MAX_STAFF_PAY_DAY,
  describeOperatorPaySchedule,
  describeStaffPaySchedule,
  type OperatorPaySchedule,
  type StaffPaySchedule,
} from '@/lib/core/org-settings'

type StaffChoice = 'semimonthly' | 'monthly-end' | 'monthly-day'
type OperatorChoice = OperatorPaySchedule['kind']

const WEEKDAYS = ['понедельника', 'вторника', 'среды', 'четверга', 'пятницы', 'субботы', 'воскресенья']

function toStaffChoice(schedule: StaffPaySchedule): StaffChoice {
  if (schedule.kind === 'semimonthly') return 'semimonthly'
  return schedule.payDay == null ? 'monthly-end' : 'monthly-day'
}

function Option<T extends string>(props: {
  name: string
  value: T
  current: T
  onChange: (value: T) => void
  title: string
  hint: string
}) {
  const active = props.current === props.value
  return (
    <label
      className={`flex cursor-pointer items-start gap-3 rounded-xl border px-3 py-2.5 text-sm ${
        active ? 'border-blue-500/50 bg-blue-500/10' : 'border-border hover:bg-slate-50 dark:hover:bg-white/5'
      }`}
    >
      <input type="radio" name={props.name} className="mt-1" checked={active} onChange={() => props.onChange(props.value)} />
      <span>
        <span className="block font-medium text-foreground">{props.title}</span>
        <span className="block text-xs text-muted-foreground">{props.hint}</span>
      </span>
    </label>
  )
}

/**
 * График зарплаты — настройка организации: период для операторов по сменам и
 * график выплаты оклада админ-составу. Менять можно, пока по графику нет выплат.
 */
export function OrgPayScheduleCard() {
  const [savedStaff, setSavedStaff] = useState<StaffPaySchedule | null>(null)
  const [savedOperator, setSavedOperator] = useState<OperatorPaySchedule | null>(null)
  const [staffChoice, setStaffChoice] = useState<StaffChoice>('semimonthly')
  const [payDay, setPayDay] = useState('10')
  const [operatorChoice, setOperatorChoice] = useState<OperatorChoice>('weekly')
  const [startDay, setStartDay] = useState(1)
  const [anchor, setAnchor] = useState(DEFAULT_BIWEEKLY_ANCHOR)
  const [saving, setSaving] = useState(false)

  function applyLoaded(staff: StaffPaySchedule, operator: OperatorPaySchedule) {
    setSavedStaff(staff)
    setSavedOperator(operator)
    setStaffChoice(toStaffChoice(staff))
    if (staff.kind === 'monthly' && staff.payDay != null) setPayDay(String(staff.payDay))
    setOperatorChoice(operator.kind)
    if (operator.kind === 'weekly') setStartDay(operator.startDay)
    if (operator.kind === 'biweekly') setAnchor(operator.anchor)
  }

  useEffect(() => {
    let cancelled = false
    fetch('/api/admin/org-settings', { cache: 'no-store' })
      .then((res) => (res.ok ? res.json() : null))
      .then((body) => {
        if (cancelled) return
        applyLoaded(
          body?.settings?.staffPaySchedule || DEFAULT_STAFF_PAY_SCHEDULE,
          body?.settings?.operatorPaySchedule || DEFAULT_OPERATOR_PAY_SCHEDULE,
        )
      })
      .catch(() => {
        if (!cancelled) applyLoaded(DEFAULT_STAFF_PAY_SCHEDULE, DEFAULT_OPERATOR_PAY_SCHEDULE)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const day = Number(payDay)
  const dayValid = Number.isInteger(day) && day >= 1 && day <= MAX_STAFF_PAY_DAY
  const nextStaff: StaffPaySchedule =
    staffChoice === 'semimonthly'
      ? { kind: 'semimonthly' }
      : { kind: 'monthly', payDay: staffChoice === 'monthly-day' ? day : null }
  const nextOperator: OperatorPaySchedule =
    operatorChoice === 'weekly'
      ? { kind: 'weekly', startDay }
      : operatorChoice === 'biweekly'
        ? { kind: 'biweekly', anchor }
        : { kind: operatorChoice }
  const staffDirty = !!savedStaff && JSON.stringify(nextStaff) !== JSON.stringify(savedStaff)
  const operatorDirty = !!savedOperator && JSON.stringify(nextOperator) !== JSON.stringify(savedOperator)
  const invalid = (staffChoice === 'monthly-day' && !dayValid) || (operatorChoice === 'biweekly' && !/^\d{4}-\d{2}-\d{2}$/.test(anchor))

  async function save() {
    if (invalid) return
    setSaving(true)
    try {
      const res = await fetch('/api/admin/org-settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...(staffDirty ? { staffPaySchedule: nextStaff } : {}),
          ...(operatorDirty ? { operatorPaySchedule: nextOperator } : {}),
        }),
      })
      const body = await res.json().catch(() => null)
      if (!res.ok) throw new Error(body?.message || body?.error || 'Не сохранилось')
      applyLoaded(body.settings.staffPaySchedule, body.settings.operatorPaySchedule)
      toast({ title: 'График зарплаты сохранён' })
    } catch (err: any) {
      toast({ title: 'Не получилось', description: err.message, variant: 'destructive' })
    }
    setSaving(false)
  }

  const loading = !savedStaff || !savedOperator

  return (
    <Card className="space-y-5 border-border bg-card p-4">
      <div>
        <h2 className="flex items-center gap-2 text-xl font-bold">
          <CalendarClock className="h-5 w-5 text-blue-400" /> График зарплаты
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          Меняется, пока по графику нет выплат: иначе уже выплаченные периоды посчитались бы заново.
        </p>
      </div>

      {loading ? (
        <div className="h-40 animate-pulse rounded-xl bg-slate-100 dark:bg-white/5" />
      ) : (
        <>
          <section className="space-y-2">
            <h3 className="text-sm font-semibold text-foreground">Операторы (по сменам) — период расчёта</h3>
            <div className="grid gap-2 md:grid-cols-3 xl:grid-cols-5">
              <Option name="op-schedule" value="weekly" current={operatorChoice} onChange={setOperatorChoice} title="Неделя" hint="7 дней с выбранного дня" />
              <Option name="op-schedule" value="biweekly" current={operatorChoice} onChange={setOperatorChoice} title="Две недели" hint="14 дней от даты начала" />
              <Option name="op-schedule" value="semimonthly" current={operatorChoice} onChange={setOperatorChoice} title="Половина месяца" hint="1–15 и 16–конец" />
              <Option name="op-schedule" value="monthly" current={operatorChoice} onChange={setOperatorChoice} title="Месяц" hint="Календарный месяц" />
              <Option name="op-schedule" value="daily" current={operatorChoice} onChange={setOperatorChoice} title="Каждый день" hint="Расчёт за каждый день" />
            </div>
            {operatorChoice === 'weekly' && (
              <label className="flex items-center gap-2 text-sm text-muted-foreground">
                Неделя начинается с
                <select
                  value={startDay}
                  onChange={(e) => setStartDay(Number(e.target.value))}
                  className="rounded-lg border border-border bg-input px-2 py-1 text-sm text-foreground"
                >
                  {WEEKDAYS.map((label, index) => (
                    <option key={label} value={index + 1}>{label}</option>
                  ))}
                </select>
              </label>
            )}
            {operatorChoice === 'biweekly' && (
              <label className="flex items-center gap-2 text-sm text-muted-foreground">
                Первый период начинается
                <input
                  type="date"
                  value={anchor}
                  onChange={(e) => setAnchor(e.target.value)}
                  className="rounded-lg border border-border bg-input px-2 py-1 text-sm text-foreground"
                />
              </label>
            )}
            <p className="text-xs text-muted-foreground">Сейчас: {describeOperatorPaySchedule(savedOperator!)}</p>
          </section>

          <section className="space-y-2">
            <h3 className="text-sm font-semibold text-foreground">Админ-состав (оклад) — выплата</h3>
            <div className="grid gap-2 md:grid-cols-3">
              <Option name="staff-schedule" value="semimonthly" current={staffChoice} onChange={setStaffChoice} title="Два раза в месяц" hint="За 1–15 и за 16–конец, по половине оклада" />
              <Option name="staff-schedule" value="monthly-end" current={staffChoice} onChange={setStaffChoice} title="Раз в месяц, в конце" hint="Оклад целиком за текущий месяц" />
              <Option name="staff-schedule" value="monthly-day" current={staffChoice} onChange={setStaffChoice} title="Раз в месяц, до числа" hint="За прошлый месяц до указанного числа" />
            </div>
            {staffChoice === 'monthly-day' && (
              <label className="flex items-center gap-2 text-sm text-muted-foreground">
                За прошлый месяц — до
                <input
                  type="number"
                  min={1}
                  max={MAX_STAFF_PAY_DAY}
                  value={payDay}
                  onChange={(e) => setPayDay(e.target.value)}
                  className="w-16 rounded-lg border border-border bg-input px-2 py-1 text-sm text-foreground"
                />
                числа следующего
                {!dayValid && <span className="text-rose-500">· от 1 до {MAX_STAFF_PAY_DAY}</span>}
              </label>
            )}
            <p className="text-xs text-muted-foreground">Сейчас: {describeStaffPaySchedule(savedStaff!)}</p>
          </section>

          <div className="flex justify-end">
            <Button
              onClick={save}
              disabled={(!staffDirty && !operatorDirty) || saving || invalid}
              className="bg-blue-600 hover:bg-blue-700"
            >
              <Save className="mr-1.5 h-4 w-4" />
              Сохранить
            </Button>
          </div>
        </>
      )}
    </Card>
  )
}
