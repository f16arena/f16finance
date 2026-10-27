'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { Building2, ChevronRight, Loader2, Plus, Search, Wallet } from 'lucide-react'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { useStoreApiUrl, useStoreScope } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { AppModal } from '@/components/ui/app-modal'
import { StoreChip, StoreEmpty, StoreStat, StoreStatGrid, plural, storeCard } from '@/components/store/store-ui'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Skeleton } from '@/components/skeleton'
import { formatMoney } from '@/lib/core/format'
import { invalidateStoreCaches } from '@/lib/client/store-cache'

type Supplier = {
  id: string
  name: string
  organization_name: string | null
  bin_iin: string | null
  contact_name: string | null
  phone: string | null
  preferred_expense_category_id: string | null
  receipts_count: number
  receipts_total: number
  last_receipt_date: string | null
  open_debts_count: number
  open_debts_sum: number
  aliases_count: number
}

const fmtDate = (value: string | null | undefined) => {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleDateString('ru-RU')
  } catch {
    return String(value)
  }
}

export default function SuppliersListPage({ embedded = false }: { embedded?: boolean } = {}) {
  const { storeCompanyId } = useStoreScope()
  const storeUrl = useStoreApiUrl()
  const { can } = useCapabilities()
  const [suppliers, setSuppliers] = useState<Supplier[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [onlyDebt, setOnlyDebt] = useState(false)

  const [addOpen, setAddOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form, setForm] = useState({ name: '', organization_name: '', bin_iin: '', contact_name: '', phone: '', lead_time_days: '3' })

  const suppliersUrl = storeUrl('/api/admin/store/suppliers')
  const suppliersCacheKey = `${suppliersUrl}#suppliers`

  const load = useCallback(async (opts?: { fresh?: boolean }) => {
    const cached = opts?.fresh ? null : readApiCache<Supplier[]>(suppliersCacheKey)
    if (cached) { setSuppliers(cached); setLoading(false) }
    try {
      const response = await fetch(suppliersUrl, { cache: 'no-store' })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось загрузить поставщиков')
      const rows = json.data?.suppliers || []
      writeApiCache(suppliersCacheKey, rows)
      setSuppliers(rows)
      setError(null)
    } catch (err: any) {
      setError(err?.message || 'Ошибка загрузки')
    } finally {
      setLoading(false)
    }
  }, [suppliersUrl, suppliersCacheKey])

  useEffect(() => { void load() }, [load])

  const submitSupplier = async () => {
    if (!form.name.trim()) { setError('Введите название поставщика'); return }
    if (!storeCompanyId) { setError('Выберите точку-магазин — поставщик привязывается к точке'); return }
    const digits = form.bin_iin.replace(/\D/g, '')
    if (digits && !/^\d{12}$/.test(digits)) { setError('ИИН/БИН — 12 цифр'); return }
    setSaving(true); setError(null)
    try {
      const res = await fetch('/api/admin/store/suppliers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          company_id: storeCompanyId,
          name: form.name.trim(),
          organization_name: form.organization_name.trim() || form.name.trim(),
          bin_iin: digits || null,
          contact_name: form.contact_name.trim() || null,
          phone: form.phone.trim() || null,
          lead_time_days: form.lead_time_days,
        }),
      })
      const j = await res.json().catch(() => null)
      if (!res.ok || !j?.ok) throw new Error(j?.error || 'Не удалось создать поставщика')
      setAddOpen(false)
      setForm({ name: '', organization_name: '', bin_iin: '', contact_name: '', phone: '', lead_time_days: '3' })
      invalidateStoreCaches()
      await load({ fresh: true })
    } catch (e: any) {
      setError(e?.message || 'Ошибка создания')
    } finally {
      setSaving(false)
    }
  }

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    const list = suppliers.filter((s) => {
      if (onlyDebt && !(s.open_debts_sum > 0)) return false
      if (!q) return true
      return (
        s.name.toLowerCase().includes(q)
        || (s.organization_name || '').toLowerCase().includes(q)
        || (s.bin_iin || '').includes(q)
      )
    })
    // Сначала те, кому мы должны больше всего
    return [...list].sort((a, b) => (b.open_debts_sum - a.open_debts_sum) || a.name.localeCompare(b.name, 'ru'))
  }, [suppliers, query, onlyDebt])

  const totals = useMemo(() => ({
    debtSum: suppliers.reduce((sum, s) => sum + Number(s.open_debts_sum || 0), 0),
    debtors: suppliers.filter((s) => s.open_debts_sum > 0).length,
    turnover: suppliers.reduce((sum, s) => sum + Number(s.receipts_total || 0), 0),
  }), [suppliers])

  const toolbar = (
    <div className="flex flex-wrap items-center gap-2">
      <div className="relative min-w-[200px] max-w-md flex-1">
        <Search className="absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" />
        <Input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Поиск по названию или БИН/ИИН..."
          className="pl-9"
        />
      </div>
      <button
        type="button"
        onClick={() => setOnlyDebt((v) => !v)}
        className={`inline-flex h-9 items-center gap-1.5 rounded-xl border px-3 text-sm transition ${onlyDebt ? 'border-rose-500/40 bg-rose-500/10 text-rose-700 dark:text-rose-300' : 'border-border text-muted-foreground hover:text-foreground'}`}
      >
        <Wallet className="h-3.5 w-3.5" /> Только с долгом
      </button>
      <Button type="button" size="sm" className="gap-1.5 bg-emerald-600 text-white hover:bg-emerald-700" onClick={() => { setError(null); setAddOpen(true) }}>
        <Plus className="h-3.5 w-3.5" /> Добавить поставщика
      </Button>
    </div>
  )

  return (
    <div className={embedded ? 'space-y-4' : 'app-page-wide space-y-4'}>
      {embedded ? (
        <div className="flex flex-wrap items-center justify-between gap-3">{toolbar}</div>
      ) : (
        <AdminPageHeader
          title="Поставщики"
          description="Все поставщики, обороты и долги"
          icon={<Building2 className="h-5 w-5" />}
          accent="emerald"
          backHref="/"
          toolbar={toolbar}
        />
      )}

      <StoreStatGrid cols={3}>
        <StoreStat label="Поставщиков" value={suppliers.length} loading={loading} />
        <StoreStat
          label="Мы должны"
          value={formatMoney(totals.debtSum)}
          tone={totals.debtSum > 0 ? 'rose' : 'neutral'}
          hint={totals.debtors > 0 ? `${totals.debtors} ${plural(totals.debtors, ['поставщик', 'поставщика', 'поставщиков'])}` : 'долгов нет'}
          loading={loading}
        />
        <StoreStat label="Оборот закупок" value={formatMoney(totals.turnover)} loading={loading} />
      </StoreStatGrid>

      {error && !addOpen ? <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-200">{error}</div> : null}

      <div className={`${storeCard} overflow-hidden`}>
        {loading ? (
          <div className="divide-y divide-border">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="flex items-center gap-3 px-4 py-3">
                <Skeleton className="h-10 w-10 rounded-xl" />
                <div className="flex-1 space-y-1.5"><Skeleton className="h-4 w-40" /><Skeleton className="h-3 w-56" /></div>
                <Skeleton className="h-5 w-20" />
              </div>
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <StoreEmpty
            icon={<Building2 className="h-5 w-5" />}
            title={suppliers.length === 0 ? 'Поставщиков пока нет' : 'Никого не нашли'}
            hint={suppliers.length === 0
              ? 'Добавьте поставщика вручную или загрузите накладную — поставщик подтянется сам.'
              : 'Измените запрос или снимите фильтр «Только с долгом».'}
          />
        ) : (
          <div className="divide-y divide-border">
            {filtered.map((s) => {
              const title = s.organization_name || s.name
              const hasDebt = s.open_debts_sum > 0
              return (
                <Link key={s.id} href={`/store/suppliers/${s.id}`} className="group flex items-center gap-3 px-4 py-3 transition-colors hover:bg-slate-50 dark:hover:bg-white/[0.03]">
                  <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl text-sm font-semibold ${hasDebt ? 'bg-rose-500/10 text-rose-600 dark:text-rose-300' : 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-300'}`}>
                    {title.trim().charAt(0).toUpperCase() || '?'}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                      <span className="truncate text-sm font-semibold text-foreground">{title}</span>
                      {s.organization_name && s.organization_name !== s.name ? <span className="truncate text-xs text-muted-foreground">{s.name}</span> : null}
                    </div>
                    <p className="mt-0.5 truncate text-[11px] text-muted-foreground">
                      {s.receipts_count > 0
                        ? `${s.receipts_count} ${plural(s.receipts_count, ['накладная', 'накладные', 'накладных'])} · оборот ${formatMoney(s.receipts_total)} · последняя ${fmtDate(s.last_receipt_date)}`
                        : 'поставок ещё не было'}
                      {s.bin_iin ? ` · ${s.bin_iin}` : ''}
                    </p>
                  </div>
                  <div className="shrink-0 text-right">
                    {hasDebt ? (
                      <>
                        <p className="text-sm font-bold tabular-nums text-rose-600 dark:text-rose-300">{formatMoney(s.open_debts_sum)}</p>
                        <p className="text-[11px] text-rose-600/80 dark:text-rose-300/80">{s.open_debts_count} {plural(s.open_debts_count, ['долг', 'долга', 'долгов'])}</p>
                      </>
                    ) : (
                      <StoreChip>нет долга</StoreChip>
                    )}
                  </div>
                  <ChevronRight className="h-4 w-4 shrink-0 text-slate-400 transition group-hover:translate-x-0.5" />
                </Link>
              )
            })}
          </div>
        )}
      </div>

      <AppModal
        open={addOpen}
        onClose={() => { if (!saving) setAddOpen(false) }}
        title="Новый поставщик"
        description="Название и реквизиты для накладных"
        icon={<Building2 className="h-4 w-4" />}
        maxWidth="max-w-md"
        footer={
          <>
            <Button variant="outline" onClick={() => setAddOpen(false)} disabled={saving}>Отмена</Button>
            {can('store-suppliers.create') && (
              <Button className="bg-emerald-600 text-white hover:bg-emerald-700" onClick={() => void submitSupplier()} disabled={saving || !form.name.trim()}>
                {saving ? <><Loader2 className="mr-1 h-4 w-4 animate-spin" />Создаю…</> : 'Создать'}
              </Button>
            )}
          </>
        }
      >
        <div className="space-y-3">
          {error ? <div className="rounded-lg border border-rose-500/20 bg-rose-500/10 px-3 py-2 text-xs text-rose-700 dark:text-rose-200">{error}</div> : null}
          <div>
            <Label className="mb-1 block text-xs">Название *</Label>
            <Input value={form.name} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} placeholder="Напр. Артур ИП" autoFocus />
          </div>
          <div>
            <Label className="mb-1 block text-xs">Организация (для накладных)</Label>
            <Input value={form.organization_name} onChange={(e) => setForm((f) => ({ ...f, organization_name: e.target.value }))} placeholder="Если пусто — возьмём название" />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label className="mb-1 block text-xs">БИН/ИИН</Label>
              <Input value={form.bin_iin} onChange={(e) => setForm((f) => ({ ...f, bin_iin: e.target.value }))} placeholder="12 цифр" inputMode="numeric" />
            </div>
            <div>
              <Label className="mb-1 block text-xs">Срок поставки, дн</Label>
              <Input type="number" min={0} value={form.lead_time_days} onChange={(e) => setForm((f) => ({ ...f, lead_time_days: e.target.value }))} />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label className="mb-1 block text-xs">Контакт</Label>
              <Input value={form.contact_name} onChange={(e) => setForm((f) => ({ ...f, contact_name: e.target.value }))} placeholder="Имя" />
            </div>
            <div>
              <Label className="mb-1 block text-xs">Телефон</Label>
              <Input value={form.phone} onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))} placeholder="+7…" />
            </div>
          </div>
        </div>
      </AppModal>
    </div>
  )
}
