'use client'

// Большой слайдер «одна система — на всех устройствах»: iPhone, Android, касса
// Windows и кабинет в браузере. Экраны нарисованы кодом из вымышленных демо-цифр —
// резкие на любом экране и не устаревают вместе со скриншотами.
// Автопрокрутка останавливается при наведении/фокусе и при prefers-reduced-motion.

import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import {
  ArrowDownToLine,
  Bell,
  Boxes,
  CalendarClock,
  Check,
  CloudOff,
  Globe,
  Home,
  LayoutGrid,
  MessageCircle,
  Monitor,
  Receipt,
  ScanLine,
  Smartphone,
  TrendingUp,
  Users,
  Wallet,
} from 'lucide-react'
import { InsightTicker } from '@/components/public/landing-demos'
import { OrdaControlMark } from '@/components/brand/orda-control-mark'
import { APP_STORE_URL, GOOGLE_PLAY_URL, OPERATOR_DOWNLOAD_URL } from '@/lib/public/app-links'

const EASE = [0.16, 1, 0.3, 1] as const
const AUTOPLAY_MS = 7000


type Slide = {
  key: string
  tab: string
  icon: typeof Smartphone
  eyebrow: string
  title: string
  text: string
  points: string[]
  actions: ReactNode
  device: ReactNode
}

const SLIDES: Slide[] = [
  {
    key: 'ios',
    tab: 'iPhone',
    icon: Smartphone,
    eyebrow: 'Приложение для iPhone',
    title: 'Бизнес в кармане владельца',
    text: 'Выручка, смены, склад и зарплаты — в нативном приложении. Пуш, когда закрылась смена или кончается товар.',
    points: ['Итоги дня и прибыль по точкам', 'Одобрение расходов в одно касание', 'Live Activity: смена на экране блокировки'],
    actions: <StoreBadge href={APP_STORE_URL} kind="apple" />,
    device: <IPhoneMock />,
  },
  {
    key: 'android',
    tab: 'Android',
    icon: Smartphone,
    eyebrow: 'Приложение для Android',
    title: 'Весь кабинет — на Android',
    text: 'Тот же кабинет, что и на сайте: доходы, расходы, склад, ревизии, задачи и команда. Режим оператора для смены — там же.',
    points: ['80+ разделов кабинета без браузера', 'Сотрудник видит свою зарплату и задачи', 'Уведомления о сменах и заявках'],
    actions: <StoreBadge href={GOOGLE_PLAY_URL} kind="google" />,
    device: <AndroidMock />,
  },
  {
    key: 'pos',
    tab: 'Касса',
    icon: Monitor,
    eyebrow: 'Программа кассира для Windows',
    title: 'Касса, которая не встаёт',
    text: 'Пропал интернет — продажи идут дальше и уходят на сервер сами. Сканер, чеки ККМ, сдача, долги сотрудников, смены.',
    points: ['Полный офлайн с очередью продаж', 'Сдача покупателю видна сразу', 'Обновляется сама, без админа'],
    actions: <StoreBadge href={OPERATOR_DOWNLOAD_URL} kind="windows" />,
    device: <PosMock />,
  },
  {
    key: 'web',
    tab: 'Браузер',
    icon: Globe,
    eyebrow: 'Кабинет в браузере',
    title: 'Ничего не нужно ставить',
    text: 'Владелец и менеджеры работают с любого компьютера: отчёты, финансы, AI-разборы и настройки точки.',
    points: ['ОПиУ, Cash Flow и налоги ИП', 'AI-копилот видит ваши цифры', 'Несколько точек в одной системе'],
    actions: (
      <a href="#contact" className="inline-flex h-[52px] items-center gap-2 rounded-[14px] bg-[#2563eb] px-6 text-[15px] font-semibold text-white transition hover:bg-[#1d4ed8]">
        Попробовать бесплатно
      </a>
    ),
    device: <BrowserMock />,
  },
]

// ─────────────── Вариант «Сцена»: лента устройств по кругу ───────────────

const RING_STEP = 46 // градусов между устройствами на «барабане»: широкие касса и браузер не наезжают друг на друга
// Высота сцены под выбранное устройство: браузер низкий, телефоны высокие.
const STAGE_HEIGHT: Record<string, number> = { ios: 780, android: 780, pos: 660, web: 620 }

/** Место устройства на ленте относительно выбранного: −1 слева, 0 в центре, 1 справа, 2 — сзади (скрыто). */
function ringOffset(i: number, index: number) {
  const n = SLIDES.length
  const d = (((i - index) % n) + n) % n
  return d > n / 2 ? d - n : d
}

/**
 * Устройства стоят на ленте, натянутой на барабан. Переключение — это поворот
 * барабана вокруг вертикальной оси: выбранное устройство приезжает в центр,
 * соседние уходят по дуге в стороны. Задних крышек и второго плана нет.
 */
