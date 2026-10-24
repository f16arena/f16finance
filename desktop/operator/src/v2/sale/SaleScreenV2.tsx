import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowRightLeft,
  BadgePercent,
  Check,
  Clock,
  Cookie,
  CupSoda,
  LayoutGrid,
  Loader2,
  Minus,
  Package,
  PenLine,
  Plus,
  Printer,
  Search,
  ShoppingBag,
  Trash2,
  UtensilsCrossed,
  X,
  type LucideIcon,
} from 'lucide-react'

import { buildReceiptHtmlForPreview, formatShiftLabel, printReceiptFromIframe } from '@/lib/receipt-html'
import type { AppConfig, BootstrapData, OperatorSession, PointInventorySaleItem } from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'
import { useSaleEngine } from './useSaleEngine'

type Props = {
  config: AppConfig
  bootstrap: BootstrapData
  session: OperatorSession
  onLogout: () => void
  onSwitchToShift?: () => void
  onSwitchToReturn?: () => void
  onSwitchToScanner?: () => void
  onSwitchToRequest?: () => void
  onSwitchToHistory?: () => void
  onSwitchToArena?: () => void
  onOpenCabinet?: () => void
}

const PALETTE = ['#0a84ff', '#ff9500', '#ff3b30', '#af52de', '#34c759', '#ff2d55', '#5ac8fa', '#ffcc00']

function lookFor(name: string | null | undefined): { icon: LucideIcon; color: string } {
  const n = String(name || '').toLowerCase()
  if (!n) return { icon: Package, color: '#8e8e93' }
  if (/напит|вода|сок|кофе|чай|drink/.test(n)) return { icon: CupSoda, color: '#0a84ff' }
  if (/снек|чипс|слад|шокол|snack/.test(n)) return { icon: Cookie, color: '#ff9500' }
  if (/еда|кухн|блюд|пицц|рамен|food/.test(n)) return { icon: UtensilsCrossed, color: '#ff3b30' }
  if (/врем|час|услуг|тариф|пакет/.test(n)) return { icon: Clock, color: '#af52de' }
  let h = 0
  for (const ch of n) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return { icon: Package, color: PALETTE[h % PALETTE.length] }
}

function money(value: number) {
  return Math.round(value).toLocaleString('ru-RU')
}

function plural(n: number, one: string, few: string, many: string) {
  const m10 = n % 10
  const m100 = n % 100
  if (m10 === 1 && m100 !== 11) return one
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few
  return many
}

/** Шторка поверх панели заказа — для смешанной оплаты, скидки, свободной цены. */
function Sheet(props: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="sheet-in glass absolute inset-x-3 bottom-3 z-20 rounded-[24px] p-5" style={{ boxShadow: 'var(--shadow-lift)' }}>
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-[18px] font-bold">{props.title}</h3>
        <button type="button" onClick={props.onClose} className="press fill grid h-9 w-9 place-items-center rounded-full" aria-label="Закрыть">
          <X className="h-4 w-4" />
        </button>
      </div>
      {props.children}
    </div>
  )
}

function MoneyField(props: { label: string; value: string; onChange: (v: string) => void; autoFocus?: boolean; suffix?: string }) {
  return (
    <label className="block">
      <span className="muted text-[13px]">{props.label}</span>
      <div className="surface mt-1 flex h-14 items-center rounded-[14px] px-4">
        <input
          value={props.value}
          onChange={(e) => props.onChange(e.target.value.replace(/[^\d]/g, ''))}
          inputMode="numeric"
          autoFocus={props.autoFocus}
          className="h-full flex-1 bg-transparent text-[22px] font-semibold outline-none"
          placeholder="0"
        />
        <span className="muted text-[18px]">{props.suffix || '₸'}</span>
      </div>
    </label>
  )
}

