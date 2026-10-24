'use client'

import { useEffect, useState } from 'react'
import { Plus, Save } from 'lucide-react'

import { AppModal } from '@/components/ui/app-modal'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/components/ui/use-toast'
import { MAX_REVENUE_ZONES, type RevenueZone } from '@/lib/core/revenue-zones'

type ZoneStats = Record<string, { total30: number; lastDate: string | null }>
type Draft = RevenueZone & { isNew?: boolean }

const PRESETS = ['ПК', 'PS5', 'VR', 'Бильярд', 'Кальян', 'Караоке', 'Бар', 'Кухня']

function money(value: number) {
  return `${Math.round(value).toLocaleString('ru-RU')} ₸`
}

function daysSince(iso: string) {
  return Math.floor((Date.now() - new Date(`${iso}T00:00:00`).getTime()) / 86400_000)
}

/**
 * Зоны выручки точки: включить, выключить, переименовать, добавить. Рядом —
 * выручка за 30 дней и последняя запись, чтобы мёртвую зону было видно сразу.
 * Зону не удаляют: её ключ лежит в истории доходов.
 */
export function RevenueZonesDialog(props: { company: { id: string; name: string } | null; onClose: () => void; onSaved: () => void }) {
  const [zones, setZones] = useState<Draft[]>([])
  const [stats, setStats] = useState<ZoneStats>({})
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [newLabel, setNewLabel] = useState('')

  const companyId = props.company?.id || null

  useEffect(() => {
    if (!companyId) return
    let cancelled = false
    setLoading(true)
    setNewLabel('')
    fetch('/api/admin/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ entity: 'company', action: 'zones', id: companyId }),
    })
      .then((r) => r.json().then((json) => ({ ok: r.ok, json })))
      .then(({ ok, json }) => {
        if (cancelled) return
        if (!ok) throw new Error(json?.error || 'Не удалось загрузить зоны')
        setZones((json.zones || []) as Draft[])
        setStats((json.stats || {}) as ZoneStats)
      })
      .catch((error) => !cancelled && toast({ title: 'Не получилось', description: error.message, variant: 'destructive' }))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [companyId])

  const addZone = (label: string) => {
    const clean = label.trim()
    if (!clean) return
    if (zones.some((z) => z.label.toLowerCase() === clean.toLowerCase())) {
      toast({ title: `Зона «${clean}» уже есть` })
      return
    }
    setZones((prev) => [...prev, { key: '', label: clean, active: true, isNew: true }])
    setNewLabel('')
  }

  const update = (index: number, patch: Partial<Draft>) =>
    setZones((prev) => prev.map((z, i) => (i === index ? { ...z, ...patch } : z)))

  const save = async () => {
    if (!companyId) return
    setSaving(true)
    try {
      const response = await fetch('/api/admin/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          entity: 'company',
          action: 'saveZones',
          id: companyId,
          zones: zones.map(({ key, label, active }) => ({ key, label, active })),
        }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok) throw new Error(json?.error || 'Не удалось сохранить')
      toast({ title: 'Зоны сохранены', description: props.company?.name })
      props.onSaved()
    } catch (error: any) {
      toast({ title: 'Не получилось', description: error.message, variant: 'destructive' })
    }
    setSaving(false)
  }

  const activeCount = zones.filter((z) => z.active).length
  const unusedPresets = PRESETS.filter((p) => !zones.some((z) => z.label.toLowerCase() === p.toLowerCase()))

  return (
    <AppModal
      open={!!props.company}
      onClose={props.onClose}
      title={`Зоны выручки · ${props.company?.name || ''}`}
      description="По включённым зонам вносится приход и делится выручка в отчётах. Выключенная зона пропадает из формы, её история остаётся."
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={props.onClose} disabled={saving}>
            Отмена
          </Button>
          <Button onClick={save} disabled={saving || loading}>
            <Save className="mr-1 h-4 w-4" /> Сохранить
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        {loading && <p className="text-sm text-muted-foreground">Загружаю…</p>}

        {!loading && zones.length === 0 && (
          <p className="rounded-lg border border-dashed border-border px-3 py-2.5 text-sm text-muted-foreground">
            Зон нет — приход на этой точке вносится одной суммой. Добавьте зоны, если хотите видеть выручку по каждой отдельно.
          </p>
        )}

        {!loading &&
          zones.map((zone, index) => {
            const stat = zone.key ? stats[zone.key] : undefined
            const idle = zone.active && !zone.isNew && (!stat?.lastDate || daysSince(stat.lastDate) > 30)
            return (
              <div
                key={zone.key || `new-${index}`}
                className={`rounded-xl border px-3 py-2.5 ${zone.active ? 'border-border' : 'border-dashed border-border opacity-60'}`}
              >
                <div className="flex items-center gap-3">
                  <Switch checked={zone.active} onCheckedChange={(checked) => update(index, { active: checked })} aria-label="Зона включена" />
                  <Input value={zone.label} onChange={(e) => update(index, { label: e.target.value })} className="h-8 flex-1" maxLength={40} />
                </div>
                <p className="mt-1.5 pl-11 text-xs text-muted-foreground">
                  {zone.isNew
                    ? 'новая зона'
                    : stat?.lastDate
                      ? `за 30 дней ${money(stat.total30)} · последняя запись ${new Date(`${stat.lastDate}T00:00:00`).toLocaleDateString('ru-RU')}`
                      : 'за год выручки не было'}
                  {!zone.active && ' · выключена'}
                </p>
                {idle && (
                  <p className="mt-1 pl-11 text-xs text-amber-700 dark:text-amber-300">
                    Больше месяца без выручки — если зону закрыли, выключите её, чтобы не мешала в форме.
                  </p>
                )}
              </div>
            )
          })}

        {!loading && zones.length < MAX_REVENUE_ZONES && (
          <div className="space-y-2 pt-1">
            <div className="flex gap-2">
              <Input
                value={newLabel}
                onChange={(e) => setNewLabel(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault()
                    addZone(newLabel)
                  }
                }}
                placeholder="Новая зона, например «Бильярд»"
                className="h-9"
                maxLength={40}
              />
              <Button variant="outline" onClick={() => addZone(newLabel)} disabled={!newLabel.trim()}>
                <Plus className="mr-1 h-4 w-4" /> Добавить
              </Button>
            </div>
            {unusedPresets.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {unusedPresets.map((preset) => (
                  <button
                    key={preset}
                    type="button"
                    onClick={() => addZone(preset)}
                    className="rounded-full border border-border px-2.5 py-0.5 text-xs text-muted-foreground hover:border-blue-500/50 hover:text-foreground"
                  >
                    + {preset}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        {!loading && zones.length > 0 && activeCount === 0 && (
          <p className="text-xs text-muted-foreground">Все зоны выключены — приход будет вноситься одной суммой.</p>
        )}
      </div>
    </AppModal>
  )
}
