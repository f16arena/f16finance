'use client'

import { useEffect, useState } from 'react'
import { ListChecks, Loader2, Plus, Trash2 } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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
    <AdminPageHeader
      title="Прайс и боксы"
      description="Цена и материалы по классу кузова. Материалы вычитаются перед процентом мастера."
      icon={<ListChecks className="h-5 w-5" />}
      backHref="/detailing"
      actions={data ? <CompanyPicker companies={data.companies} value={data.company?.id || ''} onChange={setCompanyId} /> : null}
    />
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

      <div className="grid gap-4 lg:grid-cols-2">
        <SimpleList
          title="Боксы"
          subtitle="Строки недели. Бокс с записями не удаляется, а прячется."
          items={data.boxes.filter((b) => b.is_active).map((b) => ({ id: b.id, name: b.name, note: b.note }))}
          withNote
          notePlaceholder="плёнка, полный кузов"
          addLabel="Бокс"
          onSave={(item) => call({ action: 'box.save', ...item }, 'Бокс сохранён')}
          onDelete={(id) => call({ action: 'box.delete', id }, 'Бокс удалён')}
        />
        <SimpleList
          title="Классы кузова"
          subtitle="Колонки прайса. От класса зависит цена."
          items={classes.map((c) => ({ id: c.id, name: c.name }))}
          addLabel="Класс"
          onSave={(item) => call({ action: 'class.save', id: item.id, name: item.name }, 'Класс сохранён')}
          onDelete={(id) => call({ action: 'class.delete', id }, 'Класс удалён')}
        />
      </div>

      <StoreSection
        title="Услуги"
        subtitle="Цена / материалы по каждому кузову. Пустая цена — эта услуга для кузова не делается."
        flush
        action={classes.length ? <AddServiceButton onAdd={(name) => call({ action: 'service.save', name, days: 1, prices: [] }, 'Услуга добавлена')} /> : null}
      >
        {!classes.length ? (
          <StoreEmpty title="Сначала классы кузова" hint="Добавьте хотя бы один класс — например, «Седан»." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead>
                <tr className="border-b border-border text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                  <th className="px-4 py-2 font-medium">Услуга</th>
                  <th className="px-2 py-2 text-right font-medium">Дней</th>
                  {classes.map((c) => (
                    <th key={c.id} className="px-2 py-2 text-right font-medium">{c.name}<div className="normal-case tracking-normal">цена / материалы</div></th>
                  ))}
                  <th className="px-2 py-2" />
                </tr>
              </thead>
              <tbody>
                {data.services.map((s) => (
                  <ServiceRow key={s.id} service={s} classes={classes} onSave={(body) => call({ action: 'service.save', id: s.id, ...body }, 'Услуга сохранена')} onDelete={() => call({ action: 'service.delete', id: s.id }, 'Услуга удалена')} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </StoreSection>
      <p className="text-xs text-muted-foreground">Правка прайса не меняет уже записанные и выданные машины: у записи своя цена на момент записи.</p>
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
  const [name, setName] = useState('')
  const [note, setNote] = useState('')
  return (
    <StoreSection title={props.title} subtitle={props.subtitle} flush>
      <div className="divide-y divide-border">
        {props.items.map((item) => (
          <ListRow key={item.id} item={item} withNote={props.withNote} onSave={props.onSave} onDelete={props.onDelete} />
        ))}
        <form
          className="flex flex-wrap items-center gap-2 px-4 py-3"
          onSubmit={async (e) => {
            e.preventDefault()
            if (!name.trim()) return
            if (await props.onSave({ name, note })) {
              setName('')
              setNote('')
            }
          }}
        >
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={`${props.addLabel}: название`} className="h-9 flex-1 min-w-[140px]" />
          {props.withNote ? <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder={props.notePlaceholder} className="h-9 flex-1 min-w-[140px]" /> : null}
          <Button type="submit" size="sm" variant="outline" disabled={!name.trim()}><Plus className="h-4 w-4" />Добавить</Button>
        </form>
      </div>
    </StoreSection>
  )
}

function ListRow(props: {
  item: { id: string; name: string; note?: string | null }
  withNote?: boolean
  onSave: (item: { id?: string; name: string; note?: string }) => Promise<boolean>
  onDelete: (id: string) => Promise<boolean>
}) {
  const [name, setName] = useState(props.item.name)
  const [note, setNote] = useState(props.item.note || '')
  const dirty = name !== props.item.name || note !== (props.item.note || '')
  return (
    <div className="flex flex-wrap items-center gap-2 px-4 py-2">
      <Input value={name} onChange={(e) => setName(e.target.value)} className="h-9 flex-1 min-w-[120px]" aria-label="Название" />
      {props.withNote ? <Input value={note} onChange={(e) => setNote(e.target.value)} className="h-9 flex-1 min-w-[120px]" aria-label="Подпись" /> : null}
      {dirty ? <Button size="sm" onClick={() => props.onSave({ id: props.item.id, name, note })}>Сохранить</Button> : null}
      <Button size="icon-sm" variant="ghost" aria-label="Удалить" onClick={() => props.onDelete(props.item.id)}><Trash2 className="h-4 w-4" /></Button>
    </div>
  )
}

function ServiceRow(props: {
  service: Service
  classes: BodyClass[]
  onSave: (body: Record<string, unknown>) => Promise<boolean>
  onDelete: () => Promise<boolean>
}) {
  const s = props.service
  const initial = () => ({
    name: s.name,
    package_note: s.package_note || '',
    days: String(s.days),
    prices: Object.fromEntries(
      props.classes.map((c) => {
        const p = s.prices.find((x) => x.body_class_id === c.id)
        return [c.id, { price: p ? String(Math.round(p.price)) : '', materials: p ? String(Math.round(p.materials)) : '' }]
      }),
    ) as Record<string, { price: string; materials: string }>,
  })
  const [form, setForm] = useState(initial)
  const [saving, setSaving] = useState(false)
  useEffect(() => setForm(initial()), [s, props.classes]) // eslint-disable-line react-hooks/exhaustive-deps
  const dirty = JSON.stringify(form) !== JSON.stringify(initial())
  const num = (v: string) => v.replace(/[^\d]/g, '')

  return (
    <tr className="border-b border-border align-top last:border-0">
      <td className="px-4 py-2">
        <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="h-8 font-medium" aria-label="Название услуги" />
        <Input value={form.package_note} onChange={(e) => setForm({ ...form, package_note: e.target.value })} placeholder="что входит (необязательно)" className="mt-1 h-7 text-xs" aria-label="Что входит" />
      </td>
      <td className="px-2 py-2">
        <Input value={form.days} onChange={(e) => setForm({ ...form, days: num(e.target.value) })} inputMode="numeric" className="h-8 w-14 text-right tabular-nums" aria-label="Дней в боксе" />
      </td>
      {props.classes.map((c) => (
        <td key={c.id} className="px-2 py-2">
          <Input
            value={form.prices[c.id]?.price ?? ''}
            onChange={(e) => setForm({ ...form, prices: { ...form.prices, [c.id]: { ...form.prices[c.id], price: num(e.target.value) } } })}
            inputMode="numeric"
            placeholder="цена"
            className="h-8 w-[110px] text-right tabular-nums"
            aria-label={`${s.name}, ${c.name}, цена`}
          />
          <Input
            value={form.prices[c.id]?.materials ?? ''}
            onChange={(e) => setForm({ ...form, prices: { ...form.prices, [c.id]: { ...form.prices[c.id], materials: num(e.target.value) } } })}
            inputMode="numeric"
            placeholder="материалы"
            className="mt-1 h-7 w-[110px] text-right text-xs tabular-nums"
            aria-label={`${s.name}, ${c.name}, материалы`}
          />
        </td>
      ))}
      <td className="px-2 py-2">
        <div className="flex items-center gap-1">
          {dirty ? (
            <Button
              size="sm"
              disabled={saving}
              onClick={async () => {
                setSaving(true)
                await props.onSave({
                  name: form.name,
                  package_note: form.package_note,
                  days: Number(form.days) || 1,
                  prices: props.classes
                    .filter((c) => form.prices[c.id]?.price)
                    .map((c) => ({ body_class_id: c.id, price: Number(form.prices[c.id].price), materials: Number(form.prices[c.id].materials || 0) })),
                })
                setSaving(false)
              }}
            >
              Сохранить
            </Button>
          ) : null}
          <Button size="icon-sm" variant="ghost" aria-label="Удалить услугу" onClick={props.onDelete}><Trash2 className="h-4 w-4" /></Button>
        </div>
      </td>
    </tr>
  )
}

function AddServiceButton({ onAdd }: { onAdd: (name: string) => Promise<boolean> }) {
  const [name, setName] = useState('')
  return (
    <form
      className="flex items-center gap-2"
      onSubmit={async (e) => {
        e.preventDefault()
        if (name.trim() && (await onAdd(name.trim()))) setName('')
      }}
    >
      <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Новая услуга" className="h-8 w-[170px]" />
      <Button type="submit" size="sm" variant="outline" disabled={!name.trim()}><Plus className="h-4 w-4" />Добавить</Button>
    </form>
  )
}
