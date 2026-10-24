import { useEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import {
  ClipboardList,
  CloudOff,
  Gamepad2,
  History,
  LayoutTemplate,
  LogOut,
  Moon,
  ReceiptText,
  RotateCcw,
  Settings,
  ShoppingBag,
  Sun,
  UserRound,
  Wallet,
  type LucideIcon,
} from 'lucide-react'

import { getQueueCounts, openQueueScreen } from '@/lib/offline'
import { LITE_MODE_EVENT, getTheme, resolveLiteMode, setTheme, setUiVersion } from '@/lib/preferences'

import { PreferencesModal } from '@/components/PreferencesModal'
import '../v2.css'

export type V2Tab = 'shift' | 'sale' | 'return' | 'history' | 'scanner' | 'request' | 'arena' | 'cabinet'

export type V2Nav = Partial<Record<V2Tab, () => void>>

const TABS: Array<{ id: V2Tab; label: string; icon: LucideIcon }> = [
  { id: 'shift', label: 'Смена', icon: ReceiptText },
  { id: 'sale', label: 'Продажа', icon: ShoppingBag },
  { id: 'return', label: 'Возврат', icon: RotateCcw },
  { id: 'history', label: 'История', icon: History },
  { id: 'scanner', label: 'Долги', icon: Wallet },
  { id: 'request', label: 'Заявка', icon: ClipboardList },
  { id: 'arena', label: 'Зал', icon: Gamepad2 },
]

/** Лёгкий режим: меняется в настройках, применяется сразу. */
export function useLiteMode() {
  const [lite, setLite] = useState(() => resolveLiteMode())
  useEffect(() => {
    const update = () => setLite(resolveLiteMode())
    window.addEventListener(LITE_MODE_EVENT, update)
    window.addEventListener('storage', update)
    return () => {
      window.removeEventListener(LITE_MODE_EVENT, update)
      window.removeEventListener('storage', update)
    }
  }, [])
  return lite
}

function useResolvedTheme(): ['light' | 'dark', () => void, () => void] {
  const resolve = () => (document.documentElement.classList.contains('dark') ? 'dark' : 'light') as 'light' | 'dark'
  const [theme, setLocal] = useState(resolve)
  const toggle = () => {
    const next = resolve() === 'dark' ? 'light' : 'dark'
    setTheme(next)
    setLocal(next)
  }
  useEffect(() => {
    setLocal(resolve())
  }, [])
  void getTheme
  return [theme, toggle, () => setLocal(resolve())]
}

/** Связь и очередь — молчит, пока всё хорошо. */
function ConnectionPill() {
  const [online, setOnline] = useState(() => navigator.onLine)
  const [pending, setPending] = useState(0)
  useEffect(() => {
    let cancelled = false
    const refresh = async () => {
      try {
        const counts: any = await getQueueCounts()
        if (!cancelled) setPending(Number(counts?.pending || 0) + Number(counts?.attention || 0))
      } catch {
        /* очереди нет */
      }
    }
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    void refresh()
    const t = setInterval(refresh, 10_000)
    return () => {
      cancelled = true
      clearInterval(t)
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])
  if (online && pending === 0) return null
  return (
    <button
      type="button"
      onClick={openQueueScreen}
      className="press flex h-10 items-center gap-2 rounded-full px-3.5 text-[13px] font-semibold text-white"
      style={{ background: online ? 'var(--orange)' : 'var(--red)' }}
      title="Открыть очередь отправки"
    >
      <CloudOff className="h-4 w-4" />
      {online ? `Отправляется: ${pending}` : `Нет интернета${pending ? ` · ${pending}` : ''}`}
    </button>
  )
}

/**
 * Каркас нового вида: стеклянная шапка с вкладками (Alt+1…8), временем,
 * связью, темой и выходом. Экран кладётся внутрь.
 */
export function V2Shell(props: {
  active: V2Tab
  nav: V2Nav
  companyName: string
  operatorName: string
  shiftLabel: string
  onLogout?: () => void
  children: ReactNode
}) {
  const [theme, toggleTheme, refreshTheme] = useResolvedTheme()
  const [settingsOpen, setSettingsOpen] = useState(false)
  const lite = useLiteMode()
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15_000)
    return () => clearInterval(t)
  }, [])

  const tabs = TABS.filter((t) => t.id === props.active || props.nav[t.id])
  const all: Array<{ id: V2Tab; run?: () => void }> = [...tabs.map((t) => ({ id: t.id, run: props.nav[t.id] })), { id: 'cabinet', run: props.nav.cabinet }]

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.altKey || e.ctrlKey || e.metaKey) return
      const index = Number(e.key) - 1
      const tab = all[index]
      if (!tab || tab.id === props.active || !tab.run) return
      e.preventDefault()
      tab.run()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const initials = props.companyName
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() || '')
    .join('')

  return (
    <div className="v2 flex h-screen flex-col select-none overflow-hidden" data-theme={theme} data-lite={lite ? 'true' : undefined}>
      <div className="h-9 shrink-0 drag-region" />
      <header className="glass no-drag z-10 mx-4 flex h-16 shrink-0 items-center gap-4 rounded-[22px] px-3">
        <div className="flex min-w-0 items-center gap-3 pl-1">
          <div className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-gradient-to-br from-[#5ac8fa] to-[#0a84ff] text-[15px] font-semibold text-white">
            {initials || 'OP'}
          </div>
          <div className="min-w-0">
            <div className="truncate text-[16px] font-semibold leading-tight">{props.companyName}</div>
            <div className="muted truncate text-[13px] leading-tight">{props.operatorName}</div>
          </div>
        </div>

        <nav className="fill mx-auto flex items-center gap-1 rounded-[14px] p-1">
          {tabs.map((tab, i) => {
            const active = tab.id === props.active
            const Icon = tab.icon
            return (
              <button
                key={tab.id}
                type="button"
                onClick={active ? undefined : props.nav[tab.id]}
                title={`${tab.label} (Alt+${i + 1})`}
                className={`press flex h-10 items-center gap-2 rounded-[11px] px-3.5 text-[14px] font-medium ${active ? 'pill-active' : 'ink-2'}`}
              >
                <Icon className="h-[18px] w-[18px]" />
                <span className="hidden xl:inline">{tab.label}</span>
              </button>
            )
          })}
        </nav>

        <div className="flex items-center gap-2 pr-1">
          <ConnectionPill />
          <div className="px-1 text-right leading-tight">
            <div className="text-[17px] font-semibold">{now.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}</div>
            <div className="flex items-center justify-end gap-1.5 text-[12px]">
              <span className="h-1.5 w-1.5 rounded-full" style={{ background: 'var(--green)' }} />
              <span className="muted">{props.shiftLabel}</span>
            </div>
          </div>
          <button
            type="button"
            onClick={() => setUiVersion('classic')}
            className="press fill grid h-10 w-10 place-items-center rounded-full"
            title="Классический вид кассы"
            aria-label="Классический вид кассы"
          >
            <LayoutTemplate className="h-[18px] w-[18px]" />
          </button>
          <button
            type="button"
            onClick={toggleTheme}
            className="press fill hidden h-10 w-10 place-items-center rounded-full lg:grid"
            title={theme === 'light' ? 'Тёмная тема' : 'Светлая тема'}
            aria-label={theme === 'light' ? 'Тёмная тема' : 'Светлая тема'}
          >
            {theme === 'light' ? <Moon className="h-[18px] w-[18px]" /> : <Sun className="h-[18px] w-[18px]" />}
          </button>
          <button
            type="button"
            onClick={() => setSettingsOpen(true)}
            className="press fill grid h-10 w-10 place-items-center rounded-full"
            title="Настройки: масштаб, тема, звук, экран покупателя"
            aria-label="Настройки"
          >
            <Settings className="h-[18px] w-[18px]" />
          </button>
          {props.nav.cabinet ? (
            <button type="button" onClick={props.nav.cabinet} className="press fill grid h-10 w-10 place-items-center rounded-full" title={`Профиль (Alt+${all.length})`} aria-label="Профиль">
              <UserRound className="h-[18px] w-[18px]" />
            </button>
          ) : null}
          {props.onLogout ? (
            <button type="button" onClick={props.onLogout} className="press fill grid h-10 w-10 place-items-center rounded-full" title="Выйти" aria-label="Выйти">
              <LogOut className="h-[18px] w-[18px]" />
            </button>
          ) : null}
        </div>
      </header>
      <div className="min-h-0 flex-1">{props.children}</div>
      {/* Окно настроек общее с классическим видом — рисуем вне .v2, иначе его стили
          (.v2 button { color: inherit }) перебивают цвета кнопок окна. */}
      {createPortal(
        <PreferencesModal
          v2
          open={settingsOpen}
          onClose={() => {
            setSettingsOpen(false)
            refreshTheme()
          }}
        />,
        document.body,
      )}
    </div>
  )
}
