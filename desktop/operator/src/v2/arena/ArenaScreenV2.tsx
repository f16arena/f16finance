import { memo } from 'react'
import { CalendarClock, Download, History, List, Map as MapIcon, Monitor, RefreshCw } from 'lucide-react'

import { BookingDetailsModal } from '@/components/BookingDetailsModal'
import { BookingModal } from '@/components/BookingModal'
import { BookingsPanel } from '@/components/BookingsPanel'
import { BookingTimeline } from '@/components/BookingTimeline'
import type { StationBooking } from '@/lib/api'
import { useCashlessLabels } from '@/lib/use-cashless-labels'
import type { AppConfig, ArenaSession, ArenaStation, ArenaTariff, BootstrapData, OperatorSession } from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'
import { ArenaMapV2 } from './ArenaMapV2'
import { ManageSessionSheet, MassZoneStartSheet, ReceiptSheet, SessionHistorySheet, StartSessionSheet, TechLogSheet } from './ArenaSheets'
import { clock, formatRemaining, money, sessionProgress, stationState, type StationState, type TodayIncome } from './arenaHelpers'
import { useArenaEngine } from './useArenaEngine'

interface Props {
  config: AppConfig
  bootstrap: BootstrapData
  session: OperatorSession
  onLogout: () => void
  onSwitchToShift?: () => void
  onSwitchToSale?: () => void
  onSwitchToReturn?: () => void
  onSwitchToHistory?: () => void
  onSwitchToScanner?: () => void
  onSwitchToRequest?: () => void
  onOpenCabinet?: () => void
}

const TONE: Record<StationState, { color: string; label: string }> = {
  free: { color: 'var(--green)', label: 'Свободна' },
  busy: { color: 'var(--blue)', label: 'Занята' },
  soon: { color: 'var(--orange)', label: 'Скоро конец' },
  expired: { color: 'var(--red)', label: 'Истекло' },
  off: { color: 'var(--muted)', label: 'Отключена' },
}

/** Кольцо остатка времени с таймером внутри. */
function Ring(props: { progress: number; color: string; text: string }) {
  const r = 38
  const c = 2 * Math.PI * r
  return (
    <div className="relative grid h-[92px] w-[92px] place-items-center">
      <svg width="92" height="92" viewBox="0 0 92 92" className="-rotate-90">
        <circle cx="46" cy="46" r={r} fill="none" stroke="var(--fill)" strokeWidth="7" />
        <circle
          cx="46"
          cy="46"
          r={r}
          fill="none"
          stroke={props.color}
          strokeWidth="7"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - props.progress)}
          style={{ transition: 'stroke-dashoffset 1s linear' }}
        />
      </svg>
      <span className="absolute text-[18px] font-bold leading-none" style={{ color: props.color }}>
        {props.text}
      </span>
    </div>
  )
}

const StationCard = memo(function StationCard(props: {
  station: ArenaStation
  activeSession: ArenaSession | undefined
  tariffs: ArenaTariff[]
  now: number
  nextBooking: StationBooking | null
  onStart: () => void
  onManage: () => void
}) {
  const { station, activeSession, tariffs, now } = props
  const state = stationState(station, activeSession, now)
  const tone = TONE[state]
  const tariff = activeSession?.tariff_id ? tariffs.find((t) => t.id === activeSession.tariff_id) : null
  const remainingMs = activeSession ? new Date(activeSession.ends_at).getTime() - now : 0

  return (
    <button
      type="button"
      onClick={activeSession ? props.onManage : props.onStart}
      className="press lift surface flex w-full flex-col rounded-[24px] p-4 text-left"
      style={state === 'soon' || state === 'expired' ? { boxShadow: `inset 0 0 0 2px ${tone.color}, var(--shadow)` } : undefined}
    >
      <div className="flex items-start justify-between gap-2">
        <p className="min-w-0 truncate text-[18px] font-bold leading-snug">{station.name}</p>
        <span className="flex shrink-0 items-center gap-1.5 text-[12px] font-semibold" style={{ color: tone.color }}>
          <span className="h-2 w-2 rounded-full" style={{ background: tone.color }} />
          {tone.label}
        </span>
      </div>

      {activeSession ? (
        <div className="mt-3 flex items-center gap-3">
          <Ring progress={sessionProgress(activeSession, now)} color={tone.color} text={state === 'expired' ? '0:00' : formatRemaining(remainingMs)} />
          <div className="min-w-0 flex-1">
            {tariff ? <p className="muted truncate text-[13px] font-medium">{tariff.name}</p> : null}
            <p className="muted mt-1 text-[13px]">до {clock(activeSession.ends_at)}</p>
            <p className="mt-1 text-[17px] font-bold">{money(activeSession.amount)}</p>
          </div>
        </div>
      ) : (
        <div className="muted my-3 flex h-[92px] flex-col items-center justify-center gap-1.5">
          <Monitor className="h-9 w-9" style={{ color: state === 'free' ? 'var(--green)' : undefined }} />
          <span className="text-[13px] font-medium">
            {props.nextBooking ? `Бронь с ${clock(props.nextBooking.startsAt)}` : state === 'off' ? 'Станция отключена' : 'Готова к запуску'}
          </span>
        </div>
      )}

      <div
        className={`mt-3 flex h-12 items-center justify-center rounded-[16px] text-[15px] font-semibold ${activeSession ? 'fill' : 'btn-green'}`}
      >
        {activeSession ? 'Управление' : 'Запустить'}
      </div>
    </button>
  )
})

