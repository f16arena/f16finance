'use client'

import { useEffect } from 'react'
import { usePathname } from 'next/navigation'

// Переводчик страницы (Google/Яндекс/Edge) переписывает текст в DOM — React потом
// падает с #418 и «пустыми» ошибками. Пишем признаки, чтобы такие ошибки отличать от наших.
function browserEnv() {
  if (typeof document === 'undefined') return null
  const html = document.documentElement
  return {
    translated: /translated-(ltr|rtl)/.test(html.className) || !!document.querySelector('font[style*="vertical-align"], [_msttexthash], .goog-te-banner-frame'),
    lang: html.lang || null,
    nav_lang: typeof navigator !== 'undefined' ? navigator.language : null,
    online: typeof navigator !== 'undefined' ? navigator.onLine : null,
    visible: document.visibilityState,
  }
}

export function ClientErrorReporter() {
  const pathname = usePathname()

  useEffect(() => {
    fetch('/api/client-log', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        eventType: 'page_view',
        area: pathname,
        pathname,
        source: 'client-navigation',
        userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : null,
      }),
    }).catch(() => null)

    const report = (payload: { area: string; message: string; source: string; stack?: string | null }) => {
      fetch('/api/client-log', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          eventType: 'client_error',
          ...payload,
          pathname,
          userAgent: typeof navigator !== 'undefined' ? navigator.userAgent : null,
          env: browserEnv(),
        }),
      }).catch(() => null)
    }

    // Бросают не только Error: без описания такого значения в журнал падало пустое «Uncaught ».
    const describe = (value: unknown) => {
      if (value instanceof Error) return `${value.name}: ${value.message}`
      if (value === undefined || value === null) return String(value)
      try { return `${Object.prototype.toString.call(value).slice(8, -1)}: ${JSON.stringify(value).slice(0, 300)}` } catch { return String(value) }
    }

    const onError = (event: ErrorEvent) => {
      const bare = !event.message || /^Uncaught\s*$/.test(event.message)
      report({
        area: pathname || 'window',
        message: bare ? `Uncaught ${describe(event.error)}` : event.message,
        source: event.filename ? `${event.filename}:${event.lineno}:${event.colno}` : 'window.error',
        stack: event.error?.stack || null,
      })
    }

    const onRejection = (event: PromiseRejectionEvent) => {
      const reason = event.reason
      report({
        area: pathname || 'window',
        message: reason?.message || `Unhandled rejection ${describe(reason)}`,
        source: 'unhandledrejection',
        stack: reason?.stack || null,
      })
    }

    window.addEventListener('error', onError)
    window.addEventListener('unhandledrejection', onRejection)

    return () => {
      window.removeEventListener('error', onError)
      window.removeEventListener('unhandledrejection', onRejection)
    }
  }, [pathname])

  return null
}
