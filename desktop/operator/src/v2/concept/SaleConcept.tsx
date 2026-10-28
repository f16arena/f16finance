import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowRightLeft,
  BadgePercent,
  Check,
  ClipboardList,
  Clock,
  Cookie,
  CupSoda,
  Gamepad2,
  History,
  LayoutGrid,
  Minus,
  Moon,
  PauseCircle,
  Plus,
  Printer,
  ReceiptText,
  RotateCcw,
  ScanBarcode,
  Search,
  ShoppingBag,
  Sun,
  Trash2,
  UserRound,
  UtensilsCrossed,
  Wallet,
  X,
  type LucideIcon,
} from 'lucide-react'

import { ITEMS, OPERATOR } from '@/demo/fixtures'
import type { PointInventorySaleItem } from '@/types'

/**
 * Концепция экрана продажи кассы v2 в стиле Apple — только внешний вид и
 * поведение рук оператора, без сервера. Данные — подставные товары стенда.
 */

type Line = { item: PointInventorySaleItem; qty: number }

const TABS: Array<{ label: string; icon: LucideIcon }> = [
  { label: 'Смена', icon: ReceiptText },
  { label: 'Продажа', icon: ShoppingBag },
  { label: 'Возврат', icon: RotateCcw },
  { label: 'История', icon: History },
  { label: 'Долги', icon: Wallet },
  { label: 'Заявка', icon: ClipboardList },
  { label: 'Зал', icon: Gamepad2 },
]

const CATEGORY_LOOK: Record<string, { icon: LucideIcon; color: string }> = {
  all: { icon: LayoutGrid, color: '#8e8e93' },
  'cat-drinks': { icon: CupSoda, color: '#2563eb' },
  'cat-snacks': { icon: Cookie, color: '#ff9500' },
  'cat-food': { icon: UtensilsCrossed, color: '#ff3b30' },
  'cat-time': { icon: Clock, color: '#af52de' },
}

function money(value: number) {
  return Math.round(value).toLocaleString('ru-RU')
}

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10
  const m100 = n % 100
  if (m10 === 1 && m100 !== 11) return one
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few
  return many
}

