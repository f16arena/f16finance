'use client'

/**
 * Экран мастера со своим входом: его машины на сегодня и ближайшие дни и его
 * заработок за месяц. Больше мастер ничего не видит — ни чужих машин, ни
 * клиентов, ни денег детейлинга.
 */

import { Skeleton } from '@/components/skeleton'
import { StoreEmpty, StoreSection, plural, storeCard } from '@/components/store/store-ui'
import { useApiCache } from '@/lib/client/use-api-cache'
import { DetailingHeader, Plate, dayLabel, money, shiftIso } from '@/components/detailing/detailing-shared'

type MyCar = { id: string; box: string | null; start_date: string; start_time?: string | null; days: number; status: string; car: { plate: string; model: string | null } | null; services: string[]; comment: string | null; base: number }
type MyData = {
  linked: boolean
  name?: string
  today?: string
  cars?: MyCar[]
  pay?: { jobs: number; base: number; accrued: number; paid: number; due: number; percent: number | null }
}

const MONTHS_PREP = ['январе', 'феврале', 'марте', 'апреле', 'мае', 'июне', 'июле', 'августе', 'сентябре', 'октябре', 'ноябре', 'декабре']

export default function DetailingMyPage() {
  const { data, loading, error } = useApiCache<MyData>('/api/admin/detailing/my', { ttl: 30_000 })

  if (loading && !data) return <div className="space-y-4"><DetailingHeader title="Мои машины" /><Skeleton className="h-[120px] w-full rounded-2xl" /><Skeleton className="h-[220px] w-full rounded-2xl" /></div>
  if (error && !data) return <div className="space-y-4"><DetailingHeader title="Мои машины" /><div className={`${storeCard} p-6 text-sm text-rose-600`}>{error}</div></div>
  if (!data?.linked) {
    return (
      <div className="space-y-4">
        <DetailingHeader title="Мои машины" />
        <div className={storeCard}><StoreEmpty title="Этот вход не привязан к мастеру" hint="Экран показывает машины и заработок мастера. Вход мастеру даёт владелец в разделе «Команда»." /></div>
      </div>
    )
  }

  const today = data.today!
  const cars = data.cars || []
  const pay = data.pay!
  const now = cars.filter((c) => c.status === 'in_box' || c.start_date <= today)
  const later = cars.filter((c) => c.status !== 'in_box' && c.start_date > today)
  const monthName = MONTHS_PREP[Number(today.slice(5, 7)) - 1]

  const row = (c: MyCar) => {
    const last = shiftIso(c.start_date, c.days - 1)
    const when = c.status === 'in_box' ? (last <= today ? 'сдать сегодня' : `до ${dayLabel(last).day} ${dayLabel(last).month}`) : c.start_date === today ? (c.start_time ? `приедет в ${c.start_time}` : 'приедет сегодня') : `${dayLabel(c.start_date).short.toLowerCase()} ${dayLabel(c.start_date).month}${c.start_time ? `, ${c.start_time}` : ''}`
    return (
      <div key={c.id} className="flex items-center gap-3 px-4 py-3">
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2 font-semibold">{c.car?.model || 'Машина'} <Plate plate={c.car?.plate} /></span>
          <span className="block text-sm text-muted-foreground">{c.services.join(', ') || 'без услуг'}</span>
          {c.comment ? <span className="block text-xs text-muted-foreground">{c.comment}</span> : null}
        </span>
        <span className="shrink-0 text-right text-sm">
          <b className="block">{c.box || 'бокс не выбран'}</b>
          <span className={c.status === 'in_box' ? 'font-medium text-orange-600' : 'text-muted-foreground'}>{when}</span>
        </span>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <DetailingHeader title={data.name ? `${data.name}, ваши машины` : 'Мои машины'} subtitle={`${dayLabel(today).day} ${dayLabel(today).month}`} />

      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-2xl bg-white/10 lg:grid-cols-4">
        {[
          { label: `Заработано в ${monthName}`, value: money(pay.accrued), hint: pay.percent !== null ? `${pay.percent}% с работы без материалов` : 'по вашим условиям' },
          { label: 'Уже выплачено', value: money(pay.paid), hint: '' },
          { label: 'К выплате', value: money(pay.due), hint: '' },
          { label: 'Машин выдано', value: String(pay.jobs), hint: plural(pay.jobs, ['машина', 'машины', 'машин']) + ` в ${monthName}` },
        ].map((s) => (
          <div key={s.label} className="bg-[#0b1f3b] px-4 py-4 text-white dark:bg-[#030b18]">
            <div className="text-[11px] font-bold uppercase tracking-wider text-slate-400">{s.label}</div>
            <div className="mt-0.5 font-[family-name:var(--font-manrope)] text-2xl font-extrabold tabular-nums">{s.value}</div>
            <div className="truncate text-xs text-slate-400">{s.hint}</div>
          </div>
        ))}
      </div>

      <StoreSection title="Сейчас и сегодня" flush>
        {now.length ? <div className="divide-y divide-border">{now.map(row)}</div> : <StoreEmpty title="Сегодня машин нет" hint="Когда администратор поставит вас на машину, она появится здесь." />}
      </StoreSection>

      {later.length ? (
        <StoreSection title="Дальше на неделе" flush>
          <div className="divide-y divide-border">{later.map(row)}</div>
        </StoreSection>
      ) : null}

      <p className="text-xs text-muted-foreground">Заработок считается с выданных машин. Если цифра кажется неверной — скажите владельцу, он видит расчёт по каждой машине.</p>
    </div>
  )
}
