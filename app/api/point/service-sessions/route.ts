import { NextResponse } from 'next/server'

import { writeSystemErrorLogSafe } from '@/lib/server/audit'
import { requirePointDevice } from '@/lib/server/point-devices'
import { loadActiveSessions } from '@/lib/server/store-services'

/**
 * Идущие таймеры услуг точки («Теннис · осталось 42 мин»).
 * Запускаются продажей варианта со временем (POST /api/point/inventory-sales),
 * здесь — только список и «Завершить».
 */

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

export async function GET(request: Request) {
  try {
    const point = await requirePointDevice(request)
    if ('response' in point) return point.response
    const { supabase, device } = point

    const sessions = await loadActiveSessions(supabase, device.company_id)
    return json({ ok: true, data: { sessions, server_time: new Date().toISOString() } })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'point-service-sessions:get', message: error?.message || 'Service sessions GET error' })
    return json({ error: 'Не удалось загрузить таймеры' }, 500)
  }
}

export async function POST(request: Request) {
  try {
    const point = await requirePointDevice(request)
    if ('response' in point) return point.response
    const { supabase, device } = point

    const body = (await request.json().catch(() => null)) as { action?: string; session_id?: string } | null
    if (body?.action !== 'finish') return json({ error: 'invalid-action' }, 400)
    const sessionId = String(body.session_id || '').trim()
    if (!sessionId) return json({ error: 'session-id-required' }, 400)

    const { data, error } = await supabase
      .from('store_service_sessions')
      .update({ finished_at: new Date().toISOString() })
      .eq('id', sessionId)
      .eq('company_id', device.company_id)
      .is('finished_at', null)
      .select('id')
      .maybeSingle()
    if (error) throw error
    if (!data) return json({ error: 'Таймер уже завершён' }, 404)
    return json({ ok: true })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'point-service-sessions:post', message: error?.message || 'Service sessions POST error' })
    return json({ error: 'Не удалось завершить таймер' }, 500)
  }
}
