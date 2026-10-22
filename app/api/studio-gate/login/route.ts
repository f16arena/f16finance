import { NextResponse } from 'next/server'

import { issueStudioSession, STUDIO_COOKIE, STUDIO_ORIGIN, STUDIO_SESSION_SECONDS, verifyStudioPass } from '@/lib/server/studio-gate'

// Вызывается только через Caddy с хоста studio.ordaops.kz (/__orda/login):
// меняет минутный пропуск на куку сессии этого хоста.

export const dynamic = 'force-dynamic'

export async function GET(req: Request) {
  const url = new URL(req.url)
  if (!verifyStudioPass(url.searchParams.get('t'))) {
    return NextResponse.redirect('https://www.ordaops.kz/platform/studio')
  }
  const next = url.searchParams.get('next') || '/project/default'
  const res = NextResponse.redirect(new URL(next.startsWith('/') && !next.startsWith('//') ? next : '/project/default', STUDIO_ORIGIN))
  res.cookies.set(STUDIO_COOKIE, issueStudioSession(), {
    httpOnly: true,
    secure: true,
    sameSite: 'lax',
    path: '/',
    maxAge: STUDIO_SESSION_SECONDS,
  })
  return res
}
