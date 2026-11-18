'use client'

import { useEffect, useState } from 'react'
import { Loader2, Plus, Trash2 } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { confirmDialog } from '@/components/ui/confirm-dialog'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/components/ui/use-toast'
import { StoreEmpty, StoreSection, storeCard } from '@/components/store/store-ui'
import { Skeleton } from '@/components/skeleton'
import { useApiCache } from '@/lib/client/use-api-cache'
import {
  type BodyClass,
  type Box,
  type DetailingCompany,
  type Service,
  CompanyPicker,
  DetailingHeader,
  NoDetailingPoint,
  postDetailing,
  useDetailingCompanyId,
} from '@/components/detailing/detailing-shared'

type SetupData = {
  companies: DetailingCompany[]
  company: DetailingCompany | null
  boxes: Box[]
  classes: BodyClass[]
  services: Service[]
  onlineBooking?: boolean
}

const API = '/api/admin/detailing/setup'

export default function DetailingPricesPage() {
  const [companyId, setCompanyId] = useDetailingCompanyId()
  const url = `${API}${companyId ? `?company_id=${companyId}` : ''}`
  const { data, loading, error, refresh } = useApiCache<SetupData>(url, { ttl: 30_000 })
  useEffect(() => {
    if (data?.company && data.company.id !== companyId) setCompanyId(data.company.id)
  }, [data?.company, companyId, setCompanyId])
  const [seeding, setSeeding] = useState(false)
  const [editing, setEditing] = useState<string | 'new' | null>(null)

  const call = async (body: Record<string, unknown>, done?: string) => {
    try {
      const res = await postDetailing(API, { company_id: data!.company!.id, ...body })
      if (done) toast({ title: res?.hidden ? 'Скрыто: по нему уже есть записи' : done })
      await refresh()
      return true
    } catch (e: any) {
      toast({ title: 'Не сохранилось', description: e.message, variant: 'destructive' })
      return false
    }
  }

  const header = (
    <DetailingHeader title="Прайс" subtitle="Что вы делаете и сколько это стоит. Цена зависит от кузова.">
      {data ? <CompanyPicker companies={data.companies} value={data.company?.id || ''} onChange={setCompanyId} /> : null}
    </DetailingHeader>
  )

  if (loading && !data) return <div className="space-y-4">{header}<Skeleton className="h-[420px] w-full rounded-2xl" /></div>
  if (error && !data) return <div className="space-y-4">{header}<div className={`${storeCard} p-6 text-sm text-rose-600`}>{error}</div></div>
  if (!data?.company) return <div className="space-y-4">{header}<NoDetailingPoint /></div>

  const classes = data.classes.filter((c) => c.is_active)

  return (
    <div className="space-y-4">
      {header}

      {!data.services.length ? (
        <div className={storeCard}>
          <StoreEmpty
            title="Прайс пустой"
            hint="Поставьте примерный прайс: 4 бокса, 3 класса кузова и 7 услуг с ценами и материалами. Потом поправьте цифры под себя."
            action={
              <Button
                disabled={seeding}
                onClick={async () => {
                  setSeeding(true)
                  await call({ action: 'seed' }, 'Примерный прайс поставлен')
                  setSeeding(false)
                }}
              >
                {seeding ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Заполнить примерным прайсом
              </Button>
            }
          />
        </div>
      ) : null}

      {data.services.length ? <OnlineBookingCard on={data.onlineBooking === true} onToggle={(enabled) => call({ action: 'online.set', enabled }, enabled ? 'Онлайн-запись включена' : 'Онлайн-запись выключена')} /> : null}

      <div className="grid gap-4 lg:grid-cols-2">
        <SimpleList
          title="Боксы"
          subtitle="Строки в «Записи» и гараж на «Сегодня»."
          items={data.boxes.filter((b) => b.is_active).map((b) => ({ id: b.id, name: b.name, note: b.note }))}
          withNote
          notePlaceholder="плёнка, полный кузов"
          addLabel="Бокс"
          onSave={(item) => call({ action: 'box.save', ...item }, 'Бокс сохранён')}
          onDelete={(id) => call({ action: 'box.delete', id }, 'Бокс удалён')}
        />
        <SimpleList
          title="Классы кузова"
          subtitle="От кузова зависит цена."
          items={classes.map((c) => ({ id: c.id, name: c.name }))}
          addLabel="Класс"
          onSave={(item) => call({ action: 'class.save', id: item.id, name: item.name }, 'Класс сохранён')}
          onDelete={(id) => call({ action: 'class.delete', id }, 'Класс удалён')}
        />
      </div>

      <StoreSection
        title="Услуги"
        subtitle="Цена зависит от кузова. Нажмите на услугу, чтобы поправить."
        flush
        action={classes.length ? <Button size="sm" onClick={() => setEditing('new')}><Plus className="h-4 w-4" />Услуга</Button> : null}
      >
        {!classes.length ? (
          <StoreEmpty title="Сначала классы кузова" hint="Добавьте хотя бы один класс — например, «Седан»." />
        ) : !data.services.length ? (
          <StoreEmpty title="Услуг пока нет" hint="Добавьте первую — название и цена по кузову." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[560px] text-sm">
              <thead>
                <tr className="border-b border-border text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                  <th className="px-4 py-2.5 font-bold">Услуга</th>
                  <th className="px-3 py-2.5 text-right font-bold">Дней</th>
                  {classes.map((c) => <th key={c.id} className="px-3 py-2.5 text-right font-bold last:pr-4">{c.name}</th>)}
                </tr>
              </thead>
              <tbody>
                {data.services.map((s) => (
                  <tr
                    key={s.id}
                    tabIndex={0}
                    role="button"
                    aria-label={`Изменить: ${s.name}`}
                    onClick={() => setEditing(s.id)}
                    onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && (e.preventDefault(), setEditing(s.id))}
                    className="cursor-pointer border-b border-border last:border-0 hover:bg-slate-50 focus-visible:bg-slate-50 focus-visible:outline-none dark:hover:bg-white/[0.03] dark:focus-visible:bg-white/[0.03]"
                  >
                    <td className="px-4 py-3">
                      <div className="font-bold">{s.name}</div>
                      {s.package_note ? <div className="text-xs text-muted-foreground">{s.package_note}</div> : null}
                    </td>
                    <td className="px-3 py-3 text-right tabular-nums">{s.days}</td>
                    {classes.map((c) => {
                      const price = s.prices.find((x) => x.body_class_id === c.id)
                      return (
                        <td key={c.id} className="px-3 py-3 text-right font-bold tabular-nums last:pr-4">
                          {price ? Math.round(price.price).toLocaleString('ru-RU') : <span className="font-normal text-muted-foreground">—</span>}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </StoreSection>
      <p className="text-xs text-muted-foreground">Правка прайса не меняет уже записанные и выданные машины: у записи своя цена на момент записи.</p>

      {editing ? (
        <ServiceModal
          key={editing}
          service={editing === 'new' ? null : data.services.find((x) => x.id === editing) || null}
          classes={classes}
          onClose={() => setEditing(null)}
          onSave={(body) => call({ action: 'service.save', ...(editing === 'new' ? {} : { id: editing }), ...body }, editing === 'new' ? 'Услуга добавлена' : 'Услуга сохранена')}
          onDelete={editing === 'new' ? undefined : () => call({ action: 'service.delete', id: editing }, 'Услуга удалена')}
        />
      ) : null}
    </div>
  )
}

function SimpleList(props: {
  title: string
  subtitle: string
  items: Array<{ id: string; name: string; note?: string | null }>
  withNote?: boolean
  notePlaceholder?: string
  addLabel: string
  onSave: (item: { id?: string; name: string; note?: string }) => Promise<boolean>
  onDelete: (id: string) => Promise<boolean>
}) {
  // Строки только читаются; правка и удаление — в окне, чтобы случайно не задеть.
  const [editing, setEditing] = useState<{ id?: string; name: string; note: string } | null>(null)
  const [saving, setSaving] = useState(false)
  return (
    <StoreSection
      title={props.title}
      subtitle={props.subtitle}
      flush
      action={<Button size="sm" variant="outline" onClick={() => setEditing({ name: '', note: '' })}><Plus className="h-4 w-4" />{props.addLabel}</Button>}
    >
      {props.items.length ? (
        <div className="divide-y divide-border">
          {props.items.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setEditing({ id: item.id, name: item.name, note: item.note || '' })}
              className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left transition hover:bg-slate-50 focus-visible:bg-slate-50 focus-visible:outline-none dark:hover:bg-white/[0.03] dark:focus-visible:bg-white/[0.03]"
            >
              <span className="font-bold">{item.name}</span>
              {item.note ? <span className="truncate text-sm text-muted-foreground">{item.note}</span> : null}
            </button>
          ))}
        </div>
      ) : (
        <StoreEmpty title="Пока пусто" hint={`Нажмите «${props.addLabel}», чтобы добавить.`} />
      )}

      {editing ? (
        <AppModal
          open
          onClose={() => setEditing(null)}
          title={editing.id ? props.items.find((x) => x.id === editing.id)?.name || props.addLabel : `Новый: ${props.addLabel.toLowerCase()}`}
          maxWidth="max-w-md"
          footer={
            <div className="flex items-center justify-between gap-2">
              {editing.id ? (
                <Button
                  variant="ghost"
                  className="text-rose-600 hover:text-rose-700"
                  onClick={async () => {
                    const ok = await confirmDialog({ title: `Удалить «${editing.name}»?`, description: 'Если по нему уже есть записи, он не удалится, а скроется.', confirmLabel: 'Удалить', destructive: true })
                    if (ok && (await props.onDelete(editing.id!))) setEditing(null)
                  }}
                >
                  <Trash2 className="h-4 w-4" />Удалить
                </Button>
              ) : <span />}
              <Button
                disabled={saving || editing.name.trim().length < 1}
                onClick={async () => {
                  setSaving(true)
                  const ok = await props.onSave({ id: editing.id, name: editing.name, note: editing.note })
                  setSaving(false)
                  if (ok) setEditing(null)
                }}
              >
                {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Сохранить
              </Button>
            </div>
          }
        >
          <div className="space-y-3 text-sm">
            <label className="block space-y-1.5"><span className="text-muted-foreground">Название</span><Input value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} autoFocus maxLength={40} /></label>
            {props.withNote ? (
              <label className="block space-y-1.5"><span className="text-muted-foreground">Для чего — по желанию</span><Input value={editing.note} onChange={(e) => setEditing({ ...editing, note: e.target.value })} placeholder={props.notePlaceholder} maxLength={80} /></label>
            ) : null}
          </div>
        </AppModal>
      ) : null}
    </StoreSection>
  )
}

/* ── Услуга: правка в окне ────────────────────────────────────────────── */

function ServiceModal(props: {
  service: Service | null
  classes: BodyClass[]
  onClose: () => void
  onSave: (body: Record<string, unknown>) => Promise<boolean>
  onDelete?: () => Promise<boolean>
}) {
  const s = props.service
  const [name, setName] = useState(s?.name || '')
  const [note, setNote] = useState(s?.package_note || '')
  const [days, setDays] = useState(String(s?.days || 1))
  const [prices, setPrices] = useState<Record<string, { price: string; materials: string }>>(() =>
    Object.fromEntries(
      props.classes.map((c) => {
        const p = s?.prices.find((x) => x.body_class_id === c.id)
        return [c.id, { price: p ? String(Math.round(p.price)) : '', materials: p && p.materials ? String(Math.round(p.materials)) : '' }]
      }),
    ),
  )
  const [saving, setSaving] = useState(false)
  const num = (v: string) => v.replace(/[^\d]/g, '').slice(0, 9)
  const shown = (v: string) => (v ? Number(v).toLocaleString('ru-RU') : '')
  const set = (classId: string, key: 'price' | 'materials', value: string) => setPrices({ ...prices, [classId]: { ...prices[classId], [key]: num(value) } })
  const tooMuch = props.classes.find((c) => Number(prices[c.id]?.materials || 0) > Number(prices[c.id]?.price || 0))
  const ready = name.trim().length >= 2 && props.classes.some((c) => prices[c.id]?.price) && !tooMuch

  const save = async () => {
    setSaving(true)
    const ok = await props.onSave({
      name,
      package_note: note,
      days: Number(days) || 1,
      prices: props.classes
        .filter((c) => prices[c.id]?.price)
        .map((c) => ({ body_class_id: c.id, price: Number(prices[c.id].price), materials: Number(prices[c.id].materials || 0) })),
    })
    setSaving(false)
    if (ok) props.onClose()
  }

  return (
    <AppModal
      open
      onClose={props.onClose}
      title={s ? s.name : 'Новая услуга'}
      description="Цена зависит от кузова. Пустая цена — для этого кузова услугу не делаете."
      maxWidth="max-w-lg"
      footer={
        <div className="flex items-center justify-between gap-2">
          {props.onDelete ? (
            <Button
              variant="ghost"
              className="text-rose-600 hover:text-rose-700"
              onClick={async () => {
                if (await props.onDelete!()) props.onClose()
              }}
            >
              <Trash2 className="h-4 w-4" />Удалить
            </Button>
          ) : <span />}
          <Button disabled={!ready || saving} onClick={save}>{saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Сохранить</Button>
        </div>
      }
    >
      <div className="space-y-4 text-sm">
        <div className="grid gap-3 sm:grid-cols-[1fr_110px]">
          <label className="space-y-1.5"><span className="text-muted-foreground">Название</span><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Полировка кузова" autoFocus={!s} maxLength={80} /></label>
          <label className="space-y-1.5"><span className="text-muted-foreground">Дней в боксе</span><Input value={days} onChange={(e) => setDays(num(e.target.value).slice(0, 2))} inputMode="numeric" className="text-right tabular-nums" /></label>
        </div>
        <label className="block space-y-1.5"><span className="text-muted-foreground">Что входит — по желанию</span><Input value={note} onChange={(e) => setNote(e.target.value)} placeholder="капот, бампер, фары, зеркала" maxLength={160} /></label>

        <div className="overflow-hidden rounded-xl border border-border">
          <div className="grid grid-cols-[minmax(0,1fr)_120px_120px] gap-2 border-b border-border bg-slate-50 px-3 py-2 text-[11px] font-bold uppercase tracking-wider text-muted-foreground dark:bg-white/[0.03]">
            <span>Кузов</span><span className="text-right">Цена, ₸</span><span className="text-right">Материалы, ₸</span>
          </div>
          {props.classes.map((c) => (
            <div key={c.id} className="grid grid-cols-[minmax(0,1fr)_120px_120px] items-center gap-2 border-b border-border px-3 py-2 last:border-0">
              <span className="truncate font-medium">{c.name}</span>
              <Input value={shown(prices[c.id]?.price || '')} onChange={(e) => set(c.id, 'price', e.target.value)} inputMode="numeric" placeholder="—" aria-label={`${c.name}, цена`} className="h-9 text-right tabular-nums" />
              <Input value={shown(prices[c.id]?.materials || '')} onChange={(e) => set(c.id, 'materials', e.target.value)} inputMode="numeric" placeholder="0" aria-label={`${c.name}, материалы`} className="h-9 text-right tabular-nums" />
            </div>
          ))}
        </div>
        {tooMuch ? <p className="font-medium text-rose-600">Материалы не могут быть дороже работы: {tooMuch.name}.</p> : null}
        <p className="text-xs text-muted-foreground">Материалы — сколько уходит плёнки, химии, керамики на эту работу. Они вычитаются перед процентом мастера и в «Деньгах». Клиент их не видит.</p>
      </div>
    </AppModal>
  )
}

/* ── Онлайн-запись ────────────────────────────────────────────────────── */

function OnlineBookingCard(props: { on: boolean; onToggle: (enabled: boolean) => void }) {
  const [copied, setCopied] = useState(false)
  const link = typeof window === 'undefined' ? '' : `${window.location.origin}/book`
  return (
    <section className={`${storeCard} flex flex-wrap items-center gap-3 p-4`}>
      <div className="min-w-0 flex-1">
        <div className="font-semibold">Онлайн-запись для клиентов</div>
        <div className="text-[12.5px] text-muted-foreground">
          {props.on
            ? 'Клиент сам выбирает услугу и день. Приходит заявкой — вы выбираете бокс и подтверждаете.'
            : 'Выключена. Включите — и получите ссылку, которую можно дать клиентам или поставить в Instagram.'}
        </div>
        {props.on ? (
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <code className="rounded-lg bg-slate-100 px-2 py-1 text-xs dark:bg-white/5">{link}</code>
            <Button
              variant="outline"
              size="xs"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(link)
                  setCopied(true)
                } catch {
                  toast({ title: 'Не скопировалось', description: 'Выделите ссылку и скопируйте вручную.', variant: 'destructive' })
                }
              }}
            >
              {copied ? 'Скопировано' : 'Скопировать'}
            </Button>
            <a href={link} target="_blank" rel="noopener noreferrer" className="text-xs font-medium text-blue-600 hover:underline">Посмотреть</a>
          </div>
        ) : null}
      </div>
      <Switch checked={props.on} onCheckedChange={props.onToggle} aria-label="Онлайн-запись для клиентов" />
    </section>
  )
}
