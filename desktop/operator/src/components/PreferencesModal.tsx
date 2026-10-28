/**
 * Модалка настроек оператора: тема, размер шрифта, звуки.
 * Открывается через шестерёнку в шапке.
 */

import { useEffect, useState } from 'react'
import { LayoutGrid, List, MonitorSmartphone, Sun, Moon, Monitor, Volume2, VolumeX, X } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  type Theme,
  type FontSize,
  type SalesLayout,
  getTheme,
  setTheme,
  getFontSize,
  setFontSize,
  isSoundEnabled,
  setSoundEnabled,
  isCustomerDisplayEnabled,
  setCustomerDisplayEnabled,
  getSalesLayout,
  setSalesLayout,
  type UiScale,
  type LiteMode,
  getLiteMode,
  setLiteMode,
  isWeakDevice,
  getUiScale,
  setUiScale,
  resolveUiScale,
} from '@/lib/preferences'
import { toastError, toastSuccess } from '@/lib/toast'

type PreferencesModalProps = {
  open: boolean
  onClose: () => void
  /** Вызывается при смене вида кассы — страница продаж перерисовывает раскладку сразу. */
  onSalesLayoutChange?: (layout: SalesLayout) => void
  /** Новый вид кассы: размер текста и «Карточки/Список» относятся только к классическому. */
  v2?: boolean
}

