import { useEffect, useRef, useState } from 'react'

/**
 * Длинная сетка товаров порциями. На точке может быть 1000+ товаров, и сетка
 * из тысячи карточек с тенями и значками вешала слабые моноблоки. Показываем
 * первую порцию, следующую — когда до конца списка докрутили (сторожевой
 * элемент в конце сетки). Поиск и сканер по-прежнему ищут по всем товарам.
 */
export function useProgressiveList<T>(items: T[], resetKey: unknown, step = 120) {
  const [limit, setLimit] = useState(step)
  const sentinelRef = useRef<HTMLDivElement | null>(null)

  // Новая категория или поиск — снова с первой порции.
  useEffect(() => {
    setLimit(step)
  }, [resetKey, step])

  const hasMore = items.length > limit
  useEffect(() => {
    const el = sentinelRef.current
    if (!el || !hasMore) return
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) setLimit((n) => n + step)
      },
      { rootMargin: '600px 0px' },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [hasMore, step, limit])

  return { shown: hasMore ? items.slice(0, limit) : items, hasMore, sentinelRef }
}
