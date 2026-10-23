'use client'

import { useCallback, useEffect, useState } from 'react'

import { NAV_COLLAPSED_KEY as COLLAPSED_KEY, NAV_LAYOUT_KEY as LAYOUT_KEY } from '@/lib/core/nav-layout-boot'

// Вид меню — выбор каждого сотрудника на своём устройстве: сверху (как было) или
// слева (колонка с разделами, как в Commrent). Хранится в localStorage, а на <html>
// ставится data-nav / data-nav-collapsed: CSS прячет лишнее до гидрации, поэтому
// страница не моргает при загрузке (NAV_LAYOUT_BOOT_SCRIPT в app/layout.tsx).

export type NavLayout = 'top' | 'side'

const EVENT = 'orda:nav-layout'

function readLayout(): NavLayout {
  try {
    return localStorage.getItem(LAYOUT_KEY) === 'side' ? 'side' : 'top'
  } catch {
    return 'top'
  }
}

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) === '1'
  } catch {
    return false
  }
}

function apply(layout: NavLayout, collapsed: boolean) {
  const root = document.documentElement
  if (layout === 'side') root.dataset.nav = 'side'
  else delete root.dataset.nav
  if (collapsed) root.dataset.navCollapsed = '1'
  else delete root.dataset.navCollapsed
}

function save(layout: NavLayout, collapsed: boolean) {
  try {
    localStorage.setItem(LAYOUT_KEY, layout)
    localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0')
  } catch {
    // приватный режим — выбор действует до перезагрузки
  }
  apply(layout, collapsed)
  window.dispatchEvent(new CustomEvent(EVENT))
}

/** Текущий вид меню и переключатели; все подписчики узнают о смене сразу. */
export function useNavLayout() {
  const [layout, setLayout] = useState<NavLayout>('top')
  const [collapsed, setCollapsed] = useState(false)

  useEffect(() => {
    const sync = () => {
      setLayout(readLayout())
      setCollapsed(readCollapsed())
    }
    sync()
    window.addEventListener(EVENT, sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener(EVENT, sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  const changeLayout = useCallback((next: NavLayout) => save(next, readCollapsed()), [])
  const toggleCollapsed = useCallback(() => save(readLayout(), !readCollapsed()), [])

  return { layout, collapsed, changeLayout, toggleCollapsed }
}
