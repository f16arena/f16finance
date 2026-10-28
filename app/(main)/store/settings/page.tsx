'use client'

import { useCallback, useEffect, useState } from 'react'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { Skeleton } from '@/components/skeleton'
import { Settings, Store, Loader2, Save } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/hooks/use-toast'
import { StoreSection, StoreChip, StoreEmpty } from '@/components/store/store-ui'

type Company = { id: string; name: string; code: string | null; store_enabled?: boolean }

export default function StoreSettingsPage() {
  const { can } = useCapabilities()
  const [companies, setCompanies] = useState<Company[]>([])
  const [enabled, setEnabled] = useState<Set<string>>(new Set())
  const [savedEnabled, setSavedEnabled] = useState<Set<string>>(new Set())
  const [canManage, setCanManage] = useState(false)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [msg, setMsg] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setErr(null)
    try {
      const res = await fetch('/api/admin/store/config', { cache: 'no-store' })
      const j = await res.json()
      if (!res.ok || !j.ok) throw new Error(j.error || 'Ошибка')
      setCompanies(j.data.companies || [])
      const ids = new Set<string>((j.data.store_enabled_ids || []) as string[])
      setEnabled(new Set(ids))
      setSavedEnabled(new Set(ids))
      setCanManage(!!j.data.can_manage)
    } catch (e: any) { setErr(e?.message || 'Ошибка') } finally { setLoading(false) }
  }, [])

  useEffect(() => { load() }, [load])

  const toggle = (id: string) => {
    if (!canManage) return
    setEnabled((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n })
  }

  const save = async () => {
    setSaving(true); setErr(null); setMsg(null)
    try {
      const res = await fetch('/api/admin/store/config', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ store_enabled_ids: Array.from(enabled) }),
      })
      const j = await res.json()
      if (!res.ok || !j.ok) throw new Error(j.error || 'Ошибка сохранения')
      setSavedEnabled(new Set(enabled))
      setMsg('Сохранено')
      toast({ title: 'Настройки магазина сохранены' })
      setTimeout(() => setMsg(null), 2500)
    } catch (e: any) { setErr(e?.message || 'Ошибка') } finally { setSaving(false) }
  }

  const sameSet = (a: Set<string>, b: Set<string>) => a.size === b.size && Array.from(a).every((x) => b.has(x))
  const dirty = !sameSet(enabled, savedEnabled)

  return (
    <div className="app-page-wide space-y-5">
      <AdminPageHeader
        title="Настройки магазина"
        description="Какие точки являются магазинами. У каждого магазина свои товары, склад, техкарты, документы и касса — данные между точками изолированы."
        icon={<Settings className="h-5 w-5" />}
        accent="emerald"
        backHref="/store"
      />

      <StoreSection
        title="Точки-магазины"
        subtitle={loading ? undefined : `Выбрано ${enabled.size} из ${companies.length}`}
        icon={<Store className="h-4 w-4" />}
      >
        <p className="mb-4 text-xs text-muted-foreground">
          Включите точки, которые работают как магазин — можно несколько. В шапке модуля вы переключаетесь между ними и режимом «Общий» (все магазины сразу).
        </p>

        {loading ? (
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} className="h-[58px] w-full rounded-xl" />)}
          </div>
        ) : companies.length === 0 ? (
          <StoreEmpty title="Нет точек" hint="Создайте их в «Настройки → Компании»." icon={<Store className="h-5 w-5" />} />
        ) : (
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {companies.map((c) => {
              const active = enabled.has(c.id)
              return (
                <label
                  key={c.id}
                  className={`flex items-center justify-between gap-3 rounded-xl border px-4 py-3 transition-colors ${
                    active ? 'border-blue-400/40 bg-blue-500/10' : 'border-border bg-slate-50 hover:border-slate-300 dark:bg-white/[0.02] dark:hover:border-white/20'
                  } ${canManage ? 'cursor-pointer' : 'cursor-not-allowed opacity-70'}`}
                >
                  <div className="min-w-0">
                    <div className="truncate text-sm font-medium text-foreground">{c.name}</div>
                    <div className="text-xs text-muted-foreground">{c.code || (active ? 'Магазин' : 'Не магазин')}</div>
                  </div>
                  <Switch checked={active} disabled={!canManage} onCheckedChange={() => toggle(c.id)} aria-label={c.name} />
                </label>
              )
            })}
          </div>
        )}
      </StoreSection>

      {/* Панель сохранения всегда на виду */}
      <div className="sticky bottom-3 z-10 flex flex-wrap items-center gap-3 rounded-2xl border border-border bg-white/95 px-4 py-3 shadow-lg backdrop-blur dark:bg-slate-900/90">
        {dirty ? <StoreChip tone="amber">Есть несохранённые изменения</StoreChip> : msg ? <StoreChip tone="emerald">{msg}</StoreChip> : <span className="text-xs text-muted-foreground">Все изменения сохранены</span>}
        {err && <span className="text-sm text-rose-600 dark:text-rose-300">{err}</span>}
        {!canManage && <span className="text-xs text-muted-foreground">Менять может только владелец или менеджер</span>}
        {can('store-settings.edit') && (
          <Button onClick={save} disabled={!canManage || saving || !dirty} className="ml-auto bg-blue-600 text-white hover:bg-blue-700">
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            Сохранить
          </Button>
        )}
      </div>
    </div>
  )
}
