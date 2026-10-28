import { Map as MapIcon } from 'lucide-react'

import type { StationBooking } from '@/lib/api'
import type { ArenaMapDecoration, ArenaSession, ArenaStation, ArenaZone } from '@/types'

import { clock, formatRemaining, money, stationState, type StationState } from './arenaHelpers'

const MAP_CELL = 70
const MAP_GRID_W = 24
const MAP_GRID_H = 14

const INK = 'var(--muted)'

function DecoIcon({ type, width, height, label, rotation }: { type: string; width: number; height: number; label?: string | null; rotation?: number }) {
  const style: React.CSSProperties = { width, height, display: 'flex', alignItems: 'center', justifyContent: 'center', transform: rotation ? `rotate(${rotation}deg)` : undefined }
  const svg = (scale: number, children: React.ReactNode) => (
    <div style={style}>
      <svg viewBox="0 0 40 40" width={width * scale} height={height * scale} fill="none" style={{ color: INK }}>
        {children}
      </svg>
    </div>
  )

  switch (type) {
    case 'wall':
      return (
        <div style={style}>
          <div style={{ width, height, background: 'repeating-linear-gradient(45deg, var(--fill), var(--fill) 5px, var(--line) 5px, var(--line) 10px)', borderRadius: 3 }} />
        </div>
      )
    case 'label':
      return (
        <div style={{ ...style, padding: 4 }}>
          <span style={{ fontSize: Math.max(10, Math.min(14, width / 5)), color: 'var(--muted)', textAlign: 'center', wordBreak: 'break-word', lineHeight: 1.2, fontWeight: 600 }}>{label || 'Text'}</span>
        </div>
      )
    case 'entrance':
      return svg(0.8, <>
        <rect x="4" y="4" width="32" height="36" rx="2" stroke="var(--blue)" strokeWidth="2.5" fill="none" />
        <path d="M20 4 Q36 4 36 20" stroke="var(--blue)" strokeWidth="2" />
        <circle cx="30" cy="22" r="2" fill="var(--blue)" />
      </>)
    case 'sofa':
      return svg(0.85, <>
        <rect x="3" y="18" width="34" height="16" rx="4" stroke="currentColor" strokeWidth="2" />
        <rect x="3" y="12" width="34" height="10" rx="3" stroke="currentColor" strokeWidth="1.5" />
        <rect x="3" y="12" width="6" height="22" rx="3" stroke="currentColor" strokeWidth="1.5" />
        <rect x="31" y="12" width="6" height="22" rx="3" stroke="currentColor" strokeWidth="1.5" />
      </>)
    case 'desk':
      return svg(0.85, <>
        <rect x="3" y="10" width="34" height="20" rx="3" stroke="currentColor" strokeWidth="2" />
        <line x1="10" y1="30" x2="10" y2="38" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        <line x1="30" y1="30" x2="30" y2="38" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
      </>)
    case 'tv':
      return svg(0.85, <>
        <rect x="3" y="6" width="34" height="24" rx="3" stroke="currentColor" strokeWidth="2" />
        <line x1="20" y1="30" x2="20" y2="36" stroke="currentColor" strokeWidth="2" />
        <line x1="13" y1="36" x2="27" y2="36" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
      </>)
    case 'bar':
      return svg(0.85, <>
        <rect x="3" y="14" width="34" height="6" rx="2" stroke="var(--orange)" strokeWidth="2" />
        <rect x="5" y="20" width="30" height="14" rx="2" stroke="var(--orange)" strokeWidth="1.5" />
        <line x1="13" y1="20" x2="13" y2="34" stroke="var(--orange)" strokeWidth="1" opacity="0.5" />
        <line x1="20" y1="20" x2="20" y2="34" stroke="var(--orange)" strokeWidth="1" opacity="0.5" />
        <line x1="27" y1="20" x2="27" y2="34" stroke="var(--orange)" strokeWidth="1" opacity="0.5" />
      </>)
    case 'column':
      return svg(0.7, <>
        <circle cx="20" cy="20" r="14" stroke="currentColor" strokeWidth="2.5" />
        <circle cx="20" cy="20" r="8" stroke="currentColor" strokeWidth="1.5" />
      </>)
    case 'window':
      return svg(0.8, <>
        <rect x="3" y="3" width="34" height="34" rx="2" stroke="var(--blue)" strokeWidth="2" />
        <line x1="20" y1="3" x2="20" y2="37" stroke="var(--blue)" strokeWidth="1.5" />
        <line x1="3" y1="20" x2="37" y2="20" stroke="var(--blue)" strokeWidth="1.5" />
      </>)
    case 'stairs':
      return svg(0.8, <path d="M5 35 L5 25 L15 25 L15 17 L25 17 L25 9 L35 9 L35 35 Z" stroke="var(--green)" strokeWidth="2" strokeLinejoin="round" />)
    case 'arrow':
      return svg(0.8, <path d="M5 20 L28 20 M20 10 L33 20 L20 30" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />)
    default:
      return <div style={{ ...style, fontSize: Math.min(width, height) * 0.5, opacity: 0.6 }}>?</div>
  }
}

