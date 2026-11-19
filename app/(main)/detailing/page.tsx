'use client'

import Link from 'next/link'
import { useEffect, useMemo, useState } from 'react'
import { ChevronLeft, ChevronRight, Loader2, Plus } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect } from '@/components/ui/native-select'
import { AppModal } from '@/components/ui/app-modal'
import { toast } from '@/components/ui/use-toast'
import { StoreEmpty, storeCard } from '@/components/store/store-ui'
import { Skeleton } from '@/components/skeleton'
import { useApiCache } from '@/lib/client/use-api-cache'
import { formatPhone } from '@/lib/core/format'
import {
  type BodyClass,
  type Booking,
  type BookingStatus,
  type Box,
  type DetailingCompany,
  type Master,
  type Service,
  CompanyPicker,
  DetailingHeader,
  NoDetailingPoint,
  Plate,
  QUICK_TIMES,
  STATUS_LABEL,
  STATUS_TONE,
  WaButton,
  dayLabel,
  daysBetween,
  isoLocal,
  mondayOf,
  money,
  postDetailing,
  shiftIso,
  useDetailingCompanyId,
  useOrgName,
  waText,
} from '@/components/detailing/detailing-shared'
import { DURATION_CHOICES, durationLabel, hourRange, slotTimeLabel, slotsOverlap } from '@/lib/core/detailing-slots'

type WeekData = {
  companies: DetailingCompany[]
  company: DetailingCompany | null
  weekStart: string
  boxes: Box[]
  classes: BodyClass[]
  services: Service[]
  masters: Master[]
  bookings: Booking[]
}

const API = '/api/admin/detailing'

