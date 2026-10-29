'use client'

import { useDeferredValue, useEffect, useState } from 'react'
import { Plus, Search, Trash2, Users2 } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { NativeSelect } from '@/components/ui/native-select'
import { AppModal } from '@/components/ui/app-modal'
import { toast } from '@/components/ui/use-toast'
import { StoreEmpty, storeCard } from '@/components/store/store-ui'
import { Skeleton } from '@/components/skeleton'
import { useApiCache } from '@/lib/client/use-api-cache'
import {
  type BodyClass,
  type DetailingCompany,
  CompanyPicker,
  NoDetailingPoint,
  Plate,
  dayLabel,
  money,
  postDetailing,
  useDetailingCompanyId,
} from '@/components/detailing/detailing-shared'

type Car = { id: string; plate: string; model: string | null; body_class_id: string | null; note: string | null }
type Client = {
  id: string
  name: string
  phone: string | null
  notes: string | null
  cars: Car[]
  visits: number
  spent: number
  debt: number
  last: string | null
}
type ClientsData = { companies: DetailingCompany[]; company: DetailingCompany | null; clients: Client[]; limited: boolean }

const API = '/api/admin/detailing/clients'

export default function DetailingClientsPage() {
  const [companyId, setCompanyId] = useDetailingCompanyId()
  const [q, setQ] = useState('')
  const query = useDeferredValue(q.trim())
  const params = new URLSearchParams()
  if (companyId) params.set('company_id', companyId)
  if (query) params.set('q', query)
  const { data, loading, error, refresh } = useApiCache<ClientsData>(`${API}?${params}`, { ttl: 30_000 })
  useEffect(() => {
    if (data?.company && data.company.id !== companyId) setCompanyId(data.company.id)
  }, [data?.company, companyId, setCompanyId])

  // Классы кузова — для поля «кузов» у машины.
  const { data: setup } = useApiCache<{ classes: BodyClass[] }>(data?.company ? `/api/admin/detailing/setup?company_id=${data.company.id}` : null, { ttl: 60_000 })
  const classes = (setup?.classes || []).filter((c) => c.is_active)
  const [openId, setOpenId] = useState<string | null>(null)
  const open = data?.clients.find((c) => c.id === openId) || null

  const header = (
    <AdminPageHeader
      title="Клиенты и машины"
      description="Кто приезжал, на чём, сколько заплатил и сколько должен"
      icon={<Users2 className="h-5 w-5" />}
      backHref="/detailing"
      actions={data ? <CompanyPicker companies={data.companies} value={data.company?.id || ''} onChange={setCompanyId} /> : null}
      toolbar={
        <div className="relative max-w-md">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Телефон, госномер или имя" className="pl-9" aria-label="Поиск клиента" />
        </div>
      }
    />
  )

  if (loading && !data) return <div className="space-y-4">{header}<Skeleton className="h-[360px] w-full rounded-2xl" /></div>
  if (error && !data) return <div className="space-y-4">{header}<div className={`${storeCard} p-6 text-sm text-rose-600`}>{error}</div></div>
  if (!data?.company) return <div className="space-y-4">{header}<NoDetailingPoint /></div>

  return (
    <div className="space-y-4">
      {header}
      <div className={`${storeCard} overflow-hidden`}>
        {!data.clients.length ? (
          <StoreEmpty
            title={query ? 'Никого не нашли' : 'Клиентов пока нет'}
            hint={query ? 'Проверьте номер телефона или госномер.' : 'Клиент появляется сам, когда его записывают на неделе.'}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead>
                <tr className="border-b border-border text-left text-[11px] uppercase tracking-wider text-muted-foreground">
                  <th className="px-4 py-2 font-medium">Клиент</th>
                  <th className="px-2 py-2 font-medium">Машины</th>
                  <th className="px-2 py-2 text-right font-medium">Визитов</th>
                  <th className="px-2 py-2 text-right font-medium">Оплатил</th>
                  <th className="px-2 py-2 text-right font-medium">Долг</th>
                  <th className="px-4 py-2 text-right font-medium">Последний раз</th>
                </tr>
              </thead>
              <tbody>
                {data.clients.map((c) => (
                  <tr key={c.id} onClick={() => setOpenId(c.id)} className="cursor-pointer border-b border-border last:border-0 hover:bg-slate-50 dark:hover:bg-white/[0.03]">
                    <td className="px-4 py-2.5">
                      <div className="font-medium">{c.name}</div>
                      <div className="text-xs text-muted-foreground">{c.phone || '—'}</div>
                    </td>
                    <td className="px-2 py-2.5">
                      <div className="flex flex-col gap-1">
                        {c.cars.length ? c.cars.map((car) => (
                          <span key={car.id} className="flex items-center gap-2 text-xs">{car.model || 'Машина'} <Plate plate={car.plate} /></span>
                        )) : <span className="text-xs text-muted-foreground">нет машин</span>}
                      </div>
                    </td>
                    <td className="px-2 py-2.5 text-right tabular-nums">{c.visits}</td>
                    <td className="px-2 py-2.5 text-right tabular-nums">{money(c.spent)}</td>
                    <td className={`px-2 py-2.5 text-right tabular-nums ${c.debt > 0 ? 'font-semibold text-rose-600' : 'text-muted-foreground'}`}>{c.debt > 0 ? money(c.debt) : '—'}</td>
                    <td className="px-4 py-2.5 text-right text-muted-foreground">{c.last ? `${dayLabel(c.last).day} ${dayLabel(c.last).month}` : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data.limited ? <div className="border-t border-border px-4 py-2 text-xs text-muted-foreground">Показаны последние 200. Уточните поиск, чтобы найти нужного.</div> : null}
      </div>

      {open ? <ClientModal client={open} classes={classes} companyId={data.company.id} onClose={() => setOpenId(null)} onChanged={refresh} /> : null}
    </div>
  )
}

function ClientModal(props: { client: Client; classes: BodyClass[]; companyId: string; onClose: () => void; onChanged: () => Promise<void> }) {
  const c = props.client
  const [name, setName] = useState(c.name)
  const [phone, setPhone] = useState(c.phone || '')
  const [notes, setNotes] = useState(c.notes || '')
  const [newCar, setNewCar] = useState({ plate: '', model: '', body_class_id: '' })
  const dirty = name !== c.name || phone !== (c.phone || '') || notes !== (c.notes || '')

  const call = async (body: Record<string, unknown>, done: string) => {
    try {
      await postDetailing(API, { company_id: props.companyId, ...body })
      toast({ title: done })
      await props.onChanged()
      return true
    } catch (e: any) {
      toast({ title: 'Не сохранилось', description: e.message, variant: 'destructive' })
      return false
    }
  }

  return (
    <AppModal
      open
      onClose={props.onClose}
      title={c.name}
      description={`${c.visits} визитов · оплатил ${money(c.spent)}${c.debt > 0 ? ` · долг ${money(c.debt)}` : ''}`}
      maxWidth="max-w-lg"
      footer={dirty ? <div className="flex justify-end"><Button onClick={() => call({ action: 'customer.save', id: c.id, name, phone, notes }, 'Клиент сохранён')}>Сохранить</Button></div> : undefined}
    >
      <div className="space-y-4 text-sm">
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="space-y-1"><span className="text-muted-foreground">Имя</span><Input value={name} onChange={(e) => setName(e.target.value)} /></label>
          <label className="space-y-1"><span className="text-muted-foreground">Телефон</span><Input value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" /></label>
        </div>
        <label className="block space-y-1"><span className="text-muted-foreground">Заметка</span><Input value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Предпочитает забирать после 18:00" /></label>

        <div className="space-y-2">
          <div className="text-[11px] font-medium uppercase tracking-wider text-muted-foreground">Машины</div>
          {c.cars.map((car) => (
            <div key={car.id} className="flex items-center justify-between gap-2 rounded-xl border border-border px-3 py-2">
              <span className="flex min-w-0 items-center gap-2">
                <Plate plate={car.plate} />
                <span className="truncate">{car.model || 'Машина'}</span>
                <span className="text-xs text-muted-foreground">{props.classes.find((k) => k.id === car.body_class_id)?.name || ''}</span>
              </span>
              <Button size="icon-sm" variant="ghost" aria-label="Удалить машину" onClick={() => call({ action: 'car.delete', id: car.id }, 'Машина удалена')}><Trash2 className="h-4 w-4" /></Button>
            </div>
          ))}
          <form
            className="grid gap-2 sm:grid-cols-[1fr_1fr_1fr_auto]"
            onSubmit={async (e) => {
              e.preventDefault()
              if (await call({ action: 'car.save', customer_id: c.id, ...newCar }, 'Машина добавлена')) setNewCar({ plate: '', model: '', body_class_id: '' })
            }}
          >
            <Input value={newCar.plate} onChange={(e) => setNewCar({ ...newCar, plate: e.target.value })} placeholder="Госномер" aria-label="Госномер" />
            <Input value={newCar.model} onChange={(e) => setNewCar({ ...newCar, model: e.target.value })} placeholder="Модель" aria-label="Модель" />
            <NativeSelect value={newCar.body_class_id} onChange={(e) => setNewCar({ ...newCar, body_class_id: e.target.value })} aria-label="Кузов">
              <option value="">Кузов</option>
              {props.classes.map((k) => <option key={k.id} value={k.id}>{k.name}</option>)}
            </NativeSelect>
            <Button type="submit" variant="outline" disabled={!newCar.plate.trim()}><Plus className="h-4 w-4" />Машина</Button>
          </form>
        </div>
      </div>
    </AppModal>
  )
}
