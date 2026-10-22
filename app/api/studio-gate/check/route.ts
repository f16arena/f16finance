import { NextResponse } from 'next/server'

import { STUDIO_COOKIE, verifyStudioSession } from '@/lib/server/studio-gate'

// forward_auth для studio.ordaops.kz: 200 — пускаем, иначе отправляем на вход
// через /platform (там проверяется, что это суперадмин).

export const dynamic = 'force-dynamic'

export async function GET(req: Request) {
  const cookie = req.headers.get('cookie') || ''
  const value = cookie.split(';').map((p) => p.trim()).find((p) => p.startsWith(STUDIO_COOKIE + '='))?.slice(STUDIO_COOKIE.length + 1)
  if (verifyStudioSession(value)) return new NextResponse(null, { status: 200 })
  const uri = req.headers.get('x-forwarded-uri') || '/project/default'
  const back = new URL('https://www.ordaops.kz/api/admin/platform/studio/open')
  if (uri.startsWith('/') && !uri.startsWith('/api/')) back.searchParams.set('path', uri)
  return NextResponse.redirect(back)
}