const TONE: Record<StationState, { c: string; label: string }> = {
  free: { c: 'var(--green)', label: 'Свободно' },
  busy: { c: 'var(--blue)', label: '' },
  soon: { c: 'var(--orange)', label: '' },
  expired: { c: 'var(--red)', label: 'Истекло' },
  off: { c: 'var(--muted)', label: 'Выкл.' },
}

export function ArenaMapV2(props: {
  zones: ArenaZone[]
  stations: ArenaStation[]
  decorations: ArenaMapDecoration[]
  sessions: ArenaSession[]
  now: number
  bookings: StationBooking[]
  /** Момент, на который смотрим брони; null — текущий. */
  previewAt?: number | null
  onStationClick: (station: ArenaStation) => void
}) {
  const { zones, stations, decorations, sessions, now, bookings, previewAt, onStationClick } = props
  const sessionsByStation = new Map(sessions.map((s) => [s.station_id, s]))
  const at = previewAt ?? now

  const nextBookingByStation = new Map<string, StationBooking>()
  for (const booking of bookings) {
    if (!booking.stationId) continue
    if (new Date(booking.endsAt).getTime() <= at) continue
    const current = nextBookingByStation.get(booking.stationId)
    if (!current || booking.startsAt < current.startsAt) nextBookingByStation.set(booking.stationId, booking)
  }
  const stationsOnMap = stations.filter((s) => s.grid_x != null && s.grid_y != null)
  const zonesOnMap = zones.filter((z) => z.grid_x != null)

  if (stationsOnMap.length === 0) {
    return (
      <div className="muted flex h-48 flex-col items-center justify-center gap-2">
        <MapIcon className="h-8 w-8 opacity-40" />
        <p className="text-[14px]">Карта не настроена. Настройте расположение станций на сайте Orda.</p>
      </div>
    )
  }

  return (
    <div className="surface overflow-auto rounded-[24px] p-2">
      <div className="surface-2 relative rounded-[18px]" style={{ width: MAP_GRID_W * MAP_CELL, height: MAP_GRID_H * MAP_CELL, minWidth: MAP_GRID_W * MAP_CELL }}>
        <svg className="pointer-events-none absolute inset-0" width={MAP_GRID_W * MAP_CELL} height={MAP_GRID_H * MAP_CELL}>
          {Array.from({ length: MAP_GRID_W + 1 }, (_, i) => (
            <line key={`v${i}`} x1={i * MAP_CELL} y1={0} x2={i * MAP_CELL} y2={MAP_GRID_H * MAP_CELL} stroke="var(--line)" strokeWidth="1" opacity="0.5" />
          ))}
          {Array.from({ length: MAP_GRID_H + 1 }, (_, i) => (
            <line key={`h${i}`} x1={0} y1={i * MAP_CELL} x2={MAP_GRID_W * MAP_CELL} y2={i * MAP_CELL} stroke="var(--line)" strokeWidth="1" opacity="0.5" />
          ))}
        </svg>

        {zonesOnMap.map((zone) => {
          const color = zone.color ?? '#2563eb'
          return (
            <div
              key={zone.id}
              className="pointer-events-none absolute rounded-[16px]"
              style={{
                left: zone.grid_x! * MAP_CELL + 1,
                top: zone.grid_y! * MAP_CELL + 1,
                width: (zone.grid_w ?? 4) * MAP_CELL - 2,
                height: (zone.grid_h ?? 4) * MAP_CELL - 2,
                backgroundColor: color + '14',
                border: `1.5px solid ${color}55`,
              }}
            >
              <div className="absolute inset-x-0 top-0 truncate rounded-t-[14px] px-2.5 py-1 text-[12px] font-bold" style={{ backgroundColor: color + '30', color }}>
                {zone.name}
              </div>
            </div>
          )
        })}

        {decorations.map((deco) => (
          <div
            key={deco.id}
            className="pointer-events-none absolute select-none"
            style={{ left: deco.grid_x * MAP_CELL, top: deco.grid_y * MAP_CELL, width: deco.grid_w * MAP_CELL, height: deco.grid_h * MAP_CELL, opacity: 0.9 }}
          >
            <DecoIcon type={deco.type} width={deco.grid_w * MAP_CELL} height={deco.grid_h * MAP_CELL} label={deco.label} rotation={deco.rotation} />
          </div>
        ))}

        {stationsOnMap.map((station) => {
          const activeSession = sessionsByStation.get(station.id)
          const state = stationState(station, activeSession, now)
          const occupied = !!activeSession
          const remainingMs = activeSession ? new Date(activeSession.ends_at).getTime() - now : 0
          const nextBooking = nextBookingByStation.get(station.id) ?? null
          const bookedNow = nextBooking && new Date(nextBooking.startsAt).getTime() <= at && new Date(nextBooking.endsAt).getTime() > at
          const tone = TONE[state]
          const bg = `color-mix(in srgb, ${tone.c} ${state === 'free' && nextBooking ? 8 : 18}%, var(--card))`
          return (
            <button
              key={station.id}
              type="button"
              onClick={() => onStationClick(station)}
              className="press absolute flex flex-col items-center justify-center rounded-[14px]"
              style={{
                left: station.grid_x! * MAP_CELL + 2,
                top: station.grid_y! * MAP_CELL + 2,
                width: MAP_CELL - 4,
                height: MAP_CELL - 4,
                zIndex: 4,
                gap: 1,
                background: bg,
                boxShadow: `inset 0 0 0 ${nextBooking && !occupied ? '1.5px' : '1px'} color-mix(in srgb, ${tone.c} 60%, transparent)`,
                outline: nextBooking && !occupied ? '1.5px dashed var(--muted)' : undefined,
                outlineOffset: -4,
              }}
            >
              <span className="truncate text-center text-[15px] font-bold leading-none" style={{ maxWidth: MAP_CELL - 8 }}>
                {station.name}
              </span>
              {!occupied && !nextBooking ? (
                <span className="text-[9px] font-semibold uppercase leading-none" style={{ color: tone.c }}>
                  {tone.label}
                </span>
              ) : null}
              {!occupied && nextBooking ? (
                <span className="muted text-[9px] font-semibold uppercase leading-none">
                  {bookedNow ? `Бронь до ${clock(nextBooking.endsAt)}` : `Бронь ${clock(nextBooking.startsAt)}`}
                </span>
              ) : null}
              {occupied && state !== 'expired' ? (
                <span className="text-[15px] font-extrabold leading-none" style={{ color: tone.c }}>
                  {formatRemaining(remainingMs)}
                </span>
              ) : null}
              {state === 'expired' ? (
                <span className="rounded px-1.5 py-px text-[9px] font-bold leading-none text-white" style={{ background: 'var(--red)' }}>
                  ИСТЕКЛО
                </span>
              ) : null}
              {activeSession ? (
                <span className="muted truncate text-center text-[9px] leading-none" style={{ maxWidth: MAP_CELL - 8 }}>
                  {money(activeSession.amount)}
                </span>
              ) : null}
            </button>
          )
        })}
      </div>
    </div>
  )
}
