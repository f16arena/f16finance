'use client'

import { useEffect, useMemo, useState } from 'react'
import { ShoppingCart, Loader2, Sparkles, Save, TrendingUp, TrendingDown, Calculator } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { StoreChip, StoreEmpty, StoreSection, StoreStat, StoreStatGrid, plural, storeCard } from '@/components/store/store-ui'
import { Input } from '@/components/ui/input'
import { NativeSelect } from '@/components/ui/native-select'
import { useStoreScope } from '@/components/store/store-scope'
import { formatMoney as money } from '@/lib/core/format'

type Line = {
  item_id: string
  name: string
  barcode: string
  weeklyDemand: number
  trendPct: number
  stock: number
  order: number
  unitCost: number
  amount: number
  salePrice?: number
  marginPct?: number
  coverageWeeks?: number
  wasOutOfStock?: boolean
  packSize?: number
  packs?: number
}

type SupplierGroup = {
  supplier: string
  total: number
  items: Line[]
}

type SkipLine = {
  item_id: string
  name: string
  stock: number
  weeklyDemand: number
  coverageWeeks: number
}

type PlanData = {
  company_id: string
  weekStart: string
  generatedAt: string
  total: number
  revenue4wPerWeek: number
  bySupplier: SupplierGroup[]
  doNotBuy?: SkipLine[]
}

type Company = { id: string; name: string; code?: string | null }

const num = (n: number) => {
  const v = Number(n) || 0
  return Number.isInteger(v) ? String(v) : v.toFixed(2)
}

