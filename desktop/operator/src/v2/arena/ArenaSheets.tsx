import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Check, Clock, Download, Printer, Wrench, X } from 'lucide-react'

import * as api from '@/lib/api'
import { toastError } from '@/lib/toast'
import type { AppConfig, ArenaSession, ArenaStation, ArenaTariff, ArenaZone, OperatorSession } from '@/types'

import { effectiveZoneExtensionHourly } from '../../../../../lib/core/arena-zone-extension-hourly'
import {
  TECH_REASONS,
  clock,
  downloadCsv,
  formatRemaining,
  formatTariffSchedule,
  getRemainingMs,
  money,
  previewExtensionMinutes,
  printArenaReceipt,
  type ArenaReceipt,
  type PayMethod,
} from './arenaHelpers'

// ─── Общие кирпичики ──────────────────────────────────────────────────────────

/** Нижняя шторка поверх экрана (тот же приём, что в кассе). */
export function Sheet(props: { title: string; onClose: () => void; wide?: boolean; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') props.onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })
  return (
    <div
      className="fixed inset-0 z-40 flex items-end justify-center bg-black/40 p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) props.onClose()
      }}
    >
      <div
        className={`sheet-in glass max-h-[92vh] w-full overflow-y-auto rounded-[28px] p-5 ${props.wide ? 'max-w-3xl' : 'max-w-md'}`}
        style={{ boxShadow: 'var(--shadow-lift)' }}
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-[19px] font-bold">{props.title}</h3>
          <button type="button" onClick={props.onClose} className="press fill grid h-10 w-10 place-items-center rounded-full" aria-label="Закрыть">
            <X className="h-4 w-4" />
          </button>
        </div>
        {props.children}
      </div>
    </div>
  )
}

function Field(props: { label: string; value: string; onChange: (v: string) => void; placeholder?: string; max?: number; autoFocus?: boolean }) {
  return (
    <label className="block min-w-0">
      <span className="muted text-[13px]">{props.label}</span>
      <input
        type="number"
        min="0"
        max={props.max}
        value={props.value}
        autoFocus={props.autoFocus}
        onChange={(e) => props.onChange(e.target.value)}
        placeholder={props.placeholder ?? '0'}
        className="fill placeholder-muted mt-1 h-12 w-full rounded-[14px] px-4 text-[18px] font-semibold outline-none"
      />
    </label>
  )
}

function PayPicker(props: { value: PayMethod; onChange: (m: PayMethod) => void }) {
  return (
    <div className="fill flex rounded-[14px] p-1">
      {(['cash', 'kaspi', 'mixed'] as const).map((m) => (
        <button
          key={m}
          type="button"
          onClick={() => props.onChange(m)}
          className={`press h-11 flex-1 rounded-[11px] text-[14px] font-medium ${props.value === m ? 'pill-active' : 'ink-2'}`}
        >
          {m === 'cash' ? 'Наличка' : m === 'kaspi' ? 'Безналичный' : 'Смешанный'}
        </button>
      ))}
    </div>
  )
}

