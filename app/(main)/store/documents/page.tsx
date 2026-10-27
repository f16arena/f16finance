'use client'

import { useEffect, useState } from 'react'
import { usePersistentState } from '@/lib/client/use-persistent-state'
import dynamic from 'next/dynamic'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { FileText, PackagePlus, Upload, ArchiveX, ScanSearch, CalendarClock } from 'lucide-react'
import { PageSkeleton } from '@/components/skeleton'
import { formatMoney } from '@/lib/core/format'
import { StoreAttention, plural, type StoreTodo } from '@/components/store/store-ui'
import { useStoreApiUrl } from '@/components/store/store-scope'
import { readApiCache, writeApiCache } from '@/lib/client/use-api-cache'
import { isAbortError } from '@/lib/is-abort-error'

const fallback = () => <div className="app-page-wide py-4"><PageSkeleton stats={3} rows={8} cols={7} /></div>

const Receipts = dynamic(() => import('@/app/(main)/store/receipts/page'), { ssr: false, loading: fallback })
const Postings = dynamic(() => import('@/app/(main)/store/postings/page'), { ssr: false, loading: fallback })
const Writeoffs = dynamic(() => import('@/app/(main)/store/writeoffs/page'), { ssr: false, loading: fallback })
const Revisions = dynamic(() => import('@/app/(main)/store/revisions/page'), { ssr: false, loading: fallback })
const Expiry = dynamic(() => import('@/app/(main)/store/expiry/page'), { ssr: false, loading: fallback })

type Tab = 'receipts' | 'postings' | 'writeoffs' | 'revisions' | 'expiry'
const TABS: { key: Tab; label: string; hint: string; icon: any }[] = [
  { key: 'receipts', label: 'Приёмка', hint: 'Товар от поставщика', icon: PackagePlus },
  { key: 'postings', label: 'Оприходование', hint: 'Без поставщика', icon: Upload },
  { key: 'writeoffs', label: 'Списания', hint: 'Брак, порча, потери', icon: ArchiveX },
  { key: 'revisions', label: 'Ревизия', hint: 'Пересчёт остатков', icon: ScanSearch },
  { key: 'expiry', label: 'Срок годности', hint: 'Что скоро испортится', icon: CalendarClock },
]

export default function StoreDocumentsPage() {
  const storeUrl = useStoreApiUrl()
  const [tab, setTab] = usePersistentState<Tab>('store.documents.tab', 'receipts')
  // Единственное, что на хабе требует решения: списания без причины (тот же счётчик, что на обзоре).
  const [unresolved, setUnresolved] = useState<number | null>(null)
  const [drafts, setDrafts] = useState(0)
  const [debts, setDebts] = useState<{ count: number; sum: number; overdueCount: number; overdueSum: number } | null>(null)

  const overviewUrl = storeUrl('/api/admin/store/overview')
  useEffect(() => {
    const cached = readApiCache<{ unresolvedWriteoffs?: number }>(`${overviewUrl}#metrics`)
    if (cached && typeof cached.unresolvedWriteoffs === 'number') setUnresolved(cached.unresolvedWriteoffs)
    const ac = new AbortController()
    fetch(overviewUrl, { cache: 'no-store', signal: ac.signal })
      .then((r) => r.json().catch(() => null).then((json) => ({ ok: r.ok, json })))
      .then(({ ok, json }) => {
        if (ac.signal.aborted || !ok || !json?.ok) return
        const n = Number(json.data?.unresolvedWriteoffs ?? 0)
        setUnresolved(n)
        setDrafts(Number(json.data?.receiptDrafts ?? 0))
        if (json.data?.unpaidSupplierDebts) setDebts(json.data.unpaidSupplierDebts)
        // Кэш обзора хранит полный набор счётчиков; обновляем только своё поле, если запись уже есть.
        const prev = readApiCache<Record<string, unknown>>(`${overviewUrl}#metrics`)
        if (prev) writeApiCache(`${overviewUrl}#metrics`, { ...prev, unresolvedWriteoffs: n })
      })
      .catch((e) => { if (!isAbortError(e)) setUnresolved((v) => v) })
    return () => ac.abort()
  }, [overviewUrl])

  const todo: StoreTodo[] = []
  if (unresolved && unresolved > 0) {
    todo.push({
      key: 'wo',
      icon: ArchiveX,
      tone: 'amber',
      text: `${unresolved} ${plural(unresolved, ['списание', 'списания', 'списаний'])} без причины за 90 дней`,
      onClick: () => setTab('writeoffs'),
      cta: 'Указать причину',
    })
  }

  if (drafts > 0) {
    todo.push({
      key: 'drafts',
      icon: FileText,
      tone: 'amber',
      text: `${drafts} ${plural(drafts, ['черновик приёмки не проведён', 'черновика приёмки не проведены', 'черновиков приёмки не проведены'])} — товар ещё не на складе`,
      onClick: () => setTab('receipts'),
      cta: 'Открыть',
    })
  }
  if (debts && debts.overdueCount > 0) {
    todo.push({
      key: 'debts',
      icon: ArchiveX,
      tone: 'rose',
      text: `Просрочена оплата ${debts.overdueCount} ${plural(debts.overdueCount, ['накладной', 'накладных', 'накладных'])} поставщикам на ${formatMoney(debts.overdueSum)}`,
      href: '/store/vendors',
      cta: 'К оплате',
    })
  }

  return (
    <div className="app-page-wide space-y-4">
      <AdminPageHeader
        title="Документы"
        description="Приёмка, оприходование, списания и ревизия — в одном месте"
        icon={<FileText className="h-5 w-5" />}
        accent="emerald"
        backHref="/store"
        toolbar={
          // На телефоне вкладки едут в одну строку со своей прокруткой, страница не уезжает вбок.
          <div className="-mx-1 max-w-full overflow-x-auto px-1 pb-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            <div className="inline-flex min-w-max gap-1 rounded-2xl border border-border bg-white dark:bg-slate-950/50 p-1">
              {TABS.map(({ key, label, hint, icon: Icon }) => (
                <button
                  key={key}
                  type="button"
                  title={hint}
                  data-tour={key === 'receipts' ? 'doc-tab-receipts' : undefined}
                  onClick={() => setTab(key)}
                  aria-pressed={tab === key}
                  className={`flex items-center gap-2 whitespace-nowrap rounded-xl px-3 py-2 text-sm font-medium transition-all sm:px-4 ${tab === key ? 'bg-surface-hover text-foreground shadow-sm ring-1 ring-slate-200 dark:ring-white/10' : 'text-muted-foreground hover:text-slate-900 dark:hover:text-white'}`}
                >
                  <Icon className={`h-4 w-4 ${tab === key ? 'text-emerald-700 dark:text-emerald-300' : ''}`} />
                  {label}
                  {key === 'writeoffs' && unresolved ? (
                    <span className="rounded-full bg-amber-500/15 px-1.5 text-[11px] font-semibold tabular-nums text-amber-700 dark:text-amber-300">{unresolved}</span>
                  ) : null}
                </button>
              ))}
            </div>
          </div>
        }
      />

      {tab !== 'writeoffs' && todo.length > 0 ? (
        <StoreAttention items={todo} calm={null} />
      ) : null}

      {tab === 'receipts' && <Receipts embedded />}
      {tab === 'postings' && <Postings embedded />}
      {tab === 'writeoffs' && <Writeoffs embedded />}
      {tab === 'revisions' && <Revisions embedded />}
      {tab === 'expiry' && <Expiry embedded />}
    </div>
  )
}
