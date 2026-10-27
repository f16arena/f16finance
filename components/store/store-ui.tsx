'use client'

/**
 * Общие детали дизайна раздела «Магазин». Образец — обзор `/store`:
 * спокойные белые карточки, цифры крупно и tabular-nums, цвет только по смыслу
 * (rose — потеря/нет, amber — ждёт человека, emerald — хорошо, sky — подсобка),
 * нули не кричат. Новые страницы магазина собираются из этих деталей,
 * а не из самописных `rounded-2xl border …` в каждом файле.
 */

import Link from 'next/link'
import type { ReactNode } from 'react'
import { AlertTriangle, ArrowRight, CheckCircle2, Inbox } from 'lucide-react'

import { Skeleton } from '@/components/skeleton'

export const storeCard = 'rounded-2xl border border-border bg-white dark:bg-slate-900/60 shadow-sm'

type Tone = 'neutral' | 'emerald' | 'amber' | 'rose' | 'sky' | 'violet'

const TONE_TEXT: Record<Tone, string> = {
  neutral: 'text-foreground',
  emerald: 'text-emerald-600 dark:text-emerald-300',
  amber: 'text-amber-600 dark:text-amber-300',
  rose: 'text-rose-600 dark:text-rose-300',
  sky: 'text-sky-600 dark:text-sky-300',
  violet: 'text-violet-600 dark:text-violet-300',
}
const TONE_CHIP: Record<Tone, string> = {
  neutral: 'bg-slate-100 text-slate-600 dark:bg-white/5 dark:text-slate-300',
  emerald: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-300',
  amber: 'bg-amber-500/10 text-amber-600 dark:text-amber-300',
  rose: 'bg-rose-500/10 text-rose-600 dark:text-rose-300',
  sky: 'bg-sky-500/10 text-sky-600 dark:text-sky-300',
  violet: 'bg-violet-500/10 text-violet-600 dark:text-violet-300',
}

/** Карточка-секция с шапкой: заголовок, подпись, действие справа. */
export function StoreSection(props: {
  title: ReactNode
  subtitle?: ReactNode
  icon?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
  /** true — содержимое без внутренних отступов (таблицы, списки строк). */
  flush?: boolean
}) {
  return (
    <section className={`${storeCard} overflow-hidden ${props.className || ''}`}>
      <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
        <div className="flex min-w-0 items-center gap-2.5">
          {props.icon ? <span className="shrink-0 text-muted-foreground">{props.icon}</span> : null}
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold text-foreground">{props.title}</div>
            {props.subtitle ? <div className="truncate text-xs text-muted-foreground">{props.subtitle}</div> : null}
          </div>
        </div>
        {props.action ? <div className="shrink-0">{props.action}</div> : null}
      </div>
      <div className={props.flush ? '' : 'p-4'}>{props.children}</div>
    </section>
  )
}

/** Плитка с одной цифрой. value уже отформатирован (formatMoney сам дописывает ₸). */
export function StoreStat(props: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: Tone
  icon?: ReactNode
  href?: string
  loading?: boolean
}) {
  const body = (
    <div className={`${storeCard} h-full p-3.5 sm:p-4 ${props.href ? 'transition hover:-translate-y-0.5 hover:shadow-md' : ''}`}>
      <div className="flex items-center justify-between gap-2">
        <p className="text-[11px] font-medium uppercase tracking-wider text-slate-500">{props.label}</p>
        {props.icon ? <span className={`grid h-7 w-7 place-items-center rounded-lg ${TONE_CHIP[props.tone || 'neutral']}`}>{props.icon}</span> : null}
      </div>
      {props.loading ? (
        <Skeleton className="mt-2 h-7 w-24" />
      ) : (
        <p className={`mt-1.5 text-xl font-bold tabular-nums tracking-tight sm:text-2xl ${TONE_TEXT[props.tone || 'neutral']}`}>{props.value}</p>
      )}
      {props.hint ? <p className="mt-0.5 text-[11px] text-slate-500">{props.hint}</p> : null}
    </div>
  )
  return props.href ? <Link href={props.href} className="block h-full">{body}</Link> : body
}

/** Сетка плиток: 2 на телефоне, дальше по ширине. */
export function StoreStatGrid({ children, cols = 4 }: { children: ReactNode; cols?: 3 | 4 | 5 | 6 }) {
  const lg = { 3: 'lg:grid-cols-3', 4: 'lg:grid-cols-4', 5: 'lg:grid-cols-5', 6: 'lg:grid-cols-6' }[cols]
  return <div className={`grid grid-cols-2 gap-3 md:grid-cols-3 ${lg}`}>{children}</div>
}

