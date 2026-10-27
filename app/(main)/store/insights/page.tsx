'use client'

import { useEffect, useState } from 'react'
import {
  Sparkles, Loader2, RefreshCw, Coins, Skull, TrendingUp, TrendingDown, AlertTriangle, Boxes, Snail, Trophy, Siren,
} from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { StoreChip, StoreStat, StoreStatGrid, StoreSection, storeCard } from '@/components/store/store-ui'

type InsightProduct = {
  item_id: string; name: string; soldQty: number; revenue: number; profit: number
  marginPct: number; velocityPerWeek: number; purchase: number; salePrice: number
  stock: number; stockValue: number; coverageWeeks: number; trendPct: number
}
type DeadStockRow = { item_id: string; name: string; stock: number; purchase: number; stockValue: number }
type SlowRow = { item_id: string; name: string; soldQty: number; velocityPerWeek: number; marginPct: number; profit: number; stock: number; stockValue: number }
type TrendRow = { item_id: string; name: string; trendPct: number; recentQty: number; earlierQty: number; revenue: number }
type LossRow = { item_id: string; name: string; qty: number; purchase: number; lossValue: number }
type Metrics = {
  organizationId: string | null
  days: number
  totals: { totalRevenue: number; totalProfit: number; deadStockValue: number; lossesValue: number; skuSold: number; skuDead: number }
  topProfit: InsightProduct[]
  deadStock: DeadStockRow[]
  slowLowMargin: SlowRow[]
  trending: { rising: TrendRow[]; falling: TrendRow[] }
  losses: { value: number; rows: LossRow[] }
}
type Resp = { ok: boolean; metrics: Metrics; aiText: string | null; error?: string }

const fmt = (v: number) => new Intl.NumberFormat('ru-RU').format(Math.round(v || 0))
const money = (v: number) => fmt(v) + ' ₸'
const COVERAGE_INF = 999

const PERIODS = [
  { value: 7, label: '7 дней' },
  { value: 30, label: '30 дней' },
  { value: 90, label: '90 дней' },
]

const C = {
  card: 'bg-white dark:bg-slate-900/60 shadow-sm',
  border: 'border-border',
  sub: 'text-muted-foreground',
}

function Section({ title, icon, hint, children, count }: { title: string; icon: React.ReactNode; hint?: string; children: React.ReactNode; count?: number }) {
  return (
    <StoreSection
      title={title}
      subtitle={hint}
      icon={icon}
      flush
      action={typeof count === 'number' ? <StoreChip>{count}</StoreChip> : undefined}
    >
      {children}
    </StoreSection>
  )
}

function marginColor(pct: number) {
  if (pct >= 30) return 'text-emerald-600 dark:text-emerald-400'
  if (pct >= 15) return 'text-foreground'
  return 'text-rose-600 dark:text-rose-400'
}
function trendColor(pct: number) {
  return pct >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-rose-600 dark:text-rose-400'
}

const thCls = 'px-4 py-2 text-left text-[11px] font-medium uppercase tracking-wide text-muted-foreground'
const thR = thCls + ' text-right'
const tdCls = 'px-4 py-2.5 text-sm text-body'
const tdR = 'px-4 py-2.5 text-right text-sm tabular-nums text-body'
const nameCls = 'px-4 py-2.5 text-sm font-medium text-foreground max-w-[14rem] truncate sm:max-w-[18rem]'

function EmptyRow({ cols, text }: { cols: number; text: string }) {
  return (
    <tr>
      <td colSpan={cols} className={`px-4 py-8 text-center text-sm ${C.sub}`}>{text}</td>
    </tr>
  )
}

