/* DEMO: вымышленные данные. Ничего отсюда не уходит на реальный сервер. */
import type {
  BootstrapData, OperatorInfo, OperatorSession, PointInventorySaleItem,
  ArenaZone, ArenaStation, ArenaTariff, ArenaSession,
} from '@/types'

export const COMPANY = { id: 'demo-company-1', name: 'Demo Club', code: 'DEMO' }

export const OPERATOR: OperatorInfo = {
  auth_id: 'demo-auth-1',
  operator_id: 'demo-op-1',
  username: 'demo',
  name: 'Айдар Demo',
  short_name: 'Айдар',
  full_name: 'Айдар Серикович Demo',
  telegram_chat_id: null,
  role_in_company: 'operator',
  is_primary: true,
}

export const BOOTSTRAP: BootstrapData = {
  operatorName: OPERATOR.name,
  device: {
    id: 'demo-device-1',
    name: 'Demo Club — Касса 1',
    point_mode: 'universal',
    feature_flags: {
      shift_report: true,
      income_report: true,
      debt_report: true,
      kaspi_daily_split: false,
      start_cash_prompt: true,
      arena_enabled: true,
      arena_shift_auto_totals: false,
      arena_defer_income_to_shift: false,
    },
  },
  company: COMPANY,
  companies: [COMPANY],
  operators: [
    { id: 'demo-op-1', name: 'Айдар Demo', short_name: 'Айдар', full_name: 'Айдар Серикович Demo', telegram_chat_id: null, is_active: true, role_in_company: 'operator', is_primary: true },
    { id: 'demo-op-2', name: 'Мадина Demo', short_name: 'Мадина', full_name: 'Мадина Ермековна Demo', telegram_chat_id: null, is_active: true, role_in_company: 'operator', is_primary: false },
    { id: 'demo-op-3', name: 'Данияр Demo', short_name: 'Данияр', full_name: null, telegram_chat_id: null, is_active: true, role_in_company: 'operator', is_primary: false },
  ],
}

export const SESSION: OperatorSession = {
  type: 'operator',
  operator: OPERATOR,
  company: COMPANY,
  bootstrap: BOOTSTRAP,
}

const CATS = {
  drinks: { id: 'cat-drinks', name: 'Напитки' },
  snacks: { id: 'cat-snacks', name: 'Снеки' },
  food: { id: 'cat-food', name: 'Еда' },
  time: { id: 'cat-time', name: 'Время и услуги' },
}

function p(n: number, name: string, price: number, cat: keyof typeof CATS, qty: number, unit = 'шт'): PointInventorySaleItem {
  return {
    id: `demo-item-${n}`,
    name,
    barcode: `4870000${String(1000 + n)}`,
    unit,
    sale_price: price,
    display_qty: qty,
    image_url: null,
    category: CATS[cat],
  }
}

export const ITEMS: PointInventorySaleItem[] = [
  p(1, 'Coca-Cola 0.5', 450, 'drinks', 48),
  p(2, 'Fanta 0.5', 450, 'drinks', 24),
  p(3, 'Sprite 0.5', 450, 'drinks', 30),
  p(4, 'Red Bull 0.25', 1100, 'drinks', 18),
  p(5, 'Adrenaline Rush 0.5', 900, 'drinks', 20),
  p(6, 'Вода «Тассай» 0.5', 250, 'drinks', 60),
  p(7, 'Чай чёрный', 300, 'drinks', 40),
  p(8, 'Snickers', 400, 'snacks', 35),
  p(9, 'Twix', 400, 'snacks', 22),
  p(10, "Lay's Сметана-зелень", 650, 'snacks', 16),
  p(11, 'Pringles Original', 1500, 'snacks', 9),
  p(12, 'Орешки арахис', 350, 'snacks', 25),
  p(13, 'Жвачка Orbit', 200, 'snacks', 50),
  p(14, 'Доширак говядина', 600, 'food', 30),
  p(15, 'Хот-дог', 900, 'food', 12),
  p(16, 'Сэндвич с курицей', 1400, 'food', 7),
  p(17, 'Пицца кусок', 1000, 'food', 10),
  p(18, 'Час PC', 1500, 'time', 999, 'час'),
  p(19, 'Час VIP', 2500, 'time', 999, 'час'),
  p(20, 'Печать А4', 100, 'time', 500, 'лист'),
  p(21, 'Энергетик Flash', 500, 'drinks', 0),
]

export const iso = (minAgo: number) => new Date(Date.now() - minAgo * 60_000).toISOString()
export const ymd = (d = new Date()) => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}

export type HistorySale = {
  id: string; sold_at: string; sale_date: string; shift: string; payment_method: string
  cash_amount: number; kaspi_amount: number; card_amount: number; online_amount: number
  total_amount: number; discount_amount: number; comment: string | null; operator_name: string
  items: Array<{ name: string; unit: string; quantity: number; unit_price: number; total_price: number }>
}