/** Небольшая цветная метка-статус. */
export function StoreChip({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] font-medium ${TONE_CHIP[tone]}`}>{children}</span>
}

export type StoreTodo = { key: string; icon: any; tone: 'amber' | 'rose'; text: ReactNode; href?: string; onClick?: () => void; cta?: string }

/** «Требует внимания»: только ненулевые дела. Пусто — зелёная строка «всё спокойно». */
export function StoreAttention({ items, calm, loading }: { items: StoreTodo[]; calm: ReactNode; loading?: boolean }) {
  if (loading) return <Skeleton className="h-14 w-full rounded-2xl" />
  if (items.length === 0) {
    return (
      <div className="flex items-center gap-3 rounded-2xl border border-emerald-500/25 bg-emerald-500/[0.07] px-4 py-3">
        <CheckCircle2 className="h-5 w-5 shrink-0 text-emerald-600 dark:text-emerald-300" />
        <div className="text-sm text-emerald-800 dark:text-emerald-100">{calm}</div>
      </div>
    )
  }
  return (
    <div className={`${storeCard} overflow-hidden`}>
      <div className="flex items-center gap-2 border-b border-border px-4 py-2.5 text-xs font-semibold uppercase tracking-[0.14em] text-slate-500">
        <AlertTriangle className="h-3.5 w-3.5 text-amber-500" /> Требует внимания
      </div>
      <div className="divide-y divide-border">
        {items.map((t) => {
          const inner = (
            <>
              <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${TONE_CHIP[t.tone]}`}><t.icon className="h-4 w-4" /></span>
              <span className="min-w-0 flex-1 text-sm font-medium text-foreground">{t.text}</span>
              {t.cta ? (
                <span className="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-slate-500 group-hover:text-foreground">
                  {t.cta} <ArrowRight className="h-3.5 w-3.5 transition group-hover:translate-x-0.5" />
                </span>
              ) : null}
            </>
          )
          const cls = 'group flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-slate-50 dark:hover:bg-white/[0.03]'
          return t.href
            ? <Link key={t.key} href={t.href} className={cls}>{inner}</Link>
            : <button key={t.key} type="button" onClick={t.onClick} className={cls}>{inner}</button>
        })}
      </div>
    </div>
  )
}

/** Пустое состояние: что здесь будет и как это получить. */
export function StoreEmpty({ title, hint, icon, action }: { title: ReactNode; hint?: ReactNode; icon?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-4 py-12 text-center">
      <span className="grid h-11 w-11 place-items-center rounded-2xl bg-slate-100 text-slate-400 dark:bg-white/5">{icon || <Inbox className="h-5 w-5" />}</span>
      <div className="mt-3 text-sm font-medium text-foreground">{title}</div>
      {hint ? <div className="mt-1 max-w-sm text-xs text-muted-foreground">{hint}</div> : null}
      {action ? <div className="mt-4">{action}</div> : null}
    </div>
  )
}

/** Строка списка: иконка-чип, две строки текста, справа значение. */
export function StoreRow(props: {
  icon?: any
  tone?: Tone
  title: ReactNode
  subtitle?: ReactNode
  right?: ReactNode
  href?: string
  onClick?: () => void
}) {
  const inner = (
    <>
      {props.icon ? <span className={`grid h-8 w-8 shrink-0 place-items-center rounded-lg ${TONE_CHIP[props.tone || 'neutral']}`}><props.icon className="h-4 w-4" /></span> : null}
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium text-foreground">{props.title}</div>
        {props.subtitle ? <div className="truncate text-[11px] text-muted-foreground">{props.subtitle}</div> : null}
      </div>
      {props.right ? <div className="shrink-0 text-right">{props.right}</div> : null}
    </>
  )
  const cls = 'flex w-full items-center gap-3 px-4 py-2.5 text-left'
  if (props.href) return <Link href={props.href} className={`${cls} transition-colors hover:bg-slate-50 dark:hover:bg-white/[0.03]`}>{inner}</Link>
  if (props.onClick) return <button type="button" onClick={props.onClick} className={`${cls} transition-colors hover:bg-slate-50 dark:hover:bg-white/[0.03]`}>{inner}</button>
  return <div className={cls}>{inner}</div>
}

/** Русское множественное число: plural(3, ['заявка', 'заявки', 'заявок']). */
export function plural(n: number, forms: [string, string, string]) {
  const a = Math.abs(n) % 100
  const b = a % 10
  if (a > 10 && a < 20) return forms[2]
  if (b > 1 && b < 5) return forms[1]
  if (b === 1) return forms[0]
  return forms[2]
}
