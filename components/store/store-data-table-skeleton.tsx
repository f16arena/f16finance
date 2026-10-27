import { Skeleton } from '@/components/ui/skeleton'

// Тот же вид, что у storeCard (store-ui — клиентский модуль, в серверный скелет константу не тянем).
const storeCard = 'rounded-2xl border border-border bg-white dark:bg-slate-900/60 shadow-sm'

type Props = {
  rows?: number
  columns?: number
  className?: string
}

/** Sticky-header table placeholder for store list pages (приёмка, ревизия, списания, движения). */
export function StoreDataTableSkeleton({ rows = 8, columns = 7, className }: Props) {
  return (
    <div className={className ?? `${storeCard} min-h-[240px] overflow-hidden`}>
      <div
        className="grid border-b border-border bg-slate-50 dark:bg-white/[0.03] px-4 py-2.5"
        style={{ gridTemplateColumns: `repeat(${columns}, minmax(0,1fr))` }}
      >
        {Array.from({ length: columns }).map((_, i) => (
          <Skeleton key={i} className="h-3 max-w-[4rem] opacity-50" />
        ))}
      </div>
      {Array.from({ length: rows }).map((_, ri) => (
        <div
          key={ri}
          className="grid items-center gap-2 border-b border-border/60 px-4 py-2.5"
          style={{ gridTemplateColumns: `repeat(${columns}, minmax(0,1fr))` }}
        >
          {Array.from({ length: columns }).map((_, ci) => (
            <Skeleton key={ci} className="h-4 w-full opacity-60" />
          ))}
        </div>
      ))}
    </div>
  )
}
