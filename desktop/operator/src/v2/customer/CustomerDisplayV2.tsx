import { useEffect, useRef, useState } from 'react'
import { Check, Star, Timer, UserRound } from 'lucide-react'

import { applyTheme } from '@/lib/preferences'
import {
  AdPlayer,
  formatMoney,
  useAnimatedNumber,
  useCustomerDisplayFeed,
  useQrDataUrl,
  type CartLine,
  type PaidView,
  type ShowcaseItem,
} from '@/pages/CustomerDisplay'
import { categoryLook } from '../ui/category-look'
import { useLiteMode } from '../ui/V2Shell'
import '../v2.css'

/**
 * Экран покупателя в новом виде — тот же стиль, что у кассы v2.
 * Данные те же, что у классического (useCustomerDisplayFeed): корзина вживую,
 * «Оплачено» с временем игры и бонусами, реклама и витрина в простое.
 * Тема — как на кассе: окно отдельное, поэтому слушаем storage.
 */

function useTheme(): 'light' | 'dark' {
  const resolve = () => (document.documentElement.classList.contains('dark') ? 'dark' : 'light') as 'light' | 'dark'
  const [theme, setThemeState] = useState(resolve)
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key && e.key !== 'orda.theme') return
      applyTheme()
      setThemeState(resolve())
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])
  return theme
}

function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10
  const m100 = n % 100
  if (m10 === 1 && m100 !== 11) return one
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few
  return many
}

function hhmm(iso: string) {
  return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
}