function Stat(props: { label: string; value: string; color?: string }) {
  return (
    <div className="surface flex items-center gap-2 rounded-full px-4 py-2">
      {props.color ? <span className="h-2 w-2 rounded-full" style={{ background: props.color }} /> : null}
      <span className="text-[15px] font-bold">{props.value}</span>
      <span className="muted text-[13px]">{props.label}</span>
    </div>
  )
}

function IconButton(props: { title: string; onClick: () => void; children: React.ReactNode; badge?: number }) {
  return (
    <button type="button" title={props.title} aria-label={props.title} onClick={props.onClick} className="press fill relative grid h-11 w-11 place-items-center rounded-full">
      {props.children}
      {props.badge ? (
        <span className="badge pop absolute -right-1 -top-1 min-w-[20px] rounded-full px-1.5 text-[12px] font-bold leading-5">{props.badge}</span>
      ) : null}
    </button>
  )
}

function IncomeCard({ income, cashlessName }: { income: TodayIncome; cashlessName: string }) {
  const total = income.cash + income.kaspi
  if (total === 0 && income.rows.length === 0) return null
  return (
    <div className="surface rounded-[24px] p-5">
      <div className="flex items-center justify-between">
        <span className="muted text-[13px] font-medium uppercase tracking-wide">Касса арены сегодня</span>
        <span className="t-green text-[22px] font-bold">{money(total)}</span>
      </div>
      <div className="mt-2 flex gap-6 text-[14px]">
        {income.cash > 0 ? (
          <span className="muted">
            Нал: <span className="ink font-semibold">{money(income.cash)}</span>
          </span>
        ) : null}
        {income.kaspi > 0 ? (
          <span className="muted">
            {cashlessName}: <span className="ink font-semibold">{money(income.kaspi)}</span>
          </span>
        ) : null}
      </div>
      {income.rows.length > 0 ? (
        <div className="mt-3 flex flex-col gap-1.5">
          {income.rows.slice(-5).map((r, i) => (
            <div key={i} className="muted flex items-center justify-between text-[13px]">
              <span className="truncate">{r.comment || 'Арена'}</span>
              <span className="ink ml-2 shrink-0 font-medium">{money(Number(r.cash_amount) + Number(r.kaspi_amount))}</span>
            </div>
          ))}
          {income.rows.length > 5 ? <span className="muted text-[13px]">...ещё {income.rows.length - 5} записей</span> : null}
        </div>
      ) : null}
    </div>
  )
}

