'use client'

import { useLayoutEffect, useState } from 'react'
import { Breadcrumbs } from '@/components/breadcrumbs'
import { OnboardingTour } from '@/components/onboarding-tour'
import { Sidebar } from '@/components/sidebar'
import { TopNav } from '@/components/topnav'
import { DetailingShell, useShellNiche } from '@/components/detailing/detailing-shell'

const NICHE_KEY = 'orda.shellNiche'

/**
 * Оболочка портала. У ниши своя программа (меню, раскладка), у клуба и магазина —
 * общая шапка Orda. Нишу помним в localStorage, чтобы при перезагрузке не мигала
 * клубная шапка, пока не пришла сессия.
 */
export function AppFrame({ children }: { children: React.ReactNode }) {
  const live = useShellNiche()
  const [cached, setCached] = useState<string | null>(null)

  useLayoutEffect(() => {
    try { setCached(window.localStorage.getItem(NICHE_KEY)) } catch { /* приватное окно */ }
  }, [])
  useLayoutEffect(() => {
    if (live === undefined) return
    try {
      if (live) window.localStorage.setItem(NICHE_KEY, live)
      else window.localStorage.removeItem(NICHE_KEY)
    } catch { /* приватное окно */ }
  }, [live])

  const niche = live === undefined ? cached : live
  if (niche === 'detailing') return <DetailingShell>{children}</DetailingShell>

  return (
    <>
      <OnboardingTour />
      <TopNav />
      <Sidebar desktopEnabled={false} />
      <main className="app-main relative isolate min-h-0">
        <div
          className="pointer-events-none absolute inset-0 z-0 bg-[linear-gradient(165deg,rgba(37,99,235,0.05)_0%,transparent_42%,transparent_100%)] opacity-90"
          aria-hidden
        />
        <div className="relative z-[1] min-h-0">
          <Breadcrumbs />
          {children}
        </div>
      </main>
    </>
  )
}
