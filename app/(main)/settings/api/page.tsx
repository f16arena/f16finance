'use client'

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { BookOpen, KeyRound, Loader2, Plus, ShieldOff } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { CopyText } from '@/components/ui/copy-text'
import { Input } from '@/components/ui/input'
import { NativeSelect } from '@/components/ui/native-select'

type ApiKeyRow = {
  id: string
  name: string
  company_id: string
  key_prefix: string
  scopes: string[]
  created_at: string
  last_used_at: string | null
  revoked_at: string | null
}

type Payload = {
  keys: ApiKeyRow[]
  companies: Array<{ id: string; name: string }>
  scopes: Array<{ value: string; label: string }>
}

function formatDateTime(value: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleString('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
}

export default function ApiKeysPage() {
  const [data, setData] = useState<Payload | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [name, setName] = useState('Планировщик смен')
  const [companyId, setCompanyId] = useState('')
  const [scopes, setScopes] = useState<string[]>(['schedule:read', 'schedule:write', 'schedule:publish'])
  const [creating, setCreating] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [secret, setSecret] = useState<{ value: string; name: string } | null>(null)
  const [confirmRevoke, setConfirmRevoke] = useState<string | null>(null)
  const [revoking, setRevoking] = useState<string | null>(null)

  const load = useCallback(async () => {
    const response = await fetch('/api/admin/api-keys', { cache: 'no-store' })
    const json = await response.json().catch(() => ({}))
    if (!response.ok) {
      setLoadError(json?.message || json?.error || 'Не удалось загрузить ключи')
      return
    }
    setData(json)
    setCompanyId((current) => current || json.companies?.[0]?.id || '')
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const companyName = useMemo(() => {
    const map = new Map((data?.companies || []).map((c) => [c.id, c.name]))
    return (id: string) => map.get(id) || 'точка закрыта'
  }, [data])

  const scopeLabel = useMemo(() => {
    const map = new Map((data?.scopes || []).map((s) => [s.value, s.label]))
    return (value: string) => map.get(value) || value
  }, [data])

  async function createKey(event: React.FormEvent) {
    event.preventDefault()
    setCreating(true)
    setFormError(null)
    const response = await fetch('/api/admin/api-keys', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, companyId, scopes }),
    })
    const json = await response.json().catch(() => ({}))
    setCreating(false)
    if (!response.ok) {
      setFormError(json?.error || 'Не удалось создать ключ')
      return
    }
    setSecret({ value: json.secret, name: json.key.name })
    await load()
  }

  async function revoke(id: string) {
    setRevoking(id)
    await fetch(`/api/admin/api-keys?id=${encodeURIComponent(id)}`, { method: 'DELETE' })
    setRevoking(null)
    setConfirmRevoke(null)
    await load()
  }

  return (
    <div className="app-page space-y-5">
      <AdminPageHeader
        title="API-ключи"
        description="Доступ для своих программ: ключ на одну точку и только с выбранными правами"
        icon={<KeyRound className="h-5 w-5" />}
        backHref="/settings"
        actions={
          <Button asChild variant="outline">
            <Link href="/docs/api" target="_blank">
              <BookOpen className="mr-2 h-4 w-4" />
              Документация
            </Link>
          </Button>
        }
      />

      {loadError && <Card className="p-5 text-sm text-red-600 dark:text-red-300">{loadError}</Card>}

      {secret && (
        <Card className="border-blue-500/40 bg-blue-500/5 p-5">
          <h2 className="text-base font-semibold text-foreground">Ключ «{secret.name}» создан</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Скопируйте его сейчас и передайте разработчику. Больше он не покажется — в базе хранится только отпечаток.
            Потеряли — отзовите и выпустите новый.
          </p>
          <CopyText value={secret.value} className="mt-3 block break-all rounded-lg border border-border bg-card px-3 py-2.5 font-mono text-sm">
            {secret.value}
          </CopyText>
          <Button variant="ghost" size="sm" className="mt-2" onClick={() => setSecret(null)}>
            Скопировал, скрыть
          </Button>
        </Card>
      )}

      <Card className="p-5">
        <h2 className="text-base font-semibold text-foreground">Новый ключ</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          Деньги, выручка, зарплата и склад через API недоступны — только то, что отмечено ниже.
        </p>
        {!data ? (
          <div className="mt-4 flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Загрузка…
          </div>
        ) : (
          <form onSubmit={createKey} className="mt-4 grid gap-4 md:grid-cols-2">
            <label className="space-y-1.5 text-sm">
              <span className="font-medium text-foreground">Название</span>
              <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Планировщик смен" maxLength={80} />
            </label>
            <label className="space-y-1.5 text-sm">
              <span className="font-medium text-foreground">Точка</span>
              <NativeSelect value={companyId} onChange={(e) => setCompanyId(e.target.value)}>
                {data.companies.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </NativeSelect>
            </label>
            <fieldset className="space-y-2 text-sm md:col-span-2">
              <legend className="mb-1.5 font-medium text-foreground">Права</legend>
              {data.scopes.map((s) => (
                <label key={s.value} className="flex items-center gap-2.5">
                  <input
                    type="checkbox"
                    className="h-4 w-4 accent-[#2563eb]"
                    checked={scopes.includes(s.value)}
                    onChange={(e) =>
                      setScopes((current) => (e.target.checked ? [...current, s.value] : current.filter((v) => v !== s.value)))
                    }
                  />
                  <span>{s.label}</span>
                  <code className="text-xs text-muted-foreground">{s.value}</code>
                </label>
              ))}
            </fieldset>
            <div className="flex items-center gap-3 md:col-span-2">
              <Button type="submit" disabled={creating || !companyId}>
                {creating ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Plus className="mr-2 h-4 w-4" />}
                Выпустить ключ
              </Button>
              {formError && <span className="text-sm text-red-600 dark:text-red-300">{formError}</span>}
            </div>
          </form>
        )}
      </Card>

      {data && (
        <Card className="p-5">
          <h2 className="text-base font-semibold text-foreground">Выданные ключи</h2>
          {data.keys.length === 0 ? (
            <p className="mt-2 text-sm text-muted-foreground">Пока ни одного.</p>
          ) : (
            <div className="mt-3 divide-y divide-border">
              {data.keys.map((k) => (
                <div key={k.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-foreground">{k.name}</span>
                      <code className="text-xs text-muted-foreground">{k.key_prefix}…</code>
                      {k.revoked_at && (
                        <span className="rounded-full bg-slate-500/15 px-2 py-0.5 text-[11px] text-muted-foreground">отозван</span>
                      )}
                    </div>
                    <div className="mt-0.5 text-xs text-muted-foreground">
                      {companyName(k.company_id)} · {k.scopes.map(scopeLabel).join(', ')}
                    </div>
                    <div className="mt-0.5 text-xs text-muted-foreground">
                      Выдан {formatDateTime(k.created_at)} · последний запрос {formatDateTime(k.last_used_at)}
                      {k.revoked_at ? ` · отозван ${formatDateTime(k.revoked_at)}` : ''}
                    </div>
                  </div>
                  {!k.revoked_at &&
                    (confirmRevoke === k.id ? (
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-muted-foreground">Программа перестанет работать</span>
                        <Button size="sm" variant="destructive" disabled={revoking === k.id} onClick={() => void revoke(k.id)}>
                          {revoking === k.id ? <Loader2 className="h-4 w-4 animate-spin" /> : 'Отозвать'}
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => setConfirmRevoke(null)}>Нет</Button>
                      </div>
                    ) : (
                      <Button size="sm" variant="outline" onClick={() => setConfirmRevoke(k.id)}>
                        <ShieldOff className="mr-2 h-4 w-4" />
                        Отозвать
                      </Button>
                    ))}
                </div>
              ))}
            </div>
          )}
        </Card>
      )}
    </div>
  )
}
