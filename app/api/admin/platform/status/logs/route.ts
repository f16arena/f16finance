import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { statusPanelFetch } from '@/lib/server/status-panel'

// Журналы Orda для страницы «Сервер» (только суперадмин). Пускаем только к
// контейнерам и журналам Orda — соседние проекты на сервере сюда не видны.

export const dynamic = 'force-dynamic'

const ORDA_LOGS = new Set(['backup', 'cron'])

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

  const url = new URL(req.url)
  const container = url.searchParams.get('container') || ''
  const log = url.searchParams.get('log') || ''
  const tail = Math.min(3000, Math.max(50, Number(url.searchParams.get('tail')) || 800))

  let query: string
  if (log) {
    if (!ORDA_LOGS.has(log)) return new NextResponse('Нет такого журнала', { status: 404 })
    query = `project=orda&log=${log}`
  } else {
    const state = await statusPanelFetch('/api/state').then((r) => r.json()).catch(() => null)
    const orda = (state?.projects || []).find((p: any) => p.id === 'orda')
    const allowed = new Set<string>((orda?.containers || []).map((c: any) => String(c.name)))
    if (!allowed.has(container)) return new NextResponse('Контейнер не относится к Orda', { status: 404 })
    query = `container=${encodeURIComponent(container)}`
  }

  let res: Response
  try {
    res = await statusPanelFetch(`/api/logs?${query}&tail=${tail}`, 10000)
  } catch (e: any) {
    return new NextResponse('Нет связи с панелью сервера: ' + (e?.message || e), { status: 502 })
  }
  return new NextResponse(await res.text(), {
    status: res.ok ? 200 : 502,
    headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' },
  })
}
