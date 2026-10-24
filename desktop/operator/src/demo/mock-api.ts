/**
 * DEMO: подмена window.fetch. Ни один запрос не уходит наружу:
 *  - '/api/...'            -> JSON из памяти (фикстуры + немного состояния)
 *  - чужой хост (не origin) -> блокируется (TypeError, как «нет сети»)
 *  - свой origin (vite)    -> пропускается как есть (модули, ассеты)
 * Неизвестные /api/ маршруты -> { ok: true, data: [] } + console.warn.
 */
import {
  BOOTSTRAP, COMPANY, ITEMS, OPERATOR, STATIONS, TARIFFS, ZONES,
  iso, ymd, makeActiveSessions, makeSalesHistory,
  type HistorySale,
} from './fixtures'
import type { ArenaSession, DebtItem } from '@/types'

type Json = Record<string, unknown>

const state = {
  shift: { id: 'demo-shift-1', opened_at: iso(150), shift_type: 'day', opening_cash: 15000 } as
    | { id: string; opened_at: string; shift_type: string; opening_cash: number }
    | null,
  sales: makeSalesHistory() as HistorySale[],
  sessions: makeActiveSessions() as ArenaSession[],
  returns: [] as Json[],
  techLogs: [] as Json[],
  bookings: [] as Json[],
  seq: 100,
  debts: [
    { id: 'debt-1', operator_id: 'demo-op-2', created_by_operator_id: 'demo-op-1', client_name: null, debtor_name: 'Мадина Demo', item_name: 'Red Bull 0.25', barcode: '4870000001004', quantity: 2, unit_price: 1100, total_amount: 2200, comment: 'Из зарплаты', week_start: ymd(), created_at: iso(300), source: 'point', status: 'open', company_name: COMPANY.name },
    { id: 'debt-2', operator_id: null, created_by_operator_id: 'demo-op-1', client_name: 'Ерлан (постоянный клиент)', debtor_name: 'Ерлан (постоянный клиент)', item_name: 'Час PC', barcode: null, quantity: 3, unit_price: 1500, total_amount: 4500, comment: 'Отдаст в пятницу', week_start: ymd(), created_at: iso(1500), source: 'point', status: 'open', company_name: COMPANY.name },
    { id: 'debt-3', operator_id: 'demo-op-3', created_by_operator_id: 'demo-op-1', client_name: null, debtor_name: 'Данияр Demo', item_name: 'Сэндвич с курицей', barcode: '4870000001016', quantity: 1, unit_price: 1400, total_amount: 1400, comment: null, week_start: ymd(), created_at: iso(2900), source: 'point', status: 'open', company_name: COMPANY.name },
  ] as DebtItem[],
}

const uid = (p: string) => `${p}-${++state.seq}`

const CUSTOMERS = [
  { id: 'cust-1', name: 'Алихан Нурланов', phone: '+7 701 111 22 33', card_number: 'DC-0001', loyalty_points: 320, total_spent: 48200 },
  { id: 'cust-2', name: 'Дана Касымова', phone: '+7 707 555 66 77', card_number: 'DC-0002', loyalty_points: 90, total_spent: 12800 },
  { id: 'cust-3', name: 'Тимур Беков', phone: '+7 775 333 44 55', card_number: 'DC-0003', loyalty_points: 1450, total_spent: 210500 },
]
const LOYALTY = {
  points_per_100_tenge: 5, tenge_per_point: 1, min_points_to_redeem: 50,
  max_redeem_percent: 30, max_redeem_percent_per_purchase: 30, is_active: true,
}

function toSaleRow(s: HistorySale) {
  return {
    id: s.id, sale_date: s.sale_date, shift: s.shift, payment_method: s.payment_method,
    cash_amount: s.cash_amount, kaspi_amount: s.kaspi_amount,
    kaspi_before_midnight_amount: 0, kaspi_after_midnight_amount: s.kaspi_amount,
    total_amount: s.total_amount, comment: s.comment, sold_at: s.sold_at,
    items: s.items.map((i, idx) => {
      const it = ITEMS.find((x) => x.name === i.name)
      return {
        id: `${s.id}-l${idx}`, quantity: i.quantity, unit_price: i.unit_price, total_price: i.total_price,
        returned_qty: 0, returnable_qty: i.quantity,
        item: it ? { id: it.id, name: it.name, barcode: it.barcode } : null,
      }
    }),
  }
}

