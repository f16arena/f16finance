'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ChevronDown, LogOut, PanelLeftClose, PanelLeftOpen, Search } from 'lucide-react'

import { AppLogoMark } from '@/components/app-brand-mark'
import { useNavLayout } from '@/lib/client/nav-layout'
import { SITE_NAME } from '@/lib/core/site'
import type { NavSection } from '@/lib/nav/sections'
import type { NavSession } from '@/lib/nav/use-nav-session'
import { cn } from '@/lib/utils'

const CLOSED_SECTIONS_KEY = 'orda.sideNav.closed'

function isActiveItem(pathname: string, href: string) {
  return href === '/' ? pathname === '/' : pathname === href || pathname.startsWith(href + '/')
}

function initials(name: string | null) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean)
  return (parts[0]?.[0] || 'O').toUpperCase() + (parts[1]?.[0] || '').toUpperCase()
}

/**
 * Меню слева (вид «как в Commrent»): разделы подзаголовками, сворачиваются стрелкой;
 * внизу — «Свернуть меню» и пользователь. Пункты — те же, что у верхнего меню
 * (TopNav передаёт уже отфильтрованные по правам и пакету разделы).
 * Видно только на компьютере и только при выборе «Меню слева» (CSS по data-nav).
 */
export function SideNav({
  sections,
  pathname,
  session,
  onSearch,
}: {
  sections: NavSection[]
  pathname: string
  session: NavSession
  onSearch: () => void
}) {
  const { collapsed, toggleCollapsed } = useNavLayout()
  const [closed, setClosed] = useState<string[]>([])

  useEffect(() => {
    try {
      const raw = localStorage.getItem(CLOSED_SECTIONS_KEY)
      if (raw) setClosed(JSON.parse(raw))
    } catch {
      // без сохранённого — все разделы открыты
    }
  }, [])

  function toggleSection(id: string) {
    setClosed((current) => {
      const next = current.includes(id) ? current.filter((value) => value !== id) : [...current, id]
      try {
        localStorage.setItem(CLOSED_SECTIONS_KEY, JSON.stringify(next))
      } catch {}
      return next
    })
  }

  const orgName = session.activeOrganization?.name || session.organizations[0]?.name || SITE_NAME

  return (
    <aside className="app-side-nav fixed inset-y-0 left-0 z-40 hidden flex-col border-r border-slate-200 bg-white dark:border-white/5 dark:bg-slate-950">
      {/* Организация */}
      <Link href="/dashboard" className="flex items-center gap-3 border-b border-slate-200 px-4 py-4 dark:border-white/5" title={orgName}>
        <AppLogoMark logoUrl={session.activeOrganization?.logoUrl || session.organizations[0]?.logoUrl} />
        {!collapsed && (
          <div className="min-w-0">
            <p className="truncate text-sm font-bold text-foreground">{orgName}</p>
            <p className="truncate text-xs text-slate-500">панель управления</p>
          </div>
        )}
      </Link>

      <button
        type="button"
        onClick={onSearch}
        title="Поиск страниц (Ctrl+K)"
        className={cn(
          'mx-3 mt-3 flex items-center gap-2 rounded-xl border border-slate-200 px-3 py-2 text-xs text-slate-500 transition hover:border-blue-500/30 hover:text-slate-900 dark:border-white/10 dark:hover:text-white',
          collapsed && 'justify-center px-0',
        )}
      >
        <Search className="h-4 w-4 shrink-0" />
        {!collapsed && <span>Поиск</span>}
      </button>

      {/* Разделы */}
      <nav className="mt-2 min-h-0 flex-1 overflow-y-auto px-3 pb-3">
        {sections.map((section) => {
          const open = collapsed || !closed.includes(section.id)
          return (
            <div key={section.id} className="mt-3 first:mt-1">
              {collapsed ? (
                <div className="mx-2 mb-1 border-t border-slate-200 dark:border-white/5" />
              ) : (
                <button
                  type="button"
                  onClick={() => toggleSection(section.id)}
                  className="flex w-full items-center justify-between px-2 py-1 text-[11px] font-semibold uppercase tracking-wider text-slate-400 hover:text-slate-600 dark:text-slate-500 dark:hover:text-slate-300"
                >
                  <span className="truncate">{section.title}</span>
                  <ChevronDown className={cn('h-3.5 w-3.5 shrink-0 transition-transform', !open && '-rotate-90')} />
                </button>
              )}
              {open && (
                <div className="mt-0.5 space-y-0.5">
                  {section.items.map((item) => {
                    const Icon = item.icon
                    const active = isActiveItem(pathname, item.href)
                    return (
                      <Link
                        key={item.href}
                        href={item.href}
                        prefetch
                        title={collapsed ? item.label : item.note}
                        className={cn(
                          'flex items-center gap-3 rounded-xl px-3 py-2 text-sm transition',
                          collapsed && 'justify-center px-0',
                          active
                            ? 'bg-blue-600 font-medium text-white shadow-sm shadow-blue-600/30'
                            : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-300 dark:hover:bg-white/5 dark:hover:text-white',
                        )}
                      >
                        <Icon className="h-4 w-4 shrink-0" />
                        {!collapsed && (
                          <>
                            <span className="min-w-0 flex-1 truncate">{item.label}</span>
                            {item.isNew || item.badge ? (
                              <span
                                className={cn(
                                  'rounded-md px-1.5 py-0.5 text-[10px] font-medium',
                                  active ? 'bg-white/20 text-white' : 'bg-blue-500/10 text-blue-600 dark:text-blue-300',
                                )}
                              >
                                new
                              </span>
                            ) : null}
                          </>
                        )}
                      </Link>
                    )
                  })}
                </div>
              )}
            </div>
          )
        })}
      </nav>

      {/* Низ: свернуть и пользователь */}
      <div className="border-t border-slate-200 p-3 dark:border-white/5">
        <button
          type="button"
          onClick={toggleCollapsed}
          className={cn(
            'flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-500 transition hover:bg-slate-100 hover:text-slate-900 dark:hover:bg-white/5 dark:hover:text-white',
            collapsed && 'justify-center px-0',
          )}
          title={collapsed ? 'Развернуть меню' : 'Свернуть меню'}
        >
          {collapsed ? <PanelLeftOpen className="h-4 w-4" /> : <PanelLeftClose className="h-4 w-4" />}
          {!collapsed && <span>Свернуть меню</span>}
        </button>
        <div className={cn('mt-2 flex items-center gap-3 px-1', collapsed && 'flex-col gap-2 px-0')}>
          <Link
            href="/profile"
            title="Мой профиль"
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-blue-600 text-xs font-semibold text-white"
          >
            {initials(session.displayName)}
          </Link>
          {!collapsed && (
            <Link href="/profile" className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-foreground">{session.displayName || 'Профиль'}</p>
              <p className="truncate text-xs text-slate-500">{session.roleLabel || ''}</p>
            </Link>
          )}
          <button
            type="button"
            onClick={() => void session.handleLogout()}
            title="Выйти"
            className="rounded-lg p-2 text-slate-400 transition hover:bg-red-500/10 hover:text-red-500"
          >
            <LogOut className="h-4 w-4" />
          </button>
        </div>
      </div>
    </aside>
  )
}
