import { useEffect, useMemo, useRef, useState } from 'react'
import { Minus, Moon, Plus, ScanBarcode, Search, Sun, X } from 'lucide-react'

import { ITEMS, OPERATOR } from '@/demo/fixtures'
import type { PointInventorySaleItem } from '@/types'

/**
 * Концепция экрана продажи для кассы v2 — только внешний вид и поведение
 * рук оператора, без сервера. Данные — подставные товары демо-стенда.
 */

type Line = { item: PointInventorySaleItem; qty: number }

const TABS = ['Смена', 'Продажа', 'Возврат', 'История', 'Долги', 'Заявка', 'Зал', 'Профиль']

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
  const [category, setCategory] = useState<string>('all')
  const [query, setQuery] = useState('')
  const [cart, setCart] = useState<Line[]>([])
  const [hitId, setHitId] = useState<string | null>(null)
  const [done, setDone] = useState<{ total: number; method: string } | null>(null)
  const [now, setNow] = useState(() => new Date())
  const searchRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15_000)
    return () => clearInterval(t)
  }, [])

  const categories = useMemo(() => {
    const map = new Map<string, { id: string; name: string; count: number }>()
    for (const item of ITEMS) {
      const c = item.category
      if (!c) continue
      const entry = map.get(c.id) || { id: c.id, name: c.name, count: 0 }
      entry.count += 1
      map.set(c.id, entry)
    }
    return [{ id: 'all', name: 'Все товары', count: ITEMS.length }, ...map.values()]
  }, [])

  const visible = ITEMS.filter((item) => {
    if (category !== 'all' && item.category?.id !== category) return false
    if (query.trim() && !item.name.toLowerCase().includes(query.trim().toLowerCase())) return false
    return true
  })

  const total = cart.reduce((sum, line) => sum + line.qty * line.item.sale_price, 0)
  const pieces = cart.reduce((sum, line) => sum + line.qty, 0)
  const qtyOf = (id: string) => cart.find((l) => l.item.id === id)?.qty || 0

  function add(item: PointInventorySaleItem) {
    if (item.display_qty <= 0) return
    setDone(null)
    setCart((prev) => {
      const found = prev.find((l) => l.item.id === item.id)
      if (found) return prev.map((l) => (l.item.id === item.id ? { ...l, qty: l.qty + 1 } : l))
      return [...prev, { item, qty: 1 }]
    })
    setHitId(null)
    requestAnimationFrame(() => setHitId(item.id))
  }

  function change(id: string, delta: number) {
    setCart((prev) => prev.flatMap((l) => (l.item.id !== id ? [l] : l.qty + delta <= 0 ? [] : [{ ...l, qty: l.qty + delta }])))
  }

  function pay(method: 'Наличными' | 'Kaspi') {
    if (cart.length === 0) return
    setDone({ total, method })
    setCart([])
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'F2') {
        e.preventDefault()
        searchRef.current?.focus()
      }
      if (e.key === 'F5' || e.key === 'F6') {
        e.preventDefault()
        pay(e.key === 'F5' ? 'Наличными' : 'Kaspi')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const rule = 'rule'

  return (
    <div className="v2 flex h-screen flex-col select-none" data-theme={theme}>
      {/* ── Шапка: точка, вкладки с номерами, время ───────────────────── */}
      <header className={`grid h-16 shrink-0 grid-cols-[260px_1fr_auto] items-stretch border-b ${rule}`}>
        <div className={`flex flex-col justify-center border-r px-5 ${rule}`}>
          <span className="text-[17px] font-bold leading-tight">Demo Club</span>
          <span className="v2-folio">Касса 1 · {OPERATOR.short_name || OPERATOR.name}</span>
        </div>
        <nav className="flex items-stretch overflow-x-auto">
          {TABS.map((tab, i) => {
            const active = tab === 'Продажа'
            return (
              <button
                key={tab}
                type="button"
                className={`relative flex min-w-[104px] flex-col justify-center border-r px-4 text-left ${rule} ${active ? '' : 'muted hover-ink'}`}
                title={`${tab} (Alt+${i + 1})`}
              >
                <span className="v2-folio">{pad(i + 1)}</span>
                <span className={`text-[16px] leading-tight ${active ? 'font-bold' : 'font-medium'}`}>{tab}</span>
                {active ? <span className="absolute inset-x-0 bottom-0 h-[3px] bg-accent-bar" /> : null}
              </button>
            )
          })}
        </nav>
        <div className={`flex items-center gap-5 border-l px-5 ${rule}`}>
          <div className="text-right">
            <div className="text-[22px] font-bold leading-none">
              {pad(now.getHours())}:{pad(now.getMinutes())}
            </div>
            <div className="v2-folio mt-1 ok">Онлайн · дневная смена</div>
          </div>
          <button
            type="button"
            onClick={() => setTheme(theme === 'light' ? 'dark' : 'light')}
            className={`grid h-11 w-11 place-items-center border ${rule}`}
            aria-label={theme === 'light' ? 'Тёмная тема' : 'Светлая тема'}
            title={theme === 'light' ? 'Тёмная тема' : 'Светлая тема'}
          >
            {theme === 'light' ? <Moon className="h-5 w-5" /> : <Sun className="h-5 w-5" />}
          </button>
        </div>
      </header>

      <div className="grid min-h-0 flex-1 grid-cols-[260px_1fr_440px]">
        {/* ── Категории ────────────────────────────────────────────────── */}
        <aside className={`flex min-h-0 flex-col border-r ${rule}`}>
          {categories.map((c, i) => {
            const active = c.id === category
            return (
              <button
                key={c.id}
                type="button"
                onClick={() => setCategory(c.id)}
                className={`relative flex h-16 items-center justify-between border-b px-5 text-left ${rule} ${active ? 'bg-2' : ''}`}
              >
                {active ? <span className="absolute inset-y-0 left-0 w-[3px] bg-accent-bar" /> : null}
                <span className="flex items-baseline gap-3">
                  <span className="v2-folio w-5">{pad(i)}</span>
                  <span className={`text-[17px] ${active ? 'font-bold' : 'font-medium'}`}>{c.name}</span>
                </span>
                <span className="v2-folio">{c.count}</span>
              </button>
            )
          })}
          <div className={`mt-auto border-t px-5 py-4 text-[13px] muted ${rule}`}>
            Сканер штрихкода работает на любом экране продажи.
          </div>
        </aside>

        {/* ── Товары ───────────────────────────────────────────────────── */}
        <main className="flex min-h-0 flex-col">
          <label className={`flex h-16 shrink-0 items-center gap-3 border-b px-5 ${rule}`}>
            <Search className="h-5 w-5 muted" />
            <input
              ref={searchRef}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Поиск по названию или штрихкоду"
              className="h-full flex-1 bg-transparent text-[18px] outline-none placeholder-muted"
            />
            {query ? (
              <button type="button" onClick={() => setQuery('')} aria-label="Очистить поиск" className="grid h-11 w-11 place-items-center">
                <X className="h-5 w-5" />
              </button>
            ) : (
              <span className="v2-folio">F2</span>
            )}
          </label>

          <div className="min-h-0 flex-1 overflow-auto">
            {visible.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center gap-2 muted">
                <ScanBarcode className="h-8 w-8" />
                <p className="text-[16px]">Ничего не нашлось по «{query}»</p>
              </div>
            ) : (
              <div className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))]">
                {visible.map((item) => {
                  const inCart = qtyOf(item.id)
                  const out = item.display_qty <= 0
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => add(item)}
                      disabled={out}
                      className={`relative flex h-36 flex-col justify-between border-b border-r p-4 text-left transition-colors ${rule} ${
                        inCart ? 'bg-2' : 'hover-bg-2'
                      } ${hitId === item.id ? 'v2-hit' : ''}`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <span className="line-clamp-2 text-[16px] font-medium leading-snug">{item.name}</span>
                        {inCart ? (
                          <span className="v2-display -mt-1 text-[34px] accent">{inCart}</span>
                        ) : null}
                      </div>
                      <div className="flex items-end justify-between">
                        <span className="v2-display text-[30px]">
                          {money(item.sale_price)}
                          <span className="ml-1 text-[16px] font-medium tracking-normal">₸</span>
                        </span>
                        <span className={`v2-folio ${out ? 'danger' : ''}`}>
                          {out ? 'нет в наличии' : item.display_qty >= 999 ? item.unit : `${item.display_qty} ${item.unit}`}
                        </span>
                      </div>
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        </main>

        {/* ── Чек: сумма, строки, оплата ───────────────────────────────── */}
        <section className={`flex min-h-0 flex-col border-l ${rule}`}>
          <div className={`border-b px-6 pb-5 pt-4 ${rule}`}>
            <div className="v2-folio">К оплате</div>
            <div className="v2-display mt-2 text-[88px]">
              {money(total)}
              <span className="ml-2 text-[32px] font-medium tracking-normal">₸</span>
            </div>
            <div className="v2-folio mt-2">
              {cart.length} {plural(cart.length, 'позиция', 'позиции', 'позиций')} · {pieces} шт
            </div>
          </div>

          <div className="min-h-0 flex-1 overflow-auto">
            {done ? (
              <div className="border-b rule px-6 py-5">
                <div className="v2-folio ok">Продажа проведена</div>
                <div className="mt-1 text-[22px] font-bold">
                  {money(done.total)} ₸ · {done.method}
                </div>
                <button type="button" className="mt-2 text-[15px] font-medium underline underline-offset-4">
                  Печатать чек
                </button>
              </div>
            ) : null}
            {cart.length === 0 && !done ? (
              <p className="px-6 py-6 text-[16px] leading-relaxed muted">
                Нажмите на товар или отсканируйте штрихкод.
              </p>
            ) : null}
            {cart.map((line, i) => (
              <div key={line.item.id} className={`grid grid-cols-[32px_1fr_auto] items-center gap-3 border-b px-6 py-3 ${rule}`}>
                <span className="v2-folio">{pad(i + 1)}</span>
                <div className="min-w-0">
                  <div className="truncate text-[16px] font-medium">{line.item.name}</div>
                  <div className="v2-folio">
                    {money(line.item.sale_price)} ₸ × {line.qty}
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <div className={`flex items-center border ${rule}`}>
                    <button type="button" onClick={() => change(line.item.id, -1)} className="grid h-12 w-12 place-items-center" aria-label="Меньше">
                      <Minus className="h-5 w-5" />
                    </button>
                    <span className="w-8 text-center text-[18px] font-bold">{line.qty}</span>
                    <button type="button" onClick={() => change(line.item.id, 1)} className="grid h-12 w-12 place-items-center" aria-label="Больше">
                      <Plus className="h-5 w-5" />
                    </button>
                  </div>
                  <span className="w-20 text-right text-[18px] font-bold">{money(line.qty * line.item.sale_price)}</span>
                </div>
              </div>
            ))}
          </div>

          <div className={`grid grid-cols-4 border-t text-[15px] font-medium ${rule}`}>
            {['Клиент', 'Скидка', 'Отложить', 'Очистить'].map((label, i) => (
              <button
                key={label}
                type="button"
                onClick={label === 'Очистить' ? () => setCart([]) : undefined}
                disabled={cart.length === 0 && label !== 'Клиент'}
                className={`h-14 ${i < 3 ? `border-r ${rule}` : ''} hover-bg-2`}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="grid grid-cols-2">
            <button
              type="button"
              onClick={() => pay('Наличными')}
              disabled={cart.length === 0}
              className="flex h-24 flex-col items-start justify-between bg-accent px-5 py-4 text-left"
            >
              <span className="flex w-full justify-between text-[15px] font-medium">
                Наличные <span className="opacity-70">F5</span>
              </span>
              <span className="v2-display text-[30px]">{money(total)} ₸</span>
            </button>
            <button
              type="button"
              onClick={() => pay('Kaspi')}
              disabled={cart.length === 0}
              className="flex h-24 flex-col items-start justify-between bg-ink px-5 py-4 text-left"
            >
              <span className="flex w-full justify-between text-[15px] font-medium">
                Kaspi <span className="opacity-70">F6</span>
              </span>
              <span className="v2-display text-[30px]">{money(total)} ₸</span>
            </button>
          </div>
          <button type="button" disabled={cart.length === 0} className={`h-12 border-t text-[15px] font-medium muted ${rule}`}>
            Смешанная оплата · проверить чек (F4)
          </button>
        </section>
      </div>
    </div>
  )
}