function shiftSummary() {
  const today = state.sales
  const sum = (f: (s: HistorySale) => number) => today.reduce((a, s) => a + f(s), 0)
  const retTotal = state.returns.reduce((a, r) => a + Number(r.total_amount || 0), 0)
  const cash = sum((s) => s.cash_amount)
  const kaspi = sum((s) => s.kaspi_amount)
  const total = cash + kaspi
  return {
    date: ymd(), shift: 'day', sale_count: today.length,
    item_count: today.reduce((a, s) => a + s.items.reduce((b, i) => b + i.quantity, 0), 0),
    return_count: state.returns.length, return_item_count: state.returns.length, total_amount: total - retTotal,
    cash_amount: cash, kaspi_amount: kaspi, kaspi_before_midnight_amount: 0, kaspi_after_midnight_amount: kaspi,
    sale_total_amount: total, sale_cash_amount: cash, sale_kaspi_amount: kaspi,
    sale_kaspi_before_midnight_amount: 0, sale_kaspi_after_midnight_amount: kaspi,
    return_total_amount: retTotal, return_cash_amount: retTotal, return_kaspi_amount: 0,
    return_kaspi_before_midnight_amount: 0, return_kaspi_after_midnight_amount: 0,
  }
}

const LOC = { id: 'loc-1', name: 'Витрина', code: 'SHOWCASE', location_type: 'showcase' }
const LOC_WH = { id: 'loc-2', name: 'Склад', code: 'WH', location_type: 'warehouse' }

function arenaData() {
  const active = state.sessions
  const cash = active.reduce((a, s) => a + s.cash_amount, 0)
  const kaspi = active.reduce((a, s) => a + s.kaspi_amount, 0)
  return {
    zones: ZONES, stations: STATIONS, tariffs: TARIFFS, sessions: active, decorations: [],
    today_income: {
      cash, kaspi,
      rows: active.map((s) => ({ cash_amount: s.cash_amount, kaspi_amount: s.kaspi_amount, comment: `Сессия ${s.station_id}` })),
    },
    today_tech_logs: state.techLogs,
  }
}