export function AppsStage() {
  const rm = useReducedMotion()
  // prev нужен, чтобы устройство, перепрыгивающее «через спину» ленты, не летело через центр.
  const [{ index, prev }, setPos] = useState({ index: 0, prev: 0 })
  const setIndex = useCallback((next: number | ((i: number) => number)) => {
    setPos((cur) => {
      const n = typeof next === 'function' ? next(cur.index) : next
      return n === cur.index ? cur : { index: n, prev: cur.index }
    })
  }, [])
  const [paused, setPaused] = useState(false)
  const [radius, setRadius] = useState(880)

  // Радиус барабана под ширину экрана: на телефоне соседи уходят за край.
  useEffect(() => {
    const fit = () => setRadius(window.innerWidth < 640 ? 640 : window.innerWidth < 1024 ? 760 : 880)
    fit()
    window.addEventListener('resize', fit)
    return () => window.removeEventListener('resize', fit)
  }, [])

  useEffect(() => {
    if (rm || paused) return
    const t = setTimeout(() => setIndex((i) => (i + 1) % SLIDES.length), AUTOPLAY_MS)
    return () => clearTimeout(t)
  }, [rm, paused, index, setIndex])

  const slide = SLIDES[index]

  return (
    <div
      className="relative overflow-hidden rounded-[32px] border border-[#dbe3ee] bg-gradient-to-b from-[#eef3fa] to-white"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      <div aria-hidden className="pointer-events-none absolute inset-0" style={{ backgroundImage: 'radial-gradient(circle, rgba(15,32,56,0.07) 1px, transparent 1px)', backgroundSize: '24px 24px', maskImage: 'radial-gradient(60% 60% at 50% 45%, black, transparent)', WebkitMaskImage: 'radial-gradient(60% 60% at 50% 45%, black, transparent)' }} />
      <div aria-hidden className="pointer-events-none absolute left-1/2 top-24 h-[420px] w-[760px] -translate-x-1/2 rounded-full bg-[#2563eb]/[0.14] blur-[120px]" />

      {/* Лента по дуге: каждое устройство едет по кругу и разворачивается к зрителю */}
      <motion.div
        className="relative"
        style={{ perspective: 2200, perspectiveOrigin: '50% 45%' }}
        initial={false}
        animate={{ height: STAGE_HEIGHT[slide.key] }}
        transition={{ duration: rm ? 0 : 0.9, ease: [0.65, 0, 0.35, 1] }}
      >
        {SLIDES.map((s, i) => {
          const d = ringOffset(i, index)
          const jump = Math.abs(d - ringOffset(i, prev)) > 1
          const angle = d * RING_STEP
          const rad = (angle * Math.PI) / 180
          return (
            <motion.div
              key={s.key}
              className="absolute left-1/2 top-1/2 h-0 w-0"
              style={{ zIndex: 10 - Math.abs(d) }}
              initial={false}
              animate={{
                x: Math.sin(rad) * radius,
                z: (Math.cos(rad) - 1) * radius,
                rotateY: angle,
                scale: d === 0 ? 1 : 0.78,
                opacity: d === 0 ? 1 : Math.abs(d) === 1 ? 0.45 : 0,
              }}
              transition={{ duration: rm || jump ? 0 : 1.2, ease: [0.65, 0, 0.35, 1], opacity: { duration: rm ? 0 : 0.9 } }}
            >
              <div
                role="button"
                tabIndex={-1}
                aria-label={s.tab}
                onClick={() => setIndex(i)}
                className={`absolute left-0 top-0 w-max -translate-x-1/2 -translate-y-1/2 cursor-pointer ${d === 0 ? '' : '[&_.float-toast]:!hidden'}`}
              >
                {s.device}
              </div>
            </motion.div>
          )
        })}
      </motion.div>

      {/* Вкладки и подпись */}
      <div className="relative border-t border-[#e2e8f0] bg-white/85 px-6 py-7 backdrop-blur sm:px-10">
        <div className="mx-auto flex max-w-[1180px] flex-col gap-6 lg:flex-row lg:items-center">
          <div role="tablist" className="flex shrink-0 flex-wrap gap-2">
            {SLIDES.map((s, i) => {
              const Icon = s.icon
              const active = i === index
              return (
                <button
                  key={s.key}
                  role="tab"
                  aria-selected={active}
                  onClick={() => setIndex(i)}
                  className={`relative flex items-center gap-2 overflow-hidden rounded-full px-4 py-2 text-[14px] font-semibold transition ${active ? 'bg-[#0b1f3b] text-white' : 'bg-[#f3f6fa] text-[#56657d] hover:text-[#0b1f3b]'}`}
                >
                  <Icon className="h-4 w-4" />{s.tab}
                  {active && !rm && !paused ? (
                    <motion.span key={`p-${index}`} aria-hidden className="absolute bottom-0 left-0 h-[3px] bg-[#3b82f6]" initial={{ width: '0%' }} animate={{ width: '100%' }} transition={{ duration: AUTOPLAY_MS / 1000, ease: 'linear' }} />
                  ) : null}
                </button>
              )
            })}
          </div>
          <AnimatePresence mode="wait">
            <motion.div
              key={slide.key}
              initial={rm ? false : { opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={rm ? undefined : { opacity: 0, y: -8 }}
              transition={{ duration: 0.35, ease: EASE }}
              className="flex flex-1 flex-col gap-4 lg:ml-6 lg:flex-row lg:items-center lg:justify-between"
            >
              <div>
                <div className="font-display text-[22px] font-bold text-[#0b1f3b]">{slide.title}</div>
                <div className="mt-1 max-w-[560px] text-[14.5px] leading-[1.5] text-[#56657d]">{slide.text}</div>
              </div>
              <div className="shrink-0">{slide.actions}</div>
            </motion.div>
          </AnimatePresence>
        </div>
      </div>
    </div>
  )
}

// ─────────────── Кнопки магазинов ───────────────

export function StoreBadge({ href, kind, tone = 'dark' }: { href: string; kind: 'apple' | 'google' | 'windows'; tone?: 'dark' | 'light' }) {
  const label = kind === 'apple' ? { top: 'Загрузите в', main: 'App Store' } : kind === 'google' ? { top: 'Доступно в', main: 'Google Play' } : { top: 'Скачать для', main: 'Windows' }
  const cls = tone === 'dark'
    ? 'border-white/20 bg-black text-white hover:border-white/40'
    : 'border-[#0b1f3b] bg-[#0b1f3b] text-white hover:bg-[#1a2f4f]'
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" className={`inline-flex h-[52px] items-center gap-3 rounded-[14px] border px-5 transition ${cls}`}>
      {kind === 'apple' ? <AppleLogo /> : kind === 'google' ? <PlayLogo /> : <WindowsLogo />}
      <span className="flex flex-col leading-none">
        <span className="text-[10.5px] font-medium opacity-80">{label.top}</span>
        <span className="mt-1 text-[18px] font-semibold tracking-[-0.01em]">{label.main}</span>
      </span>
      {kind === 'windows' ? <ArrowDownToLine className="ml-1 h-4 w-4 opacity-70" /> : null}
    </a>
  )
}

function AppleLogo() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6" fill="currentColor" aria-hidden>
      <path d="M16.37 12.6c-.02-2.3 1.88-3.4 1.96-3.46-1.07-1.56-2.73-1.78-3.32-1.8-1.41-.14-2.76.83-3.48.83-.72 0-1.82-.81-3-.79-1.54.02-2.96.9-3.76 2.28-1.6 2.78-.41 6.9 1.15 9.16.77 1.1 1.68 2.34 2.87 2.3 1.15-.05 1.59-.75 2.98-.75 1.39 0 1.78.75 3 .72 1.24-.02 2.02-1.12 2.78-2.23.88-1.28 1.24-2.52 1.26-2.58-.03-.01-2.42-.93-2.44-3.68zM14.1 5.86c.63-.77 1.06-1.83.94-2.89-.91.04-2.01.61-2.66 1.37-.58.67-1.1 1.76-.96 2.8 1.01.08 2.05-.52 2.68-1.28z" />
    </svg>
  )
}

