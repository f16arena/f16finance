'use client'

import type { Dispatch, SetStateAction } from 'react'
import { ArrowLeft, ClipboardList, Loader2, Plus, Trash2, Users } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { Skeleton } from '@/components/skeleton'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { StoreSection } from '@/components/store/store-ui'
import { locLabel, type Assignment, type FormData, type Loc } from './types'

/** Вид «Новый аудит-акт»: локация, режим, операторы и секции. */
export function ActCreateForm({
  error,
  locations,
  locationId,
  onLocation,
  formData,
  assignments,
  setAssignments,
  comment,
  setComment,
  mode,
  setMode,
  creating,
  canCreate,
  onBack,
  onCreate,
}: {
  error: string | null
  locations: Loc[]
  locationId: string
  onLocation: (id: string) => void
  formData: FormData | null
  assignments: Assignment[]
  setAssignments: Dispatch<SetStateAction<Assignment[]>>
  comment: string
  setComment: (value: string) => void
  mode: 'single' | 'double'
  setMode: (mode: 'single' | 'double') => void
  creating: boolean
  canCreate: boolean
  onBack: () => void
  onCreate: () => void
}) {
  return (
    <div className="app-page-tight space-y-4">
      <AdminPageHeader
        title="Новый аудит-акт"
        description="Зафиксируйте снимок остатков и назначьте операторов на секции"
        icon={<ClipboardList className="h-5 w-5" />}
        accent="emerald"
        backHref="/store/audit"
        actions={
          <Button variant="ghost" size="sm" onClick={onBack} className="gap-1.5"><ArrowLeft className="h-4 w-4" /> Назад</Button>
        }
      />
      {error ? <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-300">{error}</div> : null}

      <StoreSection title="Параметры акта" subtitle="Что считаем, кто считает и как">
        <div className="space-y-4">
          <div className="space-y-1.5">
            <Label>Что считаем (локация)</Label>
            <NativeSelect value={locationId} onChange={(e) => onLocation(e.target.value)}>
              <option value="" disabled>Выберите витрину или склад</option>
              {locations.map((l) => <option key={l.id} value={l.id}>{locLabel(l)}</option>)}
            </NativeSelect>
          </div>

          {/* Подсказка: как назначить под разные сценарии */}
          <div className="space-y-1.5 rounded-lg border border-sky-500/30 bg-sky-500/[0.06] p-3 text-xs text-sky-800 dark:text-sky-200">
            <div className="font-semibold">💡 Как назначить операторов</div>
            <div>• <b>Несколько кассиров на одну витрину</b> (считают вместе, видят «кто что уже посчитал», помогают друг другу) → режим <b>«Обычный»</b>, добавь всех на <b>«Вся локация»</b>.</div>
            <div>• <b>Поделить по секциям</b> (каждый считает свою часть, не пересекаются) → <b>«Обычный»</b>, каждому свою секцию.</div>
            <div>• <b>Перепроверка</b> (двое считают одно и то же вслепую, система ловит расхождения) → <b>«Двойной слепой»</b>, 2 оператора на одну секцию.</div>
            <div className="pt-0.5 text-sky-700/80 dark:text-sky-300/70">Системный остаток кассирам не виден — считают честно «с нуля». Расхождения покажутся тебе при закрытии акта.</div>
          </div>

          <div className="space-y-1.5">
            <Label>Режим подсчёта</Label>
            <div className="flex border border-border">
              <button type="button" onClick={() => setMode('single')} className={`flex-1 px-3 py-2 text-xs transition ${mode === 'single' ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300' : 'text-muted-foreground hover:text-foreground'}`}>Обычный</button>
              <button type="button" onClick={() => setMode('double')} className={`flex-1 border-l border-border px-3 py-2 text-xs transition ${mode === 'double' ? 'bg-amber-500/15 text-amber-700 dark:text-amber-300' : 'text-muted-foreground hover:text-foreground'}`}>Двойной слепой</button>
            </div>
            <p className="text-[11px] text-muted-foreground">{mode === 'double' ? 'Назначь 2 операторов на ОДНУ секцию — посчитают независимо, расхождение между ними пойдёт на пересчёт.' : 'Можно несколько операторов на одну секцию (считают вместе, видят друг друга) или каждому свою секцию.'}</p>
          </div>

          {locationId && !formData ? <div className="space-y-2"><Skeleton className="h-9 w-full" /><Skeleton className="h-9 w-full" /></div> : null}

          {formData ? (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <Label className="flex items-center gap-1.5"><Users className="h-3.5 w-3.5" /> Операторы и секции</Label>
                <Button type="button" variant="outline" size="sm" onClick={() => setAssignments((p) => [...p, { operator_id: '', category_id: null }])} className="h-8 gap-1"><Plus className="h-3.5 w-3.5" /> Добавить</Button>
              </div>
              {formData.operators.length === 0 ? (
                <div className="text-xs text-muted-foreground">У этой точки нет назначенных операторов — можно добавить с других точек ниже.</div>
              ) : null}
              {assignments.map((a, idx) => (
                <div key={idx} className="flex flex-col gap-2 sm:flex-row sm:items-center">
                  <NativeSelect
                    className="flex-1"
                    value={a.operator_id}
                    onChange={(e) => { const v = e.target.value; setAssignments((p) => p.map((x, i) => (i === idx ? { ...x, operator_id: v } : x))) }}
                  >
                    <option value="" disabled>Оператор</option>
                    {formData.operators.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                    {formData.otherOperators && formData.otherOperators.length > 0 ? (
                      <optgroup label="С других точек (в помощь)">
                        {formData.otherOperators.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                      </optgroup>
                    ) : null}
                  </NativeSelect>
                  <NativeSelect
                    className="flex-1"
                    value={a.category_id || 'all'}
                    onChange={(e) => { const v = e.target.value; setAssignments((p) => p.map((x, i) => (i === idx ? { ...x, category_id: v === 'all' ? null : v } : x))) }}
                  >
                    <option value="all">Вся локация</option>
                    {formData.categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </NativeSelect>
                  <Button type="button" variant="ghost" size="icon" className="self-end sm:self-auto" onClick={() => setAssignments((p) => p.filter((_, i) => i !== idx))}><Trash2 className="h-4 w-4 text-muted-foreground" /></Button>
                </div>
              ))}
            </div>
          ) : null}

          <div className="space-y-1.5">
            <Label>Комментарий</Label>
            <Textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={2} placeholder="Например: плановая ревизия за июнь" />
          </div>

          {canCreate && (
            <Button onClick={onCreate} disabled={creating || !locationId} className="w-full gap-2 bg-amber-600 hover:bg-amber-700">
              {creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
              Открыть акт
            </Button>
          )}
          <p className="text-xs text-muted-foreground">При открытии фиксируется снимок остатков. Операторы считают вслепую — системную цифру они не видят.</p>
        </div>
      </StoreSection>
    </div>
  )
}