export default function DetailingWeekPage() {
  const [companyId, setCompanyId] = useDetailingCompanyId()
  const today = isoLocal(new Date())
  const [weekStart, setWeekStart] = useState(() => mondayOf(new Date()))
  const url = `${API}?from=${weekStart}${companyId ? `&company_id=${companyId}` : ''}`
  const { data, loading, error, refresh } = useApiCache<WeekData>(url, { ttl: 30_000 })

  // Сохранённая точка пропала (закрыли, сменили нишу) — сервер отдал первую доступную.
  useEffect(() => {
    if (data?.company && data.company.id !== companyId) setCompanyId(data.company.id)
  }, [data?.company, companyId, setCompanyId])

  const [openId, setOpenId] = useState<string | null>(null)
  const [draft, setDraft] = useState<{ boxId: string; date: string } | null>(null)

  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => shiftIso(weekStart, i)), [weekStart])
  const boxes = (data?.boxes || []).filter((b) => b.is_active)
  const bookings = data?.bookings || []
  const requests = bookings.filter((b) => b.status === 'requested')
  const inBox = bookings.filter((b) => b.status === 'in_box').length
  const masterName = (id: string | null) => data?.masters.find((m) => m.id === id)?.name || 'мастер не выбран'
  const open = bookings.find((b) => b.id === openId) || null

  // «+ Записать машину» в меню ведёт сюда с ?new=1 — сразу открываем запись.
  useEffect(() => {
    if (!boxes.length || typeof window === 'undefined') return
    const params = new URLSearchParams(window.location.search)
    if (params.get('new') !== '1') return
    const wanted = boxes.find((b) => b.id === params.get('box'))
    setDraft({ boxId: (wanted || boxes[0]).id, date: days.includes(today) ? today : weekStart })
    params.delete('new')
    params.delete('box')
    const qs = params.toString()
    window.history.replaceState(null, '', window.location.pathname + (qs ? `?${qs}` : ''))
  }, [boxes, days, today, weekStart])

  const header = (
    <DetailingHeader
      title="Запись"
      subtitle={inBox ? `Неделя по боксам · сейчас в боксах: ${inBox}. Нажмите на пустую клетку, чтобы записать.` : 'Неделя по боксам. Нажмите на пустую клетку, чтобы записать. Быстрые работы по часам встают в один бокс друг за другом.'}
    >
      {data ? <CompanyPicker companies={data.companies} value={data.company?.id || ''} onChange={setCompanyId} /> : null}
      <Button variant="outline" size="icon-sm" aria-label="Прошлая неделя" onClick={() => setWeekStart(shiftIso(weekStart, -7))}><ChevronLeft className="h-4 w-4" /></Button>
      <Button variant="outline" size="sm" onClick={() => setWeekStart(mondayOf(new Date()))}>Эта неделя</Button>
      <Button variant="outline" size="icon-sm" aria-label="Следующая неделя" onClick={() => setWeekStart(shiftIso(weekStart, 7))}><ChevronRight className="h-4 w-4" /></Button>
      {boxes.length ? <Button size="sm" onClick={() => setDraft({ boxId: boxes[0].id, date: days.includes(today) ? today : weekStart })}><Plus className="h-4 w-4" />Записать</Button> : null}
    </DetailingHeader>
  )

  if (loading && !data) {
    return <div className="space-y-4">{header}<Skeleton className="h-[420px] w-full rounded-2xl" /></div>
  }
  if (error && !data) {
    return <div className="space-y-4">{header}<div className={`${storeCard} p-6 text-sm text-rose-600`}>{error}</div></div>
  }
  if (!data?.company) return <div className="space-y-4">{header}<NoDetailingPoint /></div>

  if (!boxes.length || !data.services.length) {
    return (
      <div className="space-y-4">
        {header}
        <div className={storeCard}>
          <StoreEmpty
            title={!boxes.length && data.services.length ? 'Нет ни одного бокса' : 'Сначала боксы и прайс'}
            hint={!boxes.length && data.services.length ? 'Запись идёт по боксам. Добавьте боксы — хотя бы один.' : 'Запись идёт по боксам, а цена берётся из прайса по кузову. Заполните их — или поставьте примерный прайс и поправьте под себя.'}
            action={<Link href="/detailing/prices" className="rounded-xl bg-blue-600 px-3.5 py-2 text-sm font-semibold text-white hover:bg-blue-700">Открыть «Прайс»</Link>}
          />
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {header}

      {requests.length ? (
        <div className="flex flex-wrap items-center gap-2 rounded-2xl border border-dashed border-slate-400 px-4 py-3 text-sm">
          <b>Клиенты записались сами: {requests.length}</b>
          {requests.map((r) => (
            <Button key={r.id} variant="outline" size="xs" onClick={() => setOpenId(r.id)}>
              {r.car?.model || r.car?.plate || 'Машина'} · {dayLabel(r.start_date).short}{r.start_time ? `, ${r.start_time}` : ''} · {r.customer?.name}
            </Button>
          ))}
          <span className="text-xs text-muted-foreground">выберите бокс и подтвердите</span>
        </div>
      ) : null}

      {/* Телефон: неделя списком по дням — таблицу 7×4 пальцем не пролистать. */}
      <div className="space-y-3 md:hidden">
        {days.map((d) => {
          const l = dayLabel(d)
          const list = bookings.filter((b) => {
            if (b.status === 'requested' || !b.box_id) return false
            const from = daysBetween(d, b.start_date)
            return b.status === 'no_show' ? from === 0 : from <= 0 && 0 < from + b.days
          })
          const held = list.filter((b) => b.status !== 'no_show')
          // Сначала совсем пустой бокс, потом тот, где стоят только быстрые работы по часам.
          const freeBox = boxes.find((box) => !held.some((b) => b.box_id === box.id)) || boxes.find((box) => !held.some((b) => b.box_id === box.id && !hourRange(b)))
          return (
            <section key={d} className={`${storeCard} overflow-hidden`}>
              <div className={`flex items-center justify-between px-3.5 py-2 ${d === today ? 'bg-blue-600 text-white' : 'bg-slate-50 dark:bg-white/[0.03]'}`}>
                <b className="text-sm">{l.weekday} {l.day} {l.month}{d === today ? ' · сегодня' : ''}</b>
                <span className={`text-xs ${d === today ? 'text-white/80' : 'text-muted-foreground'}`}>занято {new Set(held.map((b) => b.box_id)).size} из {boxes.length}</span>
              </div>
              <div className="divide-y divide-border">
                {list.map((b) => (
                  <button key={b.id} type="button" onClick={() => setOpenId(b.id)} className="flex w-full items-center gap-3 px-3.5 py-2.5 text-left">
                    <span className="min-w-0 flex-1">
                      <span className="flex flex-wrap items-center gap-2 text-sm font-semibold">{b.start_time && b.start_date === d ? <span className="font-mono text-[13px]">{slotTimeLabel(b)}</span> : null}{b.car?.model || b.customer?.name || 'Машина'} <Plate plate={b.car?.plate} /></span>
                      <span className="block truncate text-xs text-muted-foreground">{boxes.find((x) => x.id === b.box_id)?.name} · {b.services.map((x) => x.name).join(', ')}</span>
                    </span>
                    <span className={`shrink-0 rounded-md px-2 py-0.5 text-[11px] font-semibold ${STATUS_TONE[b.status]}`}>{STATUS_LABEL[b.status]}</span>
                  </button>
                ))}
                {freeBox ? (
                  <button type="button" onClick={() => setDraft({ boxId: freeBox.id, date: d })} className="flex w-full items-center gap-2 px-3.5 py-2.5 text-left text-sm font-medium text-blue-600">
                    <Plus className="h-4 w-4" />Записать на этот день
                  </button>
                ) : <div className="px-3.5 py-2.5 text-sm text-muted-foreground">Все боксы заняты</div>}
              </div>
            </section>
          )
        })}
      </div>

      <div className={`${storeCard} hidden overflow-x-auto md:block`}>
        <div className="grid min-w-[860px]" style={{ gridTemplateColumns: '120px repeat(7, minmax(0, 1fr))' }}>
          <div className="border-b border-border bg-slate-50 dark:bg-white/[0.03]" />
          {days.map((d) => {
            const l = dayLabel(d)
            const isToday = d === today
            return (
              <div key={d} className={`border-b border-l border-border px-3 py-2 ${isToday ? 'bg-blue-600 text-white' : 'bg-slate-50 dark:bg-white/[0.03]'}`}>
                <div className="text-sm font-semibold">{l.weekday} {l.day}</div>
                <div className={`text-[11px] ${isToday ? 'text-white/80' : 'text-muted-foreground'}`}>{isToday ? 'сегодня' : l.month}</div>
              </div>
            )
          })}

          {boxes.map((box) => {
            const mine = bookings.filter((b) => b.box_id === box.id && b.status !== 'requested')
            // Быстрые работы по часам идут столбиком в клетке дня, остальные — полосой на свои дни.
            const quick = mine.filter((b) => hourRange(b))
            const list = mine.filter((b) => !hourRange(b))
            const busyDay = (i: number) =>
              list.some((b) => {
                const from = daysBetween(weekStart, b.start_date)
                return b.status !== 'no_show' ? from <= i && i < from + b.days : from === i
              })
            return (
              <div key={box.id} className="contents">
                <div className="flex flex-col justify-center border-t border-border px-3 py-3">
                  <div className="text-sm font-semibold">{box.name}</div>
                  {box.note ? <div className="text-[11px] text-muted-foreground">{box.note}</div> : null}
                </div>
                <div className="col-span-7 grid min-h-[92px] border-t border-border" style={{ gridTemplateColumns: 'repeat(7, minmax(0, 1fr))' }}>
                  {days.map((d, i) => {
                    if (busyDay(i)) return null
                    const stack = quick.filter((b) => b.start_date === d)
                    if (stack.length) {
                      return (
                        <div key={d} style={{ gridColumn: `${i + 1}`, gridRow: 1 }} className="m-1 flex min-w-0 flex-col gap-1">
                          {stack.map((b) => (
                            <button
                              key={b.id}
                              type="button"
                              onClick={() => setOpenId(b.id)}
                              className={`flex min-w-0 flex-col rounded-lg px-2 py-1 text-left transition hover:ring-2 hover:ring-blue-400/50 ${STATUS_TONE[b.status]}`}
                            >
                              <span className="font-mono text-[11px] font-semibold">{slotTimeLabel(b)}</span>
                              <span className="truncate text-[12px] font-semibold text-foreground">{b.car?.model || b.customer?.name || 'Машина'}</span>
                              <span className="truncate text-[11px] text-muted-foreground">{b.services.map((s) => s.name).join(', ')}</span>
                            </button>
                          ))}
                          <button
                            type="button"
                            onClick={() => setDraft({ boxId: box.id, date: d })}
                            aria-label={`Записать ещё в ${box.name}, ${dayLabel(d).short}`}
                            className="rounded-lg border border-dashed border-border py-1 text-[11px] font-medium text-muted-foreground transition hover:border-slate-400 hover:text-foreground"
                          >
                            + ещё
                          </button>
                        </div>
                      )
                    }
                    return (
                      <button
                        key={d}
                        type="button"
                        style={{ gridColumn: `${i + 1}`, gridRow: 1 }}
                        onClick={() => setDraft({ boxId: box.id, date: d })}
                        aria-label={`Записать в ${box.name}, ${dayLabel(d).short}`}
                        className="m-1 rounded-xl border border-dashed border-transparent border-l-border text-xs font-medium text-transparent transition hover:border-slate-300 hover:bg-slate-50 hover:text-muted-foreground focus-visible:text-muted-foreground dark:hover:bg-white/[0.03]"
                      >
                        + записать
                      </button>
                    )
                  })}
                  {list.map((b) => {
                    const from = Math.max(0, daysBetween(weekStart, b.start_date))
                    const to = Math.min(7, daysBetween(weekStart, b.start_date) + b.days)
                    if (to <= 0 || from >= 7) return null
                    return (
                      <button
                        key={b.id}
                        type="button"
                        onClick={() => setOpenId(b.id)}
                        style={{ gridColumn: `${from + 1} / ${to + 1}`, gridRow: 1 }}
                        className={`m-1 flex min-w-0 flex-col gap-0.5 overflow-hidden rounded-xl px-2.5 py-2 text-left transition hover:ring-2 hover:ring-blue-400/50 ${STATUS_TONE[b.status]}`}
                      >
                        <span className="truncate text-[13px] font-semibold text-foreground">{b.start_time ? <span className="font-mono text-[12px]">{b.start_time} </span> : null}{b.car?.model || b.customer?.name || 'Машина'}</span>
                        <span><Plate plate={b.car?.plate} /></span>
                        <span className="truncate text-[11px] text-muted-foreground">{b.services.map((s) => s.name).join(', ')}</span>
                        <span className="truncate text-[11px] text-muted-foreground">
                          {masterName(b.master_operator_id)} · {b.status === 'no_show' ? 'не приехал' : b.due > 0 ? `осталось ${money(b.due)}` : 'оплачено'}
                        </span>
                      </button>
                    )
                  })}
                </div>
              </div>
            )
          })}
        </div>
      </div>

      <div className="hidden flex-wrap items-center gap-2 text-xs text-muted-foreground md:flex">
        {(['booked', 'in_box', 'done', 'no_show'] as BookingStatus[]).map((s) => (
          <span key={s} className={`rounded-md px-2 py-0.5 font-medium ${STATUS_TONE[s]}`}>{STATUS_LABEL[s]}</span>
        ))}
        <span>Пустая клетка: нажмите, бокс и день уже выбраны.</span>
      </div>

      {draft ? (
        <NewBookingModal
          data={data}
          days={days}
          initial={draft}
          onClose={() => setDraft(null)}
          onSaved={async (id) => {
            setDraft(null)
            await refresh()
            setOpenId(id)
          }}
        />
      ) : null}

      {open ? (
        <BookingModal
          booking={open}
          data={data}
          onClose={() => setOpenId(null)}
          onChanged={refresh}
        />
      ) : null}
    </div>
  )
}