export default function ArenaScreenV2(props: Props) {
  const { config, bootstrap, session } = props
  const e = useArenaEngine(config, session)
  const cashLabels = useCashlessLabels(session)

  const nav: V2Nav = {
    shift: props.onSwitchToShift,
    sale: props.onSwitchToSale,
    return: props.onSwitchToReturn,
    history: props.onSwitchToHistory,
    scanner: props.onSwitchToScanner,
    request: props.onSwitchToRequest,
    cabinet: props.onOpenCabinet,
  }

  const sessionsByStation = new Map(e.sessions.map((s) => [s.station_id, s]))
  const zoneGroups = e.zones
    .map((z) => ({ zone: z, stations: e.stations.filter((s) => s.zone_id === z.id) }))
    .filter((g) => g.stations.length > 0)
  const unzoned = e.stations.filter((s) => !s.zone_id)
  const hasMapData = e.stations.some((s) => s.grid_x != null)
  const sessionsTotal = e.sessions.reduce((sum, s) => sum + (s.amount || 0), 0)

  const operatorName = session.operator.short_name || session.operator.name || session.operator.username
  const deviceName = (bootstrap.device.name || '').trim()
  const operatorLabel = deviceName && deviceName !== (session.company.name || '').trim() ? `${deviceName} · ${operatorName}` : operatorName

  function renderCard(st: ArenaStation) {
    const s = sessionsByStation.get(st.id)
    const next = s ? null : e.bookingsForStation(st.id)[0] ?? null
    return (
      <StationCard
        key={st.id}
        station={st}
        activeSession={s}
        tariffs={e.tariffs}
        now={e.now}
        nextBooking={next}
        onStart={() => e.setStartTarget(st)}
        onManage={() => {
          if (s) e.setManageTarget({ station: st, session: s })
        }}
      />
    )
  }

  const grid = 'grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5'

  return (
    <V2Shell active="arena" nav={nav} companyName={session.company.name || 'Точка'} operatorName={operatorLabel} shiftLabel="Зал" onLogout={props.onLogout}>
      <div className="flex h-full min-h-0 flex-col gap-3 px-4 pb-4 pt-3">
        {/* Панель: сводка слева, действия справа */}
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <Stat label="свободно" value={String(e.stations.length - e.sessions.length)} color="var(--green)" />
          <Stat label="занято" value={String(e.sessions.length)} color="var(--blue)" />
          <Stat label="за сессии" value={money(sessionsTotal)} />

          <div className="ml-auto flex items-center gap-2">
            {hasMapData ? (
              <div className="fill flex rounded-[14px] p-1">
                <button type="button" onClick={() => e.setViewMode('list')} title="Список" className={`press flex h-9 items-center gap-1.5 rounded-[11px] px-3 text-[14px] font-medium ${e.viewMode === 'list' ? 'pill-active' : 'ink-2'}`}>
                  <List className="h-4 w-4" />
                  Список
                </button>
                <button type="button" onClick={() => e.setViewMode('map')} title="Карта" className={`press flex h-9 items-center gap-1.5 rounded-[11px] px-3 text-[14px] font-medium ${e.viewMode === 'map' ? 'pill-active' : 'ink-2'}`}>
                  <MapIcon className="h-4 w-4" />
                  Карта
                </button>
              </div>
            ) : null}
            <IconButton title="Брони" onClick={() => e.setShowBookings(true)} badge={e.bookings.length}>
              <CalendarClock className="h-5 w-5" />
            </IconButton>
            <IconButton title="История сессий" onClick={() => e.setShowHistory(true)}>
              <History className="h-5 w-5" />
            </IconButton>
            {e.sessions.length > 0 ? (
              <IconButton title="Экспорт в CSV" onClick={e.exportSessionsCsv}>
                <Download className="h-5 w-5" />
              </IconButton>
            ) : null}
            <IconButton title="Обновить" onClick={() => void e.loadArena()}>
              <RefreshCw className="h-5 w-5" />
            </IconButton>
          </div>
        </div>

        <main className="min-h-0 flex-1 overflow-y-auto pr-1">
          {e.loading ? (
            <div className="muted flex h-32 items-center justify-center gap-2 text-[14px]">
              <span className="inline-block h-4 w-4 animate-spin rounded-full border-2" style={{ borderColor: 'var(--line)', borderTopColor: 'var(--blue)' }} />
              Загрузка...
            </div>
          ) : e.stations.length === 0 ? (
            <div className="muted flex h-32 flex-col items-center justify-center gap-2">
              <Monitor className="h-8 w-8 opacity-40" />
              <p className="text-[14px]">Нет станций. Добавьте их на сайте Orda.</p>
            </div>
          ) : e.viewMode === 'map' ? (
            <div className="flex flex-col gap-4">
              <BookingTimeline stations={e.stations} bookings={e.bookings} horizonEnd={e.bookingHorizon} previewAt={e.previewAt} onPreview={e.setPreviewAt} />
              <ArenaMapV2
                zones={e.zones}
                stations={e.stations}
                decorations={e.decorations}
                sessions={e.sessions}
                now={e.now}
                bookings={e.bookings}
                previewAt={e.previewAt}
                onStationClick={e.handleStationClick}
              />
              <IncomeCard income={e.todayIncome} cashlessName={cashLabels.providerName} />
            </div>
          ) : (
            <div className="flex flex-col gap-6">
              {zoneGroups.map(({ zone, stations: zoneStations }) => {
                const freeInZone = zoneStations.filter((st) => !sessionsByStation.has(st.id)).length
                return (
                  <section key={zone.id}>
                    <div className="mb-3 flex items-center justify-between gap-2 px-1">
                      <h2 className="text-[20px] font-bold">{zone.name}</h2>
                      {freeInZone > 0 && e.tariffs.some((t) => t.zone_id === zone.id) ? (
                        <button type="button" onClick={() => e.setMassStartTarget(zone)} className="press btn-green h-11 rounded-full px-5 text-[14px] font-semibold">
                          Запустить зону · {freeInZone}
                        </button>
                      ) : null}
                    </div>
                    <div className={grid}>{zoneStations.map(renderCard)}</div>
                  </section>
                )
              })}

              {unzoned.length > 0 ? (
                <section>
                  {zoneGroups.length > 0 ? <h2 className="mb-3 px-1 text-[20px] font-bold">Без зоны</h2> : null}
                  <div className={grid}>{unzoned.map(renderCard)}</div>
                </section>
              ) : null}

              <IncomeCard income={e.todayIncome} cashlessName={cashLabels.providerName} />
            </div>
          )}
        </main>
      </div>

      {e.startTarget && e.tariffs.length > 0 ? (
        <StartSessionSheet
          station={e.startTarget}
          tariffs={e.tariffs}
          zoneId={e.startTarget.zone_id}
          cashlessName={cashLabels.providerName}
          loading={e.actionLoading}
          onConfirm={e.handleStart}
          onCancel={() => e.setStartTarget(null)}
        />
      ) : null}

      {e.bookingTarget ? (
        <BookingModal
          config={config}
          session={session}
          station={e.bookingTarget}
          allStations={e.stations}
          zones={e.zones}
          tariffs={e.tariffs}
          existing={e.bookingsForStation(e.bookingTarget.id)}
          allBookings={e.bookings}
          horizonEnd={e.bookingHorizon}
          onDone={() => {
            e.setBookingTarget(null)
            void e.loadBookings()
          }}
          onCancel={() => e.setBookingTarget(null)}
        />
      ) : null}

      {e.showBookings ? (
        <BookingsPanel
          bookings={e.bookings}
          onSelect={(booking) => {
            e.setShowBookings(false)
            e.setBookingDetails(booking)
          }}
          onClose={() => e.setShowBookings(false)}
        />
      ) : null}

      {e.bookingDetails ? (
        <BookingDetailsModal
          config={config}
          session={session}
          booking={e.bookingDetails}
          groupBookings={e.groupOf(e.bookingDetails)}
          onChanged={() => {
            e.setBookingDetails(null)
            void e.loadBookings()
          }}
          onClose={() => e.setBookingDetails(null)}
          onBookAnother={() => {
            const station = e.stations.find((s) => s.id === e.bookingDetails?.stationId)
            e.setBookingDetails(null)
            if (station) e.setBookingTarget(station)
          }}
        />
      ) : null}

      {e.manageTarget && !e.techTarget ? (
        <ManageSessionSheet
          station={e.manageTarget.station}
          session={e.manageTarget.session}
          tariffs={e.tariffs}
          zones={e.zones}
          now={e.now}
          loading={e.actionLoading}
          cashlessName={cashLabels.providerName}
          onExtend={e.handleExtend}
          onEnd={e.handleEnd}
          onEndWithRefund={e.handleEndWithRefund}
          onTech={() => e.setTechTarget(e.manageTarget!.station)}
          onClose={() => e.setManageTarget(null)}
        />
      ) : null}

      {e.techTarget ? <TechLogSheet station={e.techTarget} loading={e.actionLoading} onConfirm={e.handleTechLog} onCancel={() => e.setTechTarget(null)} /> : null}

      {e.massStartTarget ? (
        <MassZoneStartSheet
          zone={e.massStartTarget}
          tariffs={e.tariffs}
          freeCount={e.freeStationsOfZone(e.massStartTarget.id).length}
          cashlessName={cashLabels.providerName}
          loading={e.actionLoading}
          onConfirm={(tariffId, payMethod, cashAmt, kaspiAmt) => e.handleMassStart(e.massStartTarget!.id, tariffId, payMethod, cashAmt, kaspiAmt)}
          onCancel={() => e.setMassStartTarget(null)}
        />
      ) : null}

      {e.printReceipt ? <ReceiptSheet receipt={e.printReceipt} onClose={() => e.setPrintReceipt(null)} /> : null}

      {e.showHistory ? (
        <SessionHistorySheet config={config} session={session} stations={e.stations} tariffs={e.tariffs} cashlessName={cashLabels.providerName} onClose={() => e.setShowHistory(false)} />
      ) : null}
    </V2Shell>
  )
}