export function PreferencesModal({ open, onClose, onSalesLayoutChange, v2 }: PreferencesModalProps) {
  const [uiScale, setUiScaleState] = useState<UiScale>(getUiScale())
  const [lite, setLiteState] = useState<LiteMode>(getLiteMode())
  const [theme, setThemeState] = useState<Theme>(getTheme())
  const [fontSize, setFontSizeState] = useState<FontSize>(getFontSize())
  const [sound, setSound] = useState<boolean>(isSoundEnabled())
  const [salesLayout, setSalesLayoutState] = useState<SalesLayout>(getSalesLayout())
  const [customerDisplay, setCustomerDisplay] = useState<boolean>(isCustomerDisplayEnabled())
  const [customerDisplayAvailable, setCustomerDisplayAvailable] = useState<boolean>(false)

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onClose])

  useEffect(() => {
    if (!open) return
    try {
      void window.electron?.customerDisplay?.available?.().then(setCustomerDisplayAvailable)
    } catch {
      setCustomerDisplayAvailable(false)
    }
  }, [open])

  if (!open) return null

  const onThemeChange = (t: Theme) => {
    setThemeState(t)
    setTheme(t)
  }
  const onFontChange = (f: FontSize) => {
    setFontSizeState(f)
    setFontSize(f)
  }
  const onScaleChange = (scale: UiScale) => {
    setUiScaleState(scale)
    setUiScale(scale)
  }
  const onSoundChange = (s: boolean) => {
    setSound(s)
    setSoundEnabled(s)
  }
  const onSalesLayoutChangeLocal = (l: SalesLayout) => {
    setSalesLayoutState(l)
    setSalesLayout(l)
    onSalesLayoutChange?.(l)
  }

  const onCustomerDisplayChange = async (next: boolean) => {
    if (next) {
      try {
        const result = await window.electron?.customerDisplay?.open?.()
        if (!result?.ok) {
          toastError(
            result?.reason === 'no-external-display'
              ? 'Второй монитор не подключён. Включите режим расширения экранов в настройках Windows.'
              : 'Не удалось открыть экран клиента',
          )
          return
        }
        setCustomerDisplay(true)
        setCustomerDisplayEnabled(true)
        toastSuccess('Экран клиента открыт на втором мониторе')
      } catch (err: any) {
        toastError(err?.message || 'Ошибка открытия экрана клиента')
      }
    } else {
      try {
        await window.electron?.customerDisplay?.close?.()
      } catch { /* ignore */ }
      setCustomerDisplay(false)
      setCustomerDisplayEnabled(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="max-h-[92vh] w-full max-w-md overflow-y-auto rounded-2xl bg-white text-slate-900 shadow-2xl dark:bg-slate-900 dark:text-slate-100">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <h3 className="text-lg font-bold">Настройки</h3>
          <button
            onClick={onClose}
            className="grid h-9 w-9 place-items-center rounded-xl text-slate-400 hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Theme */}
        <div className="border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <p className="mb-3 text-sm font-medium">Тема</p>
          <div className="flex gap-2">
            {([
              { value: 'light', label: 'Светлая', icon: Sun },
              { value: 'dark', label: 'Тёмная', icon: Moon },
              { value: 'system', label: 'Авто', icon: Monitor },
            ] as Array<{ value: Theme; label: string; icon: any }>).map(({ value, label, icon: Icon }) => (
              <button
                key={value}
                onClick={() => onThemeChange(value)}
                className={`flex flex-1 flex-col items-center gap-1.5 rounded-xl border-2 px-3 py-3 transition ${
                  theme === value
                    ? 'border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
                    : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                }`}
              >
                <Icon className="h-5 w-5" />
                <span className="text-xs">{label}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Масштаб интерфейса — маленькие экраны моноблоков */}
        <div className="border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <p className="mb-3 text-sm font-medium">Масштаб интерфейса</p>
          <div className="flex gap-2">
            {([
              { value: 'auto', label: 'Авто' },
              { value: '75', label: '75%' },
              { value: '85', label: '85%' },
              { value: '100', label: '100%' },
              { value: '115', label: '115%' },
            ] as Array<{ value: UiScale; label: string }>).map(({ value, label }) => (
              <button
                key={value}
                onClick={() => onScaleChange(value)}
                className={`flex-1 rounded-xl border-2 py-2.5 text-sm font-semibold transition ${
                  uiScale === value
                    ? 'border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
                    : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
          <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
            {uiScale === 'auto'
              ? `Подобран под этот экран: ${Math.round(resolveUiScale('auto') * 100)}%. На маленьком моноблоке всё влезает без прокрутки.`
              : 'Если не влезает — уменьшите, если мелко — увеличьте.'}
          </p>
        </div>

        {/* Лёгкий режим — слабые моноблоки */}
        {v2 ? (
          <div className="border-b border-slate-200 px-5 py-4 dark:border-slate-800">
            <p className="mb-3 text-sm font-medium">Лёгкий режим (для слабых компьютеров)</p>
            <div className="flex gap-2">
              {([
                { value: 'auto', label: 'Авто' },
                { value: 'on', label: 'Включён' },
                { value: 'off', label: 'Выключен' },
              ] as Array<{ value: LiteMode; label: string }>).map(({ value, label }) => (
                <button
                  key={value}
                  onClick={() => {
                    setLiteState(value)
                    setLiteMode(value)
                  }}
                  className={`flex-1 rounded-xl border-2 py-2.5 text-sm font-semibold transition ${
                    lite === value
                      ? 'border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
                      : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
              Без размытия, теней и анимаций — касса не тормозит на старом железе.
              {lite === 'auto' ? (isWeakDevice() ? ' Сейчас включён: компьютер слабый.' : ' Сейчас выключен: компьютер справляется.') : ''}
            </p>
          </div>
        ) : null}

        {/* Font size */}
        {v2 ? null : (
        <div className="border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <p className="mb-3 text-sm font-medium">Размер текста</p>
          <div className="flex gap-2">
            {([
              { value: 'sm', label: 'А', size: 'text-sm' },
              { value: 'md', label: 'А', size: 'text-base' },
              { value: 'lg', label: 'А', size: 'text-lg' },
              { value: 'xl', label: 'А', size: 'text-xl' },
            ] as Array<{ value: FontSize; label: string; size: string }>).map(({ value, label, size }) => (
              <button
                key={value}
                onClick={() => onFontChange(value)}
                className={`flex-1 rounded-xl border-2 py-3 font-bold transition ${size} ${
                  fontSize === value
                    ? 'border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
                    : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
        )}

        {/* Вид кассы: карточки товаров или классический список */}
        {v2 ? null : (
        <div className="border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <p className="mb-3 text-sm font-medium">Вид кассы</p>
          <div className="flex gap-2">
            {([
              { value: 'cards', label: 'Карточки', icon: LayoutGrid, hint: 'Сетка товаров, тап для добавления' },
              { value: 'list', label: 'Список', icon: List, hint: 'Поиск и таблица позиций' },
            ] as Array<{ value: SalesLayout; label: string; icon: any; hint: string }>).map(({ value, label, icon: Icon }) => (
              <button
                key={value}
                onClick={() => onSalesLayoutChangeLocal(value)}
                className={`flex flex-1 flex-col items-center gap-1.5 rounded-xl border-2 px-3 py-3 transition ${
                  salesLayout === value
                    ? 'border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
                    : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                }`}
              >
                <Icon className="h-5 w-5" />
                <span className="text-xs">{label}</span>
              </button>
            ))}
          </div>
          <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
            {salesLayout === 'cards'
              ? 'Товары показываются карточками с фото — удобно на сенсорном экране.'
              : 'Классический режим: поиск/сканер и таблица позиций.'}
          </p>
        </div>
        )}

        {/* Sound */}
        <div className="border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <p className="mb-3 text-sm font-medium">Звуки</p>
          <button
            onClick={() => onSoundChange(!sound)}
            className={`flex w-full items-center justify-between rounded-xl border-2 px-4 py-3 transition ${
              sound
                ? 'border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
                : 'border-slate-200 dark:border-slate-700'
            }`}
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              {sound ? <Volume2 className="h-5 w-5" /> : <VolumeX className="h-5 w-5" />}
              {sound ? 'Звуки включены' : 'Звуки отключены'}
            </span>
            <div className={`h-6 w-12 rounded-full transition ${sound ? 'bg-blue-500' : 'bg-slate-300 dark:bg-slate-700'} relative`}>
              <div className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all ${sound ? 'left-6' : 'left-0.5'}`} />
            </div>
          </button>
        </div>

        {/* Customer display */}
        <div className="border-b border-slate-200 px-5 py-4 dark:border-slate-800">
          <p className="mb-3 text-sm font-medium">Экран клиента (второй монитор)</p>
          <button
            onClick={() => void onCustomerDisplayChange(!customerDisplay)}
            disabled={!customerDisplayAvailable && !customerDisplay}
            className={`flex w-full items-center justify-between rounded-xl border-2 px-4 py-3 transition disabled:cursor-not-allowed disabled:opacity-50 ${
              customerDisplay
                ? 'border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-500/10 dark:text-blue-300'
                : 'border-slate-200 dark:border-slate-700'
            }`}
          >
            <span className="flex items-center gap-2 text-sm font-medium">
              <MonitorSmartphone className="h-5 w-5" />
              {customerDisplay ? 'Окно клиента открыто' : 'Окно клиента отключено'}
            </span>
            <div className={`relative h-6 w-12 rounded-full transition ${customerDisplay ? 'bg-blue-500' : 'bg-slate-300 dark:bg-slate-700'}`}>
              <div className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all ${customerDisplay ? 'left-6' : 'left-0.5'}`} />
            </div>
          </button>
          <p className="mt-2 text-xs text-slate-500 dark:text-slate-400">
            {customerDisplayAvailable
              ? 'Второй монитор обнаружен. На нём появится экран с корзиной и итогом для клиента.'
              : 'Второй монитор не подключён или работает в режиме «Дублировать». В настройках Windows выберите «Расширить эти экраны».'}
          </p>
        </div>

        {/* Footer */}
        <div className="flex justify-end p-4">
          <Button onClick={onClose} className="bg-slate-900 text-white hover:bg-slate-700 dark:bg-white dark:text-slate-900 dark:hover:bg-slate-200">
            Готово
          </Button>
        </div>
      </div>
    </div>
  )
}
