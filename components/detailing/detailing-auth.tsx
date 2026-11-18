'use client'

/**
 * Рамка экранов «забыли пароль» и «задать пароль» на адресе детейлинга:
 * та же шапка с названием центра, что и на входе, вместо общей тёмной Orda.
 */

import { useEffect, useState, type ReactNode } from 'react'
import { CarFront } from 'lucide-react'

import { getTenantBaseHost } from '@/lib/core/tenant-domain'

export type HostNiche = { niche: 'detailing' | null; name: string | null }

function onTenantHost() {
  if (typeof window === 'undefined') return false
  const host = window.location.hostname.replace(/^www\./i, '').toLowerCase()
  const base = getTenantBaseHost().replace(/^www\./i, '').toLowerCase()
  return !!host && host !== base && host.endsWith(`.${base}`)
}

/**
 * undefined — ещё не знаем (показываем пустой светлый экран, чтобы не мигнула
 * тёмная Orda); niche null — обычный адрес, рисуем общий экран.
 */
export function useHostNiche(): HostNiche | undefined {
  const [value, setValue] = useState<HostNiche | undefined>(undefined)
  useEffect(() => {
    if (!onTenantHost()) {
      setValue({ niche: null, name: null })
      return
    }
    let alive = true
    fetch('/api/public/host')
      .then((r) => r.json())
      .then((j) => alive && setValue({ niche: j?.niche === 'detailing' ? 'detailing' : null, name: j?.name || null }))
      .catch(() => alive && setValue({ niche: null, name: null }))
    return () => { alive = false }
  }, [])
  return value
}

export function DetailingAuthFrame({ orgName, title, hint, children }: { orgName: string; title: string; hint?: string; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-[#f3f5f9] text-[#0b1f3b]">
      <header className="bg-[#0b1f3b] px-5 py-5 text-white">
        <div className="mx-auto flex max-w-md items-center gap-3">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-blue-600"><CarFront className="h-6 w-6" /></span>
          <span className="block truncate font-[family-name:var(--font-manrope)] text-lg font-extrabold">{orgName}</span>
        </div>
      </header>
      <main className="mx-auto max-w-md px-5 py-8">
        <h1 className="font-[family-name:var(--font-manrope)] text-2xl font-extrabold">{title}</h1>
        {hint ? <p className="mt-1.5 text-sm text-[#5b6b82]">{hint}</p> : null}
        <div className="mt-5 space-y-4">{children}</div>
      </main>
    </div>
  )
}

export const authField =
  'h-12 w-full rounded-xl border border-[#d4dbe6] bg-white px-3.5 text-[15px] text-[#0b1f3b] outline-none transition placeholder:text-[#94a3b8] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/20'
export const authButton =
  'flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-blue-600 text-[15px] font-bold text-white transition hover:bg-blue-700 disabled:opacity-60'
export const authError = 'rounded-xl border border-red-200 bg-red-50 px-3.5 py-3 text-sm text-red-700'
export const authOk = 'rounded-xl border border-emerald-200 bg-emerald-50 px-3.5 py-3 text-sm text-emerald-800'