function PlayLogo() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6" aria-hidden>
      <path d="M4.2 2.6 13.6 12l-9.4 9.4c-.3-.2-.5-.6-.5-1.1V3.7c0-.5.2-.9.5-1.1z" fill="#3b82f6" />
      <path d="m16.7 8.9-3.1 3.1-9.4-9.4c.3-.2.8-.2 1.2 0l11.3 6.3z" fill="#3b82f6" />
      <path d="m16.7 15.1-11.3 6.3c-.4.2-.9.2-1.2 0l9.4-9.4 3.1 3.1z" fill="#ef4444" />
      <path d="m20.2 10.9-3.5-2-3.1 3.1 3.1 3.1 3.5-2c.8-.5.8-1.7 0-2.2z" fill="#facc15" />
    </svg>
  )
}

function WindowsLogo() {
  return (
    <svg viewBox="0 0 24 24" className="h-6 w-6" fill="currentColor" aria-hidden>
      <path d="M3 5.1 10.4 4v7.2H3V5.1zm8.3-1.2L21 2.5v8.7h-9.7V3.9zM3 12.8h7.4V20L3 18.9v-6.1zm8.3 0H21v8.7l-9.7-1.4v-7.3z" />
    </svg>
  )
}

// ─────────────── Макеты устройств ───────────────
// Рамки рисуются слоями: корпус с бликом → тонкий ободок → экран. Внутри — тот же
// язык, что в приложениях: карточки, крупные суммы, зелёный акцент.

/** Строка статуса телефона: время слева, сеть/wi-fi/батарея справа. */
function StatusBar({ dark = false, className = '' }: { dark?: boolean; className?: string }) {
  const c = dark ? '#ffffff' : '#0b1f3b'
  return (
    <div className={`flex items-center justify-between text-[12.5px] font-semibold ${className}`} style={{ color: c }}>
      <span className="tabular-nums">9:41</span>
      <span className="flex items-center gap-1.5">
        <svg width="17" height="11" viewBox="0 0 17 11" fill={c} aria-hidden><rect x="0" y="7" width="3" height="4" rx="1" /><rect x="4.5" y="5" width="3" height="6" rx="1" /><rect x="9" y="2.5" width="3" height="8.5" rx="1" /><rect x="13.5" y="0" width="3" height="11" rx="1" /></svg>
        <svg width="15" height="11" viewBox="0 0 15 11" fill={c} aria-hidden><path d="M7.5 2.2c2.1 0 4 .8 5.4 2.2l1.1-1.1A9.2 9.2 0 0 0 7.5.6 9.2 9.2 0 0 0 1 3.3l1.1 1.1a7.6 7.6 0 0 1 5.4-2.2zm0 3.1c1.2 0 2.4.5 3.2 1.3l1.1-1.1a6.1 6.1 0 0 0-8.6 0l1.1 1.1c.8-.8 2-1.3 3.2-1.3zm0 3.1c-.4 0-.8.2-1.1.5L7.5 10l1.1-1.1c-.3-.3-.7-.5-1.1-.5z" /></svg>
        <span className="relative flex h-[11px] w-[23px] items-center rounded-[3.5px] border p-[1.5px]" style={{ borderColor: `${c}66` }}>
          <span className="h-full w-[78%] rounded-[1.5px]" style={{ background: c }} />
          <span className="absolute -right-[3px] h-[4px] w-[1.5px] rounded-r-sm" style={{ background: `${c}66` }} />
        </span>
      </span>
    </div>
  )
}

