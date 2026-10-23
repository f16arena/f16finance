'use client'

import { useEffect, useState } from 'react'
import { CalendarClock, Save } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { toast } from '@/components/ui/use-toast'
import {
  DEFAULT_STAFF_PAY_SCHEDULE,
  MAX_STAFF_PAY_DAY,
  describeStaffPaySchedule,
  type StaffPaySchedule,
} from '@/lib/core/org-settings'

type Choice = 'semimonthly' | 'monthly-end' | 'monthly-day'

function toChoice(schedule: StaffPaySchedule): Choice {
  if (schedule.kind === 'semimonthly') return 'semimonthly'
  return schedule.payDay == null ? 'monthly-end' : 'monthly-day'
}

/**
 * График выплаты оклада админ-составу — настройка организации.
 * Операторы по сменам пока остаются на неделе пн–вс.
 */
export function OrgPayScheduleCard() {
  const [saved, setSaved] = useState<StaffPaySchedule | null>(null)
  const [choice, setChoice] = useState<Choice>('semimonthly')
  const [payDay, setPayDay] = useState('10')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let cancelled = false
    fetch('/api/admin/org-settings', { cache: 'no-store' })
      .then((res) => (res.ok ? res.json() : null))
      .then((body) => {
        if (cancelled) return
        const schedule: StaffPaySchedule = body?.settings?.staffPaySchedule || DEFAULT_STAFF_PAY_SCHEDULE
        setSaved(schedule)
        setChoice(toChoice(schedule))
        if (schedule.kind === 'monthly' && schedule.payDay != null) setPayDay(String(schedule.payDay))
      })
      .catch(() => {
        if (!cancelled) setSaved(DEFAULT_STAFF_PAY_SCHEDULE)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const day = Number(payDay)
  const dayValid = Number.isInteger(day) && day >= 1 && day <= MAX_STAFF_PAY_DAY
  const next: StaffPaySchedule =
    choice === 'semimonthly'
      ? { kind: 'semimonthly' }
      : { kind: 'monthly', payDay: choice === 'monthly-day' ? day : null }
  const dirty = !!saved && JSON.stringify(next) !== JSON.stringify(saved)

  async function save() {
    if (choice === 'monthly-day' && !dayValid) return
    setSaving(true)
    try {
      const res = await fetch('/api/admin/org-settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ staffPaySchedule: next }),
      })
      const body = await res.json().catch(() => null)
      if (!res.ok) throw new Error(body?.message || body?.error || 'Не сохранилось')
      setSaved(body.settings.staffPaySchedule)
      toast({ title: 'График выплат сохранён', description: describeStaffPaySchedule(body.settings.staffPaySchedule) })
    } catch (err: any) {
      toast({ title: 'Не получилось', description: err.message, variant: 'destructive' })
    }
    setSaving(false)
  }

  const option = (value: Choice, title: string, hint: string) => (
    <label
      className={`flex cursor-pointer items-start gap-3 rounded-xl border px-3 py-2.5 text-sm ${
        choice === value ? 'border-blue-500/50 bg-blue-500/10' : 'border-border hover:bg-slate-50 dark:hover:bg-white/5'
      }`}
    >
      <input
        type="radio"
        name="staff-pay-schedule"
        className="mt-1"
        checked={choice === value}
        onChange={() => setChoice(value)}
      />
      <span>
        <span className="block font-medium text-foreground">{title}</span>
        <span className="block text-xs text-muted-foreground">{hint}</span>
      </span>
    </label>
  )

  return (
    <Card className="space-y-4 border-border bg-card p-4">
      <div>
        <h2 className="flex items-center gap-2 text-xl font-bold">
          <CalendarClock className="h-5 w-5 text-blue-400" /> График выплаты оклада
        </h2>
        <p className="mt-1 text-sm text-muted-foreground">
          Для сотрудников на окладе (админ-состав). Операторы по сменам пока получают по неделям, пн–вс.
          Уже проведённые выплаты не пересчитываются.
        </p>
      </div>

      {!saved ? (
        <div className="h-24 animate-pulse rounded-xl bg-slate-100 dark:bg-white/5" />
      ) : (
        <div className="grid gap-2 md:grid-cols-3">
          {option('semimonthly', 'Два раза в месяц', 'За 1–15 и за 16–конец, по половине оклада')}
          {option('monthly-end', 'Раз в месяц, в конце', 'Оклад целиком за текущий месяц')}
          {option('monthly-day', 'Раз в месяц, до числа', 'За прошлый месяц до указанного числа')}
        </div>
      )}

      {choice === 'monthly-day' && (
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

      <div className="flex items-center justify-between gap-3">
        <span className="text-xs text-muted-foreground">Сейчас: {saved ? describeStaffPaySchedule(saved) : '…'}</span>
        <Button
          onClick={save}
          disabled={!dirty || saving || (choice === 'monthly-day' && !dayValid)}
          className="bg-blue-600 hover:bg-blue-700"
        >
          <Save className="mr-1.5 h-4 w-4" />
          Сохранить
        </Button>
      </div>
    </Card>
  )
}
