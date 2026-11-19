'use client'

/**
 * Страница записи для клиента детейлинга. Делается с телефона за минуту:
 * кузов → услуги (цена видна сразу) → день → имя, телефон, номер машины.
 * Это заявка: детейлинг подтверждает её сам и пишет клиенту.
 */

import { useEffect, useMemo, useState } from 'react'
import { CarFront, Check, Loader2 } from 'lucide-react'

import { durationLabel } from '@/lib/core/detailing-slots'

type Info = {
  name: string
  classes: Array<{ id: string; name: string }>
  services: Array<{ id: string; name: string; note: string | null; days: number; minutes?: number | null; prices: Array<{ body_class_id: string; price: number }> }>
  horizonDays: number
}

const WEEKDAYS = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб']
const MONTHS = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']
const money = (n: number) => `${Math.round(n).toLocaleString('ru-RU')} ₸`
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

const field =
  'h-12 w-full rounded-xl border border-[#d4dbe6] bg-white px-3.5 text-[15px] text-[#0b1f3b] outline-none transition placeholder:text-[#94a3b8] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/20'
const pill = (on: boolean) =>
  `min-h-11 rounded-xl border-[1.5px] px-3.5 py-2 text-sm font-semibold transition ${on ? 'border-blue-600 bg-blue-600 text-white' : 'border-[#d4dbe6] bg-white text-[#0b1f3b] hover:border-blue-400'}`