export default function CustomerDisplayV2() {
  const theme = useTheme()
  const lite = useLiteMode()
  const { state, paid, now, playlist, reviewUrl, showcase } = useCustomerDisplayFeed()
  const reviewQr = useQrDataUrl(reviewUrl, 200)
  const time = now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
  const date = now.toLocaleDateString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' })
  // После «Оплачено» корзина сбрасывается вместе с названием — держим последнее.
  const lastCompany = useRef<string | null>(null)
  if (state.companyName) lastCompany.current = state.companyName
  const company = state.companyName || lastCompany.current || 'Orda Point'

  let body: React.ReactNode
  if (paid) body = <PaidScreen paid={paid} />
  else if (state.cart.length > 0) body = <CartScreen state={state} time={time} company={company} />
  else if (playlist.length > 0) body = <AdPlayer items={playlist} clock={time} />
  else if (showcase.length > 0) body = <Showcase items={showcase} time={time} company={company} />
  else body = <Welcome time={time} date={date} company={company} />

  return (
    <div className="v2 relative h-screen w-screen select-none overflow-hidden" data-theme={theme} data-lite={lite ? 'true' : undefined}>
      {body}
      {!paid && state.cart.length === 0 && reviewQr ? (
        <div className="surface absolute bottom-6 left-6 z-20 flex items-center gap-3 rounded-[22px] p-3 pr-5">
          <img src={reviewQr} alt="QR — оцените нас" className="h-24 w-24 rounded-[14px] bg-white p-1" />
          <div className="leading-tight">
            <div className="text-[17px] font-semibold">Оцените нас</div>
            <div className="muted mt-0.5 max-w-[10rem] text-[13px]">Наведите камеру и оставьте отзыв</div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

// ─── Простой: часы и приветствие ──────────────────────────────────────────

function Welcome(props: { time: string; date: string; company: string }) {
  return (
    <div className="flex h-full flex-col items-center justify-center px-10 text-center">
      <div className="text-[30px] font-semibold tracking-tight">{props.company}</div>
      <div className="mt-6 text-[clamp(120px,18vw,240px)] font-[200] leading-none tracking-[-0.04em]">{props.time}</div>
      <div className="muted mt-4 text-[26px] first-letter:uppercase">{props.date}</div>
      <div className="ink-2 mt-16 text-[28px]">Добро пожаловать</div>
    </div>
  )
}

// ─── Простой: витрина товаров с фото ──────────────────────────────────────

function Showcase(props: { items: ShowcaseItem[]; time: string; company: string }) {
  const [index, setIndex] = useState(0)
  useEffect(() => {
    const t = setInterval(() => setIndex((i) => (i + 1) % props.items.length), 8000)
    return () => clearInterval(t)
  }, [props.items.length])
  const current = props.items[index % props.items.length]
  if (!current) return null
  return (
    <div className="flex h-full flex-col">
      <header className="flex shrink-0 items-center justify-between px-10 py-7">
        <div className="text-[26px] font-semibold tracking-tight">{props.company}</div>
        <div className="muted text-[30px] font-light">{props.time}</div>
      </header>
      <div key={current.id} className="sheet-in flex flex-1 flex-col items-center justify-center pb-16">
        <div className="surface h-[50vh] w-[50vh] overflow-hidden rounded-[44px]">
          <Thumb name={current.name} imageUrl={current.image_url} rounded="rounded-none" size="h-full w-full" letter="text-[160px]" />
        </div>
        <div className="mt-9 max-w-[70vw] truncate text-[52px] font-bold tracking-tight">{current.name}</div>
        <div className="t-blue mt-2 text-[60px] font-semibold">{formatMoney(current.sale_price)} ₸</div>
      </div>
      {props.items.length > 1 ? (
        <div className="absolute bottom-8 left-1/2 flex -translate-x-1/2 gap-2">
          {props.items.slice(0, 12).map((it, i) => (
            <span
              key={it.id}
              className="h-2 rounded-full transition-all duration-500"
              style={{ width: i === index % Math.min(props.items.length, 12) ? 26 : 8, background: i === index % Math.min(props.items.length, 12) ? 'var(--ink)' : 'var(--fill)' }}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

// ─── Покупка идёт: список позиций и итог ──────────────────────────────────

function CartScreen(props: { state: ReturnType<typeof useCustomerDisplayFeed>['state']; time: string; company: string }) {
  const { state } = props
  const total = useAnimatedNumber(state.total, 450)
  const listRef = useRef<HTMLDivElement>(null)
  const pieces = state.cart.reduce((n, l) => n + l.quantity, 0)
  const big = state.cart.length <= 4

  // Новая позиция всегда видна: прокручиваем к ней.
  useEffect(() => {
    if (!state.lastAddedId || !listRef.current) return
    try {
      listRef.current.querySelector(`[data-line-id="${CSS.escape(state.lastAddedId)}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    } catch {
      /* best effort */
    }
  }, [state.lastAddedId, state.cart.length])

  return (
    <div className="grid h-full grid-cols-[minmax(0,1fr)_minmax(340px,34%)] gap-6 p-6">
      <section className="flex min-h-0 flex-col">
        <header className="flex shrink-0 items-baseline justify-between px-2 pb-5 pt-1">
          <div>
            <div className="text-[34px] font-bold tracking-tight">Ваш заказ</div>
            <div className="muted text-[17px]">
              {props.company}
              {state.operatorName ? ` · кассир ${state.operatorName}` : ''}
            </div>
          </div>
          <div className="muted text-[28px] font-light">{props.time}</div>
        </header>
        <div ref={listRef} className="min-h-0 flex-1 space-y-3 overflow-y-auto pr-1">
          {state.cart.map((line, i) => (
            <Row key={line.id || i} line={line} isNew={!!state.lastAddedId && state.lastAddedId === line.id} big={big} />
          ))}
        </div>
      </section>

      <aside className="glass flex min-h-0 flex-col rounded-[34px] p-8">
        {state.customer ? (
          <div className="surface-2 flex items-center gap-4 rounded-[22px] p-4">
            <div className="grid h-12 w-12 shrink-0 place-items-center rounded-full" style={{ background: 'var(--orange)' }}>
              <UserRound className="h-6 w-6 text-white" />
            </div>
            <div className="min-w-0">
              <div className="truncate text-[20px] font-semibold">{state.customer.name || 'Постоянный клиент'}</div>
              <div className="muted text-[16px]">
                Бонусов: <b className="ink">{formatMoney(state.customer.loyaltyPoints)}</b>
              </div>
            </div>
          </div>
        ) : null}

        <div className="mt-auto">
          <div className="muted text-[18px]">
            {pieces} {plural(pieces, 'товар', 'товара', 'товаров')}
          </div>
          {state.discount > 0 ? (
            <div className="mt-4 space-y-2 text-[20px]">
              <div className="flex justify-between">
                <span className="muted">Сумма</span>
                <span className="muted line-through">{formatMoney(state.subtotal)} ₸</span>
              </div>
              <div className="flex justify-between">
                <span className="muted">Скидка</span>
                <span className="t-green font-semibold">−{formatMoney(state.discount)} ₸</span>
              </div>
            </div>
          ) : null}
          <div className="line mt-5 border-t pt-5">
            <div className="text-[20px] font-medium">К оплате</div>
            <div className="mt-1 text-[clamp(64px,7vw,104px)] font-bold leading-none tracking-[-0.03em]">
              {formatMoney(total)}
              <span className="muted ml-2 text-[0.5em] font-semibold">₸</span>
            </div>
          </div>
        </div>
      </aside>
    </div>
  )
}

function Row(props: { line: CartLine; isNew: boolean; big: boolean }) {
  const { line } = props
  return (
    <div
      data-line-id={line.id}
      className={`surface flex items-center gap-5 rounded-[24px] ${props.big ? 'p-5' : 'p-4'} ${props.isNew ? 'pop' : ''}`}
      style={props.isNew ? { boxShadow: '0 0 0 3px var(--blue), var(--shadow)' } : undefined}
    >
      {String(line.id || '').startsWith('service:') ? (
        // Услуга из справочника — значок таймера, как на кассе
        <div
          className={`${props.big ? 'h-24 w-24 rounded-[22px]' : 'h-16 w-16 rounded-[16px]'} grid shrink-0 place-items-center`}
          style={{ background: 'color-mix(in srgb, #af52de 16%, transparent)' }}
        >
          <Timer className={props.big ? 'h-11 w-11' : 'h-8 w-8'} style={{ color: '#af52de' }} />
        </div>
      ) : (
        <Thumb
          name={line.name}
          imageUrl={line.image_url || null}
          size={props.big ? 'h-24 w-24' : 'h-16 w-16'}
          rounded={props.big ? 'rounded-[22px]' : 'rounded-[16px]'}
          letter={props.big ? 'text-[40px]' : 'text-[28px]'}
        />
      )}
      <div className="min-w-0 flex-1">
        <div className={`line-clamp-2 font-semibold leading-snug ${props.big ? 'text-[30px]' : 'text-[23px]'}`}>{line.name}</div>
        <div className={`muted ${props.big ? 'text-[20px]' : 'text-[17px]'}`}>
          {line.quantity} × {formatMoney(line.unit_price)} ₸
        </div>
      </div>
      <div className={`shrink-0 font-semibold ${props.big ? 'text-[34px]' : 'text-[26px]'}`}>{formatMoney(line.quantity * line.unit_price)} ₸</div>
    </div>
  )
}

/** Фото товара; без фото — цветная плитка с первой буквой (цвет постоянный по названию). */
function Thumb(props: { name: string; imageUrl: string | null; size: string; rounded: string; letter: string }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [props.imageUrl])
  if (props.imageUrl && !failed) {
    return <img src={props.imageUrl} alt="" className={`${props.size} ${props.rounded} shrink-0 object-cover`} onError={() => setFailed(true)} />
  }
  const color = categoryLook(props.name).color
  return (
    <div
      className={`${props.size} ${props.rounded} grid shrink-0 place-items-center font-bold`}
      style={{ background: `color-mix(in srgb, ${color} 16%, transparent)`, color }}
    >
      <span className={props.letter}>{(props.name.trim().charAt(0) || '·').toUpperCase()}</span>
    </div>
  )
}

// ─── Оплачено ─────────────────────────────────────────────────────────────

function PaidScreen({ paid }: { paid: PaidView }) {
  const qr = useQrDataUrl(paid.reviewUrl, 260)
  const loyalty = paid.loyalty
  return (
    <div className="flex h-full flex-col items-center justify-center overflow-hidden px-10 py-[3vh] text-center">
      <style>{paidStyle}</style>
      <div className="cd2-check grid h-[min(9rem,15vh)] w-[min(9rem,15vh)] shrink-0 place-items-center rounded-full" style={{ background: 'var(--green)', boxShadow: '0 20px 60px color-mix(in srgb, var(--green) 40%, transparent)' }}>
        <Check className="h-1/2 w-1/2 text-white" strokeWidth={3} />
      </div>
      <div className="cd2-up mt-[3vh] text-[clamp(28px,5vh,44px)] font-bold tracking-tight">Оплачено</div>
      <div className="cd2-up muted mt-1 text-[24px]" style={{ animationDelay: '120ms' }}>{paid.paymentLabel}</div>
      <div className="cd2-up mt-[2vh] text-[clamp(64px,min(11vw,15vh),150px)] font-bold leading-none tracking-[-0.04em]" style={{ animationDelay: '220ms' }}>
        {formatMoney(paid.total)}
        <span className="muted ml-3 text-[0.45em] font-semibold">₸</span>
      </div>

      {paid.timers.length > 0 ? (
        <div className="cd2-up mt-[4vh] flex flex-wrap justify-center gap-4" style={{ animationDelay: '340ms' }}>
          {paid.timers.map((t, i) => (
            <div key={`${t.title}-${i}`} className="surface flex items-center gap-5 rounded-[28px] px-8 py-5 text-left">
              <div className="grid h-14 w-14 place-items-center rounded-[16px]" style={{ background: 'color-mix(in srgb, #af52de 16%, transparent)' }}>
                <Timer className="h-8 w-8" style={{ color: '#af52de' }} />
              </div>
              <div>
                <div className="muted text-[20px]">{t.title} — ваше время до</div>
                <div className="text-[clamp(36px,7vh,56px)] font-bold leading-none tracking-tight">{hhmm(t.endsAt)}</div>
              </div>
            </div>
          ))}
        </div>
      ) : null}

      {loyalty && loyalty.earned > 0 ? (
        <div className="cd2-up surface mt-[3vh] flex items-center gap-3 rounded-full px-7 py-3.5" style={{ animationDelay: '440ms' }}>
          <Star className="h-7 w-7" style={{ color: 'var(--orange)', fill: 'var(--orange)' }} />
          <span className="text-[24px] font-semibold">+{formatMoney(loyalty.earned)} бонусов</span>
          <span className="muted text-[22px]">· всего {formatMoney(loyalty.totalAfter)}</span>
        </div>
      ) : null}

      {qr ? (
        <div className="cd2-up surface mt-[3vh] flex items-center gap-5 rounded-[28px] p-4 pr-7 text-left" style={{ animationDelay: '540ms' }}>
          <img src={qr} alt="QR — оцените нас" className="h-[min(8rem,14vh)] w-[min(8rem,14vh)] rounded-[16px] bg-white p-1" />
          <div>
            <div className="text-[24px] font-semibold">Оцените нас</div>
            <div className="muted mt-1 max-w-[16rem] text-[16px]">Наведите камеру телефона на QR-код и оставьте отзыв</div>
          </div>
        </div>
      ) : (
        <div className="cd2-up muted mt-10 text-[24px]" style={{ animationDelay: '440ms' }}>Спасибо за покупку!</div>
      )}
    </div>
  )
}

const paidStyle = `
  @keyframes cd2-check { 0% { transform: scale(0.3); opacity: 0 } 60% { transform: scale(1.08); opacity: 1 } 100% { transform: scale(1) } }
  @keyframes cd2-up { from { opacity: 0; transform: translateY(14px) } to { opacity: 1; transform: none } }
  .cd2-check { animation: cd2-check 560ms cubic-bezier(0.34, 1.56, 0.64, 1) both }
  .cd2-up { animation: cd2-up 520ms cubic-bezier(0.22, 1, 0.36, 1) both }
`
