'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { usePathname } from 'next/navigation'
import {
  CalendarDays,
  CarFront,
  House,
  LogOut,
  Menu,
  Plus,
  Receipt,
  Settings,
  Tag,
  Wallet,
  X,
} from 'lucide-react'
import { ThemeToggle } from '@/components/theme-toggle'
import { useNavSession } from '@/lib/nav/use-nav-session'

type Item = { href: string; label: string; icon: typeof House; exact?: boolean }

/**
 * Меню программы детейлинга. Адреса общие с Orda (/dashboard, /income…),
 * но на этих адресах у ниши свои экраны (NicheSwitch в страницах).
 */
const NAV: Item[] = [
  { href: '/dashboard', label: 'Сегодня', icon: House },
  { href: '/detailing', label: 'Запись', icon: CalendarDays, exact: true },
  { href: '/detailing/clients', label: 'Клиенты', icon: CarFront },
  { href: '/income', label: 'Доходы', icon: Wallet },
  { href: '/expenses', label: 'Расходы', icon: Receipt },
  { href: '/detailing/prices', label: 'Прайс', icon: Tag },
  { href: '/settings', label: 'Настройки', icon: Settings },
]

/**
 * Своя оболочка ниши «Детейлинг»: меню слева вместо общей шапки Orda.
 * Цвета Orda (navy + кобальт), раскладка своя.
 */
export function DetailingShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const session = useNavSession()
  const [mobileOpen, setMobileOpen] = useState(false)

  useEffect(() => { setMobileOpen(false) }, [pathname])

  const orgName = session.activeOrganization?.name || session.organizations[0]?.name || 'Детейлинг'
  const initials = (session.displayName || session.userEmail || '?')
    .split(/\s+/)
    .map((p) => p[0])
    .join('')
    .slice(0, 2)
    .toUpperCase()

  const isActive = (item: Item) =>
    item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(item.href + '/')

  const menu = (
    <div className="flex h-full flex-col gap-5 px-3.5 py-5">
      <Link href="/dashboard" className="flex items-center gap-3 px-1.5">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-blue-600 text-white">
          <CarFront className="h-5 w-5" />
        </span>
        <span className="min-w-0">
          <span className="block truncate font-[family-name:var(--font-manrope)] text-[15px] font-extrabold text-white">{orgName}</span>
          <span className="block truncate text-[11.5px] text-slate-400">детейлинг · Orda</span>
        </span>
      </Link>

      <Link
        href="/detailing?new=1"
        className="flex items-center justify-center gap-2 rounded-xl bg-blue-600 px-3 py-3 text-sm font-bold text-white transition-colors hover:bg-blue-500"
      >
        <Plus className="h-4 w-4" /> Записать машину
      </Link>

      <nav className="flex flex-col gap-0.5">
        {NAV.map((item) => {
          const active = isActive(item)
          const Icon = item.icon
          return (
            <Link
              key={item.href}
              href={item.href}
              prefetch
              aria-current={active ? 'page' : undefined}
              className={`relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-semibold transition-colors ${
                active ? 'bg-white/10 text-white' : 'text-slate-400 hover:bg-white/5 hover:text-white'
              }`}
            >
              {active && <span className="absolute inset-y-2 left-0 w-[3px] rounded-full bg-blue-500" aria-hidden />}
              <Icon className="h-[18px] w-[18px] shrink-0" />
              {item.label}
            </Link>
          )
        })}
      </nav>

      <div className="mt-auto flex flex-col gap-3 border-t border-white/10 px-1.5 pt-4">
        <div className="flex items-center gap-2.5">
          <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-white/10 text-xs font-bold text-white">{initials}</span>
          <span className="min-w-0 flex-1">
            <span className="block truncate text-sm font-semibold text-white">{session.displayName || session.userEmail}</span>
            <span className="block truncate text-[11.5px] text-slate-400">{session.roleLabel}</span>
          </span>
        </div>
        <div className="flex gap-2">
          <ThemeToggle className="flex flex-1 items-center justify-center gap-2 rounded-lg border border-white/10 px-2 py-1.5 text-xs text-slate-300 transition hover:bg-white/5 hover:text-white" />
          <button
            type="button"
            onClick={session.handleLogout}
            title="Выйти"
            className="flex items-center gap-1.5 rounded-lg border border-white/10 px-2.5 py-1.5 text-xs text-slate-300 transition hover:bg-red-500/15 hover:text-red-300"
          >
            <LogOut className="h-3.5 w-3.5" /> Выйти
          </button>
        </div>
      </div>
    </div>
  )

  return (
    <div className="fixed inset-0 z-20 flex bg-[#f3f5f9] dark:bg-[#081a33]">
      <aside className="hidden w-60 shrink-0 overflow-y-auto bg-[#0b1f3b] dark:bg-[#030b18] md:block">{menu}</aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {/* Телефон: шапка с кнопкой меню */}
        <header className="flex h-14 shrink-0 items-center gap-3 bg-[#0b1f3b] px-4 text-white md:hidden">
          <button type="button" onClick={() => setMobileOpen(true)} aria-label="Меню" className="grid h-9 w-9 place-items-center rounded-lg bg-white/10">
            <Menu className="h-4 w-4" />
          </button>
          <span className="truncate font-bold">{orgName}</span>
          <Link href="/detailing?new=1" className="ml-auto grid h-9 w-9 place-items-center rounded-lg bg-blue-600" aria-label="Записать машину">
            <Plus className="h-4 w-4" />
          </Link>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto w-full max-w-[1440px] px-4 py-5 md:px-7 md:py-7">{children}</div>
        </div>
      </div>

      {mobileOpen && (
        <div className="fixed inset-0 z-50 md:hidden">
          <div className="absolute inset-0 bg-slate-950/70" onClick={() => setMobileOpen(false)} aria-hidden />
          <div className="absolute inset-y-0 left-0 w-[80%] max-w-[17rem] overflow-y-auto bg-[#0b1f3b]">
            <button type="button" onClick={() => setMobileOpen(false)} aria-label="Закрыть" className="absolute right-3 top-3 grid h-8 w-8 place-items-center rounded-lg text-slate-300 hover:bg-white/10">
              <X className="h-4 w-4" />
            </button>
            {menu}
          </div>
        </div>
      )}
    </div>
  )
}

/** Ниша организации для выбора оболочки: детейлинг, если все точки — детейлинг.
 *  undefined — сессия ещё не пришла. */
export function useShellNiche(): 'detailing' | null | undefined {
  const session = useNavSession()
  if (!session.sessionReady) return undefined
  const industries = session.orgIndustries || []
  if (session.isSuperAdmin && !session.isTenantContext) return null
  return industries.length > 0 && industries.every((i) => i === 'detailing') ? 'detailing' : null
}
