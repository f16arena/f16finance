'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { AlertCircle, ArrowRight, Wallet } from 'lucide-react'

import { StoreSection } from '@/components/store/store-ui'
import { formatMoney } from '@/lib/core/format'

type Debt = {
  id: string
  total_amount: number
  status: string
  due_date: string | null
}

export function SupplierDebtsWidget() {
  const [openCount, setOpenCount] = useState(0)
  const [openSum, setOpenSum] = useState(0)
  const [overdueCount, setOverdueCount] = useState(0)
  const [overdueSum, setOverdueSum] = useState(0)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const response = await fetch('/api/admin/store/debts?status=open', { cache: 'no-store' })
        const json = await response.json().catch(() => null)
        if (cancelled || !response.ok || !json?.ok) return
        const debts = (json.data?.debts || []) as Debt[]
        const now = Date.now()
        let oc = 0
        let os = 0
        let xc = 0
        let xs = 0
        for (const d of debts) {
          if (d.status !== 'open') continue
          oc += 1
          os += Number(d.total_amount || 0)
          if (d.due_date && new Date(d.due_date).getTime() < now) {
            xc += 1
            xs += Number(d.total_amount || 0)
          }
        }
        setOpenCount(oc)
        setOpenSum(os)
        setOverdueCount(xc)
        setOverdueSum(xs)
      } catch {
        // ignore
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <StoreSection
      icon={<Wallet className="h-4 w-4" />}
      title="Долги поставщикам"
      subtitle="Открытые обязательства"
      action={
        <Link href="/store/billing" className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-foreground">
          Перейти <ArrowRight className="h-3 w-3" />
        </Link>
      }
    >
      <div className="grid grid-cols-2 gap-3">
        <div className="rounded-xl border border-border p-3">
          <div className="text-[11px] font-medium uppercase tracking-wider text-slate-500">Открыто</div>
          <div className={`mt-1 text-lg font-bold tabular-nums ${openCount > 0 ? 'text-rose-600 dark:text-rose-300' : 'text-slate-500'}`}>{loading ? '—' : openCount}</div>
          <div className="text-xs tabular-nums text-muted-foreground">{loading ? '—' : formatMoney(openSum)}</div>
        </div>
        <div className={`rounded-xl border p-3 ${overdueCount > 0 ? 'border-rose-500/30 bg-rose-500/[0.05]' : 'border-border'}`}>
          <div className="flex items-center gap-1 text-[11px] font-medium uppercase tracking-wider text-slate-500">
            {overdueCount > 0 ? <AlertCircle className="h-3 w-3 text-rose-500" /> : null}
            Просрочено
          </div>
          <div className={`mt-1 text-lg font-bold tabular-nums ${overdueCount > 0 ? 'text-rose-600 dark:text-rose-300' : 'text-slate-500'}`}>{loading ? '—' : overdueCount}</div>
          <div className="text-xs tabular-nums text-muted-foreground">{loading ? '—' : formatMoney(overdueSum)}</div>
        </div>
      </div>
    </StoreSection>
  )
}
