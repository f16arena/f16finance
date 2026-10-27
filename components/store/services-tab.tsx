'use client'

import { useState } from 'react'
import { Clock, Loader2, Pencil, Plus, Sparkles, Trash2, X } from 'lucide-react'

import { formatMoney } from '@/lib/core/format'
import { storeCard, StoreChip, StoreEmpty, StoreSection } from '@/components/store/store-ui'
import { AppModal } from '@/components/ui/app-modal'
import { MoneyInput } from '@/components/ui/money-input'
import { confirmDialog } from '@/components/ui/confirm-dialog'
import { toast } from '@/components/ui/use-toast'
import { useApiCache } from '@/lib/client/use-api-cache'
import { useStoreCompany } from '@/components/store/store-company-context'

/**
 * Услуги магазина: продаются на кассе плиткой, без остатка. Вариант со временем
 * («1 час») после оплаты запускает на кассе таймер «осталось 42 мин».
 */

type Variant = { id: string | null; name: string; price: number; duration_minutes: number | null }
type Service = { id: string; name: string; company_ids: string[]; is_active: boolean; variants: Variant[] }

type DraftVariant = { key: string; id: string | null; name: string; price: string; minutes: string }
type Draft = { id: string | null; name: string; companyIds: string[]; isActive: boolean; variants: DraftVariant[] }

const MINUTE_PRESETS = [15, 30, 60, 90, 120]

function newKey() {
  return Math.random().toString(36).slice(2)
}

const money = (v: number) => formatMoney(v)

export function durationLabel(minutes: number | null) {
  if (!minutes) return null
  if (minutes % 60 === 0) return `${minutes / 60} ч`
  if (minutes > 60) return `${Math.floor(minutes / 60)} ч ${minutes % 60} мин`
  return `${minutes} мин`
}

function emptyDraft(): Draft {
  return {
    id: null,
    name: '',
    companyIds: [],
    isActive: true,
    variants: [
      { key: newKey(), id: null, name: '30 мин', price: '', minutes: '30' },
      { key: newKey(), id: null, name: '1 час', price: '', minutes: '60' },
    ],
  }
}

function draftFrom(service: Service): Draft {
  return {
    id: service.id,
    name: service.name,
    companyIds: [...service.company_ids],
    isActive: service.is_active,
    variants: service.variants.map((v) => ({
      key: newKey(),
      id: v.id,
      name: v.name,
      price: String(v.price),
      minutes: v.duration_minutes ? String(v.duration_minutes) : '',
    })),
  }
}

