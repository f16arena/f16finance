'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Archive, Loader2, Pencil, RotateCcw, Save, Store, Users, X } from 'lucide-react'

import { MoneyInput } from '@/components/ui/money-input'
import { NativeSelect } from '@/components/ui/native-select'
import { toast } from '@/components/ui/use-toast'
import { invalidatePointPositions } from '@/components/admin/position-select'
import { POINT_BASE_ROLE_LABELS, peopleCountLabel, type PointBaseRole } from '@/lib/core/positions'

export type BaseRole = PointBaseRole

export type PointPosition = {
  id: string
  company_id: string | null
  name: string
  base_role: BaseRole
  shift_bonus: number
  is_active: boolean
  /** Сколько людей сейчас на этой должности (GET /api/admin/point-positions). */
  people?: number
}

/** Подпись прав для списка: «права как у старшего кассира». */
export const BASE_ROLE_LABELS: Record<BaseRole, string> = {
  operator: 'как оператор',
  senior_operator: 'как старший оператор',
  senior_cashier: 'как старший кассир',
}

type Company = { id: string; name: string }
type Draft = { id: string; name: string; company_id: string; base_role: BaseRole; shift_bonus: string }

function money(value: number) {
  return `${Math.round(value).toLocaleString('ru-RU')} ₸`
}

export async function postPointPosition(body: unknown) {
  const response = await fetch('/api/admin/point-positions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const json = await response.json().catch(() => null)
  if (!response.ok) throw new Error(json?.error || 'Не удалось сохранить')
  invalidatePointPositions()
  return json
}

const tile = 'grid h-10 w-10 shrink-0 place-items-center rounded-xl'
const row = 'rounded-2xl border border-border bg-white dark:bg-slate-900/60 p-4 transition-colors hover:border-slate-300 dark:hover:border-white/20'
const iconBtn =
  'rounded-xl border border-border bg-slate-100 dark:bg-white/5 p-2 text-muted-foreground transition-colors hover:bg-slate-200 dark:hover:bg-white/10 hover:text-slate-900 dark:hover:text-white'

/**
 * Должности на точках: «Бариста», «Официант», «Старший смены». Название, где
 * действует, права в кассе (одна из трёх базовых ролей) и доплата за смену.
 * Создаются на странице «Должности и доступ», назначаются в Структуре, найме
 * и мастере точки. Вид строк — как у офисных должностей рядом.
 */
export function PointPositionsList(props: { companies: Company[]; canEdit: boolean; reloadKey?: number }) {
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
  }, [load, props.reloadKey])

  async function save() {
    if (!draft) return
    setSaving(true)
    try {
      await postPointPosition({
        action: 'update',
        id: draft.id,
        payload: {
          name: draft.name,
          company_id: draft.company_id || null,
          base_role: draft.base_role,
          shift_bonus: Number(draft.shift_bonus || 0),
        },
      })
      toast({ title: 'Должность сохранена' })
      setDraft(null)
      await load()
    } catch (error: any) {
      toast({ title: 'Не получилось', description: error.message, variant: 'destructive' })
    }
    setSaving(false)
  }

  async function toggleArchive(position: PointPosition) {
    try {
      await postPointPosition({ action: position.is_active ? 'archive' : 'restore', id: position.id })
      toast({ title: position.is_active ? 'Должность в архиве' : 'Должность возвращена', description: position.name })
      await load()
    } catch (error: any) {
      toast({ title: 'Не получилось', description: error.message, variant: 'destructive' })
    }
  }

  const visible = positions.filter((p) => p.is_active || showArchived)
  const archivedCount = positions.filter((p) => !p.is_active).length

  if (migrationPending) {
    return (
      <p className="rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-800 dark:text-amber-200">
        Должности на точках включатся после обновления базы (миграция 20261024_point_positions).
      </p>
    )
  }

  return (
    <div className="space-y-2.5">
      {loading && (
        <p className="flex items-center gap-2 px-1 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" /> Загрузка…
        </p>
      )}

      {!loading && visible.length === 0 && (
        <div className={`${row} text-center text-sm text-slate-400`}>
          Своих должностей на точках пока нет — люди работают как оператор, старший оператор или старший кассир. Создайте
          свою выше: «Бариста», «Официант», «Старший смены».
        </div>
      )}

      {visible.map((position) =>
        draft?.id === position.id ? (
          <div key={position.id} className={`${row} grid gap-2 sm:grid-cols-2`}>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">Название</span>
              <input
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                className="w-full rounded-xl border border-border bg-white dark:bg-slate-950/50 px-3 py-2 text-sm"
                autoFocus
              />
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
              <button
                type="button"
                onClick={save}
                disabled={saving || !draft.name.trim()}
                className="inline-flex items-center gap-2 rounded-xl border border-emerald-400/30 bg-emerald-500/15 px-4 py-2 text-sm font-medium text-emerald-700 dark:text-emerald-200 hover:bg-emerald-500/25 disabled:opacity-40"
              >
                {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />} Сохранить
              </button>
              <button type="button" onClick={() => setDraft(null)} disabled={saving} className="text-slate-500 hover:text-slate-700 dark:hover:text-slate-300">
                <X className="h-4 w-4" />
              </button>
            </div>
          </div>
        ) : (
          <div key={position.id} className={`${row} ${position.is_active ? '' : 'opacity-60'}`}>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-3">
                <div className={`${tile} bg-gradient-to-br from-violet-400/25 to-amber-400/15 text-violet-700 dark:text-violet-200`}>
                  <Store className="h-5 w-5" />
                </div>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <p className="text-sm font-semibold text-foreground">{position.name}</p>
                    <span className="inline-flex items-center gap-1 rounded-full border border-border bg-slate-100 dark:bg-white/5 px-2 py-0.5 text-[10px] font-medium text-muted-foreground">
                      <Users className="h-3 w-3" /> {peopleCountLabel(position.people || 0)}
                    </span>
                    {!position.is_active && (
                      <span className="rounded-full border border-border px-2 py-0.5 text-[10px] uppercase tracking-wide text-muted-foreground">в архиве</span>
                    )}
                  </div>
                  <p className="truncate text-xs text-slate-400">
                    {position.company_id ? companyName.get(position.company_id) || 'одна точка' : 'все точки'} · права{' '}
                    {POINT_BASE_ROLE_LABELS[position.base_role].toLowerCase()} ·{' '}
                    {position.shift_bonus > 0 ? `+${money(position.shift_bonus)} за смену` : 'без доплаты'}
                  </p>
                </div>
              </div>
              {props.canEdit && (
                <div className="flex shrink-0 items-center gap-2">
                  {position.is_active && (
                    <button
                      type="button"
                      className={iconBtn}
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
                    </button>
                  )}
                  <button
                    type="button"
                    className={iconBtn}
                    aria-label={position.is_active ? 'В архив' : 'Вернуть'}
                    title={position.is_active ? 'В архив — люди сохранят должность, новым её не назначить' : 'Вернуть из архива'}
                    onClick={() => toggleArchive(position)}
                  >
                    {position.is_active ? <Archive className="h-3.5 w-3.5" /> : <RotateCcw className="h-3.5 w-3.5" />}
                  </button>
                </div>
              )}
            </div>
          </div>
        ),
      )}

      {archivedCount > 0 && (
        <button type="button" onClick={() => setShowArchived((v) => !v)} className="px-1 text-xs text-muted-foreground hover:text-foreground">
          {showArchived ? 'Скрыть архив' : `Показать архив (${archivedCount})`}
        </button>
      )}
    </div>
  )
}