function handle(method: string, path: string, query: URLSearchParams, body: Json): { status?: number; json: unknown } | null {
  switch (path) {
    case '/api/point/bootstrap':
      return { json: { ok: true, ...BOOTSTRAP } }
    case '/api/point/login':
      // Демо: любой логин/пароль принимается.
      return {
        json: {
          ok: true, must_change_password: false,
          operator: { ...OPERATOR, username: String(body.username || OPERATOR.username) },
          company: COMPANY,
          allCompanies: [{ ...COMPANY, role_in_company: 'operator' }],
        },
      }
    case '/api/point/all-operators':
      return {
        json: {
          ok: true,
          operators: [
            ...BOOTSTRAP.operators.map((o) => ({ id: o.id, name: o.name, short_name: o.short_name, full_name: o.full_name, kind: 'operator' })),
            { id: 'staff-1', name: 'Руслан (владелец)', short_name: 'Руслан', full_name: null, kind: 'staff', role_label: 'Владелец' },
          ],
        },
      }
    case '/api/point/shift/current':
      return { json: { ok: true, shift: state.shift } }
    case '/api/point/shift/open':
      state.shift = {
        id: uid('demo-shift'), opened_at: new Date().toISOString(),
        shift_type: String(body.shift_type || 'day'), opening_cash: Number(body.opening_cash || 0),
      }
      return { json: { ok: true, shift_id: state.shift.id, opening_cash: state.shift.opening_cash } }
    case '/api/point/shift/close':
      state.shift = null
      return { json: { ok: true } }
    case '/api/point/shift/report':
      return { json: { ok: true, report: null } }
    case '/api/point/shift-report':
      if (method === 'GET') {
        return { json: { ok: true, data: { date: ymd(), total: 0, isPrecise: true, warning: null, parts: [] } } }
      }
      return { json: { ok: true, data: { id: uid('report') } } }
    case '/api/point/inventory-sales': {
      if (method === 'GET') {
        if (query.get('view') === 'shift-summary') return { json: { ok: true, data: shiftSummary() } }
        return {
          json: {
            ok: true,
            data: { company: COMPANY, location: LOC, items: ITEMS, sales: state.sales.map(toSaleRow) },
          },
        }
      }
      const payload = (body.payload || {}) as Json
      if (body.action === 'correctPayment') {
        return { json: { ok: true, data: { ...payload } } }
      }
      const lines = (payload.items as Array<{ item_id?: string; universal_name?: string; quantity: number; unit_price: number }>) || []
      const discount = Number(payload.discount_amount || 0) + Number(payload.loyalty_discount_amount || 0)
      const total = Math.max(0, lines.reduce((a, l) => a + l.quantity * l.unit_price, 0) - discount)
      const pm = String(payload.payment_method || 'cash')
      const cash = pm === 'cash' ? total : Number(payload.cash_amount || 0)
      const kaspi = pm === 'kaspi' ? total : Number(payload.kaspi_amount || 0)
      const id = uid('demo-sale')
      const soldAt = new Date().toISOString()
      state.sales.unshift({
        id, sold_at: soldAt, sale_date: ymd(), shift: 'day', payment_method: pm,
        cash_amount: cash, kaspi_amount: kaspi, card_amount: 0, online_amount: 0, total_amount: total,
        discount_amount: discount, comment: (payload.comment as string) || null, operator_name: String(OPERATOR.name),
        items: lines.map((l) => {
          const it = ITEMS.find((x) => x.id === l.item_id)
          return { name: it?.name || l.universal_name || 'Позиция', unit: it?.unit || 'шт', quantity: l.quantity, unit_price: l.unit_price, total_price: l.quantity * l.unit_price }
        }),
      })
      for (const l of lines) {
        const it = ITEMS.find((x) => x.id === l.item_id)
        if (it && it.display_qty < 900) it.display_qty = Math.max(0, it.display_qty - l.quantity)
      }
      return {
        json: {
          ok: true,
          data: {
            sale_id: id, total_amount: total, sold_at: soldAt,
            customer_id: (payload.customer_id as string) || null,
            loyalty_points_earned: payload.customer_id ? Math.floor(total / 100) * LOYALTY.points_per_100_tenge : 0,
            loyalty_points_spent: Number(payload.loyalty_points_spent || 0),
          },
        },
      }
    }
    case '/api/point/sales-history': {
      const q = (query.get('q') || '').trim().toLowerCase()
      const sales = q
        ? state.sales.filter((s) => String(s.total_amount).includes(q) || s.items.some((i) => i.name.toLowerCase().includes(q)))
        : state.sales
      return { json: { ok: true, data: { sales } } }
    }
    case '/api/point/inventory-returns': {
      if (method === 'GET') {
        return {
          json: {
            ok: true,
            data: { company: COMPANY, location: LOC, returns: state.returns, sales: state.sales.map(toSaleRow) },
          },
        }
      }
      const payload = (body.payload || {}) as Json
      const lines = (payload.items as Array<{ quantity: number; unit_price: number }>) || []
      const total = lines.reduce((a, l) => a + l.quantity * l.unit_price, 0)
      const id = uid('demo-return')
      state.returns.unshift({
        id, return_date: ymd(), shift: 'day', payment_method: payload.payment_method || 'cash',
        cash_amount: total, kaspi_amount: 0, kaspi_before_midnight_amount: 0, kaspi_after_midnight_amount: 0,
        total_amount: total, comment: payload.comment || null, returned_at: new Date().toISOString(), items: [],
      })
      return { json: { ok: true, data: { return_id: id, total_amount: total } } }
    }
    case '/api/point/inventory-requests': {
      if (method === 'GET') {
        return {
          json: {
            ok: true,
            data: {
              company: COMPANY, sourceLocation: LOC_WH, targetLocation: LOC,
              items: ITEMS.filter((i) => i.category?.id !== 'cat-time').map((i) => ({
                id: i.id, name: i.name, barcode: i.barcode, unit: i.unit, sale_price: i.sale_price,
                warehouse_qty: 120 - (Number(i.id.split('-').pop()) * 3), category: i.category,
              })),
              requests: [
                {
                  id: 'req-1', status: 'approved', comment: 'К выходным', decision_comment: 'Выдадим завтра утром',
                  created_at: iso(600), approved_at: iso(300),
                  items: [
                    { id: 'ri-1', requested_qty: 24, approved_qty: 24, comment: null, item: { id: ITEMS[0].id, name: ITEMS[0].name, barcode: ITEMS[0].barcode } },
                    { id: 'ri-2', requested_qty: 12, approved_qty: 10, comment: null, item: { id: ITEMS[3].id, name: ITEMS[3].name, barcode: ITEMS[3].barcode } },
                  ],
                },
                {
                  id: 'req-2', status: 'pending', comment: null, decision_comment: null, created_at: iso(60), approved_at: null,
                  items: [{ id: 'ri-3', requested_qty: 10, approved_qty: null, comment: null, item: { id: ITEMS[7].id, name: ITEMS[7].name, barcode: ITEMS[7].barcode } }],
                },
              ],
            },
          },
        }
      }
      if (body.action === 'createRequest') return { json: { ok: true, data: { request_id: uid('req') } } }
      return { json: { ok: true, data: { status: body.action === 'receiveRequest' ? 'received' : 'cancelled' } } }
    }
    case '/api/point/customers': {
      if (method === 'GET') {
        const q = (query.get('q') || '').trim().toLowerCase()
        const list = q ? CUSTOMERS.filter((c) => c.name.toLowerCase().includes(q) || (c.phone || '').includes(q) || (c.card_number || '').toLowerCase().includes(q)) : []
        return { json: { ok: true, data: list, loyalty_config: LOYALTY } }
      }
      return { json: { ok: true, data: { customer: CUSTOMERS[0], points_earned: 0, points_spent: 0 } } }
    }
    case '/api/admin/discounts':
      return { json: { ok: true, data: { type: 'percent', value: 10 } } }
    case '/api/point/debts': {
      if (method === 'GET') return { json: { ok: true, data: { items: state.debts } } }
      if (body.action === 'createDebt') {
        const pl = (body.payload || {}) as Json
        const item = {
          id: uid('debt'), operator_id: (pl.operator_id as string) || null, created_by_operator_id: OPERATOR.operator_id,
          client_name: (pl.client_name as string) || null,
          debtor_name: String(pl.client_name || '') || BOOTSTRAP.operators.find((o) => o.id === pl.operator_id)?.name || 'Должник',
          item_name: String(pl.item_name || ''), barcode: (pl.barcode as string) || null,
          quantity: Number(pl.quantity || 1), unit_price: Number(pl.unit_price || 0), total_amount: Number(pl.total_amount || 0),
          comment: (pl.comment as string) || null, week_start: ymd(), created_at: new Date().toISOString(), source: 'point',
          status: 'open', company_name: COMPANY.name,
        } as DebtItem
        state.debts.unshift(item)
        return { json: { ok: true, data: { item } } }
      }
      if (body.action === 'deleteDebt' || body.action === 'adminPayDebt') {
        state.debts = state.debts.filter((d) => d.id !== body.itemId)
      }
      return { json: { ok: true } }
    }
    case '/api/point/arena': {
      if (method === 'GET') {
        if (query.get('action') === 'getSessions') return { json: { ok: true, sessions: state.sessions } }
        return { json: { ok: true, data: arenaData() } }
      }
      const action = body.action
      if (action === 'startSession') {
        const t = TARIFFS.find((x) => x.id === body.tariffId) || TARIFFS[0]
        const disc = Number(body.discount_percent || 0)
        const amount = Math.round(t.price * (1 - disc / 100))
        const pm = (body.payment_method as ArenaSession['payment_method']) || 'cash'
        const s: ArenaSession = {
          id: uid('sess'), point_project_id: 'demo-project', station_id: String(body.stationId), tariff_id: t.id,
          shift_id: state.shift?.id || null, operator_id: OPERATOR.operator_id, started_at: new Date().toISOString(),
          ends_at: new Date(Date.now() + t.duration_minutes * 60_000).toISOString(), ended_at: null, amount, status: 'active',
          payment_method: pm,
          cash_amount: pm === 'cash' ? amount : Number(body.cash_amount || 0),
          kaspi_amount: pm === 'kaspi' ? amount : Number(body.kaspi_amount || 0),
          discount_percent: disc,
        }
        state.sessions.push(s)
        return { json: { ok: true, data: s } }
      }
      const sess = state.sessions.find((x) => x.id === body.sessionId)
      if (action === 'endSession' || action === 'endSessionWithRefund') {
        if (!sess) return { json: { ok: true, data: null, refund: { amount: 0, cash: 0, kaspi: 0 } } }
        state.sessions = state.sessions.filter((x) => x.id !== sess.id)
        const left = Math.max(0, new Date(sess.ends_at).getTime() - Date.now())
        const total = Math.max(1, new Date(sess.ends_at).getTime() - new Date(sess.started_at).getTime())
        const refund = action === 'endSessionWithRefund' ? Math.round((sess.amount * left) / total / 50) * 50 : 0
        const done = { ...sess, status: 'completed' as const, ended_at: new Date().toISOString() }
        return { json: { ok: true, data: done, refund: { amount: refund, cash: refund, kaspi: 0 } } }
      }
      if (action === 'extendSession' && sess) {
        const t = TARIFFS.find((x) => x.id === body.tariffId)
        const add = t ? t.duration_minutes : 60
        const price = t ? t.price : 1500
        sess.ends_at = new Date(new Date(sess.ends_at).getTime() + add * 60_000).toISOString()
        sess.amount += price
        sess.cash_amount += price
        return { json: { ok: true, data: sess } }
      }
      if (action === 'techLog') {
        state.techLogs.push({
          id: uid('tech'), station_name: (body.stationName as string) || null, reason: String(body.reason || ''),
          amount: Number(body.amount || 0), created_at: new Date().toISOString(),
        })
      }
      return { json: { ok: true } }
    }
    case '/api/point/bookings': {
      if (method === 'GET') return { json: { ok: true, serverTime: new Date().toISOString(), bookings: state.bookings } }
      if (body.action === 'create') {
        const ids = ((body.stationIds as string[]) || []).map(() => uid('bk'))
        const names = ((body.stationIds as string[]) || []).map((id) => STATIONS.find((s) => s.id === id)?.name || id)
        ids.forEach((id, i) => state.bookings.push({
          id, stationId: (body.stationIds as string[])[i], stationName: names[i], groupId: ids.length > 1 ? 'grp-1' : null,
          startsAt: body.startsAt, endsAt: body.endsAt, status: 'confirmed', phone: body.phone || null, name: body.name || null,
          customerId: null, tariffId: body.tariffId || null, notes: body.notes || null, createdAt: new Date().toISOString(),
        }))
        return { json: { ok: true, bookingIds: ids, groupId: ids.length > 1 ? 'grp-1' : null, stationNames: names, knownCustomer: null } }
      }
      if (body.action === 'cancel') {
        state.bookings = state.bookings.filter((b) => b.id !== body.bookingId)
        return { json: { ok: true, cancelledCount: 1 } }
      }
      return { json: { ok: true, movedCount: 1 } }
    }
    case '/api/point/bookings/lookup':
      return {
        json: {
          phone: query.get('phone') || '', known: false, name: null, customer: null,
          stats: { total: 0, completed: 0, cancelled: 0 }, history: [],
        },
      }
    case '/api/point/operator-tasks': {
      if (method === 'GET') {
        return {
          json: {
            ok: true,
            tasks: [
              { id: 'task-1', task_number: 41, title: 'Протереть мониторы в PC Зале', description: 'Перед открытием вечерней смены.', status: 'todo', priority: 'medium', due_date: ymd(), company_id: COMPANY.id, company_name: COMPANY.name, company_code: COMPANY.code, created_at: iso(500), updated_at: iso(500), completed_at: null },
              { id: 'task-2', task_number: 42, title: 'Пересчитать остатки на витрине', description: 'Особенно энергетики и чипсы.', status: 'in_progress', priority: 'high', due_date: ymd(), company_id: COMPANY.id, company_name: COMPANY.name, company_code: COMPANY.code, created_at: iso(1400), updated_at: iso(200), completed_at: null },
            ],
            comments: [
              { id: 'c-1', task_id: 'task-2', content: 'Начал с холодильника, доделаю после обеда.', created_at: iso(200), author_name: OPERATOR.name, author_type: 'operator' },
              { id: 'c-2', task_id: 'task-2', content: 'Ок, фото акта пришли в чат.', created_at: iso(120), author_name: 'Руслан (владелец)', author_type: 'staff' },
            ],
          },
        }
      }
      return { json: { ok: true, status: 'in_progress', responseLabel: 'Принято' } }
    }
    case '/api/point/operator-cabinet':
      return {
        json: {
          ok: true,
          shifts: [3, 4, 6, 7, 9].map((d, i) => ({
            id: `sh-${i}`, date: ymd(new Date(Date.now() - d * 86400_000)), shift: i % 2 ? 'night' : 'day',
            operator_id: OPERATOR.operator_id, operator_name: OPERATOR.name,
            cash_amount: 28000 + i * 3100, kaspi_amount: 41000 + i * 2500, online_amount: 0, card_amount: 0,
            total: 69000 + i * 5600, zone: null, company_name: COMPANY.name,
          })),
          debts: state.debts.filter((d) => d.operator_id === OPERATOR.operator_id || d.operator_id === 'demo-op-2'),
        },
      }
    case '/api/point/knowledge':
      return {
        json: {
          ok: true,
          data: {
            company_id: COMPANY.id,
            articles: [
              { id: 'kb-1', title: 'Открытие смены', slug: 'open-shift', summary: 'Что проверить в первые 10 минут', content: '1. Пересчитать кассу.\n2. Включить все ПК.\n3. Проверить витрину.', tags: ['смена'], audience: ['operator'], severity: 'normal', version: 1, requires_confirmation: false, related_fine_amount: null, related_bonus_amount: null, company_id: COMPANY.id, category_id: 'kc-1', category: { id: 'kc-1', title: 'Регламенты', slug: 'regs', kind: 'regulation' } },
              { id: 'kb-2', title: 'Возврат товара', slug: 'returns', summary: 'Когда можно, а когда нельзя', content: 'Возврат — только по чеку и в тот же день.', tags: ['возврат'], audience: ['operator'], severity: 'warning', version: 2, requires_confirmation: true, related_fine_amount: 2000, related_bonus_amount: null, company_id: COMPANY.id, category_id: 'kc-1', category: { id: 'kc-1', title: 'Регламенты', slug: 'regs', kind: 'regulation' } },
            ],
            pending_confirmations: [],
            checklist_templates: [], checklist_items: [], checklist_runs: [],
            open_shift: state.shift ? { id: state.shift.id, shift_type: state.shift.shift_type, opened_at: state.shift.opened_at, operator_id: OPERATOR.operator_id } : null,
          },
        },
      }
    case '/api/point/receipt-settings':
      return {
        json: {
          ok: true,
          data: {
            settings: {
              tax_payer_name: 'ИП Demo Club', tax_payer_bin: '000000000000', point_address: 'г. Алматы, ул. Демо, 1',
              kkm_factory_number: 'DEMO-0001', kkm_registration_number: 'DEMO-REG-0001', is_vat_payer: false, vat_rate: 12,
              ofd_name: 'Demo OFD', ofd_check_url: 'https://demo.local/check', receipt_language: 'ru',
              receipt_footer_text: 'Спасибо, что выбрали Demo Club!', require_buyer_iin: false, review_url: 'https://demo.local/review',
            },
          },
        },
      }
    case '/api/point/sync-check': {
      const now = new Date().toISOString()
      return {
        json: {
          catalogVersion: 'demo-1', balancesVersion: String(state.sales.length), tariffsVersion: 'demo-1',
          lastSaleVersion: String(state.sales.length), pendingMessages: [], serverTime: now,
        },
      }
    }
    case '/api/point/sync-check/ack':
      return { json: { ok: true } }
    case '/api/point/ad-playlist':
      return { json: { ok: true, data: [] } }
    case '/api/point/checklist/run':
      return { json: { ok: true, run_id: uid('run') } }
    case '/api/point/reports':
      return { json: { ok: true, data: { shifts: [], debt_history: [], warehouse: [], worker_totals: [], client_totals: [] } } }
    case '/api/point/sale-receipt-email':
      return { json: { ok: true, status: 'sent', email: String(body.email || ''), message: 'Демо: письмо не отправлялось' } }
    case '/api/point/change-password':
    case '/api/point/admin-logout':
      return { json: { ok: true } }
    case '/api/point/admin-login':
      return { json: { ok: true, token: 'demo-admin-token', admin: { email: String(body.email || 'admin@demo.local') } } }
    case '/api/point/qr-login/start':
      return { json: { ok: true, nonce: 'demo-nonce', expires_at: new Date(Date.now() + 300_000).toISOString(), confirm_url: 'https://demo.local/qr/demo' } }
    case '/api/point/qr-login/poll':
      return { json: { status: 'pending' } }
    default:
      return null
  }
}

