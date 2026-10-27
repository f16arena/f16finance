'use client'

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { PageSkeleton, StatGridSkeleton, TableSkeleton } from '@/components/skeleton'
import { useStoreScope } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'
import { formatMoney } from '@/lib/core/format'
import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { toast } from '@/hooks/use-toast'
import { StoreChip, StoreEmpty, StoreSection, StoreStat, StoreStatGrid, plural, storeCard } from '@/components/store/store-ui'
import { Clock, Loader2, Printer, RefreshCw, Settings, User, Wallet, CreditCard, ChevronRight, TrendingUp, RotateCcw } from 'lucide-react'

type LiveTotals = { sales: number; cash: number; kaspi: number; count: number }
type Shift = {
  id: string
  status: string
  shift_type?: string | null
  opened_at: string
  closed_at: string | null
  opening_cash: number | null
  closing_cash: number | null
  closing_kaspi: number | null
  totals_json?: any
  live_totals?: LiveTotals | null
  operator?: { full_name?: string | null; short_name?: string | null } | null
}

const fmt = (n: number | null | undefined) => Number(n || 0).toLocaleString('ru-RU')
const dt = (s: string | null) => (s ? new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—')

function escHtml(s: string): string {
  return String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c] as string))
}

// A4 Z-отчёт — печать по требованию на сайте (страница «Смены»).
function printZReport(r: any) {
  const w = window.open('', '_blank', 'width=800,height=1000')
  if (!w) return
  const money = (n: number) => `${fmt(Math.round(Number(n || 0)))} ₸`
  const dts = (s: string | null) => (s ? new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—')
  const qtyU = (n: number, u: string) => `${fmt(n)}${u ? ' ' + escHtml(u) : ''}`
  const req = r.requisites || {}
  const positions = r.positions || []
  const rows = positions
    .map((p: any, i: number) => `<tr><td class="idx">${i + 1}</td><td class="nm">${escHtml(p.name)}</td><td class="num">${qtyU(p.sold, p.unit)}</td><td class="num stk">${qtyU(p.stock, p.unit)}</td><td class="num amt">${money(p.amount)}</td></tr>`)
    .join('')
  // Доп. показатели для полноты отчёта.
  const checks = Number(r.checkCount || 0)
  const avg = checks > 0 ? Math.round(Number(r.total || 0) / checks) : 0
  const soldUnits = positions.reduce((a: number, p: any) => a + Number(p.sold || 0), 0)
  const names = positions.length
  let duration = '—'
  if (r.openedAt && r.closedAt) {
    const ms = new Date(r.closedAt).getTime() - new Date(r.openedAt).getTime()
    if (ms > 0) { const h = Math.floor(ms / 3600000); const m = Math.round((ms % 3600000) / 60000); duration = `${h} ч ${m} мин` }
  }
  const kpi = (label: string, value: string) => `<div class="kpi"><div class="kl">${label}</div><div class="kv">${value}</div></div>`
  const debts = r.debts || []
  const debtRows = debts
    .map((d: any) => `<tr><td class="nm">${escHtml(d.debtor)}</td><td>${escHtml(d.item)}</td><td class="num">${fmt(d.quantity)}</td><td class="num amt">${money(d.amount)}</td></tr>`)
    .join('')
  w.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>Z-Отчёт · Смена №${r.shiftNumber}</title>
    <style>
    @page{size:A4;margin:14mm}
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:-apple-system,'Segoe UI',Arial,sans-serif;font-size:12.5px;color:#1a1a1a;line-height:1.5;-webkit-print-color-adjust:exact;print-color-adjust:exact}
    .wrap{max-width:720px;margin:0 auto}
    .hd{text-align:center;padding-bottom:12px;border-bottom:2px solid #111;margin-bottom:16px}
    .hd .co{font-size:17px;font-weight:800;letter-spacing:.2px}
    .hd .m{color:#666;font-size:11.5px;margin-top:3px}
    .title{display:flex;align-items:baseline;justify-content:space-between;margin-bottom:14px}
    .title h1{font-size:20px;font-weight:800;color:#059669;letter-spacing:1px}
    .title .sh{font-size:13px;color:#555;font-weight:700}
    .meta{display:grid;grid-template-columns:1fr 1fr;gap:2px 28px;margin-bottom:18px}
    .meta .r{display:flex;justify-content:space-between;gap:12px;border-bottom:1px dotted #ddd;padding:4px 0}
    .meta .k{color:#777}.meta .v{font-weight:700;text-align:right}
    .kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:20px}
    .kpi{border:1px solid #eee;border-radius:8px;padding:9px 10px;text-align:center}
    .kpi .kl{font-size:9px;text-transform:uppercase;letter-spacing:.4px;color:#999}
    .kpi .kv{font-size:16px;font-weight:800;margin-top:3px;font-variant-numeric:tabular-nums}
    .cash{display:grid;grid-template-columns:1fr 1fr;gap:0 24px;margin-bottom:20px}
    .cash .r{display:flex;justify-content:space-between;padding:5px 2px;border-bottom:1px solid #f1f1f1}
    .cash .r span:last-child{font-weight:600;font-variant-numeric:tabular-nums}
    .sec{font-size:10.5px;font-weight:700;text-transform:uppercase;letter-spacing:.9px;color:#999;margin-bottom:6px}
    .sum{margin-bottom:20px}
    .sum .r{display:flex;justify-content:space-between;padding:5px 2px;border-bottom:1px solid #f1f1f1}
    .sum .r span:last-child{font-variant-numeric:tabular-nums;font-weight:600}
    .sum .r.tot{border-top:2px solid #111;border-bottom:none;margin-top:5px;padding-top:9px;font-size:16px;font-weight:800}
    .sum .r.tot span:last-child{color:#059669}
    table{width:100%;border-collapse:collapse}
    thead th{text-align:left;font-size:10px;text-transform:uppercase;letter-spacing:.5px;color:#999;padding:7px 8px;border-bottom:2px solid #111}
    tbody td{padding:6px 8px;border-bottom:1px solid #f1f1f1;vertical-align:middle}
    tbody tr:nth-child(even){background:#fafafa}
    .idx{color:#bbb;width:24px;text-align:right;font-size:11px}
    .nm{width:auto}
    .num{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}
    th.num{text-align:right}
    .stk{color:#666;width:96px}.amt{font-weight:700;width:110px}
    .foot{margin-top:22px;padding-top:8px;border-top:1px solid #eee;display:flex;justify-content:space-between;color:#aaa;font-size:10.5px}
    .bar{position:sticky;top:0;z-index:9;display:flex;gap:8px;justify-content:center;padding:10px;background:#f4f4f5;border-bottom:1px solid #e4e4e7;margin:-14mm -14mm 14px}
    .bar button{border:none;border-radius:8px;padding:8px 20px;font-size:13px;font-weight:600;cursor:pointer}
    .bar .p{background:#059669;color:#fff}.bar .c{background:#e4e4e7;color:#111}
    @media print{.bar{display:none}}
    </style></head>
    <body>
      <div class="bar"><button class="p" onclick="window.print()">🖨 Печать</button><button class="c" onclick="window.close()">Закрыть</button></div>
      <div class="wrap">
        <div class="hd">
          <div class="co">${escHtml(req.name || r.pointName || 'ORDA POINT')}</div>
          <div class="m">${req.bin ? 'БИН/ИИН ' + escHtml(req.bin) : ''}${req.bin && req.address ? ' · ' : ''}${req.address ? escHtml(req.address) : ''}</div>
        </div>
        <div class="title"><h1>Z-ОТЧЁТ</h1><span class="sh">Смена №${r.shiftNumber}</span></div>
        <div class="meta">
          <div class="r"><span class="k">Точка</span><span class="v">${escHtml(r.pointName || '—')}</span></div>
          <div class="r"><span class="k">Кассир</span><span class="v">${escHtml(r.cashier || '—')}</span></div>
          <div class="r"><span class="k">Открыта</span><span class="v">${dts(r.openedAt)}</span></div>
          <div class="r"><span class="k">Закрыта</span><span class="v">${dts(r.closedAt)}</span></div>
          <div class="r"><span class="k">Продолжительность</span><span class="v">${duration}</span></div>
        </div>
        <div class="sec">Ключевые показатели</div>
        <div class="kpis">
          ${kpi('Чеков за смену', String(checks))}
          ${kpi('Средний чек', money(avg))}
          ${kpi('Позиций продано', fmt(soldUnits))}
          ${kpi('Наименований', String(names))}
        </div>
        <div class="sec">Суммы за смену</div>
        <div class="sum">
          <div class="r"><span>Наличные · ${r.cashCount || 0} чек</span><span>${money(r.cashSales)}</span></div>
          <div class="r"><span>Безналичные · ${r.kaspiCount || 0} чек</span><span>${money(r.kaspiSales)}</span></div>
          <div class="r"><span>Возвраты</span><span>${money(r.returns)}</span></div>
          <div class="r tot"><span>Итоговая сумма</span><span>${money(r.total)}</span></div>
        </div>
        <div class="sec">Наличность в кассе</div>
        <div class="cash">
          <div class="r"><span>На начало смены</span><span>${money(r.openingCash)}</span></div>
          <div class="r"><span>На конец смены</span><span>${money(r.closingCash)}</span></div>
        </div>
        <div class="sec">Позиции · ${names}</div>
        <table>
          <thead><tr><th class="idx">#</th><th>Название</th><th class="num">Продано</th><th class="num">На складе</th><th class="num">Сумма</th></tr></thead>
          <tbody>${rows || '<tr><td colspan="5" style="color:#999;padding:12px 8px">Продаж по позициям нет</td></tr>'}</tbody>
        </table>
        <div class="sum" style="margin-top:8px"><div class="r tot"><span>Итог проданных товаров</span><span>${money(r.goodsTotal)}</span></div></div>
        ${debts.length ? `
        <div class="sec" style="margin-top:20px">Долги за смену · ${debts.length}</div>
        <table>
          <thead><tr><th>Должник</th><th>Товар</th><th class="num">Кол-во</th><th class="num">Сумма</th></tr></thead>
          <tbody>${debtRows}</tbody>
        </table>
        <div class="sum" style="margin-top:8px"><div class="r tot"><span>Итого долгов за смену</span><span style="color:#e11d48 !important">${money(r.debtsTotal)}</span></div></div>
        <div style="margin-top:4px;color:#999;font-size:10.5px">Долги — товар взят без оплаты. В выручку смены не входят.</div>
        ` : ''}
        <div class="foot"><span>Orda Point · управленческий Z-отчёт</span><span>Сформировано: ${new Date().toLocaleString('ru-RU')}</span></div>
      </div>
    </body></html>`)
  w.document.close()
}

// Загрузить данные Z-отчёта и открыть печатную A4-страницу (сперва показ, печать по кнопке).
async function printZFor(shiftId: string) {
  try {
    const res = await fetch(`/api/admin/shifts/z-report?shift_id=${shiftId}`, { cache: 'no-store' })
    const j = await res.json().catch(() => null)
    if (res.ok && j?.report) printZReport(j.report)
    else toast({ title: j?.error === 'forbidden' ? 'Нет доступа к этой смене' : 'Не удалось сформировать Z-отчёт', variant: 'destructive' })
  } catch { toast({ title: 'Не удалось сформировать Z-отчёт', variant: 'destructive' }) }
}
const tm = (s: string | null) => (s ? new Date(s).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '—')
const STATUS: { key: string; label: string }[] = [
  { key: 'closed', label: 'Закрытые' },
  { key: 'open', label: 'Открытые' },
  { key: 'all', label: 'Все' },
]
const PAY_LABEL: Record<string, string> = { cash: 'Нал', kaspi: 'Безнал', card: 'Карта', online: 'Онлайн', mixed: 'Смеш.' }
const PAY_TONE: Record<string, 'emerald' | 'amber' | 'sky' | 'violet' | 'neutral'> = {
  cash: 'emerald', kaspi: 'amber', card: 'sky', online: 'violet', mixed: 'neutral',
}

function salesOf(s: Shift): number | null {
  if (s.status === 'open') return s.live_totals?.sales ?? null
  const t = s.totals_json
  if (t && typeof t === 'object') {
    const v = t.sales_total ?? t.total_sales ?? t.revenue ?? t.sales ?? t.total
    if (typeof v === 'number') return v
  }
  return null
}

/**
 * Расхождение кассы — итог, который посчитала касса при закрытии:
 * (нал + монеты + Kaspi + тех. компенс. − старт) − продажи по системе.
 * «+» излишек, «−» недостача. Иначе null.
 */
function diffOf(s: Shift): number | null {
  const reported = (s as any).cash_report_diff
  if (typeof reported === 'number' && Number.isFinite(reported)) return reported
  const t = s.totals_json
  if (t && typeof t === 'object') {
    const v = t.cash_difference ?? t.cash_diff ?? t.difference ?? t.discrepancy ?? t.diff
    if (typeof v === 'number' && Number.isFinite(v)) return v
  }
  return null
}

const fmtMoney = (n: number) => formatMoney(n)

export default function StoreShiftsPage() {
  const { can } = useCapabilities()
  const { storeCompanyId: pickedCompanyId } = useStoreScope()
  const [configCompanyId, setConfigCompanyId] = useState<string | null | undefined>(undefined)
  // Точка из переключателя в шапке главнее конфига: пока «Общий» — берём точку
  // магазина по умолчанию, иначе показываем смены выбранной точки.
  const storeCompanyId = pickedCompanyId ?? configCompanyId
  const [status, setStatus] = useState('closed')
  const [shifts, setShifts] = useState<Shift[]>([])
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [detailId, setDetailId] = useState<string | null>(null)

  // Открытие конкретной смены по ссылке из разбора эффективности:
  // /store/shifts?shift=<id>. Так из оценки смены можно сразу попасть в её
  // чеки и позиции, а не искать смену в списке глазами.
  useEffect(() => {
    const shiftParam = new URLSearchParams(window.location.search).get('shift')
    if (shiftParam) setDetailId(shiftParam)
  }, [])

  useEffect(() => {
    fetch('/api/admin/store/config', { cache: 'no-store' })
      .then((r) => r.json())
      .then((j) => setConfigCompanyId(j?.data?.store_company_id || null))
      .catch(() => setConfigCompanyId(null))
  }, [])

  const load = useCallback(async () => {
    if (!storeCompanyId) return
    const p = new URLSearchParams({ company_id: storeCompanyId, status, limit: '200' })
    const url = `/api/admin/shifts/reports?${p}`
    const cached = readApiCache<any[]>(`${url}#shifts`)
    if (cached) setShifts(cached)
    setLoading(!cached); setErr(null)
    try {
      const res = await fetch(url, { cache: 'no-store' })
      const j = await res.json()
      if (!res.ok || !j.ok) throw new Error(j.error || 'Ошибка загрузки')
      const rows = j.data?.shifts || []
      writeApiCache(`${url}#shifts`, rows)
      setShifts(rows)
    } catch (e: any) { setErr(e?.message || 'Ошибка') } finally { setLoading(false) }
  }, [storeCompanyId, status])

  useEffect(() => { if (storeCompanyId) load() }, [storeCompanyId, load])

  const summary = useMemo(() => {
    let revenue = 0; let counted = 0; let opened = 0; let diffCount = 0; let diffSum = 0
    let shortCount = 0; let shortSum = 0; let overCount = 0; let overSum = 0
    for (const s of shifts) {
      const v = salesOf(s)
      if (v != null) { revenue += v; counted += 1 }
      if (s.status === 'open') opened += 1
      const d = diffOf(s)
      if (d != null && Math.abs(d) >= 1) {
        diffCount += 1; diffSum += d
        if (d < 0) { shortCount += 1; shortSum += d } else { overCount += 1; overSum += d }
      }
    }
    return { revenue, avg: counted > 0 ? revenue / counted : 0, opened, diffCount, diffSum, shortCount, shortSum, overCount, overSum }
  }, [shifts])

  if (storeCompanyId === undefined) {
    return <div className="app-page-wide"><PageSkeleton stats={0} rows={8} cols={4} /></div>
  }

  if (!storeCompanyId) {
    return (
      <div className="app-page-wide space-y-5">
        <AdminPageHeader title="Смены" description="Смены по точке-магазину" icon={<Clock className="h-5 w-5" />} accent="emerald" backHref="/store" />
        <div className={storeCard}>
          <StoreEmpty
            icon={<Settings className="h-5 w-5" />}
            title="Точка магазина не выбрана"
            hint="Смены показываются по точке-магазину. Выберите её в настройках магазина."
            action={<Button asChild size="sm"><Link href="/store/settings">Выбрать точку</Link></Button>}
          />
        </div>
      </div>
    )
  }

  return (
    <div className="app-page-wide space-y-5">
      <AdminPageHeader
        title="Смены"
        description="Кто работал, сколько выручил и сошлась ли касса"
        icon={<Clock className="h-5 w-5" />}
        accent="emerald"
        backHref="/store"
        actions={
          <Button variant="outline" size="sm" onClick={load}>
            {loading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />} Обновить
          </Button>
        }
        toolbar={
          <div className="inline-flex flex-wrap gap-1 rounded-2xl border border-border bg-slate-50 dark:bg-slate-950/50 p-1">
            {STATUS.map(({ key, label }) => (
              <button key={key} onClick={() => setStatus(key)} className={`rounded-xl px-3 py-2 text-sm font-medium transition-all sm:px-4 ${status === key ? 'bg-white dark:bg-white/10 text-foreground shadow-sm ring-1 ring-slate-200 dark:ring-white/10' : 'text-muted-foreground hover:text-slate-900 dark:hover:text-white'}`}>{label}</button>
            ))}
          </div>
        }
      />

      {err && <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-200">{err}</div>}

      <StoreStatGrid cols={4}>
        <StoreStat label="Смен" value={shifts.length} hint={summary.opened > 0 ? `${summary.opened} открыто сейчас` : 'в списке'} loading={loading && shifts.length === 0} />
        <StoreStat label="Выручка" value={fmtMoney(summary.revenue)} tone={summary.revenue > 0 ? 'emerald' : 'neutral'} hint="по смене из списка" loading={loading && shifts.length === 0} />
        <StoreStat label="Средняя смена" value={fmtMoney(Math.round(summary.avg))} loading={loading && shifts.length === 0} />
        <StoreStat
          label="Касса не сошлась"
          value={summary.shortCount > 0 ? `−${fmtMoney(Math.abs(summary.shortSum))}` : summary.overCount > 0 ? `+${fmtMoney(summary.overSum)}` : 'сошлась'}
          tone={summary.shortCount > 0 ? 'rose' : summary.overCount > 0 ? 'amber' : 'neutral'}
          hint={summary.diffCount === 0
            ? 'по всем сменам из списка'
            : [
                summary.shortCount > 0 ? `недостача в ${summary.shortCount} ${plural(summary.shortCount, ['смене', 'сменах', 'сменах'])}` : null,
                summary.overCount > 0 ? `излишек +${fmtMoney(summary.overSum)} в ${summary.overCount}` : null,
              ].filter(Boolean).join(' · ')}
          loading={loading && shifts.length === 0}
        />
      </StoreStatGrid>

      <StoreSection title="Смены" subtitle="Нажмите на смену — откроются чеки и позиции" flush action={<StoreChip>{shifts.length}</StoreChip>}>
        {loading && shifts.length === 0 ? (
          <div className="p-4"><TableSkeleton rows={8} cols={4} /></div>
        ) : shifts.length === 0 ? (
          <StoreEmpty
            icon={<Clock className="h-5 w-5" />}
            title={status === 'open' ? 'Открытых смен сейчас нет' : 'Смен пока нет'}
            hint="Смена появляется, когда кассир открывает её в кассе. После закрытия здесь будут выручка и расхождение."
          />
        ) : (
          <div className="divide-y divide-border">
            {shifts.map((s) => {
              const sales = salesOf(s)
              const open = s.status === 'open'
              const cash = open ? (s.live_totals?.cash ?? 0) : Number(s.closing_cash || 0)
              const kaspi = open ? (s.live_totals?.kaspi ?? 0) : Number(s.closing_kaspi || 0)
              const diff = diffOf(s)
              const hasDiff = diff != null && Math.abs(diff) >= 1
              return (
                <div key={s.id} className="flex items-center gap-2 px-3 py-3 transition-colors hover:bg-slate-50 dark:hover:bg-white/[0.03] sm:px-4">
                  <button onClick={() => setDetailId(s.id)} className="flex min-w-0 flex-1 flex-wrap items-center gap-x-4 gap-y-2 text-left">
                    <div className="flex min-w-[170px] flex-1 items-center gap-2.5">
                      <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-slate-100 text-muted-foreground dark:bg-white/5"><User className="h-4 w-4" /></span>
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                          <span className="truncate">{s.operator?.full_name || s.operator?.short_name || '—'}</span>
                          <StoreChip tone={open ? 'emerald' : 'neutral'}>{open ? 'Открыта' : 'Закрыта'}</StoreChip>
                        </div>
                        <div className="text-[11px] text-muted-foreground">{dt(s.opened_at)} → {dt(s.closed_at)}</div>
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-x-5 gap-y-1 text-sm sm:ml-auto">
                      <Metric label="Выручка" value={sales != null ? fmtMoney(sales) : '—'} tone={sales ? 'emerald' : 'neutral'} bold />
                      <Metric label="Наличные" icon={<Wallet className="h-3 w-3" />} value={fmtMoney(cash)} tone={cash > 0 ? 'neutral' : 'muted'} />
                      <Metric label="Безнал" icon={<CreditCard className="h-3 w-3" />} value={fmtMoney(kaspi)} tone={kaspi > 0 ? 'sky' : 'muted'} />
                      {hasDiff && <Metric label={diff! < 0 ? 'Недостача' : 'Излишек'} value={`${diff! > 0 ? '+' : '−'}${fmtMoney(Math.abs(diff!))}`} tone={diff! < 0 ? 'rose' : 'amber'} bold />}
                    </div>
                  </button>
                  {can('store-shifts.export') && (
                    <Button variant="outline" size="xs" onClick={() => printZFor(s.id)} title="Распечатать Z-отчёт">
                      <Printer className="h-3.5 w-3.5" /> <span className="hidden sm:inline">Z-отчёт</span>
                    </Button>
                  )}
                  <ChevronRight className="h-4 w-4 shrink-0 text-slate-400" />
                </div>
              )
            })}
          </div>
        )}
      </StoreSection>

      {detailId && <ShiftDetail id={detailId} onClose={() => setDetailId(null)} onChanged={load} />}
    </div>
  )
}

function Metric({ label, value, tone, icon, bold }: { label: string; value: string; tone: 'emerald' | 'sky' | 'rose' | 'amber' | 'neutral' | 'muted'; icon?: React.ReactNode; bold?: boolean }) {
  const cls = {
    emerald: 'text-emerald-600 dark:text-emerald-300',
    amber: 'text-amber-600 dark:text-amber-300',
    sky: 'text-sky-600 dark:text-sky-300',
    rose: 'text-rose-600 dark:text-rose-300',
    neutral: 'text-foreground',
    muted: 'text-slate-400',
  }[tone]
  return (
    <div className="text-right">
      <div className="flex items-center justify-end gap-1 text-[11px] text-muted-foreground">{icon}{label}</div>
      <div className={`tabular-nums ${bold ? 'font-semibold' : 'font-medium'} ${cls}`}>{value}</div>
    </div>
  )
}

function ShiftDetail({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useCapabilities()
  const [data, setData] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [err, setErr] = useState<string | null>(null)
  const [closing, setClosing] = useState(false)
  const [closeErr, setCloseErr] = useState<string | null>(null)
  const [confirmClose, setConfirmClose] = useState(false)
  const [note, setNote] = useState('')
  const [receiptSaleId, setReceiptSaleId] = useState<string | null>(null) // чек по клику на продажу

  async function handleForceClose() {
    setClosing(true); setCloseErr(null)
    try {
      const res = await fetch(`/api/admin/shifts/reports/${id}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'closeForce', note: note.trim() || undefined }),
      })
      const j = await res.json().catch(() => ({}))
      if (!res.ok || !j.ok) {
        if (res.status === 403) throw new Error('Нет прав на принудительное закрытие смены')
        throw new Error(j.detail || j.error || 'Не удалось закрыть смену')
      }
      toast({ title: 'Смена закрыта', description: 'Выручка попала в «Доходы»' })
      onChanged()
      onClose()
    } catch (e: any) {
      setCloseErr(e?.message || 'Ошибка')
    } finally {
      setClosing(false)
    }
  }

  useEffect(() => {
    let active = true
    setLoading(true); setErr(null)
    fetch(`/api/admin/shifts/reports/${id}`, { cache: 'no-store' })
      .then((r) => r.json())
      .then((j) => { if (!active) return; if (!j.ok) throw new Error(j.error || 'Ошибка'); setData(j.data) })
      .catch((e) => { if (active) setErr(e?.message || 'Ошибка') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [id])

  const shift = data?.shift
  const sales = (data?.sales || []) as any[]
  const returns = (data?.returns || []) as any[]
  const open = shift?.status === 'open'
  const salesTotal = sales.reduce((s, x) => s + Number(x.total_amount || 0), 0)
  const cashTotal = sales.reduce((s, x) => s + Number(x.cash_amount || 0), 0)
  const kaspiTotal = sales.reduce((s, x) => s + Number(x.kaspi_amount || 0), 0)
  const returnsTotal = returns.reduce((s, x) => s + Number(x.total_amount || 0), 0)
  const who = shift?.operator?.full_name || shift?.operator?.short_name || 'Смена'

  const footer = open && !loading && !err ? (
    !confirmClose ? (
      <Button type="button" className="w-full" onClick={() => { setConfirmClose(true); setCloseErr(null) }}>
        <Clock className="h-4 w-4" /> Закрыть смену и отправить отчёт
      </Button>
    ) : (
      <div className="w-full space-y-2">
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 px-3 py-2.5 text-sm text-amber-800 dark:text-amber-100">
          Смена закроется, а выручка попадёт в <b>Доходы</b>:
          <div className="mt-1 tabular-nums">
            Нал {fmtMoney(cashTotal)} · Безнал {fmtMoney(kaspiTotal)} · <b>Итого {fmtMoney(salesTotal)}</b>
          </div>
        </div>
        <input
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Причина / комментарий (необязательно)"
          className="w-full rounded-xl border border-border bg-white px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-amber-500 dark:bg-white/5"
        />
        {closeErr && <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-700 dark:text-rose-200">{closeErr}</div>}
        <div className="flex gap-2">
          <Button type="button" variant="outline" className="flex-1" disabled={closing} onClick={() => setConfirmClose(false)}>Отмена</Button>
          {can('shifts-reports.close_force') && (
            <Button type="button" className="flex-1" disabled={closing} onClick={handleForceClose}>
              {closing ? <Loader2 className="h-4 w-4 animate-spin" /> : null} Закрыть смену
            </Button>
          )}
        </div>
      </div>
    )
  ) : undefined

  return (
    <>
      <AppModal
        open
        onClose={onClose}
        maxWidth="max-w-2xl"
        icon={<Clock className="h-5 w-5" />}
        title={
          <span className="flex flex-wrap items-center gap-2">
            {who}
            <StoreChip tone={open ? 'emerald' : 'neutral'}>{open ? 'Открыта' : 'Закрыта'}</StoreChip>
          </span>
        }
        description={
          <>
            {dt(shift?.opened_at)} → {dt(shift?.closed_at)}
            {/* Ссылка в разбор эффективности: здесь видно, что продали,
                а там — спрос это был или работа продавца. */}
            {shift?.opened_at ? (
              <Link
                href={`/sales-kpi?from=${String(shift.opened_at).slice(0, 10)}&to=${String(shift.opened_at).slice(0, 10)}`}
                className="ml-2 inline-flex items-center gap-1 text-sky-600 hover:underline dark:text-sky-400"
              >
                <TrendingUp className="h-3 w-3" /> Разбор эффективности
              </Link>
            ) : null}
          </>
        }
        footer={footer}
      >
        {loading ? (
          <div className="space-y-3">
            <StatGridSkeleton count={4} />
            <TableSkeleton rows={6} cols={3} />
          </div>
        ) : err ? (
          <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-200">{err}</div>
        ) : (
          <div className="space-y-5">
            <StoreStatGrid cols={4}>
              <StoreStat label="Продажи" value={fmtMoney(salesTotal)} tone={salesTotal > 0 ? 'emerald' : 'neutral'} icon={<TrendingUp className="h-4 w-4" />} />
              <StoreStat label="Наличные" value={fmtMoney(open ? cashTotal : (shift?.closing_cash ?? cashTotal))} icon={<Wallet className="h-4 w-4" />} />
              <StoreStat label="Безнал" value={fmtMoney(open ? kaspiTotal : (shift?.closing_kaspi ?? kaspiTotal))} tone="sky" icon={<CreditCard className="h-4 w-4" />} />
              <StoreStat label="Возвраты" value={fmtMoney(returnsTotal)} tone={returnsTotal > 0 ? 'rose' : 'neutral'} icon={<RotateCcw className="h-4 w-4" />} />
            </StoreStatGrid>
            {open && (
              <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/[0.06] px-3 py-2 text-xs text-emerald-700 dark:text-emerald-200">
                Смена открыта — суммы по продажам в реальном времени. Наличные и безнал зафиксируются при закрытии.
              </div>
            )}

            <div>
              <div className="mb-2 flex items-center justify-between">
                <span className="text-sm font-semibold text-foreground">Продажи</span>
                <StoreChip>{sales.length}</StoreChip>
              </div>
              {sales.length === 0 ? (
                <div className="rounded-xl border border-border bg-slate-50 px-4 py-8 text-center text-sm text-muted-foreground dark:bg-white/[0.02]">За смену продаж не было</div>
              ) : (
                <div className="divide-y divide-border overflow-hidden rounded-xl border border-border">
                  {sales.map((s) => {
                    const items = (s.items || []) as any[]
                    const names = items.map((it) => (it.item?.name || it.universal_name)).filter(Boolean)
                    return (
                      <button
                        key={s.id}
                        type="button"
                        onClick={() => setReceiptSaleId(String(s.id))}
                        title="Открыть чек продажи"
                        className="flex w-full items-center gap-3 px-3 py-2.5 text-left transition-colors hover:bg-slate-50 dark:hover:bg-white/5"
                      >
                        <div className="w-10 shrink-0 text-xs tabular-nums text-muted-foreground">{tm(s.sold_at)}</div>
                        <StoreChip tone={PAY_TONE[s.payment_method] || 'neutral'}>{PAY_LABEL[s.payment_method] || s.payment_method}</StoreChip>
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-sm text-foreground">{names.length > 0 ? names.join(', ') : `${items.length} позиц.`}</div>
                          {s.operator?.full_name || s.operator?.short_name ? <div className="text-[11px] text-muted-foreground">{s.operator?.full_name || s.operator?.short_name}</div> : null}
                        </div>
                        <div className="shrink-0 text-sm font-semibold tabular-nums text-foreground">{fmtMoney(Number(s.total_amount || 0))}</div>
                      </button>
                    )
                  })}
                </div>
              )}
            </div>

            {returns.length > 0 && (
              <div>
                <div className="mb-2 text-sm font-semibold text-foreground">Возвраты</div>
                <div className="divide-y divide-border overflow-hidden rounded-xl border border-rose-500/20">
                  {returns.map((r) => (
                    <div key={r.id} className="flex items-center justify-between gap-3 px-3 py-2.5">
                      <div className="text-xs text-muted-foreground">{tm(r.returned_at)} · {PAY_LABEL[r.payment_method] || r.payment_method}{r.comment ? ` · ${r.comment}` : ''}</div>
                      <div className="shrink-0 text-sm font-semibold tabular-nums text-rose-600 dark:text-rose-300">−{fmtMoney(Number(r.total_amount || 0))}</div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </AppModal>

      {/* Чек продажи по клику — как в операторской (публичная страница /r/<saleId>) */}
      <AppModal
        open={!!receiptSaleId}
        onClose={() => setReceiptSaleId(null)}
        title="Чек продажи"
        maxWidth="max-w-md"
        footer={
          <>
            <Button type="button" variant="outline" onClick={() => setReceiptSaleId(null)}>Закрыть</Button>
            <Button
              type="button"
              onClick={() => { try { (document.getElementById('shift-sale-receipt-iframe') as HTMLIFrameElement | null)?.contentWindow?.print() } catch { /* noop */ } }}
            >
              <Printer className="h-4 w-4" /> Печать
            </Button>
          </>
        }
      >
        {receiptSaleId && (
          <iframe
            id="shift-sale-receipt-iframe"
            src={`/r/${receiptSaleId}`}
            title="Чек продажи"
            className="h-[60vh] w-full rounded-lg border-0 bg-white"
          />
        )}
      </AppModal>
    </>
  )
}
