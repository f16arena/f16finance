import { useEffect, useState } from 'react'
import { CloudOff, RefreshCw } from 'lucide-react'

import { getQueueCounts, openQueueScreen } from '@/lib/offline'

/**
 * Связь и очередь — на каждом экране (рядом с вкладками), а не только на
 * продажах. Пока всё хорошо — молчит. Нет интернета или в очереди лежат
 * продажи — показывает, по клику открывает очередь.
 */
export default function ConnectionBadge() {
  const [online, setOnline] = useState(() => (typeof navigator === 'undefined' ? true : navigator.onLine))
  const [pending, setPending] = useState(0)
  const [attention, setAttention] = useState(0)

  useEffect(() => {
    let cancelled = false
    const refresh = async () => {
      try {
        const counts = await getQueueCounts()
        if (!cancelled) {
          setPending(Number((counts as any)?.pending || 0))
          setAttention(Number((counts as any)?.attention || 0))
        }
      } catch {
        /* нет очереди — нечего показывать */
      }
    }
    const goOnline = () => setOnline(true)
    const goOffline = () => setOnline(false)
    window.addEventListener('online', goOnline)
    window.addEventListener('offline', goOffline)
    void refresh()
    const timer = setInterval(refresh, 10_000)
    return () => {
      cancelled = true
      clearInterval(timer)
      window.removeEventListener('online', goOnline)
      window.removeEventListener('offline', goOffline)
    }
  }, [])

  if (online && pending === 0 && attention === 0) return null

  const tone = !online || attention > 0 ? 'border-rose-500/40 bg-rose-500/15 text-rose-200' : 'border-amber-500/40 bg-amber-500/15 text-amber-100'
  const text = !online
    ? `Нет интернета${pending ? ` · в очереди ${pending}` : ''} — продажи сохраняются`
    : attention > 0
      ? `Требует внимания: ${attention}`
      : `Отправляется: ${pending}`

  return (
    <button
      type="button"
      onClick={openQueueScreen}
      className={`no-drag inline-flex h-11 items-center gap-2 rounded-xl border px-3 text-[14px] font-medium ${tone}`}
      title="Открыть очередь отправки"
    >
      {online ? <RefreshCw className="h-4 w-4 animate-spin [animation-duration:3s]" /> : <CloudOff className="h-4 w-4" />}
      {text}
    </button>
  )
}