export function installMockApi() {
  const realFetch = window.fetch.bind(window)

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const rawUrl = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    let url: URL
    try {
      url = new URL(rawUrl, window.location.href)
    } catch {
      return realFetch(input, init)
    }

    const sameOrigin = url.origin === window.location.origin
    const isApi = url.pathname.startsWith('/api/') || url.hostname === 'demo.local'

    // Чужие хосты (ordaops.kz и всё остальное) — никогда.
    if (!sameOrigin && url.hostname !== 'demo.local') {
      console.warn('[demo] blocked external request', rawUrl)
      throw new TypeError('Failed to fetch (demo: external network blocked)')
    }
    if (sameOrigin && !isApi) return realFetch(input, init) // vite-ассеты

    const method = (init?.method || (input instanceof Request ? input.method : 'GET')).toUpperCase()
    let body: Json = {}
    try {
      if (typeof init?.body === 'string' && init.body) body = JSON.parse(init.body) as Json
    } catch { /* не JSON */ }

    await new Promise((r) => setTimeout(r, 120)) // чуть живости вместо мгновенного ответа

    const path = url.pathname.replace(/\/$/, '')
    let result = handle(method, path, url.searchParams, body)
    if (!result && path.startsWith('/api/point/checklist/run/')) {
      result = { json: { ok: true, run_id: path.split('/')[5], updated: true, status: 'completed', fines_total: 0, bonuses_total: 0 } }
    }
    if (!result && path === '/api/point/knowledge/confirm') {
      result = { json: { ok: true, data: { article_id: String(body.article_id || ''), version: 1 } } }
    }
    if (!result && path === '/api/point/knowledge/explain') {
      result = { json: { ok: true, answer: 'Демо-ответ: правило простыми словами.' } }
    }
    if (!result) {
      console.warn('[demo] unmocked route', method, path)
      result = { json: { ok: true, data: [] } }
    }
    return new Response(JSON.stringify(result.json), {
      status: result.status ?? 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  // Вторая страховка: sendBeacon / XHR / WebSocket наружу тоже не пускаем.
  const blockedOrigin = (u: string) => {
    try { return new URL(u, window.location.href).origin !== window.location.origin } catch { return true }
  }
  if (navigator.sendBeacon) {
    const orig = navigator.sendBeacon.bind(navigator)
    navigator.sendBeacon = (u: string | URL, d?: BodyInit | null) => (blockedOrigin(String(u)) ? false : orig(u, d))
  }
  const origOpen = XMLHttpRequest.prototype.open
  XMLHttpRequest.prototype.open = function (this: XMLHttpRequest, method: string, u: string | URL, ...rest: unknown[]) {
    if (blockedOrigin(String(u))) {
      console.warn('[demo] blocked XHR', String(u))
      throw new TypeError('demo: external network blocked')
    }
    return (origOpen as (...a: unknown[]) => void).call(this, method, u, ...rest)
  } as typeof XMLHttpRequest.prototype.open
}
