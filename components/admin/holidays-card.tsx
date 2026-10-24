'use client'

import { useEffect, useState } from 'react'
import { PartyPopper, Plus, Save, Trash2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Switch } from '@/components/ui/switch'
import { toast } from '@/components/ui/use-toast'
import { DEFAULT_HOLIDAY_SETTINGS, KZ_HOLIDAYS, type CustomHoliday, type HolidaySettings } from '@/lib/core/holidays'

const MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']

function dateLabel(month: number, day: number) {
  return `${day} ${MONTHS[month - 1]}`
}

/**
 * Праздники для поздравлений команды в Telegram. По умолчанию — казахстанские с
 * прежними текстами. Можно выключить рассылку, убрать праздник, переписать текст
 * ({name} — имя сотрудника) и добавить свою дату. Меняет владелец.
 */
export function HolidaysCard(props: { canEdit: boolean }) {
  const [settings, setSettings] = useState<HolidaySettings>(DEFAULT_HOLIDAY_SETTINGS)
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [editingKey, setEditingKey] = useState<string | null>(null)
  const [newDate, setNewDate] = useState('')
  const [newName, setNewName] = useState('')

  useEffect(() => {
    let cancelled = false
    fetch('/api/admin/org-settings', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : null))
      .then((json) => {
        if (cancelled) return
        if (json?.settings?.holidays) setSettings(json.settings.holidays)
        setLoaded(true)
      })
      .catch(() => setLoaded(true))
    return () => {
      cancelled = true
    }
  }, [])

  async function save(next: HolidaySettings) {
    setSaving(true)
    try {
      const response = await fetch('/api/admin/org-settings', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ holidays: next }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok) throw new Error(json?.error || 'Не удалось сохранить')
      setSettings(json?.settings?.holidays || next)
      toast({ title: 'Праздники сохранены' })
    } catch (error: any) {
      toast({ title: 'Не получилось', description: error.message, variant: 'destructive' })
    }
    setSaving(false)
  }

  const toggleHoliday = (key: string) =>
    save({
      ...settings,
      disabled: settings.disabled.includes(key) ? settings.disabled.filter((k) => k !== key) : [...settings.disabled, key],
    })

  function addCustom() {
    const [, month, day] = newDate.split('-').map(Number)
    if (!month || !day || !newName.trim()) return
    const item: CustomHoliday = { id: `c${Date.now().toString(36)}`, month, day, name: newName.trim(), greeting: '' }
    setNewDate('')
    setNewName('')
    save({ ...settings, custom: [...settings.custom, item] })
  }

  const disabledAll = !settings.enabled

  return (
    <Card className="p-5">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
            <PartyPopper className="h-5 w-5 text-pink-500" /> Праздники и поздравления
          </h2>
          <p className="text-sm text-muted-foreground">
            Бот поздравляет команду в Telegram в 9:00. В тексте <code>{'{name}'}</code> заменяется именем сотрудника.
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <Switch
            checked={settings.enabled}
            disabled={!props.canEdit || saving || !loaded}
            onCheckedChange={(checked) => save({ ...settings, enabled: checked })}
            aria-label="Поздравлять команду"
          />
          {settings.enabled ? 'Поздравляем' : 'Не поздравляем'}
        </label>
      </div>

      <div className={`space-y-1.5 ${disabledAll ? 'opacity-50' : ''}`}>
        {KZ_HOLIDAYS.map((holiday) => {
          const on = !settings.disabled.includes(holiday.key)
          const custom = settings.greetings[holiday.key]
          return (
            <div key={holiday.key} className="rounded-xl border border-border px-3 py-2">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                <Switch
                  checked={on}
                  disabled={!props.canEdit || saving || disabledAll}
                  onCheckedChange={() => toggleHoliday(holiday.key)}
                  aria-label={holiday.name}
                />
                <span className="w-24 shrink-0 text-xs text-muted-foreground">{dateLabel(holiday.month, holiday.day)}</span>
                <span className="font-medium text-foreground">
                  {holiday.emoji} {holiday.name}
                </span>
                {holiday.note && <span className="text-xs text-amber-700 dark:text-amber-300">{holiday.note}</span>}
                {props.canEdit && (
                  <button
                    type="button"
                    onClick={() => setEditingKey(editingKey === holiday.key ? null : holiday.key)}
                    className="ml-auto text-xs text-muted-foreground underline hover:text-foreground"
                  >
                    {custom ? 'свой текст' : 'текст'}
                  </button>
                )}
              </div>
              {editingKey === holiday.key && (
                <GreetingEditor
                  initial={custom || holiday.greeting}
                  onCancel={() => setEditingKey(null)}
                  onReset={custom ? () => {
                    const { [holiday.key]: _drop, ...rest } = settings.greetings
                    setEditingKey(null)
                    save({ ...settings, greetings: rest })
                  } : undefined}
                  onSave={(text) => {
                    setEditingKey(null)
                    save({ ...settings, greetings: { ...settings.greetings, [holiday.key]: text } })
                  }}
                />
              )}
            </div>
          )
        })}

        {settings.custom.map((holiday) => (
          <div key={holiday.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-dashed border-border px-3 py-2 text-sm">
            <span className="w-24 shrink-0 text-xs text-muted-foreground">{dateLabel(holiday.month, holiday.day)}</span>
            <span className="font-medium text-foreground">🎉 {holiday.name}</span>
            <span className="truncate text-xs text-muted-foreground">{holiday.greeting}</span>
            {props.canEdit && (
              <button
                type="button"
                aria-label="Убрать"
                onClick={() => save({ ...settings, custom: settings.custom.filter((h) => h.id !== holiday.id) })}
                className="ml-auto text-muted-foreground hover:text-rose-500"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
        ))}
      </div>

      {props.canEdit && (
        <div className="mt-3 flex flex-wrap gap-2">
          <Input type="date" value={newDate} onChange={(e) => setNewDate(e.target.value)} className="h-9 w-40" aria-label="Дата" />
          <Input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="Свой праздник, например «День рождения клуба»" className="h-9 min-w-56 flex-1" maxLength={80} />
          <Button variant="outline" onClick={addCustom} disabled={saving || !newDate || !newName.trim()}>
            <Plus className="mr-1 h-4 w-4" /> Добавить
          </Button>
        </div>
      )}
      {!props.canEdit && <p className="mt-3 text-xs text-muted-foreground">Менять праздники может владелец.</p>}
    </Card>
  )
}

function GreetingEditor(props: { initial: string; onSave: (text: string) => void; onCancel: () => void; onReset?: () => void }) {
  const [text, setText] = useState(props.initial)
  return (
    <div className="mt-2 space-y-2">
      <textarea
        value={text}
        onChange={(e) => setText(e.target.value)}
        rows={3}
        maxLength={600}
        className="w-full rounded-lg border border-border bg-white px-3 py-2 text-sm dark:bg-slate-900/60"
      />
      <div className="flex flex-wrap gap-2">
        <Button size="sm" onClick={() => props.onSave(text.trim())} disabled={!text.trim()}>
          <Save className="mr-1 h-3.5 w-3.5" /> Сохранить текст
        </Button>
        {props.onReset && (
          <Button size="sm" variant="ghost" onClick={props.onReset}>
            Вернуть стандартный
          </Button>
        )}
        <Button size="sm" variant="ghost" onClick={props.onCancel}>
          Отмена
        </Button>
      </div>
    </div>
  )
}
