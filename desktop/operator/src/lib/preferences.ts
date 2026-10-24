/**
 * Локальные настройки оператора: тема, размер шрифта, звуки.
 * Сохраняются в localStorage. Применяются на root <html> через классы.
 */

export type Theme = 'dark' | 'light' | 'system'
export type FontSize = 'sm' | 'md' | 'lg' | 'xl'
/** Вид кассы: 'cards' — сетка карточек товаров (как Poster, под сенсор), 'list' — поиск + таблица позиций. */
export type SalesLayout = 'cards' | 'list'

const KEY_THEME = 'orda.theme'
const KEY_FONT = 'orda.fontSize'
const KEY_SOUND = 'orda.soundEnabled'
const KEY_CUSTOMER_DISPLAY = 'orda.customerDisplay'
const KEY_SALES_LAYOUT = 'orda.salesLayout'

export function getTheme(): Theme {
  if (typeof window === 'undefined') return 'dark'
  const v = window.localStorage.getItem(KEY_THEME)
  return (v === 'light' || v === 'dark' || v === 'system') ? v : 'dark'
}

export function setTheme(theme: Theme) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(KEY_THEME, theme)
  applyTheme()
}

export function applyTheme() {
  if (typeof window === 'undefined') return
  const theme = getTheme()
  const html = document.documentElement
  const isDark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  if (isDark) html.classList.add('dark')
  else html.classList.remove('dark')
}

export function getFontSize(): FontSize {
  if (typeof window === 'undefined') return 'md'
  const v = window.localStorage.getItem(KEY_FONT)
  return (v === 'sm' || v === 'md' || v === 'lg' || v === 'xl') ? v : 'md'
}

export function setFontSize(size: FontSize) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(KEY_FONT, size)
  applyFontSize()
}

export function applyFontSize() {
  if (typeof window === 'undefined') return
  const size = getFontSize()
  const html = document.documentElement
  // px на root — Tailwind использует rem относительно
  const sizeMap: Record<FontSize, string> = {
    sm: '14px',
    md: '16px',
    lg: '18px',
    xl: '20px',
  }
  html.style.fontSize = sizeMap[size]
}

export function isSoundEnabled(): boolean {
  if (typeof window === 'undefined') return true
  const v = window.localStorage.getItem(KEY_SOUND)
  return v !== '0'  // По умолчанию включено
}

export function setSoundEnabled(enabled: boolean) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(KEY_SOUND, enabled ? '1' : '0')
}

export function isCustomerDisplayEnabled(): boolean {
  if (typeof window === 'undefined') return false
  return window.localStorage.getItem(KEY_CUSTOMER_DISPLAY) === '1'
}

export function setCustomerDisplayEnabled(enabled: boolean) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(KEY_CUSTOMER_DISPLAY, enabled ? '1' : '0')
}

export function getSalesLayout(): SalesLayout {
  if (typeof window === 'undefined') return 'cards'
  const v = window.localStorage.getItem(KEY_SALES_LAYOUT)
  // По умолчанию — карточки (сенсорный моноблок). Классический список — по выбору.
  return v === 'list' ? 'list' : 'cards'
}

export function setSalesLayout(layout: SalesLayout) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(KEY_SALES_LAYOUT, layout)
}

/** Применить все настройки при старте программы */
export function applyAllPreferences() {
  applyTheme()
  applyFontSize()
  applyUiScale()
  // Customer display — открыть второе окно если включено в настройках и есть внешний монитор
  if (typeof window !== 'undefined' && isCustomerDisplayEnabled()) {
    try {
      void window.electron?.customerDisplay?.open?.()
    } catch { /* ignore */ }
  }
}

/**
 * Вид кассы: 'v2' — новый дизайн (src/v2), 'classic' — прежние экраны.
 * Пока новый вид проверяется на точках, по умолчанию — классический; оператор
 * переключает кнопкой в шапке, вернуться можно в одно касание.
 */
export type UiVersion = 'v2' | 'classic'
const KEY_UI_VERSION = 'orda.uiVersion'
export const UI_VERSION_EVENT = 'orda:ui-version'

export function getUiVersion(): UiVersion {
  if (typeof window === 'undefined') return 'classic'
  return window.localStorage.getItem(KEY_UI_VERSION) === 'v2' ? 'v2' : 'classic'
}

export function setUiVersion(version: UiVersion) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(KEY_UI_VERSION, version)
  window.dispatchEvent(new CustomEvent(UI_VERSION_EVENT))
}

/**
 * Масштаб интерфейса. Касса рассчитана на 1920×1080; на моноблоке 1366×768
 * всё было крупным и не влезало. 'auto' подбирает масштаб по экрану.
 */
export type UiScale = 'auto' | '75' | '85' | '100' | '115'
const KEY_UI_SCALE = 'orda.uiScale'

export function getUiScale(): UiScale {
  if (typeof window === 'undefined') return 'auto'
  const v = window.localStorage.getItem(KEY_UI_SCALE)
  return v === '75' || v === '85' || v === '100' || v === '115' ? v : 'auto'
}

export function setUiScale(scale: UiScale) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(KEY_UI_SCALE, scale)
  applyUiScale()
}

export function resolveUiScale(scale: UiScale = getUiScale()): number {
  if (scale !== 'auto') return Number(scale) / 100
  if (typeof window === 'undefined') return 1
  const w = window.screen?.availWidth || 1920
  const h = window.screen?.availHeight || 1080
  const factor = Math.min(w / 1600, h / 900)
  return Math.round(Math.max(0.75, Math.min(1, factor)) * 20) / 20
}

export function applyUiScale() {
  if (typeof window === 'undefined') return
  // Экран покупателя — своё окно и свой монитор, его не масштабируем.
  if (new URLSearchParams(window.location.search).get('role') === 'customer') return
  try {
    window.electron?.zoom?.set(resolveUiScale())
  } catch {
    /* старый preload без масштаба */
  }
}
