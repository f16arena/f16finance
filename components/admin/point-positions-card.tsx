'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Archive, BadgeCheck, Pencil, Plus, RotateCcw, Save, X } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { MoneyInput } from '@/components/ui/money-input'
import { NativeSelect } from '@/components/ui/native-select'
import { toast } from '@/components/ui/use-toast'

export type BaseRole = 'operator' | 'senior_operator' | 'senior_cashier'

export type PointPosition = {
  id: string
  company_id: string | null
  name: string
  base_role: BaseRole
  shift_bonus: number
  is_active: boolean
}

export const BASE_ROLE_LABELS: Record<BaseRole, string> = {
  operator: 'как оператор',
  senior_operator: 'как старший оператор',
  senior_cashier: 'как старший кассир',
}

type Company = { id: string; name: string }
type Draft = { id: string | null; name: string; company_id: string; base_role: BaseRole; shift_bonus: string }

const EMPTY_DRAFT: Draft = { id: null, name: '', company_id: '', base_role: 'operator', shift_bonus: '0' }

function money(value: number) {
  return `${Math.round(value).toLocaleString('ru-RU')} ₸`
}

/**
 * Свои должности организации: «Бариста», «Официант», «Администратор зала».
 * Должность = название + доплата за смену; права — как у одной из трёх
 * базовых ролей. Назначается человеку на точке (Структура, профиль, мастер).
 */
