'use client'

import { PanelLeft, PanelTop } from 'lucide-react'

import { Card } from '@/components/ui/card'
import { useNavLayout, type NavLayout } from '@/lib/client/nav-layout'
import { cn } from '@/lib/utils'

/** Выбор вида меню — свой у каждого сотрудника, на этом устройстве. */
export function NavLayoutPicker() {
  const { layout, changeLayout } = useNavLayout()

  const option = (value: NavLayout, title: string, hint: string, Icon: typeof PanelTop) => (
    <button
      type="button"
      onClick={() => changeLayout(value)}
      className={cn(
        'flex flex-1 items-start gap-3 rounded-xl border px-4 py-3 text-left transition',
        layout === value ? 'border-blue-500/50 bg-blue-500/10' : 'border-border hover:bg-slate-50 dark:hover:bg-white/5',
      )}
    >
      <Icon className={cn('mt-0.5 h-5 w-5 shrink-0', layout === value ? 'text-blue-500' : 'text-slate-400')} />
      <span>
        <span className="block text-sm font-medium text-foreground">{title}</span>
        <span className="block text-xs text-muted-foreground">{hint}</span>
      </span>
    </button>
  )

  return (
    <Card className="space-y-3 border-border bg-card p-4">
      <div>
        <h2 className="text-base font-semibold text-foreground">Вид меню</h2>
        <p className="text-sm text-muted-foreground">Только для вас и этого устройства. На телефоне меню всегда выдвижное.</p>
      </div>
      <div className="flex flex-col gap-2 sm:flex-row">
        {option('top', 'Сверху', 'Разделы в строке, пункты — в выпадающем окне', PanelTop)}
        {option('side', 'Слева', 'Колонка с разделами, можно свернуть до иконок', PanelLeft)}
      </div>
    </Card>
  )
}
