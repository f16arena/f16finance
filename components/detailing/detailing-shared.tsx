'use client'

/**
 * Общие детали раздела «Детейлинг»: выбор точки, госномер, статусы записи,
 * запрос к API. Страницы раздела собираются из них и из store-ui.
 */

import { type ReactNode, useCallback, useState } from 'react'
import { CarFront } from 'lucide-react'

import { NativeSelect } from '@/components/ui/native-select'
import { StoreEmpty, storeCard } from '@/components/store/store-ui'
import { useApiCache } from '@/lib/client/use-api-cache'

export type DetailingCompany = { id: string; name: string; code: string | null }
export type BodyClass = { id: string; name: string; sort_order: number; is_active: boolean }
export type Box = { id: string; name: string; note: string | null; sort_order: number; is_active: boolean }
export type ServicePrice = { body_class_id: string; price: number; materials: number }
export type Service = {
  id: string
  name: string
  package_note: string | null
  days: number
  /** Через сколько месяцев напомнить клиенту; null — не напоминать. */
  remind_months?: number | null
  sort_order: number
  is_active: boolean
  prices: ServicePrice[]
}
export type Master = { id: string; name: string }

export type BookingStatus = 'requested' | 'booked' | 'in_box' | 'done' | 'no_show' | 'cancelled'

export type Booking = {
  id: string
  company_id: string
  box_id: string | null
  customer_id: string
  car_id: string | null
  body_class_id: string | null
  master_operator_id: string | null
  start_date: string
  /** «15:00» или null — время не назначено. */
  start_time?: string | null
  days: number
  status: BookingStatus
  price_total: number
  materials_total: number
  materials_manual: boolean
  comment: string | null
  source: 'admin' | 'online'
  paid: number
  due: number
  customer: { id: string; name: string; phone: string | null } | null
  car: { id: string; plate: string; model: string | null } | null
  services: Array<{ id: string; service_id: string | null; name: string; price: number; materials: number; days: number }>
  payments: Array<{ id: string; amount: number; method: 'cash' | 'kaspi' | 'card'; kind: 'prepayment' | 'payment'; paid_on: string }>
}

export const STATUS_LABEL: Record<BookingStatus, string> = {
  requested: 'Заявка с сайта',
  booked: 'Записан',
  in_box: 'В боксе',
  done: 'Выдан',
  no_show: 'Не приехал',
  cancelled: 'Отменён',
}

/** Цвет карточки записи в неделе и чипа статуса. */
export const STATUS_TONE: Record<BookingStatus, string> = {
  requested: 'border border-dashed border-slate-400 bg-white text-slate-600 dark:bg-slate-900/60 dark:text-slate-300',
  booked: 'bg-blue-500/10 text-blue-700 dark:text-blue-200',
  // amber в проекте переназначен на бренд (синий), поэтому «в боксе» — orange.
  in_box: 'bg-orange-500/15 text-orange-800 dark:text-orange-200',
  done: 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-200',
  no_show: 'bg-rose-500/10 text-rose-700 line-through decoration-rose-400/60 dark:text-rose-200',
  cancelled: 'bg-slate-100 text-slate-500 dark:bg-white/5',
}

export const money = (n: number) => `${Math.round(n || 0).toLocaleString('ru-RU')} ₸`

/** Казахстанский госномер — как на машине. */
export function Plate({ plate }: { plate: string | null | undefined }) {
  if (!plate) return <span className="text-xs text-muted-foreground">без номера</span>
  return (
    <span className="inline-flex items-stretch overflow-hidden rounded-[3px] border border-slate-800 bg-white align-middle font-mono text-[11px] font-bold leading-[1.5] text-slate-900 dark:border-slate-300">
      <span className="flex items-center bg-sky-500 px-[3px] text-[7px] text-white">KZ</span>
      <span className="px-1.5 tracking-wide">{plate}</span>
    </span>
  )
}

const LS_KEY = 'detailing.companyId'

/** Выбранная точка-детейлинг, запоминается между страницами раздела. */
export function useDetailingCompanyId() {
  const [companyId, setState] = useState<string>(() => {
    if (typeof window === 'undefined') return ''
    try { return window.localStorage.getItem(LS_KEY) || '' } catch { return '' }
  })
  const setCompanyId = useCallback((id: string) => {
    setState(id)
    try { window.localStorage.setItem(LS_KEY, id) } catch {}
  }, [])
  return [companyId, setCompanyId] as const
}

/** Переключатель точки: виден, только если детейлингов больше одного. */
export function CompanyPicker(props: { companies: DetailingCompany[]; value: string; onChange: (id: string) => void }) {
  if (props.companies.length < 2) return null
  return (
    <NativeSelect value={props.value} onChange={(e) => props.onChange(e.target.value)} aria-label="Точка" className="h-9 min-w-[160px]">
      {props.companies.map((c) => (
        <option key={c.id} value={c.id}>{c.name}</option>
      ))}
    </NativeSelect>
  )
}

export function NoDetailingPoint() {
  return (
    <div className={storeCard}>
      <StoreEmpty
        icon={<CarFront className="h-5 w-5" />}
        title="Нет точки-детейлинга"
        hint="Обновите страницу через минуту. Если не помогло — напишите в поддержку."
      />
    </div>
  )
}

/** POST в API раздела; ошибка сервера превращается в Error с понятным текстом. */
export async function postDetailing<T = any>(url: string, body: Record<string, unknown>): Promise<T> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const json = await res.json().catch(() => null)
  if (!res.ok) throw new Error(json?.error || 'Не удалось сохранить')
  return (json?.data ?? null) as T
}