/** Плавная area-диаграмма выручки (SVG), растягивается по ширине. */
function AreaChart({ points, color = '#2563eb', height = 70, id }: { points: number[]; color?: string; height?: number; id: string }) {
  const w = 300
  const max = Math.max(...points)
  const step = w / (points.length - 1)
  const xy = points.map((p, i) => [i * step, height - (p / max) * (height - 8) - 2] as const)
  // Сглаживание: кривые Безье через середины отрезков.
  let d = `M ${xy[0][0]} ${xy[0][1]}`
  for (let i = 1; i < xy.length; i++) {
    const [x0, y0] = xy[i - 1]
    const [x1, y1] = xy[i]
    const mx = (x0 + x1) / 2
    d += ` C ${mx} ${y0}, ${mx} ${y1}, ${x1} ${y1}`
  }
  const last = xy[xy.length - 1]
  return (
    <svg viewBox={`0 0 ${w} ${height}`} preserveAspectRatio="none" className="block w-full" style={{ height }} aria-hidden>
      <defs>
        <linearGradient id={`g-${id}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.32" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${d} L ${w} ${height} L 0 ${height} Z`} fill={`url(#g-${id})`} />
      <path d={d} fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
      <circle cx={last[0] - 3} cy={last[1]} r="4" fill="#fff" stroke={color} strokeWidth="2.5" vectorEffect="non-scaling-stroke" />
    </svg>
  )
}

