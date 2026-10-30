'use client'

/**
 * «Сегодня» — боксы нарисованы как гараж. В каждом боксе машина, которая
 * стоит сегодня, и одна главная кнопка: «Приехала — в бокс», «Готово»,
 * «<способ> · сумма и выдать». Пустой бокс — «Записать сюда».
 */

import Link from 'next/link'
import { useState } from 'react'
import { CarFront, Check, Loader2, Plus } from 'lucide-react'

import { toast } from '@/components/ui/use-toast'
import { useApiCache } from '@/lib/client/use-api-cache'
import { formatPhone } from '@/lib/core/format'
import { type Booking, type Box, Plate, isoLocal, money, postDetailing, shiftIso, useDetailingCompanyId } from '@/components/detailing/detailing-shared'

type GarageData = { company: { id: string; name: string } | null; boxes: Box[]; bookings: Booking[]; masters: Array<{ id: string; name: string }> }

const API = '/api/admin/detailing'
const PAY: Array<{ id: 'cash' | 'kaspi' | 'card'; label: string }> = [
  { id: 'kaspi', label: 'Kaspi' },
  { id: 'cash', label: 'Нал' },
  { id: 'card', label: 'Карта' },
]

/** Машина бокса на сегодня: сначала та, что уже стоит, потом записанная на сегодня. */
function carOfBox(bookings: Booking[], boxId: string, today: string) {
  const here = bookings.filter((b) => b.box_id === boxId)
  return (
    here.find((b) => b.status === 'in_box') ||
    here.find((b) => b.status === 'booked' && b.start_date <= today && shiftIso(b.start_date, b.days) > today) ||
    null
  )
}

export function GarageToday() {
  const [companyId] = useDetailingCompanyId()
  const today = isoLocal(new Date())
  const params = new URLSearchParams({ from: today })
  if (companyId) params.set('company_id', companyId)
  const { data, refresh } = useApiCache<GarageData>(`${API}?${params}`, { ttl: 15_000 })
  const [busy, setBusy] = useState<string | null>(null)
  const [ready, setReady] = useState<string | null>(null)

  const boxes = (data?.boxes || []).filter((b) => b.is_active)
  if (!data?.company || !boxes.length) return null
  const bookings = data.bookings || []
  const master = (id: string | null) => data.masters.find((m) => m.id === id)?.name

  const run = async (b: Booking, steps: Array<Record<string, unknown>>, done: string) => {
    setBusy(b.id)
    try {
      for (const s of steps) await postDetailing(API, { id: b.id, ...s })
      toast({ title: done })
      setReady(null)
      await refresh()
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
    } finally {
      setBusy(null)
    }
  }

  const busyCount = boxes.filter((x) => carOfBox(bookings, x.id, today)).length

  return (
    <section className="rounded-2xl bg-[#0b1f3b] p-4 text-white md:p-5">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="font-[family-name:var(--font-manrope)] text-lg font-extrabold">Гараж сейчас</h2>
        <span className="text-sm text-slate-300">занято {busyCount} из {boxes.length} · нажмите на бокс</span>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        {boxes.map((box) => {
          const b = carOfBox(bookings, box.id, today)
          if (!b) {
            return (
              <Link
                key={box.id}
                href={`/detailing?new=1&box=${box.id}`}
                className="flex min-h-[170px] flex-col rounded-xl border-2 border-dashed border-white/15 p-3.5 transition hover:border-blue-400 hover:bg-white/5"
              >
                <span className="text-xs font-bold uppercase tracking-wider text-slate-400">{box.name}</span>
                <span className="m-auto flex flex-col items-center gap-1.5 text-sm text-slate-300">
                  <Plus className="h-5 w-5" /> Свободен — записать сюда
                </span>
              </Link>
            )
          }
          const inBox = b.status === 'in_box'
          const endDay = shiftIso(b.start_date, b.days - 1)
          const isBusy = busy === b.id
          return (
            <div key={box.id} className={`flex min-h-[170px] flex-col gap-2 rounded-xl p-3.5 ${inBox ? 'bg-blue-600/25 ring-1 ring-blue-400/40' : 'bg-white/[0.06] ring-1 ring-white/10'}`}>
              <div className="flex items-center justify-between text-xs font-bold uppercase tracking-wider">
                <span className="text-slate-300">{box.name}</span>
                <span className={inBox ? 'text-blue-300' : 'text-orange-300'}>{inBox ? (endDay <= today ? 'сдать сегодня' : 'в работе') : 'приедет сегодня'}</span>
              </div>
              <Link href="/detailing" className="min-w-0">
                <div className="flex items-center gap-2 truncate font-semibold"><CarFront className="h-4 w-4 shrink-0 text-slate-400" />{b.car?.model || 'Машина'} <Plate plate={b.car?.plate} /></div>
                <div className="mt-0.5 truncate text-xs text-slate-300">{b.services.map((s) => s.name).join(', ') || 'без услуг'}</div>
                <div className="truncate text-xs text-slate-400">
                  {b.customer?.name || formatPhone(b.customer?.phone || '')}{master(b.master_operator_id) ? ` · ${master(b.master_operator_id)}` : ''}
                </div>
              </Link>
              <div className="text-xs text-slate-300">
                {money(b.price_total)}{b.due > 0 ? <span className="text-orange-300"> · осталось {money(b.due)}</span> : <span className="text-emerald-300"> · оплачено</span>}
              </div>

              <div className="mt-auto">
                {isBusy ? (
                  <div className="flex items-center justify-center gap-2 rounded-lg bg-white/10 py-2 text-sm"><Loader2 className="h-4 w-4 animate-spin" />Сохраняю…</div>
                ) : !inBox ? (
                  <button type="button" onClick={() => run(b, [{ action: 'status', status: 'in_box' }], `${b.car?.model || 'Машина'} в боксе`)} className="w-full rounded-lg bg-blue-600 py-2 text-sm font-bold hover:bg-blue-500">
                    Приехала — в бокс
                  </button>
                ) : ready === b.id ? (
                  b.due > 0 ? (
                    <div className="flex flex-col gap-1.5">
                      <span className="text-[11px] text-slate-300">Принять {money(b.due)} и выдать:</span>
                      <div className="grid grid-cols-3 gap-1.5">
                        {PAY.map((m) => (
                          <button
                            key={m.id}
                            type="button"
                            onClick={() => run(b, [{ action: 'pay', amount: b.due, method: m.id, paid_on: today }, { action: 'status', status: 'done' }], `Выдано, принято ${money(b.due)} · ${m.label}`)}
                            className="rounded-lg bg-emerald-500 py-2 text-xs font-bold text-white hover:bg-emerald-400"
                          >
                            {m.label}
                          </button>
                        ))}
                      </div>
                      <button type="button" onClick={() => run(b, [{ action: 'status', status: 'done' }], `Выдано в долг: ${money(b.due)}`)} className="text-[11px] text-slate-400 underline-offset-2 hover:text-white hover:underline">
                        выдать в долг
                      </button>
                    </div>
                  ) : (
                    <button type="button" onClick={() => run(b, [{ action: 'status', status: 'done' }], 'Машина выдана')} className="flex w-full items-center justify-center gap-1.5 rounded-lg bg-emerald-500 py-2 text-sm font-bold hover:bg-emerald-400">
                      <Check className="h-4 w-4" />Выдать
                    </button>
                  )
                ) : (
                  <button type="button" onClick={() => setReady(b.id)} className="w-full rounded-lg bg-white/10 py-2 text-sm font-bold hover:bg-white/20">
                    Готово
                  </button>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </section>
  )
}
