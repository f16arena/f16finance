'use client'

import Link from 'next/link'
import { useEffect, useMemo, useState } from 'react'
import { CalendarDays, ChevronLeft, ChevronRight, Loader2, Plus } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
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
  NoDetailingPoint,
  Plate,
  STATUS_LABEL,
  STATUS_TONE,
  dayLabel,
  daysBetween,
  isoLocal,
  mondayOf,
  money,
  postDetailing,
  shiftIso,
  useDetailingCompanyId,
} from '@/components/detailing/detailing-shared'

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

  const header = (
    <AdminPageHeader
      title="Запись · неделя"
      description={data?.company ? `${data.company.name} · ${inBox ? `${inBox} в боксах сейчас` : 'боксы по дням'}` : 'Боксы по дням: кто когда стоит'}
      icon={<CalendarDays className="h-5 w-5" />}
      backHref="/dashboard"
      actions={
        <div className="flex flex-wrap items-center gap-2">
          {data ? <CompanyPicker companies={data.companies} value={data.company?.id || ''} onChange={setCompanyId} /> : null}
          <Button variant="outline" size="icon-sm" aria-label="Прошлая неделя" onClick={() => setWeekStart(shiftIso(weekStart, -7))}><ChevronLeft className="h-4 w-4" /></Button>
          <Button variant="outline" size="sm" onClick={() => setWeekStart(mondayOf(new Date()))}>Сегодня</Button>
          <Button variant="outline" size="icon-sm" aria-label="Следующая неделя" onClick={() => setWeekStart(shiftIso(weekStart, 7))}><ChevronRight className="h-4 w-4" /></Button>
          {boxes.length ? <Button size="sm" onClick={() => setDraft({ boxId: boxes[0].id, date: days.includes(today) ? today : weekStart })}><Plus className="h-4 w-4" />Записать</Button> : null}
        </div>
      }
    />
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
            title="Сначала боксы и прайс"
            hint="Запись идёт по боксам, а цена берётся из прайса по кузову. Заполните их — или поставьте примерный прайс и поправьте под себя."
            action={<Link href="/detailing/prices" className="rounded-xl bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-blue-700">Открыть «Прайс и боксы»</Link>}
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
              {r.car?.model || r.car?.plate || 'Машина'} · {dayLabel(r.start_date).short} · {r.customer?.name}
            </Button>
          ))}
          <span className="text-xs text-muted-foreground">выберите бокс и подтвердите</span>
        </div>
      ) : null}

      <div className={`${storeCard} overflow-x-auto`}>
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
            const list = bookings.filter((b) => b.box_id === box.id && b.status !== 'requested')
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
                  {days.map((d, i) =>
                    busyDay(i) ? null : (
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
                    ),
                  )}
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
                        <span className="truncate text-[13px] font-semibold text-foreground">{b.car?.model || b.customer?.name || 'Машина'}</span>
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

      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
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
  const [phone, setPhone] = useState('')
  const [plate, setPlate] = useState('')
  const [name, setName] = useState('')
  const [model, setModel] = useState('')
  const [classId, setClassId] = useState(classes[0]?.id || '')
  const [picked, setPicked] = useState<string[]>([])
  const [date, setDate] = useState(props.initial.date)
  const [boxId, setBoxId] = useState(props.initial.boxId)
  const [masterId, setMasterId] = useState(data.masters[0]?.id || '')
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

  const priceOf = (s: Service) => s.prices.find((p) => p.body_class_id === classId) || null
  const lines = services.filter((s) => picked.includes(s.id))
  const total = lines.reduce((sum, s) => sum + (priceOf(s)?.price || 0), 0)
  const daysNeeded = Math.max(1, lines.reduce((sum, s) => sum + s.days, 0))

  const busyBox = (id: string) => {
    const start = daysBetween(props.days[0], date)
    return data.bookings.some((b) => {
      if (b.box_id !== id || !['booked', 'in_box', 'done'].includes(b.status)) return false
      const from = daysBetween(props.days[0], b.start_date)
      return from < start + daysNeeded && start < from + b.days
    })
  }
  const conflict = picked.length > 0 && busyBox(boxId)
  const noPrice = lines.filter((s) => !priceOf(s))
  const ready = digits.length >= 10 && plate.trim() && picked.length && classId && !conflict && !noPrice.length

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
        service_ids: picked,
        start_date: date,
        box_id: boxId,
        master_operator_id: masterId || null,
        comment,
      })
      toast({ title: 'Записано', description: `${plate.toUpperCase()} · ${dayLabel(date).short}` })
      props.onSaved(res.id)
    } catch (e: any) {
      toast({ title: 'Не записалось', description: e.message, variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <AppModal
      open
      onClose={props.onClose}
      title="Новая запись"
      description="Обязательны только телефон и госномер. Остальное можно дописать потом."
      maxWidth="max-w-xl"
      footer={
        <div className="flex items-center justify-between gap-3">
          <div className="text-sm">
            <span className="text-muted-foreground">{daysNeeded} дн в боксе · </span>
            <b className="tabular-nums">{money(total)}</b>
          </div>
          <Button onClick={save} disabled={!ready || saving}>{saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Записать</Button>
        </div>
      }
    >
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="space-y-1 text-sm"><span className="text-muted-foreground">Телефон *</span><Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" placeholder="+7 701 555 12 40" /></label>
          <label className="space-y-1 text-sm"><span className="text-muted-foreground">Госномер *</span><Input value={plate} onChange={(e) => setPlate(e.target.value)} placeholder="777 ABC 02" /></label>
        </div>

        {known ? (
          <div className="rounded-xl border border-blue-500/30 bg-blue-500/5 p-3 text-sm">
            <div>Уже был: <b>{known.name}</b></div>
            {known.cars.length ? (
              <div className="mt-2 flex flex-wrap gap-2">
                {known.cars.map((car) => (
                  <button
                    key={car.id}
                    type="button"
                    onClick={() => {
                      setPlate(car.plate)
                      setModel(car.model || '')
                      if (car.body_class_id && classes.some((c) => c.id === car.body_class_id)) setClassId(car.body_class_id)
                      setName(known.name)
                    }}
                    className="flex items-center gap-2 rounded-lg border border-border bg-white px-2 py-1 text-xs hover:border-blue-400 dark:bg-slate-900/60"
                  >
                    {car.model || 'Машина'} <Plate plate={car.plate} />
                  </button>
                ))}
              </div>
            ) : (
              <Button variant="outline" size="xs" className="mt-2" onClick={() => setName(known.name)}>Подставить имя</Button>
            )}
          </div>
        ) : null}

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="space-y-1 text-sm"><span className="text-muted-foreground">Имя</span><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="можно потом" /></label>
          <label className="space-y-1 text-sm"><span className="text-muted-foreground">Машина</span><Input value={model} onChange={(e) => setModel(e.target.value)} placeholder="Toyota Camry 70" /></label>
        </div>

        <div className="space-y-1.5 text-sm">
          <span className="text-muted-foreground">Кузов</span>
          <div className="flex flex-wrap gap-2">
            {classes.map((c) => (
              <button
                key={c.id}
                type="button"
                onClick={() => setClassId(c.id)}
                className={`rounded-xl border px-3 py-1.5 text-sm font-medium transition ${classId === c.id ? 'border-blue-600 bg-blue-600 text-white' : 'border-border hover:border-slate-400'}`}
              >
                {c.name}
              </button>
            ))}
          </div>
        </div>

        <div className="space-y-1.5 text-sm">
          <span className="text-muted-foreground">Услуги — цена уже по кузову</span>
          <div className="space-y-1.5">
            {services.map((s) => {
              const p = priceOf(s)
              const on = picked.includes(s.id)
              return (
                <label key={s.id} className={`flex cursor-pointer items-center gap-3 rounded-xl border px-3 py-2 transition ${on ? 'border-blue-500 bg-blue-500/5' : 'border-border'} ${p ? '' : 'opacity-50'}`}>
                  <input type="checkbox" className="h-4 w-4 accent-blue-600" checked={on} disabled={!p} onChange={(e) => setPicked(e.target.checked ? [...picked, s.id] : picked.filter((x) => x !== s.id))} />
                  <span className="min-w-0 flex-1">
                    <span className="font-medium">{s.name}</span>
                    {s.package_note ? <span className="block text-xs text-muted-foreground">пакет: {s.package_note}</span> : null}
                  </span>
                  <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{s.days} дн · {p ? money(p.price) : 'нет цены'}</span>
                </label>
              )
            })}
          </div>
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">День</span>
            <Input type="date" value={date} onChange={(e) => e.target.value && setDate(e.target.value)} />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Бокс</span>
            <NativeSelect value={boxId} onChange={(e) => setBoxId(e.target.value)}>
              {data.boxes.filter((b) => b.is_active).map((b) => (
                <option key={b.id} value={b.id}>{b.name}{picked.length && busyBox(b.id) ? ' — занят' : ''}</option>
              ))}
            </NativeSelect>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Мастер</span>
            <NativeSelect value={masterId} onChange={(e) => setMasterId(e.target.value)}>
              <option value="">Потом</option>
              {data.masters.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
            </NativeSelect>
          </label>
        </div>
        {!data.masters.length ? (
          <p className="text-xs text-muted-foreground">Мастеров пока нет: добавьте сотрудников и назначьте их на эту точку в разделе «Операторы».</p>
        ) : null}

        <label className="block space-y-1 text-sm"><span className="text-muted-foreground">Комментарий</span><Input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Скол на капоте, клиент привезёт к 10:00" /></label>

        {conflict ? <p className="text-sm font-medium text-rose-600">Этот бокс занят в эти дни. Выберите другой бокс или день.</p> : null}
        {noPrice.length ? <p className="text-sm font-medium text-rose-600">Нет цены для этого кузова: {noPrice.map((s) => s.name).join(', ')}</p> : null}
      </div>
    </AppModal>
  )
}

/* ── Карточка записи ─────────────────────────────────────────────────────── */

function BookingModal(props: { booking: Booking; data: WeekData; onClose: () => void; onChanged: () => Promise<void> }) {
  const b = props.booking
  const { data } = props
  const [busy, setBusy] = useState(false)
  const [materials, setMaterials] = useState(String(Math.round(b.materials_total)))
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
      description={`${box ? `${box.name} · ` : ''}${range}`}
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
