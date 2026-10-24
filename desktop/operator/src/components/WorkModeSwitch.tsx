import { useEffect, useState } from 'react'
import { ClipboardList, Gamepad2, History, ReceiptText, RotateCcw, ScanBarcode, ShoppingBasket, UserCircle2, type LucideIcon } from 'lucide-react'

import { hasAnyDraft, REQUEST_DRAFT_EVENT } from '@/lib/request-draft'
import ConnectionBadge from '@/components/ConnectionBadge'

type WorkMode = 'shift' | 'sale' | 'return' | 'history' | 'scanner' | 'request' | 'cabinet' | 'arena'

interface Props {
  active: WorkMode
  showSale?: boolean
  showReturn?: boolean
  showHistory?: boolean
  showScanner?: boolean
  showRequest?: boolean
  showArena?: boolean
  /**
   * Точка-индикатор на вкладке «Заявка»: лежит несданный черновик заявки.
   * Если проп не передан — компонент сам читает наличие черновика из
   * localStorage (lib/request-draft) и обновляется по событию.
   */
  requestBadge?: boolean
  onShift?: () => void
  onSale?: () => void
  onReturn?: () => void
  onHistory?: () => void
  onScanner?: () => void
  onRequest?: () => void
  onCabinet?: () => void
  onArena?: () => void
}

function ModeButton({
  active,
  label,
  icon: Icon,
  onClick,
  disabled,
  badge,
  hotkey,
}: {
  active: boolean
  label: string
  icon: LucideIcon
  onClick?: () => void
  disabled?: boolean
  badge?: boolean
  hotkey?: number
}) {
  return (
    <button
      type="button"
      title={`${label}${hotkey ? ` (Alt+${hotkey})` : ''}${badge ? ' — есть несохранённый черновик' : ''}`}
      onClick={onClick}
      disabled={disabled}
      aria-current={active ? 'page' : undefined}
      className={`relative flex h-11 items-center gap-2 rounded-lg px-3.5 text-[15px] font-medium transition disabled:cursor-default ${
        active
          ? 'bg-emerald-500 text-white shadow-sm shadow-emerald-500/30'
          : 'text-muted-foreground hover:bg-white/5 hover:text-foreground disabled:opacity-40'
      }`}
    >
      <Icon className="h-5 w-5 shrink-0" />
      <span className="hidden whitespace-nowrap sm:inline">{label}</span>
      {hotkey ? (
        <kbd className={`hidden rounded px-1 text-[11px] font-normal xl:inline ${active ? 'bg-white/20 text-white' : 'bg-muted text-muted-foreground'}`}>
          {hotkey}
        </kbd>
      ) : null}
      {badge ? (
        <span className="absolute right-1 top-1 h-2 w-2 rounded-full bg-amber-400 ring-2 ring-background" />
      ) : null}
    </button>
  )
}

/** Наличие черновика заявки: слушаем событие модуля и storage (другие окна). */
function useRequestDraftBadge(override: boolean | undefined): boolean {
  const [autoBadge, setAutoBadge] = useState(() => (override === undefined ? hasAnyDraft() : false))

  useEffect(() => {
    if (override !== undefined) return
    const refresh = () => setAutoBadge(hasAnyDraft())
    refresh()
    window.addEventListener(REQUEST_DRAFT_EVENT, refresh)
    window.addEventListener('storage', refresh)
    return () => {
      window.removeEventListener(REQUEST_DRAFT_EVENT, refresh)
      window.removeEventListener('storage', refresh)
    }
  }, [override])

  return override === undefined ? autoBadge : override
}

export default function WorkModeSwitch({
  active,
  showSale,
  showReturn,
  showHistory,
  showScanner,
  showRequest,
  showArena,
  requestBadge,
  onShift,
  onSale,
  onReturn,
  onHistory,
  onScanner,
  onRequest,
  onCabinet,
  onArena,
}: Props) {
  const draftBadge = useRequestDraftBadge(requestBadge)

  // Видимые вкладки по порядку — Alt+1…Alt+N переключают их с клавиатуры.
  const tabs: Array<{ mode: WorkMode; label: string; icon: LucideIcon; onClick?: () => void; badge?: boolean }> = []
  if (onShift || active === 'shift') tabs.push({ mode: 'shift', label: 'Смена', icon: ReceiptText, onClick: onShift })
  if (showSale) tabs.push({ mode: 'sale', label: 'Продажа', icon: ShoppingBasket, onClick: onSale })
  if (showReturn) tabs.push({ mode: 'return', label: 'Возврат', icon: RotateCcw, onClick: onReturn })
  if (showHistory) tabs.push({ mode: 'history', label: 'История', icon: History, onClick: onHistory })
  if (showScanner) tabs.push({ mode: 'scanner', label: 'Долги', icon: ScanBarcode, onClick: onScanner })
  if (showRequest) tabs.push({ mode: 'request', label: 'Заявка', icon: ClipboardList, onClick: onRequest, badge: draftBadge })
  if (showArena) tabs.push({ mode: 'arena', label: 'Зал', icon: Gamepad2, onClick: onArena })
  tabs.push({ mode: 'cabinet', label: 'Профиль', icon: UserCircle2, onClick: onCabinet })

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (!event.altKey || event.ctrlKey || event.metaKey) return
      const index = Number(event.key) - 1
      if (!Number.isInteger(index) || index < 0 || index >= tabs.length) return
      const tab = tabs[index]
      if (!tab.onClick || tab.mode === active) return
      event.preventDefault()
      tab.onClick()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  return (
    <div className="inline-flex flex-wrap items-center gap-2">
    <div className="inline-flex items-center gap-1 rounded-xl border border-white/10 bg-muted/40 p-1 no-drag">
      {tabs.map((tab, index) => (
        <ModeButton
          key={tab.mode}
          active={active === tab.mode}
          label={tab.label}
          icon={tab.icon}
          onClick={tab.onClick}
          disabled={!tab.onClick || active === tab.mode}
          badge={tab.badge}
          hotkey={index < 9 ? index + 1 : undefined}
        />
      ))}
    </div>
    <ConnectionBadge />
    </div>
  )
}