export function ServicesTab() {
  const { companies } = useStoreCompany()
  const { data, loading, error, refresh } = useApiCache<{ services: Service[] }>('/api/admin/store/services')
  const services = data?.services || []
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)

  const companyName = (id: string) => companies.find((c) => c.id === id)?.name || 'точка'

  async function post(body: unknown) {
    const res = await fetch('/api/admin/store/services', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const json = await res.json().catch(() => null)
    if (!res.ok) throw new Error(json?.error || 'Не удалось сохранить')
    return json
  }

  async function save() {
    if (!draft) return
    setSaving(true)
    try {
      await post({
        action: draft.id ? 'update' : 'create',
        id: draft.id,
        name: draft.name,
        company_ids: draft.companyIds,
        is_active: draft.isActive,
        variants: draft.variants.map((v) => ({
          id: v.id,
          name: v.name,
          price: Number(v.price || 0),
          duration_minutes: v.minutes.trim() ? Number(v.minutes) : null,
        })),
      })
      toast({ title: draft.id ? 'Услуга сохранена' : 'Услуга добавлена', description: 'Касса подхватит её при следующем обновлении витрины' })
      setDraft(null)
      await refresh()
    } catch (err: any) {
      toast({ title: 'Не получилось', description: err.message, variant: 'destructive' })
    } finally {
      setSaving(false)
    }
  }

  async function remove(service: Service) {
    const ok = await confirmDialog({
      title: `Удалить «${service.name}»?`,
      description: 'Касса перестанет её продавать. Прошлые продажи останутся в чеках и отчётах под тем же названием.',
      confirmLabel: 'Удалить',
      destructive: true,
    })
    if (!ok) return
    try {
      await post({ action: 'delete', id: service.id })
      toast({ title: 'Услуга удалена', description: service.name })
      await refresh()
    } catch (err: any) {
      toast({ title: 'Не получилось', description: err.message, variant: 'destructive' })
    }
  }

  const setVariant = (key: string, patch: Partial<DraftVariant>) =>
    setDraft((d) => (d ? { ...d, variants: d.variants.map((v) => (v.key === key ? { ...v, ...patch } : v)) } : d))

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card p-4">
        <div className="min-w-0">
          <div className="font-semibold">Услуги</div>
          <div className="text-sm text-muted-foreground">
            Теннис по часам, печать, аренда — то, что продаётся без остатка. На кассе появится категория «Услуги»; вариант со временем запускает таймер.
          </div>
        </div>
        <button
          type="button"
          onClick={() => setDraft(emptyDraft())}
          className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700"
        >
          <Plus className="h-4 w-4" /> Добавить услугу
        </button>
      </div>

      {loading && !data ? (
        <div className="flex items-center gap-2 p-6 text-sm text-muted-foreground"><Loader2 className="h-4 w-4 animate-spin" /> Загружаем…</div>
      ) : error ? (
        <div className="rounded-2xl border border-rose-500/30 bg-rose-500/5 p-4 text-sm text-rose-600 dark:text-rose-300">{String(error)}</div>
      ) : services.length === 0 ? (
        <StoreSection title="Услуги" flush>
          <StoreEmpty
            icon={<Sparkles className="h-5 w-5" />}
            title="Услуг пока нет"
            hint="Нажмите «Добавить услугу». Например: «Теннис» — 30 мин за 1 000 ₸ и 1 час за 2 000 ₸. Они появятся плитками на кассе."
          />
        </StoreSection>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {services.map((service) => (
            <div key={service.id} className={`${storeCard} p-4 ${service.is_active ? '' : 'opacity-60'}`}>
              <div className="flex items-start gap-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-base font-semibold">{service.name}</div>
                  <div className="text-xs text-muted-foreground">
                    {!service.is_active
                      ? 'Скрыта на кассе'
                      : service.company_ids.length === 0
                        ? 'Все магазины'
                        : service.company_ids.map(companyName).join(', ')}
                  </div>
                </div>
                <button type="button" onClick={() => setDraft(draftFrom(service))} className="rounded-lg p-2 text-muted-foreground hover:bg-surface-hover hover:text-foreground" aria-label="Изменить">
                  <Pencil className="h-4 w-4" />
                </button>
                <button type="button" onClick={() => void remove(service)} className="rounded-lg p-2 text-muted-foreground hover:bg-rose-500/10 hover:text-rose-600" aria-label="Удалить">
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                {service.variants.map((v) => (
                  <StoreChip key={v.id || v.name} tone={v.duration_minutes ? 'violet' : 'neutral'}>
                    {v.duration_minutes ? <Clock className="h-3 w-3" /> : null}
                    {v.name} · <b className="font-semibold">{money(v.price)}</b>
                  </StoreChip>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      <AppModal
        open={!!draft}
        onClose={() => !saving && setDraft(null)}
        title={draft?.id ? 'Изменить услугу' : 'Новая услуга'}
        description="Цену на кассе поменять нельзя — она берётся отсюда"
        icon={<Sparkles className="h-4 w-4" />}
        maxWidth="max-w-2xl"
        footer={
          <div className="flex justify-end gap-2">
            <button type="button" onClick={() => setDraft(null)} disabled={saving} className="rounded-xl border border-border px-4 py-2 text-sm font-medium hover:bg-surface-hover">
              Отмена
            </button>
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving}
              className="inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-60"
            >
              {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null} Сохранить
            </button>
          </div>
        }
      >
        {draft ? (
          <div className="space-y-5">
            <label className="block">
              <span className="text-sm font-medium">Название</span>
              <input
                autoFocus
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                placeholder="Теннис"
                maxLength={80}
                className="mt-1 w-full rounded-xl border border-border bg-background px-3 py-2"
              />
            </label>

            <div>
              <div className="text-sm font-medium">Варианты и цены</div>
              <div className="mt-1 text-xs text-muted-foreground">Время можно не указывать — тогда таймера нет (печать, аренда ракетки).</div>
              <div className="mt-2 space-y-2">
                {draft.variants.map((v) => (
                  <div key={v.key} className="rounded-xl border border-border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <input
                        value={v.name}
                        onChange={(e) => setVariant(v.key, { name: e.target.value })}
                        placeholder="1 час"
                        maxLength={60}
                        className="min-w-0 flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm"
                      />
                      <div className="w-32">
                        <MoneyInput value={v.price} onValueChange={(price) => setVariant(v.key, { price })} placeholder="Цена, ₸" />
                      </div>
                      <button
                        type="button"
                        onClick={() => setDraft({ ...draft, variants: draft.variants.filter((x) => x.key !== v.key) })}
                        disabled={draft.variants.length <= 1}
                        className="rounded-lg p-2 text-muted-foreground hover:bg-rose-500/10 hover:text-rose-600 disabled:opacity-30"
                        aria-label="Убрать вариант"
                      >
                        <X className="h-4 w-4" />
                      </button>
                    </div>
                    <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs">
                      <Clock className="h-3.5 w-3.5 text-muted-foreground" />
                      <button
                        type="button"
                        onClick={() => setVariant(v.key, { minutes: '' })}
                        className={`rounded-full px-2.5 py-1 ${!v.minutes ? 'bg-foreground text-background' : 'bg-surface-hover'}`}
                      >
                        Без времени
                      </button>
                      {MINUTE_PRESETS.map((m) => (
                        <button
                          key={m}
                          type="button"
                          onClick={() => setVariant(v.key, { minutes: String(m) })}
                          className={`rounded-full px-2.5 py-1 ${v.minutes === String(m) ? 'bg-violet-600 text-white' : 'bg-surface-hover'}`}
                        >
                          {durationLabel(m)}
                        </button>
                      ))}
                      <input
                        value={MINUTE_PRESETS.map(String).includes(v.minutes) ? '' : v.minutes}
                        onChange={(e) => setVariant(v.key, { minutes: e.target.value.replace(/\D/g, '').slice(0, 4) })}
                        placeholder="своё, мин"
                        inputMode="numeric"
                        className="w-24 rounded-full border border-border bg-background px-2.5 py-1"
                      />
                    </div>
                  </div>
                ))}
              </div>
              <button
                type="button"
                onClick={() => setDraft({ ...draft, variants: [...draft.variants, { key: newKey(), id: null, name: '', price: '', minutes: '' }] })}
                className="mt-2 inline-flex items-center gap-1.5 rounded-lg px-2 py-1 text-sm font-medium text-emerald-700 hover:bg-emerald-500/10 dark:text-emerald-300"
              >
                <Plus className="h-4 w-4" /> Ещё вариант
              </button>
            </div>

            {companies.length > 1 ? (
              <div>
                <div className="text-sm font-medium">Где продаётся</div>
                <div className="mt-2 flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={() => setDraft({ ...draft, companyIds: [] })}
                    className={`rounded-full px-3 py-1.5 text-sm ${draft.companyIds.length === 0 ? 'bg-emerald-600 text-white' : 'bg-surface-hover'}`}
                  >
                    Все магазины
                  </button>
                  {companies.map((c) => {
                    const on = draft.companyIds.includes(c.id)
                    return (
                      <button
                        key={c.id}
                        type="button"
                        onClick={() =>
                          setDraft({ ...draft, companyIds: on ? draft.companyIds.filter((x) => x !== c.id) : [...draft.companyIds, c.id] })
                        }
                        className={`rounded-full px-3 py-1.5 text-sm ${on ? 'bg-emerald-600 text-white' : 'bg-surface-hover'}`}
                      >
                        {c.name}
                      </button>
                    )
                  })}
                </div>
              </div>
            ) : null}

            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={draft.isActive} onChange={(e) => setDraft({ ...draft, isActive: e.target.checked })} />
              Продаётся на кассе
            </label>
          </div>
        ) : null}
      </AppModal>
    </div>
  )
}
