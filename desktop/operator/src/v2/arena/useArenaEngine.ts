import { useCallback, useEffect, useRef, useState } from 'react'

import * as api from '@/lib/api'
import { toastError, toastInfo } from '@/lib/toast'
import type { AppConfig, ArenaMapDecoration, ArenaSession, ArenaStation, ArenaTariff, ArenaZone, OperatorSession } from '@/types'

import { downloadCsv, getRemainingMs, playBeep, type ArenaReceipt, type PayMethod, type TodayIncome } from './arenaHelpers'

/** Вся логика зала: загрузка, опрос, уведомления, действия. Интерфейс — в ArenaScreenV2. */
export function useArenaEngine(config: AppConfig, session: OperatorSession) {
  const [zones, setZones] = useState<ArenaZone[]>([])
  const [stations, setStations] = useState<ArenaStation[]>([])
  const [tariffs, setTariffs] = useState<ArenaTariff[]>([])
  const [sessions, setSessions] = useState<ArenaSession[]>([])
  const [decorations, setDecorations] = useState<ArenaMapDecoration[]>([])
  const [todayIncome, setTodayIncome] = useState<TodayIncome>({ cash: 0, kaspi: 0, rows: [] })
  const [loading, setLoading] = useState(true)
  const [actionLoading, setActionLoading] = useState(false)
  const [viewMode, setViewMode] = useState<'list' | 'map'>('list')

  // Один общий секундный тик на все карточки и карту.
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])

  const [startTarget, setStartTarget] = useState<ArenaStation | null>(null)
  const [bookingTarget, setBookingTarget] = useState<ArenaStation | null>(null)
  const [bookings, setBookings] = useState<api.StationBooking[]>([])
  const [bookingHorizon, setBookingHorizon] = useState<string | null>(null)
  const [bookingDetails, setBookingDetails] = useState<api.StationBooking | null>(null)
  const [showBookings, setShowBookings] = useState(false)
  const [previewAt, setPreviewAt] = useState<number | null>(null)
  const [manageTarget, setManageTarget] = useState<{ station: ArenaStation; session: ArenaSession } | null>(null)
  const [massStartTarget, setMassStartTarget] = useState<ArenaZone | null>(null)
  const [techTarget, setTechTarget] = useState<ArenaStation | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [printReceipt, setPrintReceipt] = useState<ArenaReceipt | null>(null)

  const notifiedRef = useRef<Set<string>>(new Set())
  const bookingRemindedRef = useRef<Set<string>>(new Set())
  const prevSessionIdsRef = useRef<Set<string>>(new Set())
  const fetchInFlightRef = useRef(false)

  // ─── Загрузка ──────────────────────────────────────────────────────────────
  const loadArena = useCallback(async () => {
    if (fetchInFlightRef.current) return
    fetchInFlightRef.current = true
    try {
      const data = await api.getArena(config, session)
      setZones(data.zones)
      setStations(data.stations)
      setTariffs(data.tariffs)
      const newSessions: ArenaSession[] = data.sessions
      const newIds = new Set(newSessions.map((s: ArenaSession) => s.id))
      const endedCount = [...prevSessionIdsRef.current].filter((id) => !newIds.has(id)).length
      if (endedCount > 0 && prevSessionIdsRef.current.size > 0) {
        for (let i = 0; i < endedCount; i++) playBeep(440, i * 0.15)
      }
      prevSessionIdsRef.current = newIds
      setSessions(newSessions)
      setDecorations(data.decorations ?? [])
      if (data.today_income) setTodayIncome(data.today_income)
    } catch (err: any) {
      toastError(err?.message || 'Не удалось загрузить зал')
    } finally {
      fetchInFlightRef.current = false
      setLoading(false)
    }
  }, [config, session])

  const pollIntervalRef = useRef<number | null>(null)

  useEffect(() => {
    void loadArena()
    const handleVisibility = () => {
      if (document.visibilityState === 'visible') void loadArena()
    }
    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [loadArena])

  // Адаптивный опрос: 5 с при активных сессиях, 20 с в простое.
  useEffect(() => {
    if (pollIntervalRef.current !== null) window.clearInterval(pollIntervalRef.current)
    const ms = sessions.length > 0 ? 5_000 : 20_000
    pollIntervalRef.current = window.setInterval(() => void loadArena(), ms)
    return () => {
      if (pollIntervalRef.current !== null) window.clearInterval(pollIntervalRef.current)
    }
  }, [sessions.length, loadArena])

  // ─── Уведомление «осталось 5 минут» ────────────────────────────────────────
  useEffect(() => {
    for (const s of sessions) {
      if (notifiedRef.current.has(s.id)) continue
      const remaining = getRemainingMs(s.ends_at)
      if (remaining > 0 && remaining <= 5 * 60_000) {
        notifiedRef.current.add(s.id)
        void api.notifyArena5min(config, session, s.id)
        const st = stations.find((x) => x.id === s.station_id)
        toastInfo(`${st?.name ?? 'Станция'}: осталось менее 5 мин`, 8000)
        playBeep(880)
      }
    }
  }, [sessions, stations, config, session])

  useEffect(() => {
    const activeIds = new Set(sessions.map((s) => s.id))
    for (const id of notifiedRef.current) {
      if (!activeIds.has(id)) notifiedRef.current.delete(id)
    }
  }, [sessions])

  // ─── Напоминание о брони за 15 минут ───────────────────────────────────────
  useEffect(() => {
    for (const booking of bookings) {
      const key = booking.groupId || booking.id
      if (bookingRemindedRef.current.has(key)) continue
      const startsIn = new Date(booking.startsAt).getTime() - now
      if (startsIn > 0 && startsIn <= 15 * 60_000) {
        bookingRemindedRef.current.add(key)
        const groupStations = bookings
          .filter((b) => (b.groupId ? b.groupId === booking.groupId : b.id === booking.id))
          .map((b) => b.stationName)
          .filter(Boolean)
          .join(', ')
        toastInfo(`Через 15 минут бронь: ${groupStations || 'станция'}` + (booking.name ? ` — ${booking.name}` : ''), 12000)
        playBeep(660)
      }
    }
  }, [bookings, now])

  useEffect(() => {
    const liveKeys = new Set(bookings.map((b) => b.groupId || b.id))
    for (const key of bookingRemindedRef.current) {
      if (!liveKeys.has(key)) bookingRemindedRef.current.delete(key)
    }
  }, [bookings])

  const loadBookings = useCallback(async () => {
    try {
      const result = await api.fetchBookings(config, session)
      setBookings(result.bookings)
      setBookingHorizon((result as any).horizonEnd ?? null)
    } catch {
      // Брони — вспомогательный слой: их недоступность не мешает работе зала.
    }
  }, [config, session])

  useEffect(() => {
    void loadBookings()
    const timer = setInterval(() => void loadBookings(), 60_000)
    return () => clearInterval(timer)
  }, [loadBookings])

  // ─── Действия ──────────────────────────────────────────────────────────────
  async function handleStart(tariffId: string, payMethod: PayMethod, cashAmt: number, kaspiAmt: number, discPct: number) {
    if (!startTarget) return
    setActionLoading(true)
    try {
      const newSession = await api.startArenaSession(config, session, {
        stationId: startTarget.id,
        tariffId,
        operatorId: session.operator.operator_id,
        payment_method: payMethod,
        cash_amount: cashAmt,
        kaspi_amount: kaspiAmt,
        discount_percent: discPct,
      })
      setSessions((prev) => [...prev, newSession])
      const usedTariff = tariffs.find((t) => t.id === tariffId)
      const startMs = new Date(newSession.started_at).getTime()
      const endMs = new Date(newSession.ends_at).getTime()
      const durationMin = Math.round((endMs - startMs) / 60000)
      setPrintReceipt({
        stationName: startTarget.name,
        tariffName: usedTariff?.name ?? 'Тариф',
        durationMin,
        startedAt: newSession.started_at,
        endsAt: newSession.ends_at,
        amount: newSession.amount,
        cashAmount: newSession.cash_amount,
        kaspiAmount: newSession.kaspi_amount,
        paymentMethod: newSession.payment_method,
        operatorName: session.operator.short_name || session.operator.name || session.operator.username,
        clubName: session.company.name,
      })
      setStartTarget(null)
      toastInfo(`Сессия запущена: ${startTarget.name}`)
    } catch (err: any) {
      if (err?.message === 'station-already-occupied') {
        toastError('Станция уже занята. Обновите список.')
        void loadArena()
      } else {
        toastError(err?.message || 'Не удалось запустить сессию')
      }
    } finally {
      setActionLoading(false)
    }
  }

  async function handleEnd() {
    if (!manageTarget) return
    setActionLoading(true)
    try {
      await api.endArenaSession(config, session, manageTarget.session.id)
      setSessions((prev) => prev.filter((s) => s.id !== manageTarget.session.id))
      setManageTarget(null)
    } catch (err: any) {
      toastError(err?.message || 'Не удалось завершить сессию')
    } finally {
      setActionLoading(false)
    }
  }

  async function handleEndWithRefund() {
    if (!manageTarget) return
    setActionLoading(true)
    try {
      const { refund } = await api.endArenaSessionWithRefund(config, session, manageTarget.session.id)
      setSessions((prev) => prev.filter((s) => s.id !== manageTarget.session.id))
      setManageTarget(null)
      if (refund.amount > 0) {
        toastInfo(`Возврат: ${refund.amount.toLocaleString('ru-RU')} ₸${refund.cash > 0 ? ` (нал ${refund.cash} ₸)` : ''}${refund.kaspi > 0 ? ` (Безналичный ${refund.kaspi} ₸)` : ''}`)
      } else {
        toastInfo('Сессия завершена, возврат не начислен (время уже истекло)')
      }
    } catch (err: any) {
      toastError(err?.message || 'Не удалось завершить сессию с возвратом')
    } finally {
      setActionLoading(false)
    }
  }

  async function handleExtend(payMethod: PayMethod, cashAmt: number, kaspiAmt: number) {
    if (!manageTarget) return
    setActionLoading(true)
    try {
      const updated = await api.extendArenaSession(config, session, manageTarget.session.id, {
        amount_extension: true,
        payment_method: payMethod,
        cash_amount: cashAmt,
        kaspi_amount: kaspiAmt,
      })
      setSessions((prev) => prev.map((s) => (s.id === updated.id ? updated : s)))
      setManageTarget(null)
    } catch (err: any) {
      toastError(err?.message || 'Не удалось продлить сессию')
    } finally {
      setActionLoading(false)
    }
  }

  async function handleTechLog(reason: string, amount: number) {
    if (!techTarget) return
    setActionLoading(true)
    try {
      await api.logArenaTech(config, session, {
        stationId: techTarget.id,
        stationName: techTarget.name,
        reason,
        amount,
      })
      setTechTarget(null)
      setManageTarget(null)
      toastInfo(`Тех. компенсация записана: ${techTarget.name}`)
    } catch (err: any) {
      toastError(err?.message || 'Не удалось записать компенсацию')
    } finally {
      setActionLoading(false)
    }
  }

  function freeStationsOfZone(zoneId: string) {
    return stations.filter((s) => s.zone_id === zoneId && s.is_active && !sessions.find((sess) => sess.station_id === s.id))
  }

  async function handleMassStart(zoneId: string, tariffId: string, payMethod: PayMethod, cashAmt: number, kaspiAmt: number) {
    const freeStations = freeStationsOfZone(zoneId)
    if (freeStations.length === 0) return
    setActionLoading(true)
    await Promise.allSettled(
      freeStations.map((st) =>
        api.startArenaSession(config, session, {
          stationId: st.id,
          tariffId,
          operatorId: session.operator.operator_id,
          payment_method: payMethod,
          cash_amount: cashAmt,
          kaspi_amount: kaspiAmt,
          discount_percent: 0,
        }),
      ),
    )
    await loadArena()
    setActionLoading(false)
    setMassStartTarget(null)
  }

  // ─── Брони ─────────────────────────────────────────────────────────────────
  function groupOf(booking: api.StationBooking): api.StationBooking[] {
    if (!booking.groupId) return [booking]
    return bookings.filter((b) => b.groupId === booking.groupId)
  }

  function bookingsForStation(stationId: string): api.StationBooking[] {
    const t = Date.now()
    return bookings
      .filter((b) => b.stationId === stationId && new Date(b.endsAt).getTime() > t)
      .sort((a, b) => (a.startsAt < b.startsAt ? -1 : 1))
  }

  /** Клик по станции на карте: сессия → управление, бронь → карточка брони, иначе новая бронь. */
  function handleStationClick(station: ArenaStation) {
    const activeSession = sessions.find((s) => s.station_id === station.id)
    if (activeSession) {
      setManageTarget({ station, session: activeSession })
      return
    }
    const booked = bookingsForStation(station.id)[0]
    if (booked) {
      setBookingDetails(booked)
      return
    }
    setBookingTarget(station)
  }

  function exportSessionsCsv() {
    const rows = sessions.map((s) => {
      const st = stations.find((x) => x.id === s.station_id)
      const t = tariffs.find((x) => x.id === s.tariff_id)
      const durationMin = Math.round((new Date(s.ends_at).getTime() - new Date(s.started_at).getTime()) / 60000)
      return [
        st?.name ?? s.station_id,
        t?.name ?? s.tariff_id ?? '',
        durationMin,
        new Date(s.started_at).toLocaleString('ru-RU'),
        new Date(s.ends_at).toLocaleString('ru-RU'),
        s.amount,
        s.cash_amount,
        s.kaspi_amount,
        s.payment_method,
        s.status,
      ]
    })
    downloadCsv(
      ['Станция', 'Тариф', 'Длит.(мин)', 'Начало', 'Конец', 'Сумма', 'Наличные', 'Безналичный', 'Способ оплаты', 'Статус'],
      rows,
      `arena-sessions-${new Date().toISOString().slice(0, 10)}.csv`,
    )
  }

  return {
    zones, stations, tariffs, sessions, decorations, todayIncome, loading, actionLoading, now,
    viewMode, setViewMode,
    startTarget, setStartTarget,
    bookingTarget, setBookingTarget, bookings, bookingHorizon, bookingDetails, setBookingDetails,
    showBookings, setShowBookings, previewAt, setPreviewAt,
    manageTarget, setManageTarget, massStartTarget, setMassStartTarget,
    techTarget, setTechTarget, showHistory, setShowHistory, printReceipt, setPrintReceipt,
    loadArena, loadBookings,
    handleStart, handleEnd, handleEndWithRefund, handleExtend, handleTechLog, handleMassStart,
    freeStationsOfZone, groupOf, bookingsForStation, handleStationClick, exportSessionsCsv,
  }
}
