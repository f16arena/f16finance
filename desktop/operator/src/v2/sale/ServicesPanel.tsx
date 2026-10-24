import { useEffect, useRef, useState } from 'react'
import { Clock, Plus, Square, Timer } from 'lucide-react'

import { beep } from '@/lib/receipt-html'
import { toastError } from '@/lib/toast'
import type { PointService, PointServiceSession, PointServiceVariant } from '@/types'

/**
 * Услуги на экране продажи: плитки «Теннис — 30 мин / 1 час» и полоса
 * «Идут сейчас» с обратным отсчётом. Таймеры приходят с сервера (их видят все
 * кассы точки), здесь только отсчёт до ends_at.
 */

export const SERVICE_COLOR = '#af52de'
const WARN_MS = 5 * 60_000

function money(value: number) {
  return Math.round(value).toLocaleString('ru-RU')
}

function left(ms: number) {
  const total = Math.max(0, Math.ceil(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const mm = String(m).padStart(h ? 2 : 1, '0')
  return `${h ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`
}

export function ServiceGrid(props: {
  services: PointService[]
  inCart: (variantId: string) => number
  onAdd: (service: PointService, variant: PointServiceVariant) => void
}) {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-3">
      {props.services.map((service) => (
        <div key={service.id} className="surface rounded-[22px] p-4">
          <div className="flex items-center gap-3">
            <div className="grid h-11 w-11 place-items-center rounded-[13px]" style={{ background: `color-mix(in srgb, ${SERVICE_COLOR} 14%, transparent)` }}>
              <Clock className="h-[22px] w-[22px]" style={{ color: SERVICE_COLOR }} />
            </div>
            <div className="min-w-0 flex-1 truncate text-[17px] font-semibold">{service.name}</div>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2">
            {service.variants.map((variant) => {
              const n = props.inCart(variant.id)
              return (
                <button
                  key={variant.id}
                  type="button"
                  onClick={() => props.onAdd(service, variant)}
                  className="press fill relative flex h-[68px] flex-col items-start justify-center rounded-[16px] px-3 text-left"
                  style={n ? { boxShadow: '0 0 0 2px var(--blue)' } : undefined}
                >
                  <span className="truncate text-[15px] font-medium">{variant.name}</span>
                  <span className="text-[18px] font-semibold">{money(variant.price)} ₸</span>
                  {n ? <span className="badge absolute right-2 top-2 grid h-6 min-w-6 place-items-center rounded-full px-1.5 text-[13px] font-semibold">{n}</span> : null}
                </button>
              )
            })}
          </div>
        </div>
      ))}
    </div>
  )
}

export function RunningTimers(props: {
  sessions: PointServiceSession[]
  services: PointService[]
  onExtend: (service: PointService, variant: PointServiceVariant, session: PointServiceSession) => void
  onFinish: (id: string) => void
}) {
  const [now, setNow] = useState(() => Date.now())
  const [extending, setExtending] = useState<string | null>(null)
  const warned = useRef(new Set<string>())

  useEffect(() => {
    if (props.sessions.length === 0) return
    const id = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(id)
  }, [props.sessions.length])

  // Сигнал за 5 минут и когда время вышло — один раз на таймер.
  useEffect(() => {
    for (const s of props.sessions) {
      const rest = new Date(s.ends_at).getTime() - now
      const endKey = `${s.id}:${s.ends_at}:end`
      const warnKey = `${s.id}:${s.ends_at}:warn`
      if (rest <= 0 && !warned.current.has(endKey)) {
        warned.current.add(endKey)
        warned.current.add(warnKey)
        beep('error')
        toastError(`Время вышло: ${s.title}`)
      } else if (rest > 0 && rest <= WARN_MS && !warned.current.has(warnKey)) {
        warned.current.add(warnKey)
        beep('error')
      }
    }
  }, [now, props.sessions])

  if (props.sessions.length === 0) return null

  return (
    <div className="flex gap-2 overflow-x-auto pb-1">
      {props.sessions.map((s) => {
        const rest = new Date(s.ends_at).getTime() - now
        const over = rest <= 0
        const warn = !over && rest <= WARN_MS
        const color = over ? 'var(--red)' : warn ? 'var(--orange)' : SERVICE_COLOR
        const service = props.services.find((x) => x.id === s.service_id) || null
        const timed = service?.variants.filter((v) => v.duration_minutes) || []
        const until = new Date(s.ends_at).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
        return (
          <div key={s.id} className="surface flex shrink-0 items-center gap-3 rounded-[18px] py-2 pl-3 pr-2" style={{ boxShadow: `inset 0 0 0 2px ${color}` }}>
            <Timer className="h-5 w-5 shrink-0" style={{ color }} />
            <div className="min-w-0">
              <div className="truncate text-[14px] font-medium">{s.title}</div>
              <div className="text-[12px] muted">до {until}</div>
            </div>
            <div className="min-w-[64px] text-[20px] font-bold tabular-nums" style={{ color }}>
              {over ? 'вышло' : left(rest)}
            </div>
            {extending === s.id ? (
              <div className="flex items-center gap-1.5">
                {timed.map((v) => (
                  <button
                    key={v.id}
                    type="button"
                    onClick={() => {
                      if (service) props.onExtend(service, v, s)
                      setExtending(null)
                    }}
                    className="press btn-blue h-10 rounded-[12px] px-3 text-[14px] font-semibold"
                  >
                    +{v.name}
                  </button>
                ))}
                <button type="button" onClick={() => setExtending(null)} className="press fill h-10 rounded-[12px] px-3 text-[14px]">
                  Отмена
                </button>
              </div>
            ) : (
              <>
                {timed.length > 0 ? (
                  <button type="button" onClick={() => setExtending(s.id)} className="press fill flex h-10 items-center gap-1 rounded-[12px] px-3 text-[14px] font-medium">
                    <Plus className="h-4 w-4" /> Продлить
                  </button>
                ) : null}
                <button type="button" onClick={() => props.onFinish(s.id)} className="press fill flex h-10 items-center gap-1 rounded-[12px] px-3 text-[14px] font-medium">
                  <Square className="h-3.5 w-3.5" /> Завершить
                </button>
              </>
            )}
          </div>
        )
      })}
    </div>
  )
}
