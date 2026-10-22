import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { issueStudioPass, STUDIO_ORIGIN } from '@/lib/server/studio-gate'

// Открыть Supabase Studio: только суперадмин. Выдаём пропуск на минуту и
// отправляем на studio.ordaops.kz, там он меняется на куку сессии.

export const dynamic = 'force-dynamic'

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return NextResponse.redirect(new URL('/login', req.url))
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  const path = new URL(req.url).searchParams.get('path') || '/project/default'
  const target = new URL('/__orda/login', STUDIO_ORIGIN)
  target.searchParams.set('t', issueStudioPass())
  if (path.startsWith('/') && !path.startsWith('//')) target.searchParams.set('next', path)
  return NextResponse.redirect(target)
}