function TariffList(props: { tariffs: ArenaTariff[]; selected: string; onSelect: (id: string) => void; emptyText: string }) {
  if (props.tariffs.length === 0) return <p className="muted py-4 text-center text-[14px]">{props.emptyText}</p>
  return (
    <div className="flex flex-col gap-2">
      {props.tariffs.map((t) => {
        const on = props.selected === t.id
        return (
          <button
            key={t.id}
            type="button"
            onClick={() => props.onSelect(t.id)}
            className="press surface-2 flex min-h-[56px] items-center justify-between gap-3 rounded-[16px] px-4 text-left"
            style={on ? { boxShadow: 'inset 0 0 0 2px var(--blue)' } : undefined}
          >
            <span className="min-w-0 truncate text-[15px] font-semibold">{t.name}</span>
            <span className="flex shrink-0 items-center gap-3 text-[13px]">
              <span className="muted flex items-center gap-1">
                <Clock className="h-3.5 w-3.5" />
                {formatTariffSchedule(t)}
              </span>
              <span className="text-[16px] font-bold">{money(t.price)}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}

function Label(props: { children: ReactNode }) {
  return <p className="muted mb-2 mt-4 text-[13px] font-medium uppercase tracking-wide">{props.children}</p>
}

function MixedFields(props: { cash: string; kaspi: string; onCash: (v: string) => void; onKaspi: (v: string) => void; cashlessName: string }) {
  return (
    <div className="mt-3 grid grid-cols-2 gap-3">
      <Field label="Наличка ₸" value={props.cash} onChange={props.onCash} />
      <Field label={`${props.cashlessName} ₸`} value={props.kaspi} onChange={props.onKaspi} />
    </div>
  )
}

// ─── Запуск сессии ────────────────────────────────────────────────────────────

export function StartSessionSheet(props: {
  station: ArenaStation
  tariffs: ArenaTariff[]
  zoneId: string | null
  cashlessName: string
  loading: boolean
  onConfirm: (tariffId: string, paymentMethod: PayMethod, cashAmount: number, kaspiAmount: number, discountPct: number) => void
  onCancel: () => void
}) {
  const sorted = useMemo(() => props.tariffs.filter((t) => t.zone_id === props.zoneId).sort((a, b) => a.price - b.price), [props.tariffs, props.zoneId])
  const [selected, setSelected] = useState<string>(sorted[0]?.id ?? '')
  const [payMethod, setPayMethod] = useState<PayMethod>('cash')
  const [cashAmt, setCashAmt] = useState('')
  const [kaspiAmt, setKaspiAmt] = useState('')
  const [discountPct, setDiscountPct] = useState('')

  const selectedTariff = sorted.find((t) => t.id === selected)
  const discPct = Number(discountPct) || 0
  const finalPrice = selectedTariff ? Math.round(selectedTariff.price * (1 - discPct / 100)) : 0

  return (
    <Sheet title={`Запустить · ${props.station.name}`} onClose={props.onCancel}>
      <Label>Тариф</Label>
      <TariffList tariffs={sorted} selected={selected} onSelect={setSelected} emptyText="Нет тарифов для этой зоны. Добавьте тарифы на сайте Orda." />

      <div className="mt-4 flex items-center justify-between gap-3">
        <span className="muted text-[14px]">Скидка, %</span>
        <input
          type="number"
          min="0"
          max="100"
          value={discountPct}
          onChange={(e) => setDiscountPct(e.target.value)}
          placeholder="0"
          className="fill placeholder-muted h-11 w-24 rounded-[12px] px-3 text-right text-[16px] font-semibold outline-none"
        />
      </div>

      {selectedTariff ? (
        <div className="surface-2 mt-3 flex items-center justify-between rounded-[16px] px-4 py-3">
          <span className="muted text-[14px]">К оплате</span>
          <span className="text-[22px] font-bold">
            {money(finalPrice)}
            {discPct > 0 ? <span className="t-green ml-2 text-[13px] font-semibold">−{discPct}%</span> : null}
          </span>
        </div>
      ) : null}

      <Label>Способ оплаты</Label>
      <PayPicker value={payMethod} onChange={setPayMethod} />
      {payMethod === 'mixed' ? <MixedFields cash={cashAmt} kaspi={kaspiAmt} onCash={setCashAmt} onKaspi={setKaspiAmt} cashlessName={props.cashlessName} /> : null}

      <div className="mt-5 grid grid-cols-[1fr_auto] gap-2">
        <button
          type="button"
          onClick={() => selected && props.onConfirm(selected, payMethod, Number(cashAmt), Number(kaspiAmt), discPct)}
          disabled={!selected || props.loading || sorted.length === 0}
          className="press btn-green h-14 rounded-[18px] text-[17px] font-semibold"
        >
          {props.loading ? 'Запускаем...' : 'Запустить'}
        </button>
        <button type="button" onClick={props.onCancel} disabled={props.loading} className="press fill h-14 rounded-[18px] px-6 text-[16px] font-medium">
          Отмена
        </button>
      </div>
    </Sheet>
  )
}

// ─── Управление сессией ───────────────────────────────────────────────────────

export function ManageSessionSheet(props: {
  station: ArenaStation
  session: ArenaSession
  tariffs: ArenaTariff[]
  zones: ArenaZone[]
  now: number
  loading: boolean
  cashlessName: string
  onExtend: (paymentMethod: PayMethod, cashAmount: number, kaspiAmount: number) => void
  onEnd: () => void
  onEndWithRefund: () => void
  onTech: () => void
  onClose: () => void
}) {
  const { station, session: arenaSession, tariffs, zones, now } = props
  const [mode, setMode] = useState<'view' | 'extend'>('view')

  const rateTariff = tariffs.find((t) => t.id === arenaSession.tariff_id)
  const zoneIdForHourly = station.zone_id ?? rateTariff?.zone_id ?? null
  const zoneRow = zoneIdForHourly ? zones.find((z) => z.id === zoneIdForHourly) : undefined
  const zoneHourlyEff = effectiveZoneExtensionHourly(zoneRow, zoneIdForHourly, tariffs)
  const legacyTariffHourlyRaw = (rateTariff as { extension_hourly_price?: unknown } | undefined)?.extension_hourly_price
  const legacyTariffHourly = legacyTariffHourlyRaw != null && legacyTariffHourlyRaw !== '' ? Number(legacyTariffHourlyRaw) : NaN
  const extensionHourlyEffective = zoneHourlyEff ?? (Number.isFinite(legacyTariffHourly) && legacyTariffHourly > 0 ? legacyTariffHourly : null)

  const [extPayMethod, setExtPayMethod] = useState<PayMethod>('cash')
  const [extAmount, setExtAmount] = useState('')
  const [extCashAmt, setExtCashAmt] = useState('')
  const [extKaspiAmt, setExtKaspiAmt] = useState('')

  const paidTotal = useMemo(() => {
    if (extPayMethod === 'mixed') return Math.round((Number(extCashAmt) || 0) + (Number(extKaspiAmt) || 0))
    return Math.round(Number(extAmount) || 0)
  }, [extPayMethod, extAmount, extCashAmt, extKaspiAmt])

  const previewMin = previewExtensionMinutes(rateTariff, paidTotal, extensionHourlyEffective)

  const remainingMs = getRemainingMs(arenaSession.ends_at, now)
  const isExpired = remainingMs <= 0
  const timerColor = isExpired ? 'var(--red)' : remainingMs < 5 * 60_000 ? 'var(--orange)' : 'var(--green)'

  return (
    <Sheet title={station.name} onClose={props.onClose}>
      {mode === 'view' ? (
        <>
          <div className="surface-2 rounded-[20px] p-4">
            <div className="text-center text-[44px] font-extrabold leading-none tracking-tight" style={{ color: timerColor }}>
              {isExpired ? 'Истекло' : formatRemaining(remainingMs)}
            </div>
            <div className="mt-4 flex items-center justify-between text-[14px]">
              <span className="muted">Оканчивается в</span>
              <span className="font-semibold">{clock(arenaSession.ends_at)}</span>
            </div>
            <div className="mt-2 flex items-center justify-between text-[14px]">
              <span className="muted">Сумма</span>
              <span className="font-semibold">{money(arenaSession.amount)}</span>
            </div>
          </div>

          <div className="mt-4 flex flex-col gap-2">
            <button type="button" onClick={() => setMode('extend')} disabled={props.loading} className="press btn-blue h-14 rounded-[18px] text-[17px] font-semibold">
              Продлить
            </button>
            <button type="button" onClick={props.onTech} disabled={props.loading} className="press fill flex h-12 items-center justify-center gap-2 rounded-[16px] text-[15px] font-medium" style={{ color: 'var(--orange)' }}>
              <Wrench className="h-4 w-4" />
              Тех. компенсация
            </button>
            <button type="button" onClick={props.onEnd} disabled={props.loading} className="press fill h-12 rounded-[16px] text-[15px] font-semibold t-red">
              {props.loading ? 'Завершаем...' : 'Завершить сессию'}
            </button>
            <button type="button" onClick={props.onEndWithRefund} disabled={props.loading} className="press fill h-12 rounded-[16px] text-[15px] font-medium t-green">
              Завершить с возвратом
            </button>
          </div>
        </>
      ) : (
        <>
          {!rateTariff ? (
            <>
              <p className="muted text-[14px]">
                Продление по сумме недоступно: у сессии не сохранён тариф. Завершите сессию и запустите станцию заново с нужным тарифом.
              </p>
              <button type="button" onClick={() => setMode('view')} disabled={props.loading} className="press fill mt-4 h-12 w-full rounded-[16px] text-[15px] font-medium">
                Назад
              </button>
            </>
          ) : (
            <>
              <div className="surface-2 rounded-[18px] p-4 text-[14px]">
                <p className="font-semibold">{rateTariff.name}</p>
                <p className="muted mt-1 text-[13px]">
                  Пакет: {money(Number(rateTariff.price))} за {rateTariff.duration_minutes} мин
                  {rateTariff.tariff_type === 'time_window' ? ` · ${formatTariffSchedule(rateTariff)}` : ''}
                </p>
                <p className="muted mt-2 text-[13px]">
                  {extensionHourlyEffective != null
                    ? `Продление по сумме: ${money(extensionHourlyEffective)} за 60 мин (час зоны или тариф «1 ч»). Меньше суммы пакета — по этому часу; от полной цены пакета — целые пакеты по ${money(Number(rateTariff.price))}, остаток снова по часу.`
                    : zoneIdForHourly && !zoneRow
                      ? 'Час зоны задан на сайте, но зона не пришла в данные точки — обновите экран или проверьте привязку компании у зоны/станции.'
                      : 'Нет ни поля «час продления» у зоны, ни фикс. тарифа ровно на 60 мин в зоне — время пропорционально пакету. Задайте час у зоны (карандаш) или добавьте тариф «1 час».'}
                </p>
              </div>

              <Label>Способ оплаты</Label>
              <PayPicker value={extPayMethod} onChange={setExtPayMethod} />
              {extPayMethod === 'mixed' ? (
                <MixedFields cash={extCashAmt} kaspi={extKaspiAmt} onCash={setExtCashAmt} onKaspi={setExtKaspiAmt} cashlessName={props.cashlessName} />
              ) : (
                <div className="mt-3">
                  <Field label="Сумма ₸" value={extAmount} onChange={setExtAmount} placeholder="200" autoFocus />
                </div>
              )}

              {previewMin > 0 ? (
                <p className="t-green mt-4 text-center text-[17px] font-bold">≈ {previewMin} мин</p>
              ) : paidTotal > 0 ? (
                <p className="mt-4 text-center text-[13px]" style={{ color: 'var(--orange)' }}>
                  Суммы не хватает даже на 1 минуту по тарифу
                </p>
              ) : null}

              <div className="mt-5 grid grid-cols-[1fr_auto] gap-2">
                <button
                  type="button"
                  onClick={() => {
                    if (extPayMethod === 'cash') props.onExtend('cash', paidTotal, 0)
                    else if (extPayMethod === 'kaspi') props.onExtend('kaspi', 0, paidTotal)
                    else props.onExtend('mixed', Math.round(Number(extCashAmt) || 0), Math.round(Number(extKaspiAmt) || 0))
                  }}
                  disabled={props.loading || previewMin < 1}
                  className="press btn-blue h-14 rounded-[18px] text-[17px] font-semibold"
                >
                  {props.loading ? 'Продлеваем...' : 'Подтвердить'}
                </button>
                <button type="button" onClick={() => setMode('view')} disabled={props.loading} className="press fill h-14 rounded-[18px] px-6 text-[16px] font-medium">
                  Назад
                </button>
              </div>
            </>
          )}
        </>
      )}
    </Sheet>
  )
}

// ─── Техническая компенсация ──────────────────────────────────────────────────

export function TechLogSheet(props: { station: ArenaStation; loading: boolean; onConfirm: (reason: string, amount: number) => void; onCancel: () => void }) {
  const [reason, setReason] = useState(TECH_REASONS[0])
  const [amount, setAmount] = useState('')
  return (
    <Sheet title="Тех. компенсация" onClose={props.onCancel}>
      <p className="muted text-[14px]">
        Станция: <span className="ink font-semibold">{props.station.name}</span>
      </p>
      <Label>Причина</Label>
      <div className="flex flex-col gap-2">
        {TECH_REASONS.map((r) => (
          <button
            key={r}
            type="button"
            onClick={() => setReason(r)}
            className="press surface-2 flex min-h-[48px] items-center rounded-[14px] px-4 text-left text-[15px] font-medium"
            style={reason === r ? { boxShadow: 'inset 0 0 0 2px var(--orange)' } : undefined}
          >
            {r}
          </button>
        ))}
      </div>
      <div className="mt-4">
        <Field label="Сумма компенсации ₸" value={amount} onChange={setAmount} />
      </div>
      <div className="mt-5 grid grid-cols-[1fr_auto] gap-2">
        <button
          type="button"
          onClick={() => props.onConfirm(reason, Number(amount) || 0)}
          disabled={props.loading}
          className="press h-14 rounded-[18px] text-[17px] font-semibold text-white"
          style={{ background: 'var(--orange)' }}
        >
          {props.loading ? 'Сохраняем...' : 'Записать'}
        </button>
        <button type="button" onClick={props.onCancel} disabled={props.loading} className="press fill h-14 rounded-[18px] px-6 text-[16px] font-medium">
          Отмена
        </button>
      </div>
    </Sheet>
  )
}

// ─── Запуск всей зоны ─────────────────────────────────────────────────────────

export function MassZoneStartSheet(props: {
  zone: ArenaZone
  tariffs: ArenaTariff[]
  freeCount: number
  cashlessName: string
  loading: boolean
  onConfirm: (tariffId: string, paymentMethod: PayMethod, cashAmount: number, kaspiAmount: number) => void
  onCancel: () => void
}) {
  const sorted = useMemo(() => props.tariffs.filter((t) => t.zone_id === props.zone.id).sort((a, b) => a.price - b.price), [props.tariffs, props.zone.id])
  const [selected, setSelected] = useState<string>(sorted[0]?.id ?? '')
  const [payMethod, setPayMethod] = useState<PayMethod>('cash')
  const [cashAmt, setCashAmt] = useState('')
  const [kaspiAmt, setKaspiAmt] = useState('')

  return (
    <Sheet title="Запустить зону" onClose={props.onCancel}>
      <p className="muted text-[14px]">
        Зона: <span className="ink font-semibold">{props.zone.name}</span>
        {' · '}
        <span className="t-green font-semibold">{props.freeCount} свободных станций</span>
      </p>
      <Label>Тариф</Label>
      <TariffList tariffs={sorted} selected={selected} onSelect={setSelected} emptyText="Нет тарифов для этой зоны." />
      <Label>Способ оплаты</Label>
      <PayPicker value={payMethod} onChange={setPayMethod} />
      {payMethod === 'mixed' ? <MixedFields cash={cashAmt} kaspi={kaspiAmt} onCash={setCashAmt} onKaspi={setKaspiAmt} cashlessName={props.cashlessName} /> : null}
      <div className="mt-5 grid grid-cols-[1fr_auto] gap-2">
        <button
          type="button"
          onClick={() => selected && props.onConfirm(selected, payMethod, Number(cashAmt), Number(kaspiAmt))}
          disabled={!selected || props.loading || sorted.length === 0 || props.freeCount === 0}
          className="press btn-green h-14 rounded-[18px] text-[17px] font-semibold"
        >
          {props.loading ? 'Запускаем...' : `Запустить ${props.freeCount} станций`}
        </button>
        <button type="button" onClick={props.onCancel} disabled={props.loading} className="press fill h-14 rounded-[18px] px-6 text-[16px] font-medium">
          Отмена
        </button>
      </div>
    </Sheet>
  )
}

// ─── Чек ──────────────────────────────────────────────────────────────────────

export function ReceiptSheet(props: { receipt: ArenaReceipt; onClose: () => void }) {
  const r = props.receipt
  // По центру экрана, как остальные окна чека (v2/ui/ReceiptModal).
  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center p-6"
      style={{ background: 'rgba(0,0,0,0.45)', backdropFilter: 'blur(6px)', WebkitBackdropFilter: 'blur(6px)' }}
      onClick={props.onClose}
    >
    <div onClick={(e) => e.stopPropagation()} className="sheet-in surface w-full max-w-[440px] rounded-[28px] p-6" style={{ boxShadow: 'var(--shadow-lift)' }} role="dialog" aria-modal="true">
      <div className="mb-4 flex items-center gap-3">
        <div className="grid h-11 w-11 place-items-center rounded-full" style={{ background: 'var(--green)' }}>
          <Check className="h-6 w-6 text-white" strokeWidth={3} />
        </div>
        <div className="text-[19px] font-bold">Чек готов</div>
      </div>
      <div className="surface-2 space-y-2 rounded-[18px] p-4 text-[15px]">
        <div className="flex justify-between">
          <span className="muted">Станция</span>
          <span className="font-semibold">{r.stationName}</span>
        </div>
        <div className="flex justify-between">
          <span className="muted">Тариф</span>
          <span>{r.tariffName}</span>
        </div>
        <div className="flex justify-between">
          <span className="muted">Время</span>
          <span>{r.durationMin} мин</span>
        </div>
        <div className="line mt-2 flex justify-between border-t pt-3">
          <span className="muted">Итого</span>
          <span className="text-[20px] font-bold">{money(r.amount)}</span>
        </div>
      </div>
      <div className="mt-5 grid grid-cols-[1fr_auto] gap-2">
        <button type="button" onClick={() => printArenaReceipt(r)} className="press btn-blue flex h-14 items-center justify-center gap-2 rounded-[18px] text-[17px] font-semibold">
          <Printer className="h-5 w-5" />
          Напечатать
        </button>
        <button type="button" onClick={props.onClose} className="press fill h-14 rounded-[18px] px-6 text-[16px] font-medium">
          Закрыть
        </button>
      </div>
    </div>
    </div>
  )
}

// ─── История сессий ───────────────────────────────────────────────────────────

export function SessionHistorySheet(props: {
  config: AppConfig
  session: OperatorSession
  stations: ArenaStation[]
  tariffs: ArenaTariff[]
  cashlessName: string
  onClose: () => void
}) {
  const today = new Date().toISOString().slice(0, 10)
  const [from, setFrom] = useState(today)
  const [to, setTo] = useState(today)
  const [rows, setRows] = useState<ArenaSession[]>([])
  const [loading, setLoading] = useState(false)
  const [loaded, setLoaded] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const data = await api.getArenaSessions(props.config, props.session, from, to)
      setRows(data)
      setLoaded(true)
    } catch (err: any) {
      toastError(err?.message || 'Не удалось загрузить историю')
    } finally {
      setLoading(false)
    }
  }

  function names(r: ArenaSession) {
    const st = props.stations.find((x) => x.id === r.station_id)
    const t = props.tariffs.find((x) => x.id === r.tariff_id)
    return {
      station: st?.name ?? (r as any)['arena_stations']?.name ?? '',
      tariff: t?.name ?? (r as any)['arena_tariffs']?.name ?? '',
    }
  }

  function exportCsv() {
    downloadCsv(
      ['Станция', 'Тариф', 'Начало', 'Конец', 'Статус', 'Сумма', 'Нал', 'Безналичный', 'Способ'],
      rows.map((r) => {
        const n = names(r)
        return [
          n.station || r.station_id,
          n.tariff,
          new Date(r.started_at).toLocaleString('ru-RU'),
          r.ended_at ? new Date(r.ended_at).toLocaleString('ru-RU') : '',
          r.status,
          r.amount,
          r.cash_amount,
          r.kaspi_amount,
          r.payment_method,
        ]
      }),
      `history-${from}-${to}.csv`,
    )
  }

  const totalAmount = rows.reduce((s, r) => s + (r.amount || 0), 0)
  const totalCash = rows.reduce((s, r) => s + (r.cash_amount || 0), 0)
  const totalKaspi = rows.reduce((s, r) => s + (r.kaspi_amount || 0), 0)
  const dt = { hour: '2-digit', minute: '2-digit', day: '2-digit', month: '2-digit' } as const

  return (
    <Sheet title="История сессий" onClose={props.onClose} wide>
      <div className="flex flex-wrap items-end gap-3">
        <label className="block">
          <span className="muted text-[13px]">С</span>
          <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="fill mt-1 block h-11 rounded-[12px] px-3 text-[15px] outline-none" />
        </label>
        <label className="block">
          <span className="muted text-[13px]">По</span>
          <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="fill mt-1 block h-11 rounded-[12px] px-3 text-[15px] outline-none" />
        </label>
        <button type="button" onClick={load} disabled={loading} className="press btn-blue h-11 rounded-[14px] px-5 text-[15px] font-semibold">
          {loading ? 'Загрузка...' : 'Показать'}
        </button>
        {rows.length > 0 ? (
          <button type="button" onClick={exportCsv} className="press fill ml-auto flex h-11 items-center gap-2 rounded-[14px] px-4 text-[14px] font-medium">
            <Download className="h-4 w-4" />
            CSV
          </button>
        ) : null}
      </div>

      <div className="mt-4 max-h-[50vh] overflow-y-auto">
        {!loaded ? (
          <div className="muted flex h-32 items-center justify-center text-[14px]">Выберите период и нажмите «Показать»</div>
        ) : rows.length === 0 ? (
          <div className="muted flex h-32 items-center justify-center text-[14px]">Нет сессий за выбранный период</div>
        ) : (
          <table className="w-full text-[14px]">
            <thead>
              <tr className="muted text-left text-[12px]">
                <th className="px-3 py-2 font-medium">Станция</th>
                <th className="px-3 py-2 font-medium">Тариф</th>
                <th className="px-3 py-2 font-medium">Начало</th>
                <th className="px-3 py-2 font-medium">Конец</th>
                <th className="px-3 py-2 text-right font-medium">Сумма</th>
                <th className="px-3 py-2 font-medium">Статус</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const n = names(r)
                return (
                  <tr key={r.id} className="line border-t">
                    <td className="px-3 py-2.5 font-semibold">{n.station || '—'}</td>
                    <td className="muted px-3 py-2.5">{n.tariff || '—'}</td>
                    <td className="muted px-3 py-2.5 text-[13px]">{new Date(r.started_at).toLocaleString('ru-RU', dt)}</td>
                    <td className="muted px-3 py-2.5 text-[13px]">
                      {r.ended_at ? new Date(r.ended_at).toLocaleString('ru-RU', dt) : <span className="t-green">активна</span>}
                    </td>
                    <td className="px-3 py-2.5 text-right font-semibold">{money(r.amount)}</td>
                    <td className="px-3 py-2.5">
                      <span className={`rounded-full px-2.5 py-0.5 text-[12px] font-medium ${r.status === 'active' ? 't-green' : 'muted'} fill`}>
                        {r.status === 'active' ? 'активна' : 'завершена'}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {rows.length > 0 ? (
        <div className="line mt-3 flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-[14px]">
          <span className="muted">{rows.length} сессий</span>
          <div className="flex flex-wrap gap-4">
            {totalCash > 0 ? (
              <span className="muted">
                Нал: <span className="ink font-semibold">{money(totalCash)}</span>
              </span>
            ) : null}
            {totalKaspi > 0 ? (
              <span className="muted">
                {props.cashlessName}: <span className="ink font-semibold">{money(totalKaspi)}</span>
              </span>
            ) : null}
            <span className="font-bold">Итого: {money(totalAmount)}</span>
          </div>
        </div>
      ) : null}
    </Sheet>
  )
}