export function makeSalesHistory(): HistorySale[] {
  const mk = (id: string, minAgo: number, lines: Array<[number, number]>, pay: 'cash' | 'kaspi' | 'mixed', op = 'Айдар Demo'): HistorySale => {
    const items = lines.map(([idx, q]) => {
      const it = ITEMS[idx]
      return { name: it.name, unit: it.unit, quantity: q, unit_price: it.sale_price, total_price: it.sale_price * q }
    })
    const total = items.reduce((s, i) => s + i.total_price, 0)
    return {
      id, sold_at: iso(minAgo), sale_date: ymd(), shift: 'day', payment_method: pay,
      cash_amount: pay === 'cash' ? total : pay === 'mixed' ? Math.round(total / 2) : 0,
      kaspi_amount: pay === 'kaspi' ? total : pay === 'mixed' ? total - Math.round(total / 2) : 0,
      card_amount: 0, online_amount: 0, total_amount: total, discount_amount: 0, comment: null,
      operator_name: op, items,
    }
  }
  return [
    mk('demo-sale-1', 12, [[0, 2], [7, 1]], 'cash'),
    mk('demo-sale-2', 35, [[3, 1], [10, 1]], 'kaspi'),
    mk('demo-sale-3', 71, [[16, 2], [0, 2]], 'mixed', 'Мадина Demo'),
    mk('demo-sale-4', 120, [[12, 1], [5, 2]], 'cash'),
    mk('demo-sale-5', 190, [[13, 1], [6, 1], [8, 2]], 'kaspi', 'Мадина Demo'),
  ]
}

export const ZONES: ArenaZone[] = [
  { id: 'zone-pc', point_project_id: 'demo-project', name: 'PC Зал', is_active: true, extension_hourly_price: 1500, grid_x: 0, grid_y: 0, grid_w: 6, grid_h: 4, color: '#3b82f6' },
  { id: 'zone-vip', point_project_id: 'demo-project', name: 'VIP', is_active: true, extension_hourly_price: 2500, grid_x: 7, grid_y: 0, grid_w: 4, grid_h: 4, color: '#a855f7' },
]

export const STATIONS: ArenaStation[] = [
  ...[1, 2, 3, 4, 5, 6].map((n, i): ArenaStation => ({
    id: `st-pc-${n}`, point_project_id: 'demo-project', zone_id: 'zone-pc', name: `PC ${n}`,
    order_index: n, is_active: true, grid_x: i % 3, grid_y: Math.floor(i / 3),
  })),
  ...[1, 2, 3].map((n, i): ArenaStation => ({
    id: `st-vip-${n}`, point_project_id: 'demo-project', zone_id: 'zone-vip', name: `VIP ${n}`,
    order_index: 10 + n, is_active: true, grid_x: 7 + (i % 2), grid_y: Math.floor(i / 2),
  })),
]

const tariff = (id: string, zone: string, name: string, min: number, price: number, extra: Partial<ArenaTariff> = {}): ArenaTariff => ({
  id, point_project_id: 'demo-project', zone_id: zone, company_id: COMPANY.id, name,
  duration_minutes: min, price, is_active: true, tariff_type: 'fixed',
  window_start_time: null, window_end_time: null, ...extra,
})

export const TARIFFS: ArenaTariff[] = [
  tariff('t-pc-30', 'zone-pc', '30 минут', 30, 800),
  tariff('t-pc-60', 'zone-pc', '1 час', 60, 1500),
  tariff('t-pc-180', 'zone-pc', '3 часа', 180, 3900),
  tariff('t-pc-night', 'zone-pc', 'Ночь 23:00–08:00', 540, 6000, { tariff_type: 'time_window', window_start_time: '23:00', window_end_time: '08:00' }),
  tariff('t-vip-60', 'zone-vip', '1 час VIP', 60, 2500),
  tariff('t-vip-180', 'zone-vip', '3 часа VIP', 180, 6500),
]

export function makeActiveSessions(): ArenaSession[] {
  const mk = (id: string, station: string, tariffId: string, startedAgo: number, durMin: number, amount: number, pay: 'cash' | 'kaspi'): ArenaSession => ({
    id, point_project_id: 'demo-project', station_id: station, tariff_id: tariffId, shift_id: 'demo-shift-1',
    operator_id: OPERATOR.operator_id, started_at: iso(startedAgo),
    ends_at: new Date(Date.now() + (durMin - startedAgo) * 60_000).toISOString(), ended_at: null,
    amount, status: 'active', payment_method: pay, cash_amount: pay === 'cash' ? amount : 0,
    kaspi_amount: pay === 'kaspi' ? amount : 0, discount_percent: 0,
  })
  return [
    mk('sess-1', 'st-pc-1', 't-pc-60', 20, 60, 1500, 'cash'),
    mk('sess-2', 'st-pc-3', 't-pc-180', 100, 180, 3900, 'kaspi'),
    mk('sess-3', 'st-pc-5', 't-pc-30', 27, 30, 800, 'cash'),
    mk('sess-4', 'st-vip-2', 't-vip-60', 55, 60, 2500, 'kaspi'),
  ]
}
