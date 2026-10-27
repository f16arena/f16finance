'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { ArrowLeft, ClipboardList } from 'lucide-react'

import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { CardSkeleton, TableSkeleton } from '@/components/skeleton'
import { Button } from '@/components/ui/button'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { useStoreApiUrl } from '@/components/store/store-scope'
import { StoreSection } from '@/components/store/store-ui'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'
import { invalidateStoreCaches } from '@/lib/client/store-cache'
import { toast } from '@/hooks/use-toast'
import { confirmDialog } from '@/components/ui/confirm-dialog'

import { ActCreateForm } from './_parts/act-create-form'
import { ActHeaderCard } from './_parts/act-header-card'
import { ActsList } from './_parts/acts-list'
import { CloseResult } from './_parts/close-result'
import { CountProgress } from './_parts/count-progress'
import { ZeroPreviewModal } from './_parts/zero-preview-modal'
import type {
  ActListRow,
  Assignment,
  CloseRow,
  CloseSummary,
  Detail,
  FormData,
  Loc,
  ReportRow,
} from './_parts/types'

export default function StoreAuditPage() {
  const { can } = useCapabilities()
  const storeUrl = useStoreApiUrl()
  const [view, setView] = useState<'list' | 'create' | 'detail'>('list')
  const [acts, setActs] = useState<ActListRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // Ошибки действий — тостами: карточка ошибки прячется за окном предпросмотра
  const notifyError = (message: string) => toast({ title: message, variant: 'destructive' })

  const [locations, setLocations] = useState<Loc[]>([])
  const [locationId, setLocationId] = useState('')
  const [formData, setFormData] = useState<FormData | null>(null)
  const [assignments, setAssignments] = useState<Assignment[]>([])
  const [comment, setComment] = useState('')
  const [mode, setMode] = useState<'single' | 'double'>('single')
  const [creating, setCreating] = useState(false)

  const [detail, setDetail] = useState<Detail | null>(null)
  const [detailId, setDetailId] = useState('')
  const [closing, setClosing] = useState(false)
  const [reverting, setReverting] = useState(false)
  const [canceling, setCanceling] = useState(false)
  // Пока идёт «принять значение»/«на пересчёт» — кнопки строки заблокированы
  const [itemBusy, setItemBusy] = useState<string | null>(null)
  const [assignDebt, setAssignDebt] = useState(false)
  // Жёсткая (полная) ревизия: непосчитанное обнуляется
  const [zeroUncounted, setZeroUncounted] = useState(false)
  const [assignDebtZeroed, setAssignDebtZeroed] = useState(false)
  const [zeroPreviewOpen, setZeroPreviewOpen] = useState(false)
  // Что именно обнулить: по умолчанию не трогаем то, что продавалось за неделю
  const [zeroSelected, setZeroSelected] = useState<Set<string>>(new Set())
  const [debtsCreated, setDebtsCreated] = useState<number | null>(null)
  const [closeReport, setCloseReport] = useState<CloseRow[] | null>(null)
  const [closeSummary, setCloseSummary] = useState<CloseSummary | null>(null)

  const actsUrl = storeUrl('/api/admin/store/audit')

  const loadActs = useCallback(async (opts?: { fresh?: boolean }) => {
    const cached = opts?.fresh ? null : readApiCache<ActListRow[]>(actsUrl)
    if (cached) { setActs(cached); setLoading(false) }
    else setLoading(true)
    try {
      const res = await fetch(actsUrl, { cache: 'no-store' })
      const j = await res.json().catch(() => null)
      if (!res.ok) throw new Error(j?.error || 'Ошибка')
      const rows = j?.data || []
      writeApiCache(actsUrl, rows)
      setActs(rows)
      setError(null)
    } catch (e: any) {
      setError(e?.message || 'Не удалось загрузить акты')
    } finally {
      setLoading(false)
    }
  }, [actsUrl])

  useEffect(() => {
    void loadActs()
    void (async () => {
      const res = await fetch(storeUrl('/api/admin/store/revisions?scope=all'), { cache: 'no-store' })
      const j = await res.json().catch(() => null)
      if (res.ok) setLocations((j?.data?.locations || []) as Loc[])
    })()
  }, [loadActs, storeUrl])

  // загрузка операторов/категорий при выборе локации в форме создания
  useEffect(() => {
    if (view !== 'create' || !locationId) return
    setFormData(null)
    setAssignments([])
    void (async () => {
      const res = await fetch(storeUrl(`/api/admin/store/audit?form=${encodeURIComponent(locationId)}`), { cache: 'no-store' })
      const j = await res.json().catch(() => null)
      if (res.ok) setFormData({ operators: j?.data?.operators || [], otherOperators: j?.data?.otherOperators || [], categories: j?.data?.categories || [] })
    })()
  }, [view, locationId, storeUrl])

  const openDetail = useCallback(async (id: string, keepReport = false) => {
    setDetailId(id)
    setView('detail')
    setDetail(null)
    if (!keepReport) {
      setCloseReport(null)
      setCloseSummary(null)
      setDebtsCreated(null)
    }
    const res = await fetch(storeUrl(`/api/admin/store/audit?act=${encodeURIComponent(id)}`), { cache: 'no-store' })
    const j = await res.json().catch(() => null)
    if (res.ok) setDetail(j?.data || null)
    else toast({ title: j?.error || 'Не удалось загрузить акт', variant: 'destructive' })
  }, [storeUrl])

  // Живой опрос: пока открыта деталь НЕзакрытого акта — каждые 4с тихо подтягиваем
  // подсчёты операторов и прогресс (без мигания), чтобы видеть подсчёт в реальном времени.
  useEffect(() => {
    if (view !== 'detail' || !detailId || detail?.act.status !== 'open') return
    const t = setInterval(() => {
      fetch(storeUrl(`/api/admin/store/audit?act=${encodeURIComponent(detailId)}`), { cache: 'no-store' })
        .then((r) => (r.ok ? r.json() : null))
        .then((j) => { if (j?.data) setDetail(j.data) })
        .catch(() => {})
    }, 4000)
    return () => clearInterval(t)
  }, [view, detailId, detail?.act.status, storeUrl])

  const createAct = async () => {
    if (creating) return
    if (!locationId || assignments.filter((a) => a.operator_id).length === 0) {
      notifyError('Выберите локацию и хотя бы одного оператора')
      return
    }
    setCreating(true)
    try {
      const res = await fetch('/api/admin/store/audit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'create', location_id: locationId, comment, mode, assignments: assignments.filter((a) => a.operator_id) }),
      })
      const j = await res.json().catch(() => null)
      if (!res.ok) throw new Error(j?.error || 'Ошибка создания')
      toast({ title: 'Акт создан' })
      invalidateStoreCaches()
      await loadActs({ fresh: true })
      await openDetail(j.data.id)
    } catch (e: any) {
      notifyError(e?.message || 'Не удалось создать акт')
    } finally {
      setCreating(false)
    }
  }

  const closeAct = async (force = false, skipConfirm = false) => {
    if (!detailId || closing) return
    const ok = skipConfirm
      ? true
      : force
      ? await confirmDialog({
          title: 'Принудительно закрыть акт?',
          description: 'Блокировки игнорируются: заявки склад ↔ витрина в пути, расхождения двойного счёта, отсутствие подсчёта. Посчитанные позиции проведутся (расхождение двойного счёта берётся по последнему счёту), непосчитанные останутся без изменений.',
          confirmLabel: 'Закрыть принудительно',
          destructive: true,
        })
      : await confirmDialog({
          title: 'Закрыть акт и провести ревизию?',
          description: 'Остатки будут обновлены.',
          confirmLabel: 'Закрыть и провести',
        })
    if (!ok) return
    setZeroPreviewOpen(false)
    setClosing(true)
    try {
      const res = await fetch('/api/admin/store/audit', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'close', act_id: detailId, assignDebt, force, zero_uncounted: !force && zeroUncounted, zero_item_ids: !force && zeroUncounted ? Array.from(zeroSelected) : undefined, assignDebtZeroed: !force && zeroUncounted && assignDebtZeroed }) })
      const j = await res.json().catch(() => null)
      if (!res.ok) throw new Error(j?.message || j?.error || 'Ошибка закрытия')
      setCloseReport((j?.data?.report || []) as CloseRow[])
      setCloseSummary((j?.data?.summary || null) as CloseSummary | null)
      setDebtsCreated(Number(j?.data?.debtsCreated || 0))
      toast({ title: 'Акт закрыт, ревизия проведена' })
      invalidateStoreCaches()
      await loadActs({ fresh: true })
      await openDetail(detailId, true)
    } catch (e: any) {
      notifyError(e?.message || 'Не удалось закрыть акт')
    } finally {
      setClosing(false)
    }
  }

  const cancelAct = async () => {
    if (!detailId || canceling) return
    const ok = await confirmDialog({
      title: 'Отменить акт?',
      description: 'Снимок и введённые подсчёты будут отброшены. Остатки НЕ изменятся (акт ещё не проведён). Акт станет «Отменён».',
      confirmLabel: 'Отменить акт',
      cancelLabel: 'Назад',
      destructive: true,
    })
    if (!ok) return
    setCanceling(true)
    try {
      const res = await fetch('/api/admin/store/audit', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'cancel', act_id: detailId }) })
      const j = await res.json().catch(() => null)
      if (!res.ok) throw new Error(j?.message || j?.error || 'Ошибка отмены')
      toast({ title: 'Акт отменён' })
      invalidateStoreCaches()
      await loadActs({ fresh: true })
      setView('list')
    } catch (e: any) {
      notifyError(e?.message || 'Не удалось отменить акт')
    } finally {
      setCanceling(false)
    }
  }

  const revertAct = async () => {
    if (!detailId || reverting) return
    const ok = await confirmDialog({
      title: 'Откатить ревизию?',
      description: 'Остатки вернутся к состоянию до проведения акта (изменения ревизии развернутся; продажи/движения после ревизии сохранятся). Созданные этим актом активные долги будут удалены. Акт станет «Отменён». Действие необратимо.',
      confirmLabel: 'Откатить',
      destructive: true,
    })
    if (!ok) return
    setReverting(true)
    try {
      const res = await fetch('/api/admin/store/audit', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'revert', act_id: detailId }) })
      const j = await res.json().catch(() => null)
      if (!res.ok) throw new Error(j?.message || j?.error || 'Ошибка отката')
      toast({
        title: 'Откат выполнен',
        description: `Возвращено позиций: ${j?.data?.reversedItems ?? 0}. Удалено долгов: ${j?.data?.debtsRemoved ?? 0}.`,
      })
      setCloseReport(null)
      setCloseSummary(null)
      invalidateStoreCaches()
      await loadActs({ fresh: true })
      await openDetail(detailId)
    } catch (e: any) {
      notifyError(e?.message || 'Не удалось откатить акт')
    } finally {
      setReverting(false)
    }
  }

  // Решение расхождения: ответ сервера раньше игнорировался — кнопка «ничего не делала»
  const postItemAction = async (body: Record<string, unknown>, itemId: string, okTitle: string, failTitle: string) => {
    if (itemBusy) return
    setItemBusy(itemId)
    try {
      const res = await fetch('/api/admin/store/audit', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
      const j = await res.json().catch(() => null)
      if (!res.ok) throw new Error(j?.message || j?.error || failTitle)
      toast({ title: okTitle })
      await openDetail(detailId)
    } catch (e: any) {
      notifyError(e?.message || failTitle)
    } finally {
      setItemBusy(null)
    }
  }

  const recountItem = (itemId: string) =>
    postItemAction({ action: 'recount', act_id: detailId, item_id: itemId }, itemId, 'Позиция отправлена на пересчёт', 'Не удалось отправить на пересчёт')

  const resolveItem = (itemId: string, qty: number) =>
    postItemAction({ action: 'resolve', act_id: detailId, item_id: itemId, qty }, itemId, 'Значение принято', 'Не удалось принять значение')

  // Слепая ревизия по умолчанию: пока акт открыт, расхождения скрыты, чтобы
  // считающий не «подгонял» цифру под учёт. Владелец может раскрыть их у себя —
  // тогда видно недостачу сразу, не дожидаясь закрытия акта.
  const [revealVariance, setRevealVariance] = useState(false)

  // Для текущего/исторического вида (без closeReport) — грубая прикидка по detail.report.
  const totals = useMemo(() => {
    const rows = detail?.report || []
    let short = 0
    let surplus = 0
    let shortValue = 0
    let surplusValue = 0
    for (const r of rows) {
      const price = Number(r.purchase_price || 0)
      if (r.variance < 0) {
        short += -r.variance
        shortValue += -r.variance * price
      } else if (r.variance > 0) {
        surplus += r.variance
        surplusValue += r.variance * price
      }
    }
    return { short, surplus, shortValue, surplusValue }
  }, [detail?.report])

  // ── Список ───────────────────────────────────────────────────────────────
  if (view === 'list') {
    return (
      <ActsList
        acts={acts}
        loading={loading}
        error={error}
        onRefresh={() => void loadActs({ fresh: true })}
        onNew={() => { setView('create'); setLocationId(''); setAssignments([]); setComment(''); setMode('single') }}
        onOpen={(id) => void openDetail(id)}
      />
    )
  }

  // ── Создание ────────────────────────────────────────────────────────────
  if (view === 'create') {
    return (
      <ActCreateForm
        error={error}
        locations={locations}
        locationId={locationId}
        onLocation={setLocationId}
        formData={formData}
        assignments={assignments}
        setAssignments={setAssignments}
        comment={comment}
        setComment={setComment}
        mode={mode}
        setMode={setMode}
        creating={creating}
        canCreate={can('store-revisions.create')}
        onBack={() => setView('list')}
        onCreate={createAct}
      />
    )
  }

  // ── Детали / закрытие ────────────────────────────────────────────────────
  const detailRows: ReportRow[] = (detail?.report || []) as ReportRow[]
  const isOpen = detail?.act.status === 'open'
  // Раскрывать расхождения во время счёта может тот, кто акт и закрывает
  const canSeeVariance = can('store-revisions.commit')
  const showVariance = !isOpen || (canSeeVariance && revealVariance)
  const hasConflicts = isOpen && !closeReport && detailRows.some((r) => r.conflict)
  return (
    <div className="app-page-wide space-y-4">
      <AdminPageHeader
        title="Аудит-акт"
        description="Подсчёт, расхождения и проведение ревизии"
        icon={<ClipboardList className="h-5 w-5" />}
        accent="emerald"
        backHref="/store/audit"
        actions={
          <Button variant="ghost" size="sm" onClick={() => { setView('list'); void loadActs() }} className="gap-1.5"><ArrowLeft className="h-4 w-4" /> К списку</Button>
        }
      />
      {error ? <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-300">{error}</div> : null}

      {!detail ? (
        <div className="space-y-4">
          <CardSkeleton rows={3} />
          <StoreSection title="Позиции"><TableSkeleton rows={6} cols={5} /></StoreSection>
        </div>
      ) : (
        <>
          <ActHeaderCard
            detail={detail}
            isOpen={isOpen}
            hasConflicts={hasConflicts}
            assignDebt={assignDebt}
            setAssignDebt={setAssignDebt}
            zeroUncounted={zeroUncounted}
            setZeroUncounted={setZeroUncounted}
            assignDebtZeroed={assignDebtZeroed}
            setAssignDebtZeroed={setAssignDebtZeroed}
            debtsCreated={debtsCreated}
            closing={closing}
            canceling={canceling}
            reverting={reverting}
            onCloseClick={() => {
              if (zeroUncounted && (detail.uncounted || []).length > 0) {
                setZeroSelected(new Set((detail.uncounted || []).filter((r) => !(r.sold_recent || 0)).map((r) => r.item_id)))
                setZeroPreviewOpen(true)
              }
              else void closeAct(false)
            }}
            onForceClose={() => void closeAct(true)}
            onCancel={() => void cancelAct()}
            onRevert={() => void revertAct()}
          />

          {/* Результат ревизии (после закрытия) — расхождения по позициям */}
          {closeReport ? (
            <CloseResult report={closeReport} summary={closeSummary} />
          ) : (
            /* Подсчёт в процессе / исторический акт */
            <CountProgress
              rows={detailRows}
              isOpen={isOpen}
              canSeeVariance={canSeeVariance}
              showVariance={showVariance}
              revealVariance={revealVariance}
              onToggleReveal={() => setRevealVariance((v) => !v)}
              totals={totals}
              itemBusy={itemBusy}
              onResolve={(itemId, qty) => void resolveItem(itemId, qty)}
              onRecount={(itemId) => void recountItem(itemId)}
            />
          )}

          {/* Предпросмотр жёсткого закрытия: что будет обнулено */}
          <ZeroPreviewModal
            open={zeroPreviewOpen}
            detail={detail}
            zeroSelected={zeroSelected}
            setZeroSelected={setZeroSelected}
            assignDebtZeroed={assignDebtZeroed}
            closing={closing}
            onClose={() => setZeroPreviewOpen(false)}
            onConfirm={() => void closeAct(false, true)}
          />
        </>
      )}
    </div>
  )
}
