'use client'

/**
 * Стартовая страница детейлинга вместо клубного «Рабочего кабинета».
 * Пока не настроено — 3 шага: боксы и прайс, мастера, первая запись.
 * Точку руками не заводят — её создаёт сервер (ensureNichePoint).
 * Когда все сделаны — сразу «Сегодня» (/dashboard).
 */

import Link from 'next/link'
import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { Check, Loader2 } from 'lucide-react'

import { useApiCache } from '@/lib/client/use-api-cache'
import { useNavSession } from '@/lib/nav/use-nav-session'

type SetupData = {
  company: { id: string; name: string } | null
  boxes?: Array<{ is_active: boolean }>
  services?: Array<{ is_active: boolean }>
  masters?: Array<{ id: string }>
  bookingsCount?: number
}

export function DetailingWelcome() {
  const router = useRouter()
  const session = useNavSession()
  const { data, loading } = useApiCache<SetupData>('/api/admin/detailing/setup', { ttl: 10_000 })

  const hasPoint = !!data?.company
  const steps = [
    {
      title: 'Боксы и прайс по кузову',
      done: hasPoint && !!data?.boxes?.some((b) => b.is_active) && !!data?.services?.some((s) => s.is_active),
      doneText: `${data?.boxes?.filter((b) => b.is_active).length || 0} бокса · ${data?.services?.filter((s) => s.is_active).length || 0} услуг`,
      hint: 'можно заполнить примерным прайсом и поправить цены',
      href: '/detailing/prices',
      action: 'Настроить',
    },
    {
      title: 'Мастера',
      done: hasPoint && (data?.masters?.length || 0) > 0,
      doneText: `${data?.masters?.length || 0} мастер(а)`,
      hint: 'кто работает в боксах — их выбирают в записи',
      href: '/operators',
      action: 'Добавить',
    },
    {
      title: 'Первая запись',
      done: (data?.bookingsCount || 0) > 0,
      doneText: 'есть записи',
      hint: 'телефон, номер, машина — и она в календаре',
      href: '/detailing?new=1',
      action: 'Записать',
    },
  ]
  const doneCount = steps.filter((s) => s.done).length
  const allDone = !!data && doneCount === steps.length

  useEffect(() => {
    if (allDone) router.replace('/dashboard')
  }, [allDone, router])

  if ((loading && !data) || allDone) {
    return (
      <div className="flex min-h-[50vh] items-center justify-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin text-blue-600" /> Открываю…
      </div>
    )
  }

  const orgName = session.activeOrganization?.name || 'ваш детейлинг'
  // Первый незакрытый шаг — главная кнопка, остальные спокойнее.
  const nextIndex = steps.findIndex((s) => !s.done)

  return (
    <div className="mx-auto w-full max-w-3xl space-y-5 pb-10">
      <header className="space-y-2">
        <span className="inline-block rounded-full bg-blue-100 px-3 py-1 text-[11px] font-bold uppercase tracking-wider text-blue-700 dark:bg-blue-500/15 dark:text-blue-300">Первый запуск</span>
        <h1 className="font-[family-name:var(--font-manrope)] text-2xl font-extrabold text-[#0b1f3b] dark:text-white md:text-3xl">Добро пожаловать в {orgName}</h1>
        <p className="text-sm text-muted-foreground">Настройте 3 вещи — и можно записывать машины. Это займёт минут 5.</p>
      </header>

      <div className="rounded-2xl border border-border bg-card p-4">
        <div className="mb-2 text-xs text-muted-foreground">Готово {doneCount} из {steps.length}</div>
        <div className="h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-white/10">
          <div className="h-full rounded-full bg-blue-600 transition-all" style={{ width: `${(doneCount / steps.length) * 100}%` }} />
        </div>
      </div>

      <div className="space-y-2">
        {steps.map((s, i) => {
          // Точка создаётся сама при первом запросе; пока её нет — шаги ждут.
          const locked = !hasPoint
          return (
            <div key={s.title} className={`flex items-center gap-3 rounded-2xl border border-border bg-card p-4 ${locked ? 'opacity-50' : ''}`}>
              <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-full text-sm font-bold ${s.done ? 'bg-emerald-600 text-white' : 'bg-blue-100 text-blue-700 dark:bg-blue-500/15 dark:text-blue-300'}`}>
                {s.done ? <Check className="h-4 w-4" /> : i + 1}
              </span>
              <div className="min-w-0 flex-1">
                <div className={`font-semibold ${s.done ? 'text-muted-foreground line-through' : ''}`}>{s.title}</div>
                <div className="truncate text-xs text-muted-foreground">{s.done ? s.doneText : s.hint}</div>
              </div>
              {!s.done && !locked ? (
                <Link
                  href={s.href}
                  className={`shrink-0 rounded-xl px-3.5 py-2 text-sm font-bold ${i === nextIndex ? 'bg-blue-600 text-white hover:bg-blue-700' : 'border border-border hover:bg-slate-50 dark:hover:bg-white/5'}`}
                >
                  {s.action}
                </Link>
              ) : null}
            </div>
          )
        })}
      </div>

      <div className="rounded-2xl bg-orange-100 p-4 text-sm text-orange-900 dark:bg-orange-500/10 dark:text-orange-200">
        Можно пропустить — {hasPoint ? <Link href="/dashboard" className="font-semibold underline">открыть «Сегодня»</Link> : 'шаги останутся здесь'}. Когда все 3 сделаны, эта страница больше не показывается.
      </div>
    </div>
  )
}