export default function SaleConcept() {
  const [theme, setTheme] = useState<'light' | 'dark'>('light')
  const [category, setCategory] = useState('all')
  const [query, setQuery] = useState('')
  const [cart, setCart] = useState<Line[]>([])
  const [popId, setPopId] = useState<{ id: string; n: number } | null>(null)
  const [paid, setPaid] = useState<{ total: number; method: 'cash' | 'kaspi' } | null>(null)
  const [now, setNow] = useState(() => new Date())
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15_000)
    return () => clearInterval(t)
  }, [])

  const categories = useMemo(() => {
    const map = new Map<string, { id: string; name: string }>()
    for (const item of ITEMS) if (item.category) map.set(item.category.id, item.category)
    return [{ id: 'all', name: 'Все' }, ...map.values()]
  }, [])

  const visible = ITEMS.filter((item) => {
    if (category !== 'all' && item.category?.id !== category) return false
    if (query.trim() && !item.name.toLowerCase().includes(query.trim().toLowerCase())) return false
    return true
  })

  const total = cart.reduce((s, l) => s + l.qty * l.item.sale_price, 0)
  const pieces = cart.reduce((s, l) => s + l.qty, 0)
  const qtyOf = (id: string) => cart.find((l) => l.item.id === id)?.qty || 0

  function add(item: PointInventorySaleItem) {
    if (item.display_qty <= 0) return
    setPaid(null)
    setCart((prev) =>
      prev.some((l) => l.item.id === item.id)
        ? prev.map((l) => (l.item.id === item.id ? { ...l, qty: l.qty + 1 } : l))
        : [...prev, { item, qty: 1 }],
    )
    setPopId((p) => ({ id: item.id, n: (p?.n || 0) + 1 }))
  }

  function change(id: string, delta: number) {
    setCart((prev) => prev.flatMap((l) => (l.item.id !== id ? [l] : l.qty + delta <= 0 ? [] : [{ ...l, qty: l.qty + delta }])))
  }

  function pay(method: 'cash' | 'kaspi') {
    if (cart.length === 0) return
    setPaid({ total, method })
    setCart([])
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'F2') {
        e.preventDefault()
        searchRef.current?.focus()
      } else if (e.key === 'F5' || e.key === 'F6') {
        e.preventDefault()
        pay(e.key === 'F5' ? 'cash' : 'kaspi')
      } else if (e.key === 'Escape') {
        setPaid(null)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  return (
    <div className="v2 flex h-screen flex-col select-none overflow-hidden" data-theme={theme}>
      {/* ── Шапка ─────────────────────────────────────────────────────── */}
      <header className="glass z-10 mx-4 mt-3 flex h-16 shrink-0 items-center gap-4 rounded-[22px] px-3">
        <div className="flex min-w-0 items-center gap-3 pl-1">
          <div className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-gradient-to-br from-[#60a5fa] to-[#2563eb] text-[15px] font-semibold text-white">
            DC
          </div>
          <div className="min-w-0">
            <div className="truncate text-[16px] font-semibold leading-tight">Demo Club</div>
            <div className="muted truncate text-[13px] leading-tight">Касса 1 · {OPERATOR.short_name || OPERATOR.name}</div>
          </div>
        </div>

        <nav className="fill mx-auto flex items-center gap-1 rounded-[14px] p-1">
          {TABS.map((tab, i) => {
            const active = tab.label === 'Продажа'
            const Icon = tab.icon
            return (
              <button
                key={tab.label}
                type="button"
                title={`${tab.label} (Alt+${i + 1})`}
                className={`press flex h-10 items-center gap-2 rounded-[11px] px-3.5 text-[14px] font-medium ${active ? 'pill-active' : 'ink-2'}`}
              >
                <Icon className="h-[18px] w-[18px]" strokeWidth={2} />
                <span className="hidden xl:inline">{tab.label}</span>
              </button>
            )
          })}
        </nav>

        <div className="flex items-center gap-3 pr-1">
          <div className="text-right leading-tight">
            <div className="text-[17px] font-semibold">
              {pad(now.getHours())}:{pad(now.getMinutes())}
            </div>
            <div className="flex items-center justify-end gap-1.5 text-[12px]">
              <span className="h-1.5 w-1.5 rounded-full" style={{ background: 'var(--green)' }} />
              <span className="muted">Дневная смена</span>
            </div>
          </div>
          <button
            type="button"
            onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
            className="press fill grid h-10 w-10 place-items-center rounded-full"
            aria-label={theme === 'light' ? 'Тёмная тема' : 'Светлая тема'}
            title={theme === 'light' ? 'Тёмная тема' : 'Светлая тема'}
          >
            {theme === 'light' ? <Moon className="h-[18px] w-[18px]" /> : <Sun className="h-[18px] w-[18px]" />}
          </button>
          <button type="button" className="press fill grid h-10 w-10 place-items-center rounded-full" aria-label="Профиль" title="Профиль (Alt+8)">
            <UserRound className="h-[18px] w-[18px]" />
          </button>
        </div>
      </header>

      <div className="grid min-h-0 flex-1 grid-cols-[1fr_420px] gap-4 p-4">
        {/* ── Каталог ──────────────────────────────────────────────────── */}
        <main className="flex min-h-0 flex-col gap-3">
          <div className="flex items-center gap-3">
            <label className="surface flex h-12 flex-1 items-center gap-2.5 rounded-[14px] px-4">
              <Search className="muted h-[18px] w-[18px]" />
              <input
                ref={searchRef}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Поиск или штрихкод"
                className="placeholder-muted h-full flex-1 bg-transparent text-[16px] outline-none"
              />
              {query ? (
                <button type="button" onClick={() => setQuery('')} className="fill grid h-6 w-6 place-items-center rounded-full" aria-label="Очистить поиск">
                  <X className="h-3.5 w-3.5" />
                </button>
              ) : (
                <kbd className="fill muted rounded-md px-1.5 text-[12px]">F2</kbd>
              )}
            </label>
            <button type="button" className="press surface flex h-12 items-center gap-2 rounded-[14px] px-4 text-[15px] font-medium">
              <ScanBarcode className="t-blue h-[18px] w-[18px]" /> Свободная цена
            </button>
          </div>

          <div className="flex gap-2 overflow-x-auto pb-1">
            {categories.map((c) => {
              const look = CATEGORY_LOOK[c.id] || CATEGORY_LOOK.all
              const Icon = look.icon
              const active = c.id === category
              return (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => setCategory(c.id)}
                  className={`press flex h-11 shrink-0 items-center gap-2 rounded-full px-4 text-[15px] font-medium ${active ? 'surface' : 'fill'}`}
                  style={active ? { boxShadow: `inset 0 0 0 2px ${look.color}` } : undefined}
                >
                  <Icon className="h-[18px] w-[18px]" style={{ color: look.color }} />
                  {c.name}
                </button>
              )
            })}
          </div>

          <div className="min-h-0 flex-1 overflow-auto pr-1">
            {visible.length === 0 ? (
              <div className="muted flex h-full flex-col items-center justify-center gap-2">
                <Search className="h-8 w-8" />
                <p className="text-[16px]">Ничего не нашлось по «{query}»</p>
              </div>
            ) : (
              <div className="grid grid-cols-[repeat(auto-fill,minmax(176px,1fr))] gap-3">
                {visible.map((item) => {
                  const inCart = qtyOf(item.id)
                  const out = item.display_qty <= 0
                  const look = CATEGORY_LOOK[item.category?.id || 'all'] || CATEGORY_LOOK.all
                  const Icon = look.icon
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => add(item)}
                      disabled={out}
                      className="press lift surface relative flex h-[148px] flex-col rounded-[22px] p-4 text-left"
                      style={inCart ? { boxShadow: `0 0 0 2px var(--blue), var(--shadow)` } : undefined}
                    >
                      <div
                        className="grid h-11 w-11 place-items-center rounded-[13px]"
                        style={{ background: `color-mix(in srgb, ${look.color} 14%, transparent)` }}
                      >
                        <Icon className="h-[22px] w-[22px]" style={{ color: look.color }} />
                      </div>
                      {inCart ? (
                        <span
                          key={popId?.id === item.id ? popId.n : 0}
                          className="badge pop absolute right-3 top-3 grid h-7 min-w-7 place-items-center rounded-full px-2 text-[14px] font-semibold"
                        >
                          {inCart}
                        </span>
                      ) : null}
                      <span className="mt-auto line-clamp-2 text-[15px] font-medium leading-snug">{item.name}</span>
                      <span className="mt-1 flex items-baseline justify-between">
                        <span className="text-[19px] font-semibold">{money(item.sale_price)} ₸</span>
                        <span className={`text-[12px] ${out ? 't-red' : 'muted'}`}>
                          {out ? 'нет' : item.display_qty >= 999 ? item.unit : `${item.display_qty} ${item.unit}`}
                        </span>
                      </span>
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        </main>

        {/* ── Заказ ────────────────────────────────────────────────────── */}
        <aside className="glass relative flex min-h-0 flex-col rounded-[28px] p-4">
          <div className="flex items-center justify-between px-1">
            <h2 className="text-[22px] font-bold tracking-tight">Заказ</h2>
            <button type="button" onClick={() => setCart([])} disabled={cart.length === 0} className="press t-red flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[14px] font-medium">
              <Trash2 className="h-4 w-4" /> Очистить
            </button>
          </div>

          <div className="mt-3 min-h-0 flex-1 space-y-2 overflow-auto">
            {cart.length === 0 && !paid ? (
              <div className="muted flex h-full flex-col items-center justify-center gap-3 text-center">
                <div className="fill grid h-16 w-16 place-items-center rounded-full">
                  <ShoppingBag className="h-7 w-7" />
                </div>
                <p className="max-w-[240px] text-[15px] leading-relaxed">Нажмите на товар или отсканируйте штрихкод</p>
              </div>
            ) : null}
            {cart.map((line) => (
              <div key={line.item.id} className="slide-in surface flex items-center gap-3 rounded-[18px] p-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[15px] font-medium">{line.item.name}</div>
                  <div className="muted text-[13px]">{money(line.item.sale_price)} ₸</div>
                </div>
                <div className="fill flex items-center rounded-full p-1">
                  <button type="button" onClick={() => change(line.item.id, -1)} className="press surface grid h-10 w-10 place-items-center rounded-full" aria-label="Меньше">
                    <Minus className="h-4 w-4" />
                  </button>
                  <span className="w-9 text-center text-[16px] font-semibold">{line.qty}</span>
                  <button type="button" onClick={() => change(line.item.id, 1)} className="press surface grid h-10 w-10 place-items-center rounded-full" aria-label="Больше">
                    <Plus className="h-4 w-4" />
                  </button>
                </div>
                <div className="w-[76px] text-right text-[16px] font-semibold">{money(line.qty * line.item.sale_price)} ₸</div>
              </div>
            ))}
          </div>

          <div className="mt-3 grid grid-cols-3 gap-2">
            {[
              { label: 'Клиент', icon: UserRound },
              { label: 'Скидка', icon: BadgePercent },
              { label: 'Отложить', icon: PauseCircle },
            ].map(({ label, icon: Icon }) => (
              <button key={label} type="button" disabled={cart.length === 0 && label !== 'Клиент'} className="press fill flex h-11 items-center justify-center gap-1.5 rounded-[14px] text-[14px] font-medium">
                <Icon className="h-4 w-4" /> {label}
              </button>
            ))}
          </div>

          <div className="mt-4 flex items-end justify-between px-1">
            <div>
              <div className="muted text-[14px]">Итого</div>
              <div className="muted text-[13px]">
                {cart.length} {plural(cart.length, 'позиция', 'позиции', 'позиций')} · {pieces} шт
              </div>
            </div>
            <div className="text-[44px] font-bold leading-none tracking-tight">{money(total)} ₸</div>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-3">
            <button type="button" onClick={() => pay('cash')} disabled={cart.length === 0} className="press btn-green flex h-[72px] flex-col items-center justify-center rounded-[20px]">
              <span className="text-[17px] font-semibold">Наличные</span>
              <span className="text-[12px] opacity-80">F5</span>
            </button>
            <button type="button" onClick={() => pay('kaspi')} disabled={cart.length === 0} className="press btn-blue flex h-[72px] flex-col items-center justify-center rounded-[20px]">
              <span className="text-[17px] font-semibold">Kaspi</span>
              <span className="text-[12px] opacity-80">F6</span>
            </button>
          </div>
          <button type="button" disabled={cart.length === 0} className="press t-blue mt-2 flex h-10 items-center justify-center gap-1.5 rounded-[14px] text-[14px] font-medium">
            <ArrowRightLeft className="h-4 w-4" /> Смешанная оплата · проверить чек (F4)
          </button>

          {paid ? (
            <div className="sheet-in glass absolute inset-x-3 bottom-3 rounded-[24px] p-5 text-center" style={{ boxShadow: 'var(--shadow-lift)' }}>
              <div className="mx-auto grid h-14 w-14 place-items-center rounded-full" style={{ background: 'var(--green)' }}>
                <Check className="h-7 w-7 text-white" strokeWidth={3} />
              </div>
              <div className="mt-3 text-[20px] font-bold">Оплачено {money(paid.total)} ₸</div>
              <div className="muted text-[14px]">{paid.method === 'cash' ? 'Наличными' : 'Kaspi'}</div>
              <div className="mt-4 grid grid-cols-2 gap-2">
                <button type="button" className="press fill flex h-12 items-center justify-center gap-2 rounded-[14px] text-[15px] font-medium">
                  <Printer className="h-[18px] w-[18px]" /> Печать чека
                </button>
                <button type="button" onClick={() => setPaid(null)} className="press btn-blue flex h-12 items-center justify-center rounded-[14px] text-[15px] font-semibold">
                  Новая продажа
                </button>
              </div>
            </div>
          ) : null}
        </aside>
      </div>
    </div>
  )
}
