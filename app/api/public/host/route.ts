import { NextResponse } from 'next/server'

import { resolveHostNiche } from '@/lib/server/tenant-hosts'

/**
 * Чей это адрес: название организации и её ниша. Открыто без входа — нужно
 * экранам «забыли пароль» и «задать пароль», чтобы человек видел свою
 * программу, а не общую Orda. Отдаёт только то, что и так видно на странице входа.
 */
export async function GET(request: Request) {
  const found = await resolveHostNiche(request.headers.get('host')).catch(() => null)
  return NextResponse.json(
    { niche: found?.niche || null, name: found?.niche ? found.org.name : null },
    { headers: { 'Cache-Control': 'public, max-age=60' } },
  )
}
