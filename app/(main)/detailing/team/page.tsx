'use client'

/**
 * Команда детейлинга: кто заходит в программу и кем работает.
 * Сейчас владелец один и видит всё. Нанял человека — выбрал готовую роль,
 * получил ссылку и отправил её в WhatsApp; пароль человек придумывает сам.
 */

import Link from 'next/link'
import { useState } from 'react'
import { Check, Copy, Loader2, X } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { confirmDialog } from '@/components/ui/confirm-dialog'
import { Input } from '@/components/ui/input'
import { NativeSelect } from '@/components/ui/native-select'
import { toast } from '@/components/ui/use-toast'
import { Skeleton } from '@/components/skeleton'
import { StoreSection, storeCard } from '@/components/store/store-ui'
import { useApiCache } from '@/lib/client/use-api-cache'
import { formatPhone } from '@/lib/core/format'
import { postDetailing } from '@/components/detailing/detailing-shared'

type Person = { id: string; name: string; role: string; roleLabel: string; email: string | null; phone: string | null; editable: boolean; roleLocked?: boolean; me: boolean }
type Role = { code: string; label: string; summary: string; can: string[]; cannot: string[] }
type MasterRow = { id: string; name: string; hasLogin: boolean }
type TeamData = { people: Person[]; roles: Role[]; masters?: MasterRow[] }
type Access = { link: string | null; phone: string | null; name: string }

const API = '/api/admin/detailing/team'
const initials = (name: string) => name.split(/\s+/).map((p) => p[0]).join('').slice(0, 2).toUpperCase()