export default function PurchasePlanPage() {
  const { can } = useCapabilities()
  const { storeCompanyId } = useStoreScope()

  const [companies, setCompanies] = useState<Company[]>([])
  const [companyId, setCompanyId] = useState<string>('')
  const [plan, setPlan] = useState<PlanData | null>(null)
  // Отредактированные количества: item_id -> order
  const [edits, setEdits] = useState<Record<string, number>>({})
  // Размер упаковки (переопределение/сохранение): item_id -> pack_size
  const [packEdits, setPackEdits] = useState<Record<string, number>>({})

  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [aiText, setAiText] = useState<string | null>(null)
  const [aiLoading, setAiLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  // Список точек.
  useEffect(() => {
    let active = true
    fetch('/api/admin/companies?open=1', { cache: 'no-store' })
      .then((r) => r.json())
      .then((j) => {
        if (!active) return
        const list = (Array.isArray(j?.data) ? j.data : []) as Company[]
        setCompanies(list)
        // Если магазин залочен на одну точку — выбрать её.
        if (storeCompanyId && list.some((c) => c.id === storeCompanyId)) setCompanyId(storeCompanyId)
        else if (list.length === 1) setCompanyId(list[0].id)
      })
      .catch(() => { if (active) setCompanies([]) })
    return () => { active = false }
  }, [storeCompanyId])

  const calculate = async () => {
    if (!companyId) { setError('Выберите точку'); return }
    setLoading(true)
    setError(null)
    setSuccess(null)
    setAiText(null)
    setPlan(null)
    setEdits({})
    try {
      const res = await fetch(`/api/admin/store/purchase-plan/suggest?company_id=${encodeURIComponent(companyId)}`, { cache: 'no-store' })
      const json = await res.json().catch(() => null)
      if (!res.ok || !json?.ok) throw new Error(json?.error || 'Не удалось рассчитать план')
      setPlan(json.data as PlanData)
    } catch (err: any) {
      setError(err?.message || 'Ошибка расчёта')
    } finally {
      setLoading(false)
    }
  }

  const orderOf = (line: Line) => {
    const e = edits[line.item_id]
    return e === undefined ? line.order : e
  }

  const setOrder = (itemId: string, value: string) => {
    const n = Math.max(0, Math.floor(Number(value) || 0))
    setEdits((prev) => ({ ...prev, [itemId]: n }))
  }

  // Изменить размер упаковки: пересчитать локально + сохранить на сервере.
  const savePackSize = (itemId: string, value: string) => {
    const n = Math.max(1, Math.floor(Number(value) || 1))
    setPackEdits((prev) => ({ ...prev, [itemId]: n }))
    // снять ручную правку количества — пусть пересчитается по новой упаковке
    setEdits((prev) => { const c = { ...prev }; delete c[itemId]; return c })
    void fetch('/api/admin/store/purchase-plan/pack-size', {
      method: 'PATCH', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ item_id: itemId, pack_size: n }),
    }).catch(() => {})
  }

  // Итоги с учётом ручных правок.
  const computed = useMemo(() => {
    if (!plan) return null
    const groups = plan.bySupplier.map((g) => {
      const items = g.items.map((it) => {
        const effPack = packEdits[it.item_id] ?? (it.packSize || 1)
        const manual = edits[it.item_id]
        let order: number
        let packs: number
        if (manual !== undefined) {
          order = manual
          packs = effPack > 0 ? Math.ceil(order / effPack) : order
        } else {
          const need = Math.max(0, it.weeklyDemand * 2 - it.stock)
          packs = Math.ceil(need / effPack)
          order = packs * effPack
        }
        return { ...it, _order: order, _packs: packs, _packSize: effPack, _amount: order * it.unitCost }
      })
      const total = items.reduce((s, it) => s + it._amount, 0)
      return { supplier: g.supplier, items, total }
    })
    const total = groups.reduce((s, g) => s + g.total, 0)
    const positions = groups.reduce((s, g) => s + g.items.filter((it) => it._order > 0).length, 0)
    return { groups, total, positions }
  }, [plan, edits, packEdits])

  const askAi = async () => {
    if (!companyId) return
    setAiLoading(true)
    setAiText(null)
    setError(null)
    try {
      const res = await fetch('/api/ai/purchase-plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ company_id: companyId }),
      })
      const json = await res.json().catch(() => null)
      if (!res.ok || !json?.ok) throw new Error(json?.error || 'AI-совет недоступен')
      setAiText(String(json.text || ''))
    } catch (err: any) {
      setError(err?.message || 'AI-совет недоступен')
    } finally {
      setAiLoading(false)
    }
  }

  const savePlan = async () => {
    if (!plan || !computed) return
    const rows: Array<{ supplier: string; item: Line; order: number }> = []
    for (const g of computed.groups) {
      for (const it of g.items) {
        if (it._order > 0) rows.push({ supplier: g.supplier, item: it, order: it._order })
      }
    }
    if (rows.length === 0) { setError('Нечего сохранять — нет позиций к закупу'); return }

    setSaving(true)
    setError(null)
    setSuccess(null)
    try {
      let saved = 0
      for (const row of rows) {
        const res = await fetch('/api/admin/purchase-plan', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            company_id: companyId,
            week_start: plan.weekStart,
            day_of_week: 1, // понедельник недели плана
            title: row.item.name,
            supplier: row.supplier === '—' ? null : row.supplier,
            quantity: row.order,
            amount: row.order * row.item.unitCost,
            comment: 'Авторасчёт: план закупа',
          }),
        })
        const json = await res.json().catch(() => null)
        if (!res.ok || !json?.ok) throw new Error(json?.error || 'Не удалось сохранить позицию')
        saved += 1
      }
      setSuccess(`Сохранено позиций: ${saved}. Открыть в недельном плане закупа.`)
    } catch (err: any) {
      setError(err?.message || 'Не удалось сохранить план')
    } finally {
      setSaving(false)
    }
  }

  const fmtDate = (iso: string) => {
    try { return new Date(iso + 'T00:00:00Z').toLocaleDateString('ru-RU') } catch { return iso }
  }

  return (
    <div className="app-page-wide space-y-5">
      <AdminPageHeader
        title="План закупа"
        description="Сколько закупить на следующую неделю — по продажам и остаткам, с запасом на 2 недели"
        icon={<ShoppingCart className="h-5 w-5" />}
        accent="emerald"
        backHref="/store"
      />

      {/* Панель управления */}
      <div className={`${storeCard} p-4`}>
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-full sm:w-auto sm:min-w-[220px] flex-1 space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">Точка</label>
            <NativeSelect value={companyId} onChange={(e) => setCompanyId(e.target.value)}>
              <option value="" disabled>Выберите точку</option>
              {companies.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </NativeSelect>
          </div>
          <Button onClick={calculate} disabled={loading || !companyId} className="bg-blue-600 hover:bg-blue-500">
            {loading ? <Loader2 className="h-4 w-4 animate-spin mr-1.5" /> : <Calculator className="h-4 w-4 mr-1.5" />}
            Рассчитать
          </Button>
          {plan ? (
            <>
              {can('store-purchase-plan.ai_advice') && (
              <Button variant="outline" onClick={askAi} disabled={aiLoading}>
                {aiLoading ? <Loader2 className="h-4 w-4 animate-spin mr-1.5" /> : <Sparkles className="h-4 w-4 mr-1.5" />}
                AI-совет
              </Button>
              )}
              {can('store-purchase-plan.create') && (
              <Button variant="outline" onClick={savePlan} disabled={saving}>
                {saving ? <Loader2 className="h-4 w-4 animate-spin mr-1.5" /> : <Save className="h-4 w-4 mr-1.5" />}
                Сохранить план
              </Button>
              )}
            </>
          ) : null}
        </div>
        <p className="mt-3 text-xs text-muted-foreground">
          Спрос — средние продажи за 4 недели. Цель — запас на 2 недели. К закупу = цель − остаток (округление вверх). Цена — из последней приёмки товара.
        </p>
      </div>

      {error ? (
        <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-sm text-rose-700 dark:text-rose-200">{error}</div>
      ) : null}
      {success ? (
        <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3 text-sm text-emerald-700 dark:text-emerald-200">{success}</div>
      ) : null}

      {aiText ? (
        <div className="rounded-2xl border border-emerald-500/30 bg-emerald-500/[0.06] p-4">
          <div className="flex items-center gap-2 mb-2 text-sm font-semibold text-emerald-700 dark:text-emerald-200">
            <Sparkles className="h-4 w-4" /> AI-совет по закупу
          </div>
          <p className="whitespace-pre-wrap text-sm leading-relaxed text-body">{aiText}</p>
        </div>
      ) : null}

      {/* Состояния */}
      {loading && !plan ? (
        <div className="flex items-center justify-center gap-2 py-16 text-slate-400">
          <Loader2 className="h-5 w-5 animate-spin" /> Считаем спрос и остатки…
        </div>
      ) : null}

      {plan && computed ? (
        plan.bySupplier.length === 0 ? (
          <div className={storeCard}>
            <StoreEmpty
              title="Докупать нечего"
              hint="Остатков хватает на следующую неделю, либо за последние 4 недели не было продаж — по ним план не строится."
            />
          </div>
        ) : (
          <div className="space-y-5">
            {/* Итог */}
            <div className={`${storeCard} p-4`}>
              <div className="text-xs font-medium uppercase tracking-wider text-slate-500">Закупить на неделю с {fmtDate(plan.weekStart)}</div>
              <div className="mt-1 text-3xl font-bold tabular-nums tracking-tight text-foreground">{money(computed.total)}</div>
              <div className="mt-3">
                <StoreStatGrid cols={3}>
                  <StoreStat label="Поставщиков" value={computed.groups.length} />
                  <StoreStat label="Позиций" value={computed.positions} />
                  <StoreStat label="Выручка за 7 дней" value={plan.revenue4wPerWeek > 0 ? money(plan.revenue4wPerWeek) : '—'} hint="для сравнения" />
                </StoreStatGrid>
              </div>
            </div>

            {/* По поставщикам */}
            {computed.groups.map((g) => (
              <StoreSection
                key={g.supplier}
                flush
                title={g.supplier === '—' ? 'Поставщик не указан' : g.supplier}
                subtitle={`${g.items.length} ${plural(g.items.length, ['позиция', 'позиции', 'позиций'])}`}
                action={<span className="text-base font-bold tabular-nums text-foreground">{money(g.total)}</span>}
              >
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[760px] text-sm">
                    <thead>
                      <tr className="text-xs uppercase tracking-wide text-muted-foreground border-b border-border">
                        <th className="text-left font-medium px-4 py-2">Товар</th>
                        <th className="text-right font-medium px-3 py-2">~ в нед.</th>
                        <th className="text-right font-medium px-3 py-2">В наличии</th>
                        <th className="text-right font-medium px-3 py-2">Купить</th>
                        <th className="text-right font-medium px-3 py-2">Цена</th>
                        <th className="text-right font-medium px-3 py-2">Маржа</th>
                        <th className="text-right font-medium px-4 py-2">Сумма</th>
                      </tr>
                    </thead>
                    <tbody>
                      {g.items.map((it) => (
                        <tr key={it.item_id} className="border-b border-slate-100 dark:border-white/5 last:border-0">
                          <td className="px-4 py-2">
                            <div className="flex items-center gap-1.5">
                              <span className="font-medium text-foreground">{it.name}</span>
                              {it.wasOutOfStock ? <span title="Сейчас в нуле — реальный спрос мог быть выше"><StoreChip tone="amber">был в нуле</StoreChip></span> : null}
                            </div>
                            {it.barcode ? <div className="text-[11px] text-slate-400 tabular-nums">{it.barcode}</div> : null}
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums">
                            <div className="inline-flex items-center justify-end gap-1">
                              <span className="text-body">{num(it.weeklyDemand)}</span>
                              {it.trendPct >= 5 ? (
                                <span className="inline-flex items-center text-emerald-600 dark:text-emerald-400 text-[11px]">
                                  <TrendingUp className="h-3 w-3" />{Math.round(it.trendPct)}%
                                </span>
                              ) : it.trendPct <= -5 ? (
                                <span className="inline-flex items-center text-rose-600 dark:text-rose-400 text-[11px]">
                                  <TrendingDown className="h-3 w-3" />{Math.round(it.trendPct)}%
                                </span>
                              ) : null}
                            </div>
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums text-body">{num(it.stock)}</td>
                          <td className="px-3 py-2 text-right">
                            <Input
                              type="number"
                              min={0}
                              value={String(it._order)}
                              onChange={(e) => setOrder(it.item_id, e.target.value)}
                              className="h-8 w-20 text-right tabular-nums ml-auto"
                            />
                            <div className="mt-1 flex items-center justify-end gap-1 text-[10px] text-slate-400">
                              <span title="Размер упаковки (шт в коробке)">уп:</span>
                              <input
                                type="number"
                                min={1}
                                value={String(it._packSize)}
                                onChange={(e) => savePackSize(it.item_id, e.target.value)}
                                className="w-9 rounded border border-border bg-transparent px-1 py-0.5 text-right tabular-nums"
                              />
                              {it._packSize > 1 ? <span className="text-muted-foreground">= {it._packs} кор</span> : null}
                            </div>
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums text-body">{money(it.unitCost)}</td>
                          <td className="px-3 py-2 text-right tabular-nums">
                            {it.marginPct != null && it.marginPct !== 0 ? (
                              <span className={it.marginPct >= 25 ? 'text-emerald-600 dark:text-emerald-400' : it.marginPct >= 10 ? 'text-amber-600 dark:text-amber-400' : 'text-rose-600 dark:text-rose-400'}>{Math.round(it.marginPct)}%</span>
                            ) : <span className="text-slate-400">—</span>}
                          </td>
                          <td className="px-4 py-2 text-right tabular-nums font-medium text-foreground">{money(it._amount)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </StoreSection>
            ))}

            {/* Не бери — затоваренные позиции (остатка хватит надолго) */}
            {plan.doNotBuy && plan.doNotBuy.length > 0 ? (
              <StoreSection
                flush
                title={`Не бери — затоварено (${plan.doNotBuy.length})`}
                subtitle="Остатка хватит надолго — деньги заморожены, лучше распродать."
              >
                <div className="divide-y divide-border">
                  {plan.doNotBuy.map((s) => (
                    <div key={s.item_id} className="flex items-center justify-between gap-3 px-4 py-2 text-sm">
                      <span className="min-w-0 truncate text-body">{s.name}</span>
                      <div className="flex shrink-0 items-center gap-4 tabular-nums text-xs">
                        <span className="text-muted-foreground">в наличии {num(s.stock)} · ~{num(s.weeklyDemand)}/нед</span>
                        <span className="font-medium text-sky-600 dark:text-sky-400">хватит на {s.coverageWeeks >= 99 ? '∞' : `~${num(s.coverageWeeks)} нед`}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </StoreSection>
            ) : null}
          </div>
        )
      ) : null}
    </div>
  )
}
