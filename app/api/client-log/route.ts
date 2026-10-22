import { NextResponse } from 'next/server'

import { writeAuditLog, writeSystemErrorLog } from '@/lib/server/audit'
import { createRequestSupabaseClient } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

type Body = {
  eventType?: 'page_view' | 'client_error'
  area?: string
  message?: string
  pathname?: string
  source?: string
  stack?: string
  userAgent?: string
  // Чем браузер мог помешать: переводчик страницы, язык документа (ошибки React из-за расширений)
  env?: Record<string, string | boolean | null>
}

export async function POST(req: Request) {
  try {
    const body = (await req.json().catch(() => null)) as Body | null
    if (!body?.eventType) {
      return NextResponse.json({ error: 'eventType обязателен' }, { status: 400 })
    }

    if (body.eventType === 'client_error' && !body.message) {
      return NextResponse.json({ error: 'message обязателен' }, { status: 400 })
    }

    const requestClient = createRequestSupabaseClient(req)
    const {
      data: { user },
    } = await requestClient.auth.getUser()

    const client = hasAdminSupabaseCredentials() ? createAdminSupabaseClient() : requestClient

    if (body.eventType === 'page_view') {
      await writeAuditLog(client, {
        actorUserId: user?.id || null,
        entityType: 'page-view',
        entityId: body.pathname || body.area || 'unknown-page',
        action: 'visit',
        payload: {
          pathname: body.pathname || null,
          source: body.source || 'client-navigation',
          user_agent: body.userAgent || null,
        },
      })

      return NextResponse.json({ ok: true })
    }

    await writeSystemErrorLog(client, {
      actorUserId: user?.id || null,
      scope: 'client',
      area: body.area || body.pathname || 'unknown-client-area',
      message: body.message || 'Unhandled client error',
      payload: {
        pathname: body.pathname || null,
        source: body.source || null,
        stack: body.stack || null,
        user_agent: body.userAgent || null,
        env: body.env && typeof body.env === 'object' ? Object.fromEntries(Object.entries(body.env).slice(0, 8).map(([k, v]) => [String(k).slice(0, 40), typeof v === 'string' ? v.slice(0, 120) : v])) : null,
      },
    })

    return NextResponse.json({ ok: true })
  } catch (error: any) {
    console.error('Client log route error', error)
    return NextResponse.json({ error: error?.message || 'Ошибка сервера' }, { status: 500 })
  }
}