export default function DetailingTeamPage() {
  const { data, loading, error, refresh } = useApiCache<TeamData>(API, { ttl: 30_000 })
  const [adding, setAdding] = useState<{ role: Role; master?: MasterRow } | null>(null)
  const [access, setAccess] = useState<Access | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const header = (
    <header>
      <h1 className="font-[family-name:var(--font-manrope)] text-[26px] font-extrabold leading-tight text-[#0b1f3b] dark:text-white">Команда</h1>
      <p className="mt-1 max-w-2xl text-sm text-muted-foreground">
        Кто заходит в программу и что видит. Наняли человека — добавьте его и выберите, кем он работает.
      </p>
    </header>
  )
  if (loading && !data) return <div className="space-y-4">{header}<Skeleton className="h-[180px] w-full rounded-2xl" /><Skeleton className="h-[260px] w-full rounded-2xl" /></div>
  if (error && !data) return <div className="space-y-4">{header}<div className={`${storeCard} p-6 text-sm text-muted-foreground`}>Этот раздел открыт только владельцу.</div></div>
  if (!data) return null

  const officeRoles = data.roles.filter((r) => r.code !== 'detailing_master')
  const masterRole = data.roles.find((r) => r.code === 'detailing_master') || null
  const masters = data.masters || []

  const run = async (id: string, body: Record<string, unknown>, done?: string) => {
    setBusy(id)
    try {
      const res = await postDetailing<Access | null>(API, { staff_id: id, ...body })
      if (done) toast({ title: done })
      await refresh()
      return res
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
      return null
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="space-y-4">
      {header}

      <StoreSection title="Кто заходит в программу" flush>
        <div className="divide-y divide-border">
          {data.people.map((p) => (
            <div key={p.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-full bg-[#0b1f3b] text-xs font-extrabold text-white dark:bg-white/10">{initials(p.name || '?')}</span>
              <div className="min-w-0 flex-1">
                <div className="truncate font-semibold">{p.name}</div>
                <div className="truncate text-[12.5px] text-muted-foreground">
                  {p.email || 'почта не указана'}{p.phone ? ` · ${formatPhone(p.phone)}` : ''}
                </div>
              </div>
              {p.editable ? (
                <div className="flex flex-wrap items-center gap-2">
                  {p.roleLocked ? (
                    <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-bold text-slate-700 dark:bg-white/10 dark:text-slate-200">{p.roleLabel}</span>
                  ) : (
                    <NativeSelect
                      value={p.role}
                      disabled={busy === p.id}
                      onChange={(e) => run(p.id, { action: 'role', role: e.target.value }, 'Роль изменена')}
                      aria-label={`Роль: ${p.name}`}
                      className="h-9 min-w-[150px]"
                    >
                      {officeRoles.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
                    </NativeSelect>
                  )}
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy === p.id}
                    onClick={async () => {
                      const res = await run(p.id, { action: 'link' })
                      if (res) setAccess(res)
                    }}
                  >
                    Ссылка для входа
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy === p.id}
                    onClick={async () => {
                      const ok = await confirmDialog({
                        title: `Убрать ${p.name}?`,
                        description: 'Вход в программу закроется сразу. Всё, что человек записал, останется.',
                        confirmLabel: 'Убрать',
                        destructive: true,
                      })
                      if (ok) await run(p.id, { action: 'remove' }, 'Вход закрыт')
                    }}
                  >
                    Убрать
                  </Button>
                </div>
              ) : (
                <span className="rounded-full bg-blue-600/10 px-2.5 py-1 text-xs font-bold text-blue-700 dark:text-blue-200">
                  {p.roleLabel}{p.me ? ' · это вы' : ''}
                </span>
              )}
            </div>
          ))}
        </div>
      </StoreSection>

      <div>
        <h2 className="font-[family-name:var(--font-manrope)] text-[19px] font-extrabold text-[#0b1f3b] dark:text-white">Добавить человека</h2>
        <p className="mt-1 text-sm text-muted-foreground">Имя, почта и роль. Вы получите ссылку — отправьте её в WhatsApp, пароль он придумает сам.</p>
      </div>
      <div className="grid gap-3.5 lg:grid-cols-3">
        {officeRoles.map((r) => (
          <button
            key={r.code}
            type="button"
            onClick={() => setAdding({ role: r })}
            className={`${storeCard} flex flex-col gap-2.5 p-4 text-left transition hover:border-blue-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500`}
          >
            <b className="text-base">{r.label}</b>
            <span className="text-[12.5px] text-muted-foreground">{r.summary}</span>
            <ul className="space-y-1 text-[13px]">
              {r.can.map((x) => <li key={x} className="flex gap-2"><Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-emerald-600" />{x}</li>)}
              {r.cannot.map((x) => <li key={x} className="flex gap-2 text-muted-foreground"><X className="mt-0.5 h-3.5 w-3.5 shrink-0 text-rose-600" />{x}</li>)}
            </ul>
            <span className="mt-1 self-start rounded-xl bg-blue-600 px-3 py-2 text-[13px] font-bold text-white">+ Добавить</span>
          </button>
        ))}
        <div className={`${storeCard} flex flex-col gap-2.5 p-4`}>
          <b className="text-base">Мастер</b>
          <span className="text-[12.5px] text-muted-foreground">Вход ему не обязателен. Дадите — увидит только свои машины и свой заработок.</span>
          {masters.length ? (
            <ul className="divide-y divide-border">
              {masters.map((m) => (
                <li key={m.id} className="flex items-center justify-between gap-2 py-2 text-sm">
                  <span className="truncate font-semibold">{m.name}</span>
                  {m.hasLogin ? (
                    <span className="shrink-0 text-xs font-medium text-emerald-600">вход есть</span>
                  ) : masterRole ? (
                    <Button size="xs" variant="outline" onClick={() => setAdding({ role: masterRole, master: m })}>Дать вход</Button>
                  ) : null}
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-[13px] text-muted-foreground">Мастеров пока нет.</span>
          )}
          <Link href="/detailing/masters" className="mt-1 self-start rounded-xl border border-border px-3 py-2 text-[13px] font-bold hover:border-slate-400">Открыть «Мастера»</Link>
        </div>
      </div>

      {adding ? (
        <AddPersonModal
          role={adding.role}
          master={adding.master}
          onClose={() => setAdding(null)}
          onAdded={async (res) => {
            setAdding(null)
            await refresh()
            setAccess(res)
          }}
        />
      ) : null}
      {access ? <AccessModal access={access} onClose={() => setAccess(null)} /> : null}
    </div>
  )
}

function AddPersonModal(props: { role: Role; master?: MasterRow; onClose: () => void; onAdded: (res: Access) => void }) {
  const [name, setName] = useState(props.master?.name || '')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [saving, setSaving] = useState(false)
  const ready = name.trim().length >= 2 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())

  const save = async () => {
    setSaving(true)
    try {
      const res = await postDetailing<Access>(API, { action: 'add', name, email, phone, role: props.role.code, operator_id: props.master?.id })
      props.onAdded(res)
    } catch (e: any) {
      toast({ title: 'Не получилось', description: e.message, variant: 'destructive' })
      setSaving(false)
    }
  }

  return (
    <AppModal
      open
      onClose={props.onClose}
      title={props.master ? `Вход для мастера: ${props.master.name}` : `Добавить: ${props.role.label.toLowerCase()}`}
      description={props.role.summary}
      maxWidth="max-w-md"
      footer={<Button className="h-11 w-full" disabled={!ready || saving} onClick={save}>{saving ? <Loader2 className="h-4 w-4 animate-spin" /> : null}Добавить и получить ссылку</Button>}
    >
      <div className="space-y-3">
        <label className="block space-y-1.5 text-sm"><span className="text-muted-foreground">Имя</span><Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Айжан" autoFocus maxLength={120} /></label>
        <label className="block space-y-1.5 text-sm">
          <span className="text-muted-foreground">Почта — по ней он будет входить</span>
          <Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="aizhan@mail.ru" inputMode="email" />
        </label>
        <label className="block space-y-1.5 text-sm">
          <span className="text-muted-foreground">Телефон — по желанию, чтобы отправить ссылку в WhatsApp</span>
          <Input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+7 705 300 44 55" inputMode="tel" />
        </label>
        <p className="text-xs text-muted-foreground">Роль можно поменять, а человека убрать — в любой момент.</p>
      </div>
    </AppModal>
  )
}

function AccessModal({ access, onClose }: { access: Access; onClose: () => void }) {
  const [copied, setCopied] = useState(false)
  if (!access.link) {
    return (
      <AppModal open onClose={onClose} title={`${access.name} добавлен`} maxWidth="max-w-md">
        <p className="text-sm text-muted-foreground">Ссылку для входа сделать не получилось. Нажмите «Ссылка для входа» напротив человека в списке — она создастся заново.</p>
      </AppModal>
    )
  }
  const text = `Здравствуйте! Вот вход в нашу программу. Откройте ссылку и придумайте пароль:\n${access.link}`
  const wa = `https://wa.me/${(access.phone || '').replace(/\D/g, '')}?text=${encodeURIComponent(text)}`
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(access.link!)
      setCopied(true)
    } catch {
      toast({ title: 'Не скопировалось', description: 'Выделите ссылку и скопируйте вручную.', variant: 'destructive' })
    }
  }
  return (
    <AppModal open onClose={onClose} title={`Вход для: ${access.name}`} description="Отправьте ссылку человеку. Он откроет её, придумает пароль и попадёт в программу." maxWidth="max-w-md">
      <div className="space-y-3">
        <Input readOnly value={access.link} onFocus={(e) => e.currentTarget.select()} aria-label="Ссылка для входа" className="font-mono text-xs" />
        <div className="flex flex-wrap gap-2">
          {access.phone ? (
            <a href={wa} target="_blank" rel="noopener noreferrer" className="flex h-11 flex-1 items-center justify-center rounded-xl bg-[#1fa855] px-4 text-sm font-bold text-white hover:brightness-105">Отправить в WhatsApp</a>
          ) : null}
          <Button variant="outline" className="h-11 flex-1" onClick={copy}>{copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}{copied ? 'Скопировано' : 'Скопировать'}</Button>
        </div>
        <p className="text-xs text-muted-foreground">Ссылка одноразовая и действует один час. Не успел открыть — сделайте новую кнопкой «Ссылка для входа».</p>
      </div>
    </AppModal>
  )
}
