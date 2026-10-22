import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { containerLog, containers, HOST_LOGS, tailFile } from '@/lib/server/host-status'

// Журналы Orda для /platform/status (только суперадмин): сборка, кроны, бэкап и
// контейнеры Orda (хвосты кладёт status-snapshot.sh). Соседних проектов тут нет.

export const dynamic = 'force-dynamic'

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

  const url = new URL(req.url)
  const container = url.searchParams.get('container') || ''
  const log = url.searchParams.get('log') || ''
  const tail = Math.min(3000, Math.max(50, Number(url.searchParams.get('tail')) || 800))

  let text: string
  if (log) {
    if (!HOST_LOGS[log]) return new NextResponse('Нет такого журнала', { status: 404 })
    text = await tailFile(HOST_LOGS[log], tail)
  } else {
    const { list } = await containers()
    if (!list.some((c: { name: string }) => c.name === container)) return new NextResponse('Контейнер не относится к Orda', { status: 404 })
    text = (await containerLog(container)) || 'Журнала пока нет — снимок появится в течение 20 секунд'
  }
  return new NextResponse(text, { headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' } })
}