/** Всплывающее уведомление рядом с устройством — «живость» макета. */
function FloatToast({ className = '', icon, title, text, delay = 0.6 }: { className?: string; icon: ReactNode; title: string; text: string; delay?: number }) {
  const rm = useReducedMotion()
  return (
    <motion.div
      initial={rm ? false : { opacity: 0, y: 14, scale: 0.96 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.6, ease: EASE, delay }}
      className={`float-toast pointer-events-none absolute z-20 hidden items-center gap-3 rounded-[16px] border border-white/60 bg-white py-2.5 pl-2.5 pr-4 text-[#0b1f3b] shadow-[0_18px_40px_-16px_rgba(15,32,56,0.45)] sm:flex ${className}`}
    >
      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-[11px] bg-gradient-to-br from-[#3b82f6] to-[#1d4ed8] text-white">{icon}</span>
      <span className="leading-tight">
        <span className="block text-[12.5px] font-bold">{title}</span>
        <span className="block text-[11.5px] text-[#64748b]">{text}</span>
      </span>
    </motion.div>
  )
}

function IPhoneMock() {
  return (
    <div className="relative w-[300px] sm:w-[330px]">
      {/* боковые кнопки */}
      <span className="absolute -left-[3px] top-[120px] h-[30px] w-[4px] rounded-l-md bg-[#8a8f99]" />
      <span className="absolute -left-[3px] top-[170px] h-[56px] w-[4px] rounded-l-md bg-[#8a8f99]" />
      <span className="absolute -left-[3px] top-[236px] h-[56px] w-[4px] rounded-l-md bg-[#8a8f99]" />
      <span className="absolute -right-[3px] top-[190px] h-[86px] w-[4px] rounded-r-md bg-[#8a8f99]" />
      {/* корпус: «титан» с бликом */}
      <div className="relative rounded-[56px] bg-[linear-gradient(145deg,#d9dde3_0%,#8d939d_22%,#5b616b_50%,#9aa0a9_78%,#e3e6ea_100%)] p-[3px] shadow-[0_50px_90px_-30px_rgba(0,0,0,0.75),0_0_0_1px_rgba(0,0,0,0.25)]">
        <div className="rounded-[53px] bg-black p-[9px]">
          <div className="relative overflow-hidden rounded-[45px] bg-[#f2f4f8] text-[#0b1f3b] [clip-path:inset(0_round_45px)]">
            <div className="absolute left-1/2 top-[11px] z-20 h-[30px] w-[100px] -translate-x-1/2 rounded-full bg-black" />
            {/* шапка с градиентом */}
            <div className="bg-gradient-to-b from-[#0b1f3b] to-[#17325a] px-5 pb-16 pt-4 text-white">
              <StatusBar dark className="px-2" />
              <div className="mt-6 flex items-center justify-between">
                <div>
                  <div className="text-[12px] text-white/60">Вторник, 28 октября</div>
                  <div className="text-[25px] font-bold tracking-[-0.02em]">Сегодня</div>
                </div>
                <span className="grid h-10 w-10 place-items-center rounded-full bg-gradient-to-br from-[#60a5fa] to-[#1d4ed8] text-[14px] font-bold ring-2 ring-white/20">А</span>
              </div>
            </div>
            <div className="relative -mt-12 px-4 pb-24">
              <div className="rounded-[22px] bg-white p-4 shadow-[0_12px_30px_-16px_rgba(15,32,56,0.35)]">
                <div className="flex items-start justify-between">
                  <div>
                    <div className="text-[11.5px] font-medium text-[#64748b]">Выручка за день</div>
                    <div className="mt-0.5 text-[28px] font-bold tracking-[-0.03em] tabular-nums">412 800 ₸</div>
                  </div>
                  <span className="rounded-full bg-[#2563eb]/10 px-2 py-0.5 text-[11px] font-bold text-[#1d4ed8]">+8%</span>
                </div>
                <div className="mt-2"><AreaChart id="ios" points={[30, 42, 35, 58, 49, 66, 61, 84]} height={64} /></div>
                <div className="mt-1 flex justify-between text-[9.5px] font-medium text-[#94a3b8]">
                  {['10', '12', '14', '16', '18', '20', '22'].map((h) => <span key={h}>{h}:00</span>)}
                </div>
              </div>
              <div className="mt-3 grid grid-cols-2 gap-2.5">
                {[
                  { l: 'Прибыль', v: '1,96 млн', d: '+12%', up: true },
                  { l: 'Маржа', v: '38%', d: '−1,4%', up: false },
                ].map((c) => (
                  <div key={c.l} className="rounded-[18px] bg-white p-3 shadow-[0_8px_20px_-14px_rgba(15,32,56,0.3)]">
                    <div className="text-[10.5px] text-[#64748b]">{c.l}</div>
                    <div className="mt-0.5 text-[18px] font-bold tabular-nums">{c.v}</div>
                    <div className={`text-[10.5px] font-semibold ${c.up ? 'text-[#2563eb]' : 'text-[#94a3b8]'}`}>{c.d} к прошлому</div>
                  </div>
                ))}
              </div>
              <div className="mt-3 rounded-[18px] bg-white p-3.5 shadow-[0_8px_20px_-14px_rgba(15,32,56,0.3)]">
                {[
                  { n: 'Точка на Абая', v: '248 300 ₸', w: '62%' },
                  { n: 'Точка в ТРЦ', v: '164 500 ₸', w: '41%' },
                ].map((p, i) => (
                  <div key={p.n} className={i ? 'mt-2.5' : ''}>
                    <div className="flex items-center justify-between text-[12.5px]">
                      <span className="font-medium">{p.n}</span>
                      <span className="font-semibold tabular-nums">{p.v}</span>
                    </div>
                    <div className="mt-1.5 h-[5px] rounded-full bg-[#eef2f7]"><div className="h-full rounded-full bg-gradient-to-r from-[#3b82f6] to-[#1d4ed8]" style={{ width: p.w }} /></div>
                  </div>
                ))}
              </div>
            </div>
            {/* таб-бар iOS с размытием */}
            <div className="absolute inset-x-0 bottom-0 border-t border-black/5 bg-white/95 px-6 pb-2 pt-2">
              <div className="flex justify-between">
                {[
                  { i: Home, l: 'Главная', a: true },
                  { i: TrendingUp, l: 'Аналитика' },
                  { i: LayoutGrid, l: 'Сервисы' },
                  { i: Bell, l: 'Лента' },
                ].map((t) => {
                  const Icon = t.i
                  return (
                    <span key={t.l} className={`flex flex-col items-center gap-0.5 text-[9.5px] font-medium ${t.a ? 'text-[#2563eb]' : 'text-[#94a3b8]'}`}>
                      <Icon className="h-[19px] w-[19px]" strokeWidth={t.a ? 2.4 : 2} />{t.l}
                    </span>
                  )
                })}
              </div>
              <div className="mx-auto mt-2 h-[5px] w-[118px] rounded-full bg-black" />
            </div>
            {/* блик стекла */}
            <div aria-hidden className="pointer-events-none absolute inset-0 bg-[linear-gradient(115deg,rgba(255,255,255,0.18)_0%,rgba(255,255,255,0)_38%)]" />
          </div>
        </div>
      </div>
      <FloatToast className="-right-24 top-[150px]" icon={<Bell className="h-4 w-4" />} title="Смена закрыта" text="Касса сошлась до тенге" />
    </div>
  )
}

function AndroidMock() {
  const tiles = [
    { l: 'Доходы', i: TrendingUp, c: '#2563eb' },
    { l: 'Расходы', i: Wallet, c: '#f97316' },
    { l: 'Склад', i: Boxes, c: '#3b82f6' },
    { l: 'Смены', i: CalendarClock, c: '#8b5cf6' },
    { l: 'Команда', i: Users, c: '#0ea5e9' },
    { l: 'Ревизия', i: ScanLine, c: '#ec4899' },
    { l: 'Чеки', i: Receipt, c: '#14b8a6' },
    { l: 'Чат', i: MessageCircle, c: '#eab308' },
    { l: 'Ещё', i: LayoutGrid, c: '#64748b' },
  ]
  return (
    <div className="relative w-[300px] sm:w-[330px]">
      <span className="absolute -right-[3px] top-[130px] h-[44px] w-[4px] rounded-r-md bg-[#3a3f48]" />
      <span className="absolute -right-[3px] top-[190px] h-[86px] w-[4px] rounded-r-md bg-[#3a3f48]" />
      <div className="relative rounded-[46px] bg-[linear-gradient(150deg,#4b515c_0%,#23272f_45%,#3d424c_100%)] p-[3px] shadow-[0_50px_90px_-30px_rgba(0,0,0,0.75),0_0_0_1px_rgba(0,0,0,0.3)]">
        <div className="rounded-[43px] bg-black p-[8px]">
          <div className="relative overflow-hidden rounded-[36px] bg-[#f6f8fb] text-[#0b1f3b] [clip-path:inset(0_round_36px)]">
            <div className="absolute left-1/2 top-[12px] z-20 h-[13px] w-[13px] -translate-x-1/2 rounded-full bg-black ring-[3px] ring-[#1a1a1a]" />
            <StatusBar className="px-6 pt-3" />
            <div className="px-4 pb-24 pt-4">
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-[12px] text-[#5b6b82]">Добрый день, Айгерим</div>
                  <div className="text-[24px] font-semibold tracking-[-0.01em]">Сервисы</div>
                </div>
                <span className="grid h-11 w-11 place-items-center rounded-full bg-[#dbeafe] text-[15px] font-bold text-[#1d4ed8]">А</span>
              </div>
              <div className="mt-3 flex gap-1.5">
                {['Все точки', 'Сегодня', 'Неделя'].map((c, i) => (
                  <span key={c} className={`flex items-center gap-1 rounded-[9px] border px-2.5 py-1 text-[11px] font-medium ${i === 0 ? 'border-transparent bg-[#dbeafe] text-[#1e3a8a]' : 'border-[#d6dde8] text-[#5b6b82]'}`}>
                    {i === 0 ? <Check className="h-3 w-3" /> : null}{c}
                  </span>
                ))}
              </div>
              <div className="relative mt-3 overflow-hidden rounded-[28px] bg-gradient-to-br from-[#2563eb] to-[#1d4ed8] p-4 text-white shadow-[0_14px_30px_-14px_rgba(37,99,235,0.7)]">
                <div aria-hidden className="absolute -right-8 -top-10 h-32 w-32 rounded-full bg-white/10" />
                <div aria-hidden className="absolute -bottom-12 right-10 h-24 w-24 rounded-full bg-white/10" />
                <div className="relative text-[11.5px] text-white/80">Касса сейчас</div>
                <div className="relative mt-0.5 text-[26px] font-semibold tabular-nums">186 450 ₸</div>
                <div className="relative mt-2 flex gap-1.5">
                  <span className="rounded-full bg-white/20 px-2 py-0.5 text-[10.5px] font-medium">2 смены открыты</span>
                  <span className="rounded-full bg-[#fbbf24] px-2 py-0.5 text-[10.5px] font-bold text-[#422006]">3 расхода ждут</span>
                </div>
              </div>
              <div className="mt-4 grid grid-cols-3 gap-x-2 gap-y-3.5">
                {tiles.map((t) => {
                  const Icon = t.i
                  return (
                    <div key={t.l} className="flex flex-col items-center gap-1.5">
                      <span className="grid h-[54px] w-[54px] place-items-center rounded-[19px]" style={{ background: `linear-gradient(145deg, ${t.c}26, ${t.c}14)`, color: t.c }}>
                        <Icon className="h-6 w-6" />
                      </span>
                      <span className="text-[11px] font-medium">{t.l}</span>
                    </div>
                  )
                })}
              </div>
            </div>
            {/* Material-навигация */}
            <div className="absolute inset-x-0 bottom-0 bg-[#eef2f8] px-2 pb-2 pt-2.5">
              <div className="flex items-center justify-around">
                {[
                  { i: Home, l: 'Главная', a: false },
                  { i: LayoutGrid, l: 'Сервисы', a: true },
                  { i: TrendingUp, l: 'Аналитика', a: false },
                  { i: Bell, l: 'Лента', a: false },
                ].map((n) => {
                  const Icon = n.i
                  return (
                    <div key={n.l} className="flex flex-col items-center gap-1">
                      <span className={`grid h-8 w-14 place-items-center rounded-full ${n.a ? 'bg-[#dbeafe] text-[#1e3a8a]' : 'text-[#5b6b82]'}`}><Icon className="h-[18px] w-[18px]" /></span>
                      <span className={`text-[10px] ${n.a ? 'font-bold' : 'font-medium'}`}>{n.l}</span>
                    </div>
                  )
                })}
              </div>
              <div className="mx-auto mt-2 h-[4px] w-[100px] rounded-full bg-[#0b1f3b]/70" />
            </div>
            {/* FAB */}
            <span className="absolute bottom-[86px] right-4 grid h-14 w-14 place-items-center rounded-[18px] bg-[#dbeafe] text-[26px] font-light text-[#1e3a8a] shadow-[0_8px_18px_-8px_rgba(20,83,45,0.6)]">+</span>
            <div aria-hidden className="pointer-events-none absolute inset-0 bg-[linear-gradient(115deg,rgba(255,255,255,0.16)_0%,rgba(255,255,255,0)_36%)]" />
          </div>
        </div>
      </div>
      <FloatToast className="-left-24 top-[300px]" icon={<Wallet className="h-4 w-4" />} title="Расход одобрен" text="Закуп воды · 24 000 ₸" delay={0.8} />
    </div>
  )
}

function PosMock() {
  const products = [
    { n: 'Энергетик 0,45', p: '800', c: '#3b82f6', e: '⚡' },
    { n: 'Вода 0,5', p: '300', c: '#0ea5e9', e: '💧' },
    { n: 'Чипсы', p: '650', c: '#f59e0b', e: '🥔' },
    { n: 'Хот-дог', p: '900', c: '#ef4444', e: '🌭' },
    { n: 'Кофе', p: '700', c: '#a16207', e: '☕' },
    { n: 'Шоколад', p: '450', c: '#7c3aed', e: '🍫' },
    { n: 'Лимонад', p: '550', c: '#2563eb', e: '🍋' },
    { n: 'Сэндвич', p: '1 200', c: '#ea580c', e: '🥪' },
  ]
  return (
    <div className="relative w-full max-w-[720px]">
      {/* монитор: тонкая рамка + подбородок с логотипом */}
      <div className="rounded-[20px] bg-[linear-gradient(160deg,#4b515c,#1b1f26_40%,#2c313a)] p-[3px] shadow-[0_50px_90px_-30px_rgba(0,0,0,0.75)]">
        <div className="rounded-[17px] bg-[#0c0f14] p-[10px] pb-0">
          <div className="relative overflow-hidden rounded-[9px] bg-[#0b1626] [clip-path:inset(0_round_9px)]">
            <div className="flex items-center justify-between border-b border-white/10 bg-[#0e1b2e] px-3 py-2 text-[11px] text-white/70">
              <span className="flex items-center gap-2">
                <OrdaControlMark size={20} />
                Orda Point — Касса · Смена Айгерим
              </span>
              <span className="flex items-center gap-1.5 rounded-full bg-[#f97316]/15 px-2 py-0.5 text-[10px] font-semibold text-[#fdba74]"><CloudOff className="h-3 w-3" />Офлайн · 3 в очереди</span>
            </div>
            <div className="grid grid-cols-[1.4fr_1fr]">
              <div className="p-3">
                <div className="flex items-center gap-2 rounded-[9px] border border-white/10 bg-white/[0.05] px-2.5 py-1.5 text-[11px] text-white/45"><ScanLine className="h-3.5 w-3.5 text-[#60a5fa]" />Сканируйте штрихкод или найдите товар</div>
                <div className="mt-2 flex gap-1.5">
                  {['Все', 'Напитки', 'Еда', 'Снеки'].map((c, i) => (
                    <span key={c} className={`rounded-full px-2.5 py-0.5 text-[10px] font-semibold ${i === 0 ? 'bg-white text-[#0b1626]' : 'bg-white/[0.07] text-white/60'}`}>{c}</span>
                  ))}
                </div>
                <div className="mt-2.5 grid grid-cols-2 gap-1.5 sm:grid-cols-4">
                  {products.map((p, i) => (
                    <div key={p.n} className={`relative rounded-[10px] border p-2 ${i === 0 ? 'border-[#3b82f6] bg-[#2563eb]/15 shadow-[0_0_0_3px_rgba(34,197,94,0.15)]' : 'border-white/10 bg-white/[0.04]'}`}>
                      <span className="grid h-8 w-8 place-items-center rounded-[9px] text-[15px]" style={{ background: `${p.c}33` }}>{p.e}</span>
                      <div className="mt-1.5 truncate text-[10.5px] font-medium text-white/90">{p.n}</div>
                      <div className="text-[11px] font-bold text-white tabular-nums">{p.p} ₸</div>
                      {i === 0 ? <span className="absolute right-1.5 top-1.5 grid h-4 w-4 place-items-center rounded-full bg-[#3b82f6] text-[9px] font-bold text-white">2</span> : null}
                    </div>
                  ))}
                </div>
              </div>
              <div className="flex flex-col border-l border-white/10 bg-[#0d1a2c] p-3 text-white">
                <div className="flex items-center justify-between text-[10.5px] font-semibold uppercase tracking-[0.08em] text-white/50"><span>Чек №248</span><span>3 поз.</span></div>
                {[
                  { n: 'Энергетик 0,45', q: '2 × 800', s: '1 600' },
                  { n: 'Хот-дог', q: '1 × 900', s: '900' },
                  { n: 'Вода 0,5', q: '1 × 300', s: '300' },
                ].map((r) => (
                  <div key={r.n} className="mt-2 flex items-start justify-between gap-2 border-b border-dashed border-white/10 pb-2 text-[11px]">
                    <div><div className="font-medium">{r.n}</div><div className="text-white/45">{r.q}</div></div>
                    <span className="font-semibold tabular-nums">{r.s}</span>
                  </div>
                ))}
                <div className="mt-auto pt-3">
                  <div className="flex items-baseline justify-between">
                    <span className="text-[11px] text-white/60">Итого</span>
                    <span className="text-[21px] font-bold tabular-nums">2 800 ₸</span>
                  </div>
                  <div className="mt-1.5 flex items-center justify-between rounded-[8px] bg-[#2563eb]/15 px-2 py-1 text-[10.5px]">
                    <span className="text-white/70">Наличные 5 000</span>
                    <span className="font-bold text-[#93c5fd]">Сдача 2 200 ₸</span>
                  </div>
                  <div className="mt-2 rounded-[10px] bg-gradient-to-b from-[#3b82f6] to-[#2563eb] py-2 text-center text-[12.5px] font-bold shadow-[0_8px_18px_-8px_rgba(34,197,94,0.8)]">Оплатить · Enter</div>
                </div>
              </div>
            </div>
            <div aria-hidden className="pointer-events-none absolute inset-0 bg-[linear-gradient(120deg,rgba(255,255,255,0.07)_0%,rgba(255,255,255,0)_35%)]" />
          </div>
          <div className="flex h-7 items-center justify-center"><span className="h-1.5 w-1.5 rounded-full bg-white/20" /></div>
        </div>
      </div>
      {/* стойка */}
      <div className="mx-auto h-12 w-[16%] bg-gradient-to-b from-[#3a3f48] to-[#22262d] [clip-path:polygon(18%_0,82%_0,100%_100%,0_100%)]" />
      <div className="mx-auto h-2.5 w-[38%] rounded-t-[10px] rounded-b-[4px] bg-gradient-to-b from-[#4b515c] to-[#2a2f37] shadow-[0_14px_24px_-10px_rgba(0,0,0,0.6)]" />
      <FloatToast className="-left-10 bottom-16" icon={<Receipt className="h-4 w-4" />} title="Продажа · 2 800 ₸" text="Уйдёт на сервер, когда вернётся сеть" delay={0.7} />
    </div>
  )
}

const BROWSER_INSIGHTS = [
  'Маржа ↓ 1,4% — выросла закупка «напитки» на 14%. Есть поставщик дешевле на 11%.',
  'По товару «энергетики» осталось на 3 дня продаж. В план закупа добавлено 4 упаковки.',
  'Смена закрыта без расхождений: наличные, Kaspi и система сошлись до тенге.',
]

function BrowserMock() {
  return (
    <div className="relative w-full max-w-[720px]">
      <div className="overflow-hidden rounded-[16px] bg-white text-[#0b1f3b] shadow-[0_50px_90px_-30px_rgba(0,0,0,0.75),0_0_0_1px_rgba(15,32,56,0.08)]">
        <div className="flex items-center gap-3 border-b border-[#e2e8f0] bg-[#f3f6fa] px-3 py-2.5">
          <span className="flex gap-1.5"><i className="h-3 w-3 rounded-full bg-[#ff5f57]" /><i className="h-3 w-3 rounded-full bg-[#febc2e]" /><i className="h-3 w-3 rounded-full bg-[#28c840]" /></span>
          <span className="flex flex-1 items-center gap-1.5 truncate rounded-[8px] bg-white px-3 py-1 text-[11px] text-[#64748b] ring-1 ring-[#e2e8f0]"><span className="text-[#2563eb]">🔒</span>ordaops.kz/dashboard</span>
        </div>
        <div className="grid grid-cols-[120px_1fr]">
          <div className="hidden flex-col gap-1 border-r border-[#e2e8f0] bg-[#f8fafc] p-2.5 sm:flex">
            <div className="mb-2 flex items-center gap-1.5 px-1.5 text-[11px] font-bold"><OrdaControlMark size={20} />Orda</div>
            {['Обзор', 'Доходы', 'Расходы', 'Склад', 'Зарплата', 'Отчёты'].map((m, i) => (
              <span key={m} className={`rounded-[7px] px-2 py-1.5 text-[11px] font-medium ${i === 0 ? 'bg-[#2563eb]/10 text-[#1d4ed8]' : 'text-[#56657d]'}`}>{m}</span>
            ))}
          </div>
          <div className="col-span-2 bg-[#eef2f8] p-3 sm:col-span-1 sm:p-4">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
              {[
                { l: 'Выручка за день', v: '412 800 ₸', d: '+8%', up: true },
                { l: 'Маржа', v: '38%', d: '−1,4%', up: false },
                { l: 'Прибыль, мес', v: '1,96 млн ₸', d: '+12%', up: true },
                { l: 'ФОТ', v: '540 000 ₸', d: '0%', up: false },
              ].map((c) => (
                <div key={c.l} className="rounded-[10px] border border-[#e2e8f0] bg-white p-2.5">
                  <div className="text-[9.5px] font-semibold uppercase tracking-[0.05em] text-[#64748b]">{c.l}</div>
                  <div className="mt-1 text-[14.5px] font-extrabold tabular-nums">{c.v}</div>
                  <div className={`text-[10px] font-semibold ${c.up ? 'text-[#2563eb]' : 'text-[#94a3b8]'}`}>{c.d}</div>
                </div>
              ))}
            </div>
            <div className="mt-2 grid gap-2 sm:grid-cols-[1.25fr_1fr]">
              <div className="rounded-[10px] border border-[#e2e8f0] bg-white p-3">
                <div className="flex items-center justify-between text-[10px] font-semibold uppercase tracking-[0.05em] text-[#64748b]"><span>Выручка · 30 дней</span><span className="text-[#2563eb]">+8%</span></div>
                <div className="mt-2"><AreaChart id="web" points={[40, 46, 38, 52, 49, 58, 54, 63, 59, 70, 66, 78]} height={70} /></div>
              </div>
              <div className="rounded-[10px] border border-[#2563eb]/25 bg-gradient-to-br from-[#2563eb]/[0.08] to-transparent p-3">
                <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.05em] text-[#1d4ed8]"><span className="grid h-4 w-4 place-items-center rounded-[5px] bg-[#2563eb] text-[9px] text-white">AI</span>Наблюдение</div>
                <InsightTicker items={BROWSER_INSIGHTS} className="mt-1.5 min-h-[66px] [&_div]:text-[11.5px] [&_div]:text-[#334155]" />
              </div>
            </div>
          </div>
        </div>
      </div>
      <FloatToast className="-right-8 -bottom-6" icon={<TrendingUp className="h-4 w-4" />} title="Прибыль +12%" text="к прошлому месяцу" delay={0.7} />
    </div>
  )
}
