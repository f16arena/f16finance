import { NextResponse } from 'next/server'

import { writeSystemErrorLogSafe } from '@/lib/server/audit'
import { requireOperator } from '@/lib/server/operator-context'
import { loadActiveSessions } from '@/lib/server/store-services'

/**
 * Идущие таймеры услуг точки для мобильной кассы (Bearer-оператор).
 * Зеркало /api/point/service-sessions: список и «Завершить», тот же скоуп по company_id.
 */

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

export async function GET(request: Request) {
  try {
    const ctx = await requireOperator(request)
    if ('response' in ctx) return ctx.response
    const { supabase, companyId } = ctx

    const sessions = await loadActiveSessions(supabase, companyId)
    return json({ ok: true, data: { sessions, server_time: new Date().toISOString() } })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'operator-service-sessions:get', message: error?.message || 'Service sessions GET error' })
    return json({ error: 'Не удалось загрузить таймеры' }, 500)
  }
}

export async function POST(request: Request) {
  try {
    const ctx = await requireOperator(request)
    if ('response' in ctx) return ctx.response
    const { supabase, companyId } = ctx

    const body = (await request.json().catch(() => null)) as { action?: string; session_id?: string } | null
    if (body?.action !== 'finish') return json({ error: 'invalid-action' }, 400)
    const sessionId = String(body.session_id || '').trim()
    if (!sessionId) return json({ error: 'session-id-required' }, 400)

    const { data, error } = await supabase
      .from('store_service_sessions')
      .update({ finished_at: new Date().toISOString() })
      .eq('id', sessionId)
      .eq('company_id', companyId)
      .is('finished_at', null)
      .select('id')
      .maybeSingle()
    if (error) throw error
    if (!data) return json({ error: 'Таймер уже завершён' }, 404)
    return json({ ok: true })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'operator-service-sessions:post', message: error?.message || 'Service sessions POST error' })
    return json({ error: 'Не удалось завершить таймер' }, 500)
  }
}