export default function SaleScreenV2(props: Props) {
  const engine = useSaleEngine({ config: props.config, bootstrap: props.bootstrap, session: props.session })
  const [category, setCategory] = useState('all')
  const [query, setQuery] = useState('')
  const [sheet, setSheet] = useState<null | 'mixed' | 'discount' | 'universal'>(null)
  const [mixedCash, setMixedCash] = useState('')
  const [discountDraft, setDiscountDraft] = useState('')
  const [uniName, setUniName] = useState('')
  const [uniPrice, setUniPrice] = useState('')
  const [popKey, setPopKey] = useState<{ id: string; n: number } | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const receiptRef = useRef<HTMLIFrameElement>(null)

  const nav: V2Nav = {
    shift: props.onSwitchToShift,
    return: props.onSwitchToReturn,
    history: props.onSwitchToHistory,
    scanner: props.onSwitchToScanner,
    request: props.onSwitchToRequest,
    arena: props.onSwitchToArena,
    cabinet: props.onOpenCabinet,
  }

  const categories = useMemo(() => {
    const names = new Set<string>()
    for (const it of engine.items) names.add(it.category?.name || 'Без категории')
    return ['all', ...Array.from(names).sort((a, b) => a.localeCompare(b, 'ru'))]
  }, [engine.items])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return engine.items
      .filter((it) => category === 'all' || (it.category?.name || 'Без категории') === category)
      .filter((it) => !q || it.name.toLowerCase().includes(q) || (it.barcode || '').includes(q))
      .sort((a, b) => {
        const av = Number(a.display_qty || 0) > 0 ? 1 : 0
        const bv = Number(b.display_qty || 0) > 0 ? 1 : 0
        return av !== bv ? bv - av : a.name.localeCompare(b.name, 'ru')
      })
  }, [engine.items, category, query])

  const qtyOf = (id: string) => engine.cart.find((l) => l.item_id === id)?.quantity || 0

  function add(item: PointInventorySaleItem) {
    if (engine.addItem(item)) setPopKey((p) => ({ id: item.id, n: (p?.n || 0) + 1 }))
  }

  function submitSearch() {
    const q = query.trim()
    if (!q) return
    if (engine.scan(q)) {
      setQuery('')
      return
    }
    const matches = engine.items.filter((it) => Number(it.display_qty || 0) > 0 && (it.name.toLowerCase().includes(q.toLowerCase()) || (it.barcode || '').includes(q)))
    if (matches.length === 1) {
      add(matches[0])
      setQuery('')
    }
  }

  async function payNow(method: 'cash' | 'kaspi') {
    setSheet(null)
    await engine.pay(method)
  }

  async function payMixed() {
    const cash = Number(mixedCash || 0)
    if (await engine.pay('mixed', cash)) {
      setSheet(null)
      setMixedCash('')
    }
  }

  // Сканер штрихкода печатает как клавиатура и жмёт Enter — ловим ввод,
  // даже если фокус не в поиске (кроме полей шторок).
  useEffect(() => {
    let buffer = ''
    let last = 0
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement
      const inField = ['INPUT', 'TEXTAREA'].includes(target?.tagName || '')
      if (e.key === 'F2') {
        e.preventDefault()
        searchRef.current?.focus()
        return
      }
      if ((e.key === 'F5' || e.key === 'F6') && engine.cart.length > 0 && !sheet) {
        e.preventDefault()
        void payNow(e.key === 'F5' ? 'cash' : 'kaspi')
        return
      }
      if (e.key === 'F4' && engine.cart.length > 0) {
        e.preventDefault()
        setSheet('mixed')
        return
      }
      if (e.key === 'Escape') {
        if (sheet) setSheet(null)
        else if (engine.lastReceipt) engine.closeReceipt()
        return
      }
      if (inField) return
      const t = Date.now()
      if (t - last > 80) buffer = ''
      last = t
      if (e.key === 'Enter' && buffer.length >= 4) {
        if (!engine.scan(buffer)) setQuery(buffer)
        buffer = ''
      } else if (e.key.length === 1) {
        buffer += e.key
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const receipt = engine.lastReceipt
  const discountOn = engine.discountPercent > 0

  return (
    <V2Shell
      active="sale"
      nav={nav}
      companyName={props.session.company?.name || 'Точка'}
      operatorName={`${engine.operatorName}`}
      shiftLabel={formatShiftLabel(engine.runtimeShift.shift)}
      onLogout={props.onLogout}
    >
      <div className="grid h-full min-h-0 grid-cols-[1fr_420px] gap-4 p-4">
        {/* ── Каталог ─────────────────────────────────────────────────── */}
        <main className="flex min-h-0 flex-col gap-3">
          <div className="flex items-center gap-3">
            <label className="surface flex h-12 flex-1 items-center gap-2.5 rounded-[14px] px-4">
              <Search className="muted h-[18px] w-[18px]" />
              <input
                ref={searchRef}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && submitSearch()}
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
            <button type="button" onClick={() => setSheet('universal')} className="press surface flex h-12 items-center gap-2 rounded-[14px] px-4 text-[15px] font-medium">
              <PenLine className="t-blue h-[18px] w-[18px]" /> Свободная цена
            </button>
          </div>

          <div className="flex gap-2 overflow-x-auto pb-1">
            {categories.map((c) => {
              const look = c === 'all' ? { icon: LayoutGrid, color: '#8e8e93' } : lookFor(c)
              const Icon = look.icon
              const active = c === category
              return (
                <button
                  key={c}
                  type="button"
                  onClick={() => setCategory(c)}
                  className={`press flex h-11 shrink-0 items-center gap-2 rounded-full px-4 text-[15px] font-medium ${active ? 'surface' : 'fill'}`}
                  style={active ? { boxShadow: `inset 0 0 0 2px ${look.color}` } : undefined}
                >
                  <Icon className="h-[18px] w-[18px]" style={{ color: look.color }} />
                  {c === 'all' ? 'Все' : c}
                </button>
              )
            })}
          </div>

          <div className="min-h-0 flex-1 overflow-auto pr-1">
            {engine.loading && engine.items.length === 0 ? (
              <div className="muted flex h-full items-center justify-center gap-2">
                <Loader2 className="h-6 w-6 animate-spin" /> Загружаем витрину…
              </div>
            ) : engine.error && engine.items.length === 0 ? (
              <div className="flex h-full flex-col items-center justify-center gap-3 text-center">
                <p className="t-red text-[16px]">{engine.error}</p>
                <button type="button" onClick={() => void engine.reload()} className="press btn-blue h-11 rounded-[14px] px-5 text-[15px] font-semibold">
                  Повторить
                </button>
              </div>
            ) : visible.length === 0 ? (
              <div className="muted flex h-full flex-col items-center justify-center gap-2">
                <Search className="h-8 w-8" />
                <p className="text-[16px]">{query ? `Ничего не нашлось по «${query}»` : 'В этой категории пусто'}</p>
              </div>
            ) : (
              <div className="grid grid-cols-[repeat(auto-fill,minmax(176px,1fr))] gap-3">
                {visible.map((item) => {
                  const inCart = qtyOf(item.id)
                  const stock = Number(item.display_qty || 0)
                  const out = stock <= 0
                  const look = lookFor(item.category?.name)
                  const Icon = look.icon
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => add(item)}
                      disabled={out}
                      className="press lift surface relative flex h-[148px] flex-col overflow-hidden rounded-[22px] p-4 text-left"
                      style={inCart ? { boxShadow: '0 0 0 2px var(--blue), var(--shadow)' } : undefined}
                    >
                      {item.image_url ? (
                        <img src={item.image_url} alt="" className="h-11 w-11 rounded-[13px] object-cover" loading="lazy" />
                      ) : (
                        <div className="grid h-11 w-11 place-items-center rounded-[13px]" style={{ background: `color-mix(in srgb, ${look.color} 14%, transparent)` }}>
                          <Icon className="h-[22px] w-[22px]" style={{ color: look.color }} />
                        </div>
                      )}
                      {inCart ? (
                        <span
                          key={popKey?.id === item.id ? popKey.n : 0}
                          className="badge pop absolute right-3 top-3 grid h-7 min-w-7 place-items-center rounded-full px-2 text-[14px] font-semibold"
                        >
                          {inCart}
                        </span>
                      ) : null}
                      <span className="mt-auto line-clamp-2 text-[15px] font-medium leading-snug">{item.name}</span>
                      <span className="mt-1 flex items-baseline justify-between">
                        <span className="text-[19px] font-semibold">{money(item.sale_price)} ₸</span>
                        <span className={`text-[12px] ${out ? 't-red' : stock <= 3 ? 't-red' : 'muted'}`}>
                          {out ? 'нет' : stock >= 999 ? item.unit : `${stock} ${item.unit || 'шт'}`}
                        </span>
                      </span>
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        </main>

        {/* ── Заказ ───────────────────────────────────────────────────── */}
        <aside className="glass relative flex min-h-0 flex-col rounded-[28px] p-4">
          <div className="flex items-center justify-between px-1">
            <h2 className="text-[22px] font-bold tracking-tight">Заказ</h2>
            <button type="button" onClick={engine.clear} disabled={engine.cart.length === 0} className="press t-red flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[14px] font-medium">
              <Trash2 className="h-4 w-4" /> Очистить
            </button>
          </div>

          <div className="mt-3 min-h-0 flex-1 space-y-2 overflow-auto">
            {engine.cart.length === 0 ? (
              <div className="muted flex h-full flex-col items-center justify-center gap-3 text-center">
                <div className="fill grid h-16 w-16 place-items-center rounded-full">
                  <ShoppingBag className="h-7 w-7" />
                </div>
                <p className="max-w-[240px] text-[15px] leading-relaxed">Нажмите на товар или отсканируйте штрихкод</p>
              </div>
            ) : null}
            {engine.cart.map((line) => (
              <div key={line.id} className="slide-in surface flex items-center gap-3 rounded-[18px] p-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[15px] font-medium">{line.name}</div>
                  <div className="muted text-[13px]">{money(line.unit_price)} ₸</div>
                </div>
                <div className="fill flex items-center rounded-full p-1">
                  <button type="button" onClick={() => engine.changeQty(line.id, line.quantity - 1)} className="press surface grid h-10 w-10 place-items-center rounded-full" aria-label="Меньше">
                    <Minus className="h-4 w-4" />
                  </button>
                  <span className="w-9 text-center text-[16px] font-semibold">{line.quantity}</span>
                  <button type="button" onClick={() => engine.changeQty(line.id, line.quantity + 1)} className="press surface grid h-10 w-10 place-items-center rounded-full" aria-label="Больше">
                    <Plus className="h-4 w-4" />
                  </button>
                </div>
                <div className="w-[76px] text-right text-[16px] font-semibold">{money(line.quantity * line.unit_price)} ₸</div>
              </div>
            ))}
          </div>

          <div className="mt-3 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={() => {
                setDiscountDraft(engine.discountPercent ? String(engine.discountPercent) : '')
                setSheet('discount')
              }}
              disabled={engine.cart.length === 0}
              className="press fill flex h-11 items-center justify-center gap-1.5 rounded-[14px] text-[14px] font-medium"
              style={discountOn ? { color: 'var(--blue)' } : undefined}
            >
              <BadgePercent className="h-4 w-4" /> {discountOn ? `Скидка ${engine.discountPercent}%` : 'Скидка'}
            </button>
            <button type="button" onClick={() => setSheet('mixed')} disabled={engine.cart.length === 0} className="press fill flex h-11 items-center justify-center gap-1.5 rounded-[14px] text-[14px] font-medium">
              <ArrowRightLeft className="h-4 w-4" /> Смешанная · F4
            </button>
          </div>

          <div className="mt-4 flex items-end justify-between px-1">
            <div>
              <div className="muted text-[14px]">Итого</div>
              <div className="muted text-[13px]">
                {engine.cart.length} {plural(engine.cart.length, 'позиция', 'позиции', 'позиций')} · {engine.pieces} шт
                {discountOn ? ` · −${money(engine.discountAmount)} ₸` : ''}
              </div>
            </div>
            <div className="text-[44px] font-bold leading-none tracking-tight">{money(engine.total)} ₸</div>
          </div>

          <div className="mt-4 grid grid-cols-2 gap-3">
            <button type="button" onClick={() => void payNow('cash')} disabled={engine.cart.length === 0 || engine.saving} className="press btn-green flex h-[72px] flex-col items-center justify-center rounded-[20px]">
              <span className="text-[17px] font-semibold">Наличные</span>
              <span className="text-[12px] opacity-80">F5</span>
            </button>
            <button type="button" onClick={() => void payNow('kaspi')} disabled={engine.cart.length === 0 || engine.saving} className="press btn-blue flex h-[72px] flex-col items-center justify-center rounded-[20px]">
              <span className="text-[17px] font-semibold">{engine.cashLabels.providerName}</span>
              <span className="text-[12px] opacity-80">F6</span>
            </button>
          </div>

          {sheet === 'mixed' ? (
            <Sheet title={`Смешанная оплата · ${money(engine.total)} ₸`} onClose={() => setSheet(null)}>
              <MoneyField label="Наличными" value={mixedCash} onChange={setMixedCash} autoFocus />
              <div className="muted mt-3 flex justify-between text-[15px]">
                <span>{engine.cashLabels.providerName}</span>
                <span className="ink font-semibold">{money(Math.max(0, engine.total - Number(mixedCash || 0)))} ₸</span>
              </div>
              <button type="button" onClick={() => void payMixed()} className="press btn-blue mt-4 h-14 w-full rounded-[16px] text-[17px] font-semibold">
                Оплатить
              </button>
            </Sheet>
          ) : null}

          {sheet === 'discount' ? (
            <Sheet title="Скидка на чек" onClose={() => setSheet(null)}>
              <div className="mb-3 grid grid-cols-4 gap-2">
                {[5, 10, 15, 20].map((p) => (
                  <button key={p} type="button" onClick={() => setDiscountDraft(String(p))} className={`press h-12 rounded-[14px] text-[16px] font-semibold ${Number(discountDraft) === p ? 'btn-blue' : 'fill'}`}>
                    {p}%
                  </button>
                ))}
              </div>
              <MoneyField label="Свой процент" value={discountDraft} onChange={(v) => setDiscountDraft(String(Math.min(99, Number(v || 0)) || ''))} suffix="%" />
              <div className="mt-4 grid grid-cols-2 gap-2">
                <button
                  type="button"
                  onClick={() => {
                    engine.setDiscountPercent(0)
                    setSheet(null)
                  }}
                  className="press fill h-12 rounded-[14px] text-[15px] font-medium"
                >
                  Без скидки
                </button>
                <button
                  type="button"
                  onClick={() => {
                    engine.setDiscountPercent(Number(discountDraft || 0))
                    setSheet(null)
                  }}
                  className="press btn-blue h-12 rounded-[14px] text-[15px] font-semibold"
                >
                  Применить
                </button>
              </div>
            </Sheet>
          ) : null}

          {sheet === 'universal' ? (
            <Sheet title="Товар по свободной цене" onClose={() => setSheet(null)}>
              <label className="block">
                <span className="muted text-[13px]">Название</span>
                <input
                  value={uniName}
                  onChange={(e) => setUniName(e.target.value)}
                  autoFocus
                  className="surface mt-1 h-12 w-full rounded-[14px] px-4 text-[17px] outline-none"
                  placeholder="Например, ремонт джойстика"
                />
              </label>
              <div className="mt-3">
                <MoneyField label="Цена" value={uniPrice} onChange={setUniPrice} />
              </div>
              <button
                type="button"
                disabled={!uniName.trim() || !(Number(uniPrice) > 0)}
                onClick={() => {
                  engine.addUniversal(uniName, Number(uniPrice))
                  setUniName('')
                  setUniPrice('')
                  setSheet(null)
                }}
                className="press btn-blue mt-4 h-14 w-full rounded-[16px] text-[17px] font-semibold"
              >
                Добавить в заказ
              </button>
            </Sheet>
          ) : null}

          {receipt && !sheet ? (
            <div className="sheet-in glass absolute inset-3 z-20 flex flex-col rounded-[24px] p-5" style={{ boxShadow: 'var(--shadow-lift)' }}>
              <div className="flex items-center gap-3">
                <div className="grid h-12 w-12 shrink-0 place-items-center rounded-full" style={{ background: 'var(--green)' }}>
                  <Check className="h-6 w-6 text-white" strokeWidth={3} />
                </div>
                <div className="min-w-0">
                  <div className="text-[20px] font-bold leading-tight">Оплачено {money(receipt.totalAmount)} ₸</div>
                  <div className="muted text-[14px]">
                    {receipt.paymentMethod === 'cash' ? 'Наличными' : receipt.paymentMethod === 'kaspi' ? engine.cashLabels.providerName : 'Смешанная оплата'}
                  </div>
                </div>
              </div>
              <div className="surface-2 mt-4 min-h-0 flex-1 overflow-hidden rounded-[16px]">
                <iframe ref={receiptRef} title="Чек" srcDoc={buildReceiptHtmlForPreview(receipt)} className="h-full w-full bg-white" />
              </div>
              <div className="mt-4 grid grid-cols-2 gap-2">
                <button type="button" onClick={() => printReceiptFromIframe(receiptRef.current)} className="press fill flex h-12 items-center justify-center gap-2 rounded-[14px] text-[15px] font-medium">
                  <Printer className="h-[18px] w-[18px]" /> Печать чека
                </button>
                <button type="button" onClick={engine.closeReceipt} className="press btn-blue flex h-12 items-center justify-center rounded-[14px] text-[15px] font-semibold">
                  Новая продажа
                </button>
              </div>
            </div>
          ) : null}
        </aside>
      </div>
    </V2Shell>
  )
}