export function PointPositionsCard(props: { companies: Company[]; canEdit: boolean }) {
  const [positions, setPositions] = useState<PointPosition[]>([])
  const [loading, setLoading] = useState(true)
  const [migrationPending, setMigrationPending] = useState(false)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [saving, setSaving] = useState(false)
  const [showArchived, setShowArchived] = useState(false)

  const companyName = useMemo(() => new Map(props.companies.map((c) => [c.id, c.name])), [props.companies])

  const load = useCallback(async () => {
    const response = await fetch('/api/admin/point-positions', { cache: 'no-store' }).catch(() => null)
    const json = await response?.json().catch(() => null)
    if (response?.ok) {
      setPositions((json?.data || []) as PointPosition[])
      setMigrationPending(!!json?.migrationPending)
    }
    setLoading(false)
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function post(body: unknown) {
    const response = await fetch('/api/admin/point-positions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const json = await response.json().catch(() => null)
    if (!response.ok) throw new Error(json?.error || 'Не удалось сохранить')
  }

  async function save() {
    if (!draft) return
    setSaving(true)
    try {
      const payload = {
        name: draft.name,
        company_id: draft.company_id || null,
        base_role: draft.base_role,
        shift_bonus: Number(draft.shift_bonus || 0),
      }
      await post(draft.id ? { action: 'update', id: draft.id, payload } : { action: 'create', payload })
      toast({ title: draft.id ? 'Должность сохранена' : 'Должность добавлена' })
      setDraft(null)
      await load()
    } catch (error: any) {
      toast({ title: 'Не получилось', description: error.message, variant: 'destructive' })
    }
    setSaving(false)
  }

  async function toggleArchive(position: PointPosition) {
    try {
      await post({ action: position.is_active ? 'archive' : 'restore', id: position.id })
      await load()
    } catch (error: any) {
      toast({ title: 'Не получилось', description: error.message, variant: 'destructive' })
    }
  }

  const visible = positions.filter((p) => p.is_active || showArchived)
  const archivedCount = positions.filter((p) => !p.is_active).length

  return (
    <Card className="p-5">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
            <BadgeCheck className="h-5 w-5 text-violet-500" /> Должности на точках
          </h2>
          <p className="text-sm text-muted-foreground">
            Свои названия и доплата за смену. Доплата заменяет бонус старшего из правил зарплаты.
          </p>
        </div>
        {props.canEdit && !migrationPending && !draft && (
          <Button variant="outline" onClick={() => setDraft({ ...EMPTY_DRAFT })}>
            <Plus className="mr-1 h-4 w-4" /> Должность
          </Button>
        )}
      </div>

      {migrationPending && (
        <p className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-sm text-amber-800 dark:text-amber-200">
          Должности включатся после обновления базы (миграция 20261024_point_positions).
        </p>
      )}

      {draft && (
        <div className="mb-3 grid gap-2 rounded-xl border border-blue-500/30 bg-blue-500/5 p-3 sm:grid-cols-2">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Название</span>
            <Input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Например, Бариста" autoFocus />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Где</span>
            <NativeSelect value={draft.company_id} onChange={(e) => setDraft({ ...draft, company_id: e.target.value })}>
              <option value="">На всех точках</option>
              {props.companies.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </NativeSelect>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Права в кассе</span>
            <NativeSelect value={draft.base_role} onChange={(e) => setDraft({ ...draft, base_role: e.target.value as BaseRole })}>
              {(Object.keys(BASE_ROLE_LABELS) as BaseRole[]).map((role) => (
                <option key={role} value={role}>{BASE_ROLE_LABELS[role]}</option>
              ))}
            </NativeSelect>
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">Доплата за смену, ₸</span>
            <MoneyInput value={draft.shift_bonus} onValueChange={(v) => setDraft({ ...draft, shift_bonus: v })} />
          </label>
          <div className="flex gap-2 sm:col-span-2">
            <Button onClick={save} disabled={saving || !draft.name.trim()}>
              <Save className="mr-1 h-4 w-4" /> Сохранить
            </Button>
            <Button variant="ghost" onClick={() => setDraft(null)} disabled={saving}>
              <X className="mr-1 h-4 w-4" /> Отмена
            </Button>
          </div>
        </div>
      )}

      {!loading && !migrationPending && visible.length === 0 && !draft && (
        <p className="text-sm text-muted-foreground">
          Пока только базовые роли: оператор, старший оператор, старший кассир. Добавьте свои — «Бариста», «Официант».
        </p>
      )}

      <div className="space-y-2">
        {visible.map((position) => (
          <div
            key={position.id}
            className={`flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-border px-3 py-2 text-sm ${position.is_active ? '' : 'opacity-60'}`}
          >
            <span className="min-w-32 font-medium text-foreground">{position.name}</span>
            <span className="text-muted-foreground">
              {position.company_id ? companyName.get(position.company_id) || 'точка' : 'все точки'} · {BASE_ROLE_LABELS[position.base_role]} ·{' '}
              {position.shift_bonus > 0 ? `+${money(position.shift_bonus)} за смену` : 'без доплаты'}
              {!position.is_active && ' · в архиве'}
            </span>
            {props.canEdit && (
              <span className="ml-auto flex gap-1">
                {position.is_active && (
                  <Button
                    size="icon"
                    variant="ghost"
                    className="h-7 w-7"
                    aria-label="Изменить"
                    onClick={() =>
                      setDraft({
                        id: position.id,
                        name: position.name,
                        company_id: position.company_id || '',
                        base_role: position.base_role,
                        shift_bonus: String(position.shift_bonus || 0),
                      })
                    }
                  >
                    <Pencil className="h-3.5 w-3.5" />
                  </Button>
                )}
                <Button size="icon" variant="ghost" className="h-7 w-7" aria-label={position.is_active ? 'В архив' : 'Вернуть'} onClick={() => toggleArchive(position)}>
                  {position.is_active ? <Archive className="h-3.5 w-3.5" /> : <RotateCcw className="h-3.5 w-3.5" />}
                </Button>
              </span>
            )}
          </div>
        ))}
      </div>

      {archivedCount > 0 && (
        <button type="button" onClick={() => setShowArchived((v) => !v)} className="mt-2 text-xs text-muted-foreground hover:text-foreground">
          {showArchived ? 'Скрыть архив' : `Показать архив (${archivedCount})`}
        </button>
      )}
    </Card>
  )
}
