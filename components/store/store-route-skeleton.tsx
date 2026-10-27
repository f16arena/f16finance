import { Skeleton } from '@/components/ui/skeleton'

// Тот же вид, что у storeCard (store-ui — клиентский модуль, в серверный скелет константу не тянем).
const storeCard = 'rounded-2xl border border-border bg-white dark:bg-slate-900/60 shadow-sm'

/**
 * Показывается из app/(main)/store/loading.tsx. Форма как у обзора магазина:
 * шапка страницы, ряд плиток с цифрами и белая карточка-секция.
 */
export function StoreRouteSkeleton() {
  return (
    <div className="mx-auto w-full max-w-screen-2xl space-y-4 p-4 md:px-4 md:pb-6">
      <div className="flex flex-wrap items-center gap-3">
        <Skeleton className="h-10 w-10 shrink-0 rounded-xl" />
        <div className="min-w-0 flex-1 space-y-2">
          <Skeleton className="h-6 w-48 max-w-full" />
          <Skeleton className="h-3 w-72 max-w-full" />
        </div>
        <Skeleton className="h-9 w-24 shrink-0 rounded-xl" />
      </div>
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className={`${storeCard} space-y-3 p-4`}>
            <Skeleton className="h-3 w-20" />
            <Skeleton className="h-7 w-28 max-w-full" />
          </div>
        ))}
      </div>
      <div className={`${storeCard} overflow-hidden`}>
        <div className="space-y-1.5 border-b border-border px-4 py-3">
          <Skeleton className="h-4 w-36" />
          <Skeleton className="h-3 w-52 max-w-full" />
        </div>
        <div className="space-y-3 p-4">
          {Array.from({ length: 5 }).map((_, i) => (
            <Skeleton key={i} className="h-10 w-full rounded-xl" />
          ))}
        </div>
      </div>
    </div>
  )
}