/** Понедельник недели, в которую попадает дата (YYYY-MM-DD, по местному времени). */
export function mondayOf(date: Date) {
  const d = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const shift = (d.getDay() + 6) % 7
  d.setDate(d.getDate() - shift)
  return isoLocal(d)
}

export function isoLocal(d: Date) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

export function shiftIso(iso: string, days: number) {
  const [y, m, d] = iso.split('-').map(Number)
  return isoLocal(new Date(y, m - 1, d + days))
}

/** Разница в днях между двумя датами YYYY-MM-DD. */
export function daysBetween(fromIso: string, toIso: string) {
  const [y1, m1, d1] = fromIso.split('-').map(Number)
  const [y2, m2, d2] = toIso.split('-').map(Number)
  return Math.round((Date.UTC(y2, m2 - 1, d2) - Date.UTC(y1, m1 - 1, d1)) / 86_400_000)
}

const WEEKDAYS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']
const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']

export function dayLabel(iso: string) {
  const [y, m, d] = iso.split('-').map(Number)
  const wd = (new Date(y, m - 1, d).getDay() + 6) % 7
  return { weekday: WEEKDAYS[wd], day: d, month: MONTHS_GEN[m - 1], short: `${WEEKDAYS[wd]} ${d}` }
}

/** Часы для кнопок выбора времени: рабочий день с шагом в час. */
export const QUICK_TIMES = ['09:00', '10:00', '11:00', '12:00', '13:00', '14:00', '15:00', '16:00', '17:00', '18:00', '19:00']

/* ── WhatsApp ───────────────────────────────────────────────────────────
 * Без официального API: кнопка открывает WhatsApp с готовым текстом, человек
 * сам нажимает «отправить». Работает и с телефона, и с компьютера. */

/** Название детейлинга — подпись в сообщениях клиенту. */
export function useOrgName() {
  const { data } = useApiCache<{ activeOrganization?: { name?: string | null } | null }>('/api/auth/session-role', { ttl: 5 * 60_000 })
  return String(data?.activeOrganization?.name || '').trim()
}

type WaCar = { customer: { name: string; phone: string | null } | null; car: { model: string | null; plate?: string | null } | null }

const hello = (b: WaCar) => {
  const name = String(b.customer?.name || '').trim()
  return name && !/^Клиент /.test(name) ? `Здравствуйте, ${name}!` : 'Здравствуйте!'
}
const carName = (b: WaCar) => b.car?.model || 'машина'
const sign = (org: string) => (org ? `\n${org}` : '')

export const waText = {
  confirm: (b: WaCar & { start_date: string; start_time?: string | null; services: Array<{ name: string }> }, org: string) =>
    `${hello(b)} Вы записаны на ${dayLabel(b.start_date).day} ${dayLabel(b.start_date).month}${b.start_time ? ` в ${b.start_time}` : ''}: ${b.services.map((s) => s.name).join(', ') || carName(b)}. Ждём вас.${sign(org)}`,
  ready: (b: WaCar & { due: number }, org: string) =>
    `${hello(b)} Ваша ${carName(b)} готова, можно забирать.${b.due > 0 ? ` К оплате ${money(b.due)}.` : ''}${sign(org)}`,
  debt: (b: WaCar & { due: number }, org: string) => `${hello(b)} Напоминаем про оплату ${money(b.due)} за ${carName(b)}.${sign(org)}`,
  hi: (b: WaCar, org: string) => `${hello(b)}${sign(org)}`,
  remind: (b: WaCar & { service: string; months: number }, org: string) =>
    `${hello(b)} ${b.months >= 12 && b.months % 12 === 0 ? (b.months === 12 ? 'Год' : `${b.months / 12} года`) : `${b.months} мес.`} назад делали вам «${b.service}». Пора обновить — записать вас?${sign(org)}`,
}

/** Ссылка wa.me с готовым текстом; null — телефона нет. */
export function waLink(phone: string | null | undefined, text: string) {
  const digits = String(phone || '').replace(/\D/g, '')
  if (digits.length < 10) return null
  const intl = digits.length === 10 ? `7${digits}` : digits.replace(/^8(?=\d{10}$)/, '7')
  return `https://wa.me/${intl}?text=${encodeURIComponent(text)}`
}

export function WaButton(props: { phone: string | null | undefined; text: string; label?: string; className?: string }) {
  const href = waLink(props.phone, props.text)
  if (!href) return null
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      onClick={(e) => e.stopPropagation()}
      className={props.className || 'inline-flex h-8 shrink-0 items-center rounded-lg bg-[#1fa855] px-2.5 text-xs font-bold text-white hover:brightness-105'}
    >
      {props.label || 'WhatsApp'}
    </a>
  )
}

/* ── Шапка страницы ───────────────────────────────────────────────────── */

/** Одна шапка на все экраны детейлинга: заголовок, строка пояснения, действия справа. */
export function DetailingHeader(props: { title: string; subtitle?: ReactNode; children?: ReactNode; toolbar?: ReactNode }) {
  return (
    <header className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h1 className="font-[family-name:var(--font-manrope)] text-[26px] font-extrabold leading-tight text-[#0b1f3b] dark:text-white">{props.title}</h1>
          {props.subtitle ? <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{props.subtitle}</p> : null}
        </div>
        {props.children ? <div className="flex flex-wrap items-center gap-2">{props.children}</div> : null}
      </div>
      {props.toolbar}
    </header>
  )
}