export default function StoreInsightsPage() {
  const [days, setDays] = useState(30)
  const [data, setData] = useState<Resp | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = async (d: number) => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetch(`/api/ai/store-insights?days=${d}`, { cache: 'no-store' })
      const j = (await res.json().catch(() => null)) as Resp | null
      if (!res.ok || !j?.ok) {
        setError(j?.error || 'Не удалось построить разбор')
        setData(null)
      } else {
        setData(j)
      }
    } catch {
      setError('Сеть недоступна')
      setData(null)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load(days) /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [days])

  const metrics = data?.metrics
  const t = metrics?.totals

  return (
    <div className="app-page-wide space-y-5">
      <AdminPageHeader
        title="AI-разбор магазина"
        description="Что приносит деньги, где они заморожены и где теряются — разбор по кнопке, цифры посчитаны кодом"
        accent="violet"
        backHref="/store"
        icon={<Sparkles className="h-5 w-5" />}
        actions={
          <Button variant="outline" size="sm" onClick={() => load(days)} disabled={loading}>
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} /> Обновить
          </Button>
        }
        toolbar={
          <div className="inline-flex gap-1 rounded-2xl border border-border bg-slate-50 p-1 dark:bg-slate-950/50">
            {PERIODS.map((p) => (
              <button
                key={p.value}
                onClick={() => setDays(p.value)}
                className={`rounded-xl px-3 py-2 text-sm font-medium transition-all ${
                  days === p.value
                    ? 'bg-white text-foreground shadow-sm ring-1 ring-slate-200 dark:bg-white/10 dark:ring-white/10'
                    : 'text-muted-foreground hover:text-foreground'
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>
        }
      />

      {loading && !data ? (
        <div className={`${storeCard} flex items-center justify-center gap-2 py-16 text-sm text-muted-foreground`}>
          <Loader2 className="h-4 w-4 animate-spin" /> Считаю продажи, остатки и маржу…
        </div>
      ) : error ? (
        <div className="rounded-2xl border border-rose-500/30 bg-rose-500/10 px-4 py-6 text-sm text-rose-700 dark:text-rose-200">
          {error}
        </div>
      ) : !metrics || !t ? null : (
        <>
          {/* KPI */}
          <StoreStatGrid cols={4}>
            <StoreStat label="Выручка" value={money(t.totalRevenue)} tone={t.totalRevenue > 0 ? 'emerald' : 'neutral'} icon={<Coins className="h-4 w-4" />} hint={`${t.skuSold} позиций продавалось`} />
            <StoreStat label="Валовая прибыль" value={money(t.totalProfit)} tone={t.totalProfit > 0 ? 'emerald' : t.totalProfit < 0 ? 'rose' : 'neutral'} icon={<TrendingUp className="h-4 w-4" />} hint={t.totalRevenue > 0 ? `маржа ${Math.round((t.totalProfit / t.totalRevenue) * 100)}%` : undefined} />
            <StoreStat label="Мёртвый груз" value={money(t.deadStockValue)} tone={t.deadStockValue > 0 ? 'amber' : 'neutral'} icon={<Skull className="h-4 w-4" />} hint={`${t.skuDead} позиций без продаж`} />
            <StoreStat label="Потери" value={money(t.lossesValue)} tone={t.lossesValue > 0 ? 'rose' : 'neutral'} icon={<AlertTriangle className="h-4 w-4" />} hint="списания и недостачи" />
          </StoreStatGrid>

          {/* AI-вывод */}
          <div className="rounded-2xl border border-violet-200 bg-violet-50/60 p-4 dark:border-violet-400/20 dark:bg-violet-500/[0.07] sm:p-5">
            <div className="mb-2 flex items-center gap-2 text-sm font-semibold text-violet-700 dark:text-violet-200">
              <Sparkles className="h-4 w-4" /> AI-вывод
            </div>
            {loading ? (
              <div className={`flex items-center gap-2 text-sm ${C.sub}`}>
                <Loader2 className="h-4 w-4 animate-spin" /> Анализирую…
              </div>
            ) : data?.aiText ? (
              <p className="whitespace-pre-wrap text-sm leading-relaxed text-body">{data.aiText}</p>
            ) : (
              <p className={`text-sm ${C.sub}`}>
                AI-вердикт недоступен (нет ключа OpenAI или сервис не ответил). Цифры ниже посчитаны и точны.
              </p>
            )}
          </div>

          {/* Звёзды прибыли */}
          <Section title="Звёзды прибыли" icon={<Trophy className="h-4 w-4 text-emerald-500" />} hint="Что кормит магазин — по валовой прибыли за период" count={metrics.topProfit.length}>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className={`border-b ${C.border}`}>
                    <th className={thCls}>Товар</th>
                    <th className={thR}>Прибыль</th>
                    <th className={thR}>Маржа</th>
                    <th className={thR}>Продано</th>
                    <th className={thR}>Выручка</th>
                    <th className={thR}>Скорость/нед</th>
                  </tr>
                </thead>
                <tbody className={`divide-y ${C.border}`}>
                  {metrics.topProfit.length ? metrics.topProfit.map((p) => (
                    <tr key={p.item_id}>
                      <td className={nameCls} title={p.name}>{p.name}</td>
                      <td className={`${tdR} font-semibold text-emerald-600 dark:text-emerald-400`}>{money(p.profit)}</td>
                      <td className={`${tdR} font-medium ${marginColor(p.marginPct)}`}>{p.marginPct}%</td>
                      <td className={tdR}>{fmt(p.soldQty)}</td>
                      <td className={tdR}>{money(p.revenue)}</td>
                      <td className={tdR}>{p.velocityPerWeek}</td>
                    </tr>
                  )) : <EmptyRow cols={6} text="Продаж за период нет" />}
                </tbody>
              </table>
            </div>
          </Section>

          {/* Мёртвый груз */}
          <Section title="Мёртвый груз" icon={<Skull className="h-4 w-4 text-amber-500" />} hint="Есть остаток, но 0 продаж за период — заморожены деньги" count={metrics.deadStock.length}>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className={`border-b ${C.border}`}>
                    <th className={thCls}>Товар</th>
                    <th className={thR}>Заморожено ₸</th>
                    <th className={thR}>Остаток</th>
                    <th className={thR}>Закуп</th>
                  </tr>
                </thead>
                <tbody className={`divide-y ${C.border}`}>
                  {metrics.deadStock.length ? metrics.deadStock.map((p) => (
                    <tr key={p.item_id}>
                      <td className={nameCls} title={p.name}>{p.name}</td>
                      <td className={`${tdR} font-semibold text-amber-600 dark:text-amber-400`}>{money(p.stockValue)}</td>
                      <td className={tdR}>{fmt(p.stock)}</td>
                      <td className={tdR}>{money(p.purchase)}</td>
                    </tr>
                  )) : <EmptyRow cols={4} text="Мёртвого груза нет — всё движется" />}
                </tbody>
              </table>
            </div>
          </Section>

          {/* Медленные + низкая маржа */}
          <Section title="Медленные + низкая маржа" icon={<Snail className="h-4 w-4" />} hint="Продаются вяло и почти без наценки — кандидаты убрать или поднять цену" count={metrics.slowLowMargin.length}>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className={`border-b ${C.border}`}>
                    <th className={thCls}>Товар</th>
                    <th className={thR}>Маржа</th>
                    <th className={thR}>Скорость/нед</th>
                    <th className={thR}>Прибыль</th>
                    <th className={thR}>Остаток ₸</th>
                  </tr>
                </thead>
                <tbody className={`divide-y ${C.border}`}>
                  {metrics.slowLowMargin.length ? metrics.slowLowMargin.map((p) => (
                    <tr key={p.item_id}>
                      <td className={nameCls} title={p.name}>{p.name}</td>
                      <td className={`${tdR} font-medium ${marginColor(p.marginPct)}`}>{p.marginPct}%</td>
                      <td className={tdR}>{p.velocityPerWeek}</td>
                      <td className={tdR}>{money(p.profit)}</td>
                      <td className={tdR}>{money(p.stockValue)}</td>
                    </tr>
                  )) : <EmptyRow cols={5} text="Нет проблемных позиций" />}
                </tbody>
              </table>
            </div>
          </Section>

          {/* Тренды */}
          <div className="grid gap-5 lg:grid-cols-2">
            <Section title="Растут" icon={<TrendingUp className="h-4 w-4 text-emerald-500" />} hint="Вторая половина периода против первой" count={metrics.trending.rising.length}>
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className={`border-b ${C.border}`}>
                      <th className={thCls}>Товар</th>
                      <th className={thR}>Тренд</th>
                      <th className={thR}>Было→Стало</th>
                    </tr>
                  </thead>
                  <tbody className={`divide-y ${C.border}`}>
                    {metrics.trending.rising.length ? metrics.trending.rising.map((p) => (
                      <tr key={p.item_id}>
                        <td className={nameCls} title={p.name}>{p.name}</td>
                        <td className={`${tdR} font-medium ${trendColor(p.trendPct)}`}>+{p.trendPct}%</td>
                        <td className={tdR}>{fmt(p.earlierQty)}→{fmt(p.recentQty)}</td>
                      </tr>
                    )) : <EmptyRow cols={3} text="Нет растущих" />}
                  </tbody>
                </table>
              </div>
            </Section>

            <Section title="Падают" icon={<TrendingDown className="h-4 w-4 text-rose-500" />} hint="Вторая половина периода против первой" count={metrics.trending.falling.length}>
              <div className="overflow-x-auto">
                <table className="w-full">
                  <thead>
                    <tr className={`border-b ${C.border}`}>
                      <th className={thCls}>Товар</th>
                      <th className={thR}>Тренд</th>
                      <th className={thR}>Было→Стало</th>
                    </tr>
                  </thead>
                  <tbody className={`divide-y ${C.border}`}>
                    {metrics.trending.falling.length ? metrics.trending.falling.map((p) => (
                      <tr key={p.item_id}>
                        <td className={nameCls} title={p.name}>{p.name}</td>
                        <td className={`${tdR} font-medium ${trendColor(p.trendPct)}`}>{p.trendPct}%</td>
                        <td className={tdR}>{fmt(p.earlierQty)}→{fmt(p.recentQty)}</td>
                      </tr>
                    )) : <EmptyRow cols={3} text="Нет падающих" />}
                  </tbody>
                </table>
              </div>
            </Section>
          </div>

          {/* Потери */}
          <Section title="Потери" icon={<Siren className="h-4 w-4 text-rose-500" />} hint="Списания и инвентаризация за период (в закупочных ₸)" count={metrics.losses.rows.length}>
            <div className="overflow-x-auto">
              <table className="w-full">
                <thead>
                  <tr className={`border-b ${C.border}`}>
                    <th className={thCls}>Товар</th>
                    <th className={thR}>Потеря ₸</th>
                    <th className={thR}>Кол-во</th>
                    <th className={thR}>Закуп</th>
                  </tr>
                </thead>
                <tbody className={`divide-y ${C.border}`}>
                  {metrics.losses.rows.length ? metrics.losses.rows.map((p) => (
                    <tr key={p.item_id}>
                      <td className={nameCls} title={p.name}>{p.name}</td>
                      <td className={`${tdR} font-semibold text-rose-600 dark:text-rose-400`}>{money(p.lossValue)}</td>
                      <td className={tdR}>{fmt(p.qty)}</td>
                      <td className={tdR}>{money(p.purchase)}</td>
                    </tr>
                  )) : <EmptyRow cols={4} text="Потерь за период нет" />}
                </tbody>
              </table>
            </div>
          </Section>

          <p className={`flex items-center gap-1.5 text-xs ${C.sub}`}>
            <Boxes className="h-3.5 w-3.5" /> За {metrics.days} дней продавалось {t.skuSold} позиций. Прибыль = продано × (цена − закуп). Заморожено = остаток × закуп.
          </p>
        </>
      )}
    </div>
  )
}
