'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Archive, Loader2, Pencil, RotateCcw, Save, Store, Users, X } from 'lucide-react'

import { MoneyInput } from '@/components/ui/money-input'
import { NativeSelect } from '@/components/ui/native-select'
import { toast } from '@/components/ui/use-toast'
import { invalidatePointPositions } from '@/components/admin/position-select'
import { peopleCountLabel, type PointBaseRole } from '@/lib/core/positions'
import {
  ALL_KASSA_PERMISSIONS,
  ALL_LEAD_PERMISSIONS,
  DEFAULT_KASSA_PERMISSIONS,
  KASSA_PERMISSIONS,
  LEAD_PERMISSIONS,
  assignmentPointPermissions,
  type PointPermission,
} from '@/lib/core/point-permissions'

export type BaseRole = PointBaseRole

export type PointPosition = {
  id: string
  company_id: string | null
  name: string
  base_role: BaseRole
  /** null — бонус старшего из правил зарплаты по base_role. */
  shift_bonus: number | null
  /** null — права по base_role (старший — всё). */
  permissions?: string[] | null
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

/** Откуда доплата: правила зарплаты (с бонусом старшего или без) или своя сумма. */
export type BonusMode = 'rules:operator' | 'rules:senior_operator' | 'rules:senior_cashier' | 'fixed'

const BONUS_MODES: Array<{ value: BonusMode; label: string }> = [
  { value: 'rules:operator', label: 'Ставка из правил зарплаты, без бонуса старшего' },
  { value: 'rules:senior_operator', label: 'Ставка + бонус старшего оператора из правил' },
  { value: 'rules:senior_cashier', label: 'Ставка + бонус старшего кассира из правил' },
  { value: 'fixed', label: 'Ставка + своя доплата за смену' },
]

export type PointPositionDraft = {
  id: string | null
  name: string
  company_id: string
  permissions: PointPermission[]
  bonusMode: BonusMode
  bonusAmount: string
}

export function emptyPointDraft(): PointPositionDraft {
  // Права кассы — как сейчас у всех; права старшего — по выбору.
  return { id: null, name: '', company_id: '', permissions: [...DEFAULT_KASSA_PERMISSIONS], bonusMode: 'rules:operator', bonusAmount: '0' }
}

export function draftFromPosition(position: PointPosition): PointPositionDraft {
  return {
    id: position.id,
    name: position.name,
    company_id: position.company_id || '',
    permissions: assignmentPointPermissions({ position: { ...position, is_active: true } }),
    bonusMode: position.shift_bonus == null ? (`rules:${position.base_role}` as BonusMode) : 'fixed',
    bonusAmount: String(position.shift_bonus ?? 0),
  }
}

/** base_role теперь только про деньги: какой бонус старшего брать из правил. */
export function draftToPayload(draft: PointPositionDraft) {
  const fixed = draft.bonusMode === 'fixed'
  return {
    name: draft.name,
    company_id: draft.company_id || null,
    permissions: draft.permissions,
    base_role: fixed ? (draft.permissions.length > 0 ? 'senior_operator' : 'operator') : draft.bonusMode.slice('rules:'.length),
    shift_bonus: fixed ? Number(draft.bonusAmount || 0) : null,
  }
}

function payDescription(position: PointPosition) {
  if (position.shift_bonus != null) return position.shift_bonus > 0 ? `+${money(position.shift_bonus)} за смену` : 'без доплаты'
  if (position.base_role === 'senior_cashier') return 'бонус старшего кассира из правил'
  if (position.base_role === 'senior_operator') return 'бонус старшего оператора из правил'
  return 'ставка из правил'
}

function permissionsDescription(position: PointPosition) {
  const granted = assignmentPointPermissions({ position: { ...position, is_active: true } })
  const lead = ALL_LEAD_PERMISSIONS.filter((p) => granted.includes(p)).length
  const kassa = ALL_KASSA_PERMISSIONS.filter((p) => granted.includes(p)).length
  const leadText = lead === 0 ? 'не старший' : lead === ALL_LEAD_PERMISSIONS.length ? 'старший' : `старший: ${lead} из ${ALL_LEAD_PERMISSIONS.length}`
  const kassaText = kassa === ALL_KASSA_PERMISSIONS.length ? 'касса без ограничений' : `касса: ${kassa} из ${ALL_KASSA_PERMISSIONS.length} прав`
  return `${leadText}, ${kassaText}`
}

function PermissionGroup(props: {
  title: string
  items: ReadonlyArray<{ key: PointPermission; label: string; hint: string }>
  selected: PointPermission[]
  onToggle: (key: PointPermission) => void
}) {
  return (
    <fieldset className="space-y-1.5">
      <legend className="mb-1 text-xs text-slate-400">{props.title}</legend>
      {props.items.map((permission) => (
        <label key={permission.key} className="flex cursor-pointer items-start gap-2.5 rounded-xl border border-border px-3 py-2 text-sm hover:bg-slate-50 dark:hover:bg-white/5">
          <input
            type="checkbox"
            checked={props.selected.includes(permission.key)}
            onChange={() => props.onToggle(permission.key)}
            className="mt-0.5 rounded border-border"
          />
          <span>
            <span className="block font-medium text-foreground">{permission.label}</span>
            <span className="block text-xs text-muted-foreground">{permission.hint}</span>
          </span>
        </label>
      ))}
    </fieldset>
  )
}

/** Поля должности на точке — одни и те же при создании и при правке. */
export function PointPositionFields(props: { draft: PointPositionDraft; onChange: (next: PointPositionDraft) => void; companies: Company[] }) {
  const { draft, onChange } = props
  const togglePermission = (key: PointPermission) =>
    onChange({
      ...draft,
      permissions: draft.permissions.includes(key) ? draft.permissions.filter((p) => p !== key) : [...draft.permissions, key],
    })
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <label className="space-y-1 text-sm">
        <span className="text-xs text-slate-400">Где</span>
        <NativeSelect value={draft.company_id} onChange={(e) => onChange({ ...draft, company_id: e.target.value })}>
          <option value="">На всех точках</option>
          {props.companies.map((c) => (
            <option key={c.id} value={c.id}>{c.name}</option>
          ))}
        </NativeSelect>
      </label>
      <label className="space-y-1 text-sm">
        <span className="text-xs text-slate-400">Деньги за смену</span>
        <NativeSelect value={draft.bonusMode} onChange={(e) => onChange({ ...draft, bonusMode: e.target.value as BonusMode })}>
          {BONUS_MODES.map((mode) => (
            <option key={mode.value} value={mode.value}>{mode.label}</option>
          ))}
        </NativeSelect>
      </label>
      {draft.bonusMode === 'fixed' && (
        <label className="space-y-1 text-sm sm:col-start-2">
          <span className="text-xs text-slate-400">Доплата за смену, ₸ (вместо бонуса старшего)</span>
          <MoneyInput value={draft.bonusAmount} onValueChange={(v) => onChange({ ...draft, bonusAmount: v })} />
        </label>
      )}
      <div className="grid gap-3 sm:col-span-2 lg:grid-cols-2">
        <PermissionGroup title="Старший на точке" items={LEAD_PERMISSIONS} selected={draft.permissions} onToggle={togglePermission} />
        <PermissionGroup title="Касса" items={KASSA_PERMISSIONS} selected={draft.permissions} onToggle={togglePermission} />
      </div>
      <fieldset className="sm:col-span-2">
        <p className="text-xs text-muted-foreground">Продавать, открывать и закрывать смену может любой человек на точке.</p>
      </fieldset>
    </div>
  )
}

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
  const [draft, setDraft] = useState<PointPositionDraft | null>(null)
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
    if (!draft?.id) return
    setSaving(true)
    try {
      await postPointPosition({ action: 'update', id: draft.id, payload: draftToPayload(draft) })
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
          <div key={position.id} className={`${row} space-y-3`}>
            <label className="block space-y-1 text-sm">
              <span className="text-xs text-slate-400">Название</span>
              <input
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                className="w-full rounded-xl border border-border bg-white dark:bg-slate-950/50 px-3 py-2 text-sm"
                autoFocus
              />
            </label>
            <PointPositionFields draft={draft} onChange={setDraft} companies={props.companies} />
            <div className="flex gap-2">
              <button
                type="button"
                onClick={save}
                disabled={saving || !draft.name.trim()}
                className="inline-flex items-center gap-2 rounded-xl border border-blue-400/30 bg-blue-500/15 px-4 py-2 text-sm font-medium text-blue-700 dark:text-blue-200 hover:bg-blue-500/25 disabled:opacity-40"
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
                    {position.company_id ? companyName.get(position.company_id) || 'одна точка' : 'все точки'} · {permissionsDescription(position)} ·{' '}
                    {payDescription(position)}
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
                      onClick={() => setDraft(draftFromPosition(position))}
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