/* ── Новая запись ───────────────────────────────────────────────────────── */

type KnownClient = { id: string; name: string; phone: string | null; cars: Array<{ id: string; plate: string; model: string | null; body_class_id: string | null }> }

function NewBookingModal(props: {
  data: WeekData
  days: string[]
  initial: { boxId: string; date: string }
  onClose: () => void
  onSaved: (id: string) => void
}) {
  const { data } = props
  const classes = data.classes.filter((c) => c.is_active)
  const services = data.services.filter((s) => s.is_active)
  const boxes = data.boxes.filter((b) => b.is_active)
  const today = isoLocal(new Date())
  const [phone, setPhone] = useState('')
  const [plate, setPlate] = useState('')
  const [name, setName] = useState('')
  const [model, setModel] = useState('')
  const [classId, setClassId] = useState(classes[0]?.id || '')
  const [picked, setPicked] = useState<string[]>([])
  const [date, setDate] = useState(props.initial.date)
  const [time, setTime] = useState('')
  // undefined — длительность берём из услуг; null — весь день; число — выбрали сами.
  const [durPick, setDurPick] = useState<number | null | undefined>(undefined)
  const [boxId, setBoxId] = useState(props.initial.boxId)
  const [masterId, setMasterId] = useState('')
  const [comment, setComment] = useState('')
  const [saving, setSaving] = useState(false)
  const [known, setKnown] = useState<KnownClient | null>(null)

  // Постоянный клиент: по 6+ цифрам телефона подтягиваем имя и машины.
  const digits = phone.replace(/\D/g, '')
  useEffect(() => {
    if (digits.length < 6) {
      setKnown(null)
      return
    }
    const ctrl = new AbortController()
    const t = setTimeout(() => {
      fetch(`/api/admin/detailing/clients?company_id=${data.company!.id}&q=${digits}`, { signal: ctrl.signal })
        .then((r) => r.json())
        .then((j) => setKnown((j?.data?.clients || [])[0] || null))
        .catch(() => {})
    }, 300)
    return () => {
      clearTimeout(t)
      ctrl.abort()
    }
  }, [digits, data.company])

  const applyCar = (car: KnownClient['cars'][number]) => {
    setPlate(car.plate)
    setModel(car.model || '')
    if (car.body_class_id && classes.some((c) => c.id === car.body_class_id)) setClassId(car.body_class_id)
    if (known) setName(known.name)
  }
  // Нашли клиента с одной машиной — подставляем сразу, как в блокноте «тот же, что в прошлый раз».
  useEffect(() => {
    if (known && known.cars.length === 1 && !plate.trim()) applyCar(known.cars[0])
    else if (known && !name.trim()) setName(known.name)
  }, [known]) // eslint-disable-line react-hooks/exhaustive-deps

  const priceOf = (s: Service) => s.prices.find((p) => p.body_class_id === classId) || null
  const lines = services.filter((s) => picked.includes(s.id) && priceOf(s))
  const total = lines.reduce((sum, s) => sum + (priceOf(s)?.price || 0), 0)
  // Все услуги быстрые — запись на часы; есть «дневная» — дни считаем по дневным.
  const quickSum = lines.length && lines.every((s) => s.duration_minutes) ? lines.reduce((sum, s) => sum + (s.duration_minutes || 0), 0) : null
  const daysNeeded = quickSum ? 1 : Math.max(1, lines.filter((s) => !s.duration_minutes).reduce((sum, s) => sum + s.days, 0))
  const duration = daysNeeded === 1 ? (durPick === undefined ? quickSum : durPick) : null
  const durations = [...new Set([...DURATION_CHOICES, ...(quickSum ? [quickSum] : [])])].sort((a, b) => a - b)
  const slot = { start_date: date, days: daysNeeded, start_time: time || null, duration_minutes: duration }
  const byHours = !!hourRange(slot)

  // Занятость считаем по записям загруженной недели; для дней за её пределами сервер проверит сам.
  const holds = (b: Booking) => ['booked', 'in_box', 'done'].includes(b.status)
  const busyBox = (id: string) => data.bookings.some((b) => b.box_id === id && holds(b) && slotsOverlap(slot, b))
  // Кто уже стоит по часам в выбранном боксе в этот день — чтобы видеть свободное время.
  const neighbours = data.bookings.filter((b) => b.box_id === boxId && holds(b) && b.start_date === date && hourRange(b))
  const conflict = lines.length > 0 && busyBox(boxId)
  const ready = digits.length >= 10 && plate.trim() && lines.length && classId && !conflict

  // Ближайшие дни кнопками; остальное — через календарь.
  const quickDays = Array.from({ length: 6 }, (_, i) => shiftIso(today, i))
  const dayName = (iso: string, i: number) => (i === 0 ? 'сегодня' : i === 1 ? 'завтра' : dayLabel(iso).short.toLowerCase())

  const save = async () => {
    setSaving(true)
    try {
      const res = await postDetailing<{ id: string }>(API, {
        action: 'create',
        company_id: data.company!.id,
        phone,
        plate,
        client_name: name,
        model,
        body_class_id: classId,
        service_ids: lines.map((s) => s.id),
        start_date: date,
        start_time: time || null,
        duration_minutes: duration,
        box_id: boxId,
        master_operator_id: masterId || null,
        comment,
      })
      toast({ title: 'Записано', description: `${plate.toUpperCase()} · ${dayLabel(date).short}${time ? `, ${time}` : ''}` })
      props.onSaved(res.id)
    } catch (e: any) {
      toast({ title: 'Не записалось', description: e.message, variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  const label = 'text-[11px] font-bold uppercase tracking-wider text-muted-foreground'
  const pill = (on: boolean, off = false) =>
    `min-h-10 rounded-xl border-[1.5px] px-3 py-2 text-left text-sm font-semibold transition ${
      on ? 'border-blue-600 bg-blue-600/10 text-blue-700 dark:text-blue-200' : off ? 'border-border text-muted-foreground opacity-50' : 'border-border hover:border-slate-400'
    }`

  return (
    <AppModal
      open
      onClose={props.onClose}
      title="Записать машину"
      maxWidth="max-w-xl"
      footer={
        <div className="space-y-2">
          <div className="flex items-center justify-between rounded-xl bg-slate-50 px-3.5 py-2.5 dark:bg-white/[0.03]">
            <span className="text-sm text-muted-foreground">
              {!lines.length ? 'Выберите услугу' : byHours && duration ? `По прайсу · ${durationLabel(duration)} в боксе` : `По прайсу · ${daysNeeded} ${daysNeeded === 1 ? 'день' : daysNeeded < 5 ? 'дня' : 'дней'} в боксе`}
            </span>
            <b className="font-[family-name:var(--font-manrope)] text-xl tabular-nums">{money(total)}</b>
          </div>
          <Button className="h-11 w-full text-[15px]" onClick={save} disabled={!ready || saving}>{saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Записать</Button>
        </div>
      }
    >
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="space-y-1.5"><span className={label}>Телефон</span><Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" placeholder="+7 701 555 12 40" autoFocus className="h-11" /></label>
          <label className="space-y-1.5"><span className={label}>Госномер</span><Input value={plate} onChange={(e) => setPlate(e.target.value)} placeholder="777 ABC 02" className="h-11 uppercase" /></label>
        </div>

        {known ? (
          <div className="rounded-xl bg-emerald-500/10 px-3 py-2.5 text-sm font-medium text-emerald-800 dark:text-emerald-200">
            {known.name} уже был у вас{known.cars.length > 1 ? ' — выберите машину:' : known.cars.length === 1 ? ' — машина подставлена.' : '.'}
            {known.cars.length > 1 ? (
              <div className="mt-2 flex flex-wrap gap-2">
                {known.cars.map((car) => (
                  <button key={car.id} type="button" onClick={() => applyCar(car)} className="flex items-center gap-2 rounded-lg border border-emerald-600/30 bg-white px-2 py-1 text-xs text-foreground hover:border-emerald-600 dark:bg-slate-900/60">
                    {car.model || 'Машина'} <Plate plate={car.plate} />
                  </button>
                ))}
              </div>
            ) : null}
          </div>
        ) : null}

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="space-y-1.5"><span className={label}>Машина</span><Input value={model} onChange={(e) => setModel(e.target.value)} placeholder="Toyota Camry 70" /></label>
          <label className="space-y-1.5"><span className={label}>Имя — можно потом</span><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Арман" /></label>
        </div>

        <div className="space-y-1.5">
          <div className={label}>Кузов</div>
          <div className="flex flex-wrap gap-1.5">
            {classes.map((c) => <button key={c.id} type="button" aria-pressed={classId === c.id} onClick={() => setClassId(c.id)} className={pill(classId === c.id)}>{c.name}</button>)}
          </div>
        </div>

        <div className="space-y-1.5">
          <div className={label}>Что делаем</div>
          <div className="flex flex-wrap gap-1.5">
            {services.map((s) => {
              const p = priceOf(s)
              const on = picked.includes(s.id) && !!p
              return (
                <button
                  key={s.id}
                  type="button"
                  aria-pressed={on}
                  disabled={!p}
                  title={p ? undefined : 'Для этого кузова цены нет'}
                  onClick={() => setPicked(on ? picked.filter((x) => x !== s.id) : [...picked, s.id])}
                  className={pill(on, !p)}
                >
                  <span className="block">{s.name}</span>
                  <span className="block text-xs font-medium text-muted-foreground">{p ? money(p.price) : 'нет цены'}{s.duration_minutes ? ` · ${durationLabel(s.duration_minutes)}` : s.days > 1 ? ` · ${s.days} дн` : ''}</span>
                </button>
              )
            })}
          </div>
        </div>

        <div className="space-y-1.5">
          <div className={label}>Когда</div>
          <div className="flex flex-wrap items-center gap-1.5">
            {quickDays.map((d, i) => <button key={d} type="button" aria-pressed={date === d} onClick={() => setDate(d)} className={pill(date === d)}>{dayName(d, i)}</button>)}
            <Input type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="Другой день" className="h-10 w-[150px]" />
          </div>
        </div>

        <div className="space-y-1.5">
          <div className={label}>Во сколько приедет</div>
          <div className="flex flex-wrap items-center gap-1.5">
            {QUICK_TIMES.map((t) => <button key={t} type="button" aria-pressed={time === t} onClick={() => setTime(time === t ? '' : t)} className={pill(time === t)}>{t}</button>)}
            <Input type="time" value={time} onChange={(e) => setTime(e.target.value)} aria-label="Другое время" className="h-10 w-[120px]" />
          </div>
          {!time ? <p className="text-xs text-muted-foreground">{duration ? 'Выберите время — без него бокс будет занят весь день.' : 'Не выбрали — запись будет на день без времени.'}</p> : null}
        </div>

        {daysNeeded === 1 ? (
          <div className="space-y-1.5">
            <div className={label}>Сколько займёт</div>
            <div className="flex flex-wrap items-center gap-1.5">
              <button type="button" aria-pressed={!duration} onClick={() => setDurPick(null)} className={pill(!duration)}>Весь день</button>
              {durations.map((m) => <button key={m} type="button" aria-pressed={duration === m} onClick={() => setDurPick(m)} className={pill(duration === m)}>{durationLabel(m)}</button>)}
            </div>
            <p className="text-xs text-muted-foreground">Быстрая работа занимает бокс только на свои часы — в этот же день в него можно записать ещё машину.</p>
          </div>
        ) : null}

        <div className="space-y-1.5">
          <div className={label}>Бокс</div>
          <div className="flex flex-wrap gap-1.5">
            {boxes.map((b) => {
              const busy = lines.length > 0 && busyBox(b.id)
              return (
                <button key={b.id} type="button" aria-pressed={boxId === b.id} onClick={() => setBoxId(b.id)} className={pill(boxId === b.id, busy && boxId !== b.id)}>
                  {b.name}{busy ? <span className="block text-xs font-medium text-rose-600">занят</span> : null}
                </button>
              )
            })}
          </div>
          {neighbours.length ? (
            <p className="text-xs text-muted-foreground">
              В этом боксе в этот день уже: {neighbours.map((b) => `${slotTimeLabel(b)} ${b.car?.model || b.car?.plate || 'машина'}`).join(' · ')}
            </p>
          ) : null}
          {conflict ? <p className="text-sm font-medium text-rose-600">{byHours ? 'В это время бокс занят. Выберите другое время или бокс.' : 'Этот бокс занят в эти дни. Выберите другой бокс или день.'}</p> : null}
        </div>

        {data.masters.length ? (
          <div className="space-y-1.5">
            <div className={label}>Мастер — можно потом</div>
            <div className="flex flex-wrap gap-1.5">
              {data.masters.map((m) => <button key={m.id} type="button" aria-pressed={masterId === m.id} onClick={() => setMasterId(masterId === m.id ? '' : m.id)} className={pill(masterId === m.id)}>{m.name}</button>)}
            </div>
          </div>
        ) : null}

        <label className="block space-y-1.5"><span className={label}>Комментарий — по желанию</span><Input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Скол на капоте, привезёт к 10:00" /></label>
      </div>
    </AppModal>
  )
}

/* ── Карточка записи ─────────────────────────────────────────────────────── */

function BookingModal(props: { booking: Booking; data: WeekData; onClose: () => void; onChanged: () => Promise<void> }) {
  const b = props.booking
  const { data } = props
  const [busy, setBusy] = useState(false)
  const orgName = useOrgName()
  const [materials, setMaterials] = useState(String(Math.round(b.materials_total)))
  const [time, setTime] = useState(b.start_time || '')
  useEffect(() => setTime(b.start_time || ''), [b.start_time])
  const [boxId, setBoxId] = useState(b.box_id || data.boxes.find((x) => x.is_active)?.id || '')
  useEffect(() => setMaterials(String(Math.round(b.materials_total))), [b.materials_total])

  const run = async (body: Record<string, unknown>, done: string) => {
    setBusy(true)
    try {
      await postDetailing(API, { id: b.id, ...body })
      toast({ title: done })
      await props.onChanged()
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
    } finally {
      setBusy(false)
    }
  }

  const base = Math.max(0, b.price_total - b.materials_total)
  const lastDay = shiftIso(b.start_date, b.days - 1)
  const range = b.days > 1 ? `${dayLabel(b.start_date).short} – ${dayLabel(lastDay).short} ${dayLabel(lastDay).month}` : `${dayLabel(b.start_date).short} ${dayLabel(b.start_date).month}`
  const box = data.boxes.find((x) => x.id === b.box_id)

  return (
    <AppModal
      open
      onClose={props.onClose}
      title={b.car?.model || 'Машина'}
      description={`${box ? `${box.name} · ` : ''}${range}${b.start_time ? `, ${slotTimeLabel(b)}` : ''}`}
      maxWidth="max-w-lg"
    >
      <div className="space-y-4 text-sm">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Plate plate={b.car?.plate} />
          <span className={`rounded-md px-2 py-0.5 text-xs font-semibold ${STATUS_TONE[b.status]}`}>{STATUS_LABEL[b.status]}</span>
        </div>
        <div className="text-muted-foreground">{b.customer?.name} · {formatPhone(b.customer?.phone || null)}</div>

        {b.status === 'requested' ? (
          <div className="space-y-2 rounded-xl border border-border p-3">
            <div className="font-medium">Подтвердить заявку</div>
            <div className="flex gap-2">
              <NativeSelect value={boxId} onChange={(e) => setBoxId(e.target.value)} className="flex-1">
                {data.boxes.filter((x) => x.is_active).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
              </NativeSelect>
              <Button
                disabled={busy || !boxId}
                onClick={async () => {
                  await run({ action: 'update', box_id: boxId }, 'Бокс выбран')
                  await run({ action: 'status', status: 'booked' }, 'Заявка в неделе')
                }}
              >
                Поставить
              </Button>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-3 gap-1.5">
            {(['booked', 'in_box', 'done'] as BookingStatus[]).map((s) => (
              <button
                key={s}
                type="button"
                disabled={busy}
                onClick={() => run({ action: 'status', status: s }, STATUS_LABEL[s])}
                className={`rounded-lg border px-2 py-1.5 text-xs font-semibold transition ${b.status === s ? 'border-blue-600 bg-blue-600 text-white' : 'border-border text-muted-foreground hover:border-slate-400'}`}
              >
                {STATUS_LABEL[s]}
              </button>
            ))}
          </div>
        )}

        <div className="space-y-1.5 border-y border-border py-3">
          {b.services.map((s) => (
            <div key={s.id} className="flex justify-between gap-3"><span>{s.name}</span><span className="tabular-nums">{money(s.price)}</span></div>
          ))}
          <div className="flex justify-between gap-3 font-semibold"><span>Итого</span><span className="tabular-nums">{money(b.price_total)}</span></div>
          {b.paid > 0 ? <div className="flex justify-between gap-3 text-muted-foreground"><span>Оплачено</span><span className="tabular-nums">−{money(b.paid)}</span></div> : null}
          {b.due > 0 ? <div className={`flex justify-between gap-3 font-semibold ${b.status === 'done' ? 'text-rose-600' : ''}`}><span>{b.status === 'done' ? 'Долг клиента' : 'Осталось'}</span><span className="tabular-nums">{money(b.due)}</span></div> : null}
        </div>

        <div className="space-y-2 rounded-xl bg-slate-50 p-3 dark:bg-white/[0.03]">
          <div className="flex items-center justify-between gap-3">
            <span className="text-muted-foreground">Во сколько приедет</span>
            <Input
              type="time"
              value={time}
              onChange={(e) => setTime(e.target.value)}
              onBlur={() => time !== (b.start_time || '') && run({ action: 'update', start_time: time || null }, time ? `Время: ${time}` : 'Время убрано')}
              aria-label="Во сколько приедет"
              className="h-8 w-[120px]"
            />
          </div>
          {b.days === 1 ? (
            <div className="flex items-center justify-between gap-3">
              <span className="text-muted-foreground">Сколько займёт{b.duration_minutes && !b.start_time ? ' — нужно время' : ''}</span>
              <NativeSelect
                value={String(b.duration_minutes || '')}
                onChange={(e) => run({ action: 'update', duration_minutes: Number(e.target.value) || null }, e.target.value ? `Займёт ${durationLabel(Number(e.target.value))}` : 'Бокс занят на весь день')}
                aria-label="Сколько займёт"
                className="h-8 max-w-[180px]"
              >
                <option value="">Весь день</option>
                {[...new Set([...DURATION_CHOICES, ...(b.duration_minutes ? [b.duration_minutes] : [])])].sort((x, y) => x - y).map((m) => <option key={m} value={m}>{durationLabel(m)}</option>)}
              </NativeSelect>
            </div>
          ) : null}
          <div className="flex items-center justify-between gap-3">
            <span className="text-muted-foreground">Мастер</span>
            <NativeSelect value={b.master_operator_id || ''} onChange={(e) => run({ action: 'update', master_operator_id: e.target.value || null }, 'Мастер сохранён')} className="h-8 max-w-[180px]">
              <option value="">Не выбран</option>
              {data.masters.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
            </NativeSelect>
          </div>
          <div className="flex items-center justify-between gap-3">
            <span className="text-muted-foreground">Материалы {b.materials_manual ? '(поправлено)' : '(из прайса)'}</span>
            <Input
              value={materials}
              onChange={(e) => setMaterials(e.target.value.replace(/[^\d]/g, ''))}
              onBlur={() => Number(materials) !== Math.round(b.materials_total) && run({ action: 'update', materials_total: Number(materials) || 0 }, 'Материалы сохранены')}
              inputMode="numeric"
              className="h-8 w-[130px] text-right tabular-nums"
            />
          </div>
          <div className="flex justify-between gap-3"><span className="text-muted-foreground">Мастеру с суммы</span><b className="tabular-nums">{money(base)}</b></div>
          <p className="text-xs text-muted-foreground">Процент мастера считается с цены минус материалы. Если работали двое, делятся сами.</p>
        </div>

        {b.status !== 'requested' ? <PaymentBlock booking={b} busy={busy} run={run} /> : null}

        {b.customer?.phone && b.status !== 'no_show' ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground">Написать клиенту:</span>
            {b.status === 'booked' || b.status === 'requested' ? <WaButton phone={b.customer.phone} text={waText.confirm(b, orgName)} label="Подтвердить запись" /> : null}
            {b.status === 'in_box' ? <WaButton phone={b.customer.phone} text={waText.ready(b, orgName)} label="Машина готова" /> : null}
            {b.status === 'done' && b.due > 0 ? <WaButton phone={b.customer.phone} text={waText.debt(b, orgName)} label="Напомнить об оплате" /> : null}
            {b.status === 'done' && b.due <= 0 ? <WaButton phone={b.customer.phone} text={waText.hi(b, orgName)} label="Написать" /> : null}
          </div>
        ) : null}

        {b.comment ? <div className="rounded-xl border border-border p-3 text-muted-foreground">{b.comment}</div> : null}

        <div className="flex flex-wrap justify-between gap-2">
          {b.status === 'booked' ? <Button variant="outline" size="sm" disabled={busy} onClick={() => run({ action: 'status', status: 'no_show' }, 'Отмечено: не приехал')}>Не приехал</Button> : <span />}
          {b.status === 'booked' || b.status === 'requested' || b.status === 'no_show' ? (
            <Button variant="outline" size="sm" disabled={busy} onClick={async () => { await run({ action: 'cancel' }, 'Запись отменена'); props.onClose() }}>Отменить запись</Button>
          ) : null}
        </div>
      </div>
    </AppModal>
  )
}

/* ── Оплата ───────────────────────────────────────────────────────────── */

const METHODS: Array<{ id: 'cash' | 'kaspi' | 'card'; label: string }> = [
  { id: 'kaspi', label: 'Kaspi' },
  { id: 'cash', label: 'Наличные' },
  { id: 'card', label: 'Карта' },
]

function PaymentBlock(props: { booking: Booking; busy: boolean; run: (body: Record<string, unknown>, done: string) => Promise<void> }) {
  const b = props.booking
  const [amount, setAmount] = useState(String(Math.round(b.due)))
  const [method, setMethod] = useState<'cash' | 'kaspi' | 'card'>('kaspi')
  useEffect(() => setAmount(String(Math.round(b.due))), [b.due])
  const value = Number(amount) || 0
  const prepay = b.status === 'booked' && b.paid === 0 && value > 0 && value < b.price_total

  return (
    <div className="space-y-2 rounded-xl border border-border p-3">
      {b.payments?.length ? (
        <div className="space-y-1">
          {b.payments.map((p) => (
            <div key={p.id} className="flex items-center justify-between gap-2 text-xs">
              <span className="text-muted-foreground">{p.kind === 'prepayment' ? 'Предоплата' : 'Оплата'} · {METHODS.find((m) => m.id === p.method)?.label} · {dayLabel(p.paid_on).short}</span>
              <span className="flex items-center gap-2">
                <b className="tabular-nums">{money(p.amount)}</b>
                <button type="button" disabled={props.busy} onClick={() => props.run({ action: 'unpay', payment_id: p.id }, 'Оплата возвращена')} className="text-muted-foreground underline-offset-2 hover:text-rose-600 hover:underline">вернуть</button>
              </span>
            </div>
          ))}
        </div>
      ) : null}
      {b.due > 0 ? (
        <>
          <div className="font-medium">{prepay ? 'Принять предоплату' : 'Принять оплату'}</div>
          <div className="flex flex-wrap gap-2">
            <Input value={amount} onChange={(e) => setAmount(e.target.value.replace(/[^\d]/g, ''))} inputMode="numeric" className="h-9 w-[130px] text-right tabular-nums" aria-label="Сумма" />
            <div className="flex gap-1">
              {METHODS.map((m) => (
                <button key={m.id} type="button" onClick={() => setMethod(m.id)} className={`rounded-lg border px-2.5 py-1.5 text-xs font-medium ${method === m.id ? 'border-blue-600 bg-blue-600 text-white' : 'border-border hover:border-slate-400'}`}>{m.label}</button>
              ))}
            </div>
            <Button size="sm" disabled={props.busy || value <= 0 || value > b.due} onClick={() => props.run({ action: 'pay', amount: value, method }, `${money(value)} принято, ушло в доходы`)}>
              Принять {money(value)}
            </Button>
          </div>
          {value > b.due ? <p className="text-xs text-rose-600">Больше, чем осталось ({money(b.due)})</p> : null}
        </>
      ) : <div className="text-sm font-medium text-emerald-600">Оплачено полностью</div>}
    </div>
  )
}