export function OnlineBookingForm({ orgName }: { orgName: string }) {
  const [info, setInfo] = useState<Info | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'off' | 'sent'>('loading')
  const [classId, setClassId] = useState('')
  const [picked, setPicked] = useState<string[]>([])
  const [date, setDate] = useState('')
  const [time, setTime] = useState('')
  const [form, setForm] = useState({ name: '', phone: '', plate: '', model: '', comment: '', website: '' })
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    fetch('/api/public/detailing/book')
      .then(async (r) => ({ ok: r.ok, json: await r.json().catch(() => null) }))
      .then(({ ok, json }) => {
        if (!alive) return
        if (!ok || !json?.data?.classes?.length || !json.data.services.length) return setState('off')
        setInfo(json.data)
        setClassId(json.data.classes[0].id)
        setState('ready')
      })
      .catch(() => alive && setState('off'))
    return () => { alive = false }
  }, [])

  const days = useMemo(() => {
    const now = new Date()
    return Array.from({ length: Math.min(14, (info?.horizonDays || 14) + 1) }, (_, i) => new Date(now.getFullYear(), now.getMonth(), now.getDate() + i))
  }, [info?.horizonDays])

  const priceOf = (s: Info['services'][number]) => s.prices.find((p) => p.body_class_id === classId)?.price ?? null
  const available = (info?.services || []).filter((s) => priceOf(s) !== null)
  const chosen = available.filter((s) => picked.includes(s.id))
  const total = chosen.reduce((sum, s) => sum + (priceOf(s) || 0), 0)
  const ready = chosen.length > 0 && !!date && form.name.trim().length >= 2 && form.phone.replace(/\D/g, '').length >= 10 && form.plate.trim().length >= 3

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSending(true)
    setError(null)
    try {
      const res = await fetch('/api/public/detailing/book', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...form, body_class_id: classId, service_ids: chosen.map((s) => s.id), date, time }),
      })
      const json = await res.json().catch(() => null)
      if (!res.ok) throw new Error(json?.error || 'Не получилось отправить заявку')
      setState('sent')
    } catch (err: any) {
      setError(err.message)
    } finally {
      setSending(false)
    }
  }

  const shell = (children: React.ReactNode) => (
    <div className="min-h-screen bg-[#f3f5f9] text-[#0b1f3b]">
      <header className="bg-[#0b1f3b] px-5 py-5 text-white">
        <div className="mx-auto flex max-w-xl items-center gap-3">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-blue-600"><CarFront className="h-6 w-6" /></span>
          <span className="min-w-0">
            <span className="block truncate font-[family-name:var(--font-manrope)] text-lg font-extrabold">{orgName}</span>
            <span className="block text-xs text-slate-400">запись на детейлинг</span>
          </span>
        </div>
      </header>
      <main className="mx-auto max-w-xl px-5 py-6">{children}</main>
    </div>
  )

  if (state === 'loading') return shell(<div className="flex items-center gap-2 py-10 text-sm text-[#5b6b82]"><Loader2 className="h-4 w-4 animate-spin" />Открываю запись…</div>)
  if (state === 'off') return shell(<div className="rounded-2xl border border-[#dde3ec] bg-white p-6"><h1 className="text-xl font-extrabold">Онлайн-запись сейчас выключена</h1><p className="mt-2 text-sm text-[#5b6b82]">Позвоните или напишите нам — запишем вас сами.</p></div>)
  if (state === 'sent') {
    return shell(
      <div className="rounded-2xl border border-[#dde3ec] bg-white p-6">
        <span className="grid h-12 w-12 place-items-center rounded-full bg-emerald-100 text-emerald-700"><Check className="h-6 w-6" /></span>
        <h1 className="mt-4 font-[family-name:var(--font-manrope)] text-2xl font-extrabold">Заявка у нас</h1>
        <p className="mt-2 text-sm leading-6 text-[#5b6b82]">Мы посмотрим свободные боксы и напишем вам, чтобы подтвердить день и время. Это ещё не окончательная запись.</p>
      </div>,
    )
  }

  const label = 'text-[11px] font-bold uppercase tracking-wider text-[#5b6b82]'

  return shell(
    <form onSubmit={submit} className="space-y-6">
      <div>
        <h1 className="font-[family-name:var(--font-manrope)] text-2xl font-extrabold">Записаться</h1>
        <p className="mt-1 text-sm text-[#5b6b82]">Выберите, что сделать, и удобный день. Мы подтвердим запись сообщением.</p>
      </div>

      <section className="space-y-2">
        <div className={label}>Кузов</div>
        <div className="flex flex-wrap gap-2">
          {info!.classes.map((c) => (
            <button key={c.id} type="button" aria-pressed={classId === c.id} onClick={() => { setClassId(c.id); setPicked([]) }} className={pill(classId === c.id)}>{c.name}</button>
          ))}
        </div>
      </section>

      <section className="space-y-2">
        <div className={label}>Что сделать</div>
        <div className="space-y-2">
          {available.map((s) => {
            const on = picked.includes(s.id)
            return (
              <label key={s.id} className={`flex cursor-pointer items-center gap-3 rounded-xl border-[1.5px] bg-white px-3.5 py-3 transition ${on ? 'border-blue-600' : 'border-[#d4dbe6]'}`}>
                <input type="checkbox" className="h-5 w-5 shrink-0 accent-blue-600" checked={on} onChange={(e) => setPicked(e.target.checked ? [...picked, s.id] : picked.filter((x) => x !== s.id))} />
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold">{s.name}</span>
                  <span className="block text-xs text-[#5b6b82]">{s.note ? `${s.note} · ` : ''}{s.minutes ? `около ${durationLabel(s.minutes)}` : s.days > 1 ? `${s.days} дн.` : '1 день'}</span>
                </span>
                <b className="shrink-0 tabular-nums">{money(priceOf(s) || 0)}</b>
              </label>
            )
          })}
        </div>
      </section>

      <section className="space-y-2">
        <div className={label}>Когда привезёте машину</div>
        <div className="flex gap-2 overflow-x-auto pb-1">
          {days.map((d, i) => {
            const value = iso(d)
            return (
              <button key={value} type="button" aria-pressed={date === value} onClick={() => setDate(value)} className={`${pill(date === value)} shrink-0 text-center leading-tight`}>
                <span className="block text-xs font-medium opacity-80">{i === 0 ? 'сегодня' : i === 1 ? 'завтра' : WEEKDAYS[d.getDay()]}</span>
                <span className="block">{d.getDate()} {MONTHS[d.getMonth()]}</span>
              </button>
            )
          })}
        </div>
      </section>

      <section className="space-y-2">
        <div className={label}>Во сколько удобно — по желанию</div>
        <div className="flex gap-2 overflow-x-auto pb-1">
          {['09:00', '10:00', '11:00', '12:00', '13:00', '14:00', '15:00', '16:00', '17:00', '18:00', '19:00'].map((t) => (
            <button key={t} type="button" aria-pressed={time === t} onClick={() => setTime(time === t ? '' : t)} className={`${pill(time === t)} shrink-0`}>{t}</button>
          ))}
        </div>
      </section>

      <section className="space-y-3">
        <div className={label}>Как с вами связаться</div>
        <input className={field} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Ваше имя" autoComplete="name" aria-label="Ваше имя" maxLength={120} />
        <input className={field} value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} placeholder="Телефон, +7 701 555 12 40" inputMode="tel" autoComplete="tel" aria-label="Телефон" />
        <div className="grid gap-3 sm:grid-cols-2">
          <input className={field} value={form.plate} onChange={(e) => setForm({ ...form, plate: e.target.value })} placeholder="Госномер, 777 ABC 02" aria-label="Госномер" maxLength={20} />
          <input className={field} value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} placeholder="Машина, Toyota Camry" aria-label="Машина" maxLength={80} />
        </div>
        <input className={field} value={form.comment} onChange={(e) => setForm({ ...form, comment: e.target.value })} placeholder="Пожелания — по желанию" aria-label="Пожелания" maxLength={300} />
        {/* Ловушка для ботов: человеку не видна и не читается с экрана. */}
        <input type="text" tabIndex={-1} autoComplete="off" aria-hidden="true" className="hidden" value={form.website} onChange={(e) => setForm({ ...form, website: e.target.value })} />
      </section>

      {error ? <div role="alert" className="rounded-xl border border-red-200 bg-red-50 px-3.5 py-3 text-sm text-red-700">{error}</div> : null}

      <div className="sticky bottom-0 -mx-5 border-t border-[#dde3ec] bg-[#f3f5f9]/95 px-5 py-3 backdrop-blur">
        <div className="mb-2 flex items-center justify-between text-sm">
          <span className="text-[#5b6b82]">{chosen.length ? 'По прайсу' : 'Выберите услуги'}</span>
          <b className="font-[family-name:var(--font-manrope)] text-xl tabular-nums">{money(total)}</b>
        </div>
        <button type="submit" disabled={!ready || sending} className="flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-blue-600 text-[15px] font-bold text-white transition hover:bg-blue-700 disabled:opacity-50">
          {sending ? <Loader2 className="h-5 w-5 animate-spin" /> : null}Отправить заявку
        </button>
      </div>
    </form>,
  )
}
