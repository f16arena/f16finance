import { arenaExtensionMinutesFromPayment } from '../../../../../lib/core/arena-extension-minutes'
import type { ArenaSession, ArenaStation, ArenaTariff } from '@/types'

// ─── Звук (один AudioContext на всё окно) ─────────────────────────────────────
let audioCtx: AudioContext | null = null
export function playBeep(freq: number, delay = 0) {
  try {
    if (!audioCtx || audioCtx.state === 'closed') audioCtx = new AudioContext()
    const ctx = audioCtx
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.connect(gain)
    gain.connect(ctx.destination)
    osc.frequency.value = freq
    gain.gain.setValueAtTime(0.3, ctx.currentTime + delay)
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + delay + 0.3)
    osc.start(ctx.currentTime + delay)
    osc.stop(ctx.currentTime + delay + 0.3)
  } catch {
    /* звук не критичен */
  }
}

export type PayMethod = 'cash' | 'kaspi' | 'mixed'

export function money(n: number) {
  return Math.round(Number(n) || 0).toLocaleString('ru-RU') + ' ₸'
}

export function clock(iso: string | number) {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
}

export function formatTariffSchedule(t: ArenaTariff): string {
  if (t.tariff_type !== 'time_window' || !t.window_end_time) return `${t.duration_minutes} мин`
  if (t.window_start_time) {
    const pa = t.window_start_time.split(':').map(Number)
    const pb = t.window_end_time.split(':').map(Number)
    const ma = (pa[0] ?? 0) * 60 + (pa[1] ?? 0)
    const mb = (pb[0] ?? 0) * 60 + (pb[1] ?? 0)
    if (ma > mb) return `${t.window_start_time}–${t.window_end_time} · ночь`
    return `${t.window_start_time}–${t.window_end_time} · день`
  }
  return `до ${t.window_end_time}`
}

export function getRemainingMs(endsAt: string, now = Date.now()): number {
  return new Date(endsAt).getTime() - now
}

export function formatRemaining(ms: number): string {
  if (ms <= 0) return '0:00'
  const totalSec = Math.ceil(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${m}:${String(s).padStart(2, '0')}`
}

export const SOON_MS = 5 * 60_000

export type StationState = 'free' | 'busy' | 'soon' | 'expired' | 'off'

export function stationState(station: ArenaStation, session: ArenaSession | undefined, now: number): StationState {
  if (!session) return station.is_active === false ? 'off' : 'free'
  const left = getRemainingMs(session.ends_at, now)
  if (left <= 0) return 'expired'
  if (left < SOON_MS) return 'soon'
  return 'busy'
}

/** Доля оставшегося времени сессии (0..1). */
export function sessionProgress(session: ArenaSession, now: number): number {
  const total = new Date(session.ends_at).getTime() - new Date(session.started_at).getTime()
  if (!(total > 0)) return 0
  return Math.max(0, Math.min(1, (new Date(session.ends_at).getTime() - now) / total))
}

/** Минуты продления по сумме (та же логика, что на сервере). */
export function previewExtensionMinutes(
  tariff: ArenaTariff | undefined,
  paidTotal: number,
  extensionHourlyPrice: number | null,
): number {
  if (!tariff) return 0
  const paid = Math.round(Number(paidTotal))
  if (paid < 1) return 0
  const r = arenaExtensionMinutesFromPayment(
    Number(tariff.price),
    Number(tariff.duration_minutes),
    paid,
    extensionHourlyPrice != null && extensionHourlyPrice > 0 ? extensionHourlyPrice : null,
  )
  return r.ok ? r.minutes : 0
}

export const TECH_REASONS = ['Зависание игры', 'Долгая загрузка', 'Перезагрузка ПК', 'Проблемы со звуком', 'Другое']

export type ArenaReceipt = {
  stationName: string
  tariffName: string
  durationMin: number
  startedAt: string
  endsAt: string
  amount: number
  cashAmount: number
  kaspiAmount: number
  paymentMethod: string
  operatorName: string
  clubName: string
}

export type TodayIncome = { cash: number; kaspi: number; rows: { cash_amount: number; kaspi_amount: number; comment: string | null }[] }

function receiptTime(iso: string) {
  return new Date(iso).toLocaleString('ru-RU', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit', year: 'numeric' })
}

export function printArenaReceipt(receipt: ArenaReceipt) {
  const payStr =
    receipt.paymentMethod === 'cash'
      ? `Наличные: ${money(receipt.cashAmount)}`
      : receipt.paymentMethod === 'kaspi'
        ? `Безналичный: ${money(receipt.kaspiAmount)}`
        : `Наличные: ${money(receipt.cashAmount)}, Безналичный: ${money(receipt.kaspiAmount)}`

  const html = `<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8"/>
<title>Чек</title>
<style>
  body { font-family: 'Courier New', monospace; font-size: 13px; margin: 0; padding: 12px; width: 280px; }
  h1 { font-size: 15px; text-align: center; margin: 0 0 4px; }
  .sub { text-align: center; font-size: 11px; color: #555; margin: 0 0 10px; }
  hr { border: none; border-top: 1px dashed #999; margin: 8px 0; }
  .row { display: flex; justify-content: space-between; margin: 3px 0; }
  .label { color: #555; }
  .total { font-size: 16px; font-weight: bold; }
  .footer { text-align: center; font-size: 11px; color: #777; margin-top: 10px; }
</style>
</head>
<body>
<h1>${receipt.clubName}</h1>
<p class="sub">Оператор: ${receipt.operatorName}</p>
<hr/>
<div class="row"><span class="label">Станция:</span><span>${receipt.stationName}</span></div>
<div class="row"><span class="label">Тариф:</span><span>${receipt.tariffName}</span></div>
<div class="row"><span class="label">Длительность:</span><span>${receipt.durationMin} мин</span></div>
<hr/>
<div class="row"><span class="label">Начало:</span><span>${receiptTime(receipt.startedAt)}</span></div>
<div class="row"><span class="label">Конец:</span><span>${receiptTime(receipt.endsAt)}</span></div>
<hr/>
<div class="row total"><span>Итого:</span><span>${money(receipt.amount)}</span></div>
<div class="row"><span class="label">Оплата:</span><span>${payStr}</span></div>
<p class="footer">Спасибо за визит!</p>
</body>
</html>`

  const win = window.open('', '_blank', 'width=350,height=600')
  if (!win) return
  win.document.write(html)
  win.document.close()
  win.focus()
  win.print()
  win.close()
}

export function downloadCsv(header: string[], rows: Array<Array<string | number | null | undefined>>, filename: string) {
  const csv = [header, ...rows]
    .map((row) => row.map((c) => `"${String(c ?? '').replace(/"/g, '""')}"`).join(','))
    .join('\n')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}
