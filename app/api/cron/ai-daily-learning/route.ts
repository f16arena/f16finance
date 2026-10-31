/**
 * Cron: ежедневное обучение ИИ на потоке данных (lib/server/ai-learning.ts).
 *
 * Для каждой организации с точками: пересчитать нормы за 8 недель и дать ИИ
 * разобрать вчерашний день — подтвердить, опровергнуть или добавить
 * закономерности. Память потом видят все ИИ-разборы организации.
 *
 * Расписание — 03:30 UTC (08:30 по Казахстану): ночная смена закрыта и внесена,
 * а утренняя ИИ-сводка (04:00 UTC) уже видит свежую память. Идемпотентно:
 * выученный день повторно не учится.
 *
 * Запуск: GET /api/cron/ai-daily-learning с Authorization: Bearer ${CRON_SECRET}
 * ?day=YYYY-MM-DD — выучить конкретный день (догнать пропуск).
 */

import { NextResponse } from 'next/server'

import { describeError, writeSystemErrorLogSafe } from '@/lib/server/audit'
import { learnOrganizationDay } from '@/lib/server/ai-learning'
import { verifyCronRequest } from '@/lib/server/cron-auth'
import { kzTodayISO } from '@/lib/server/forecast-inputs'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

export const runtime = 'nodejs'
export const dynamic = 'force-dynamic'
export const maxDuration = 300

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: { 'Cache-Control': 'no-store' } })
}

export async function GET(request: Request) {
  if (!verifyCronRequest(request)) return json({ error: 'unauthorized' }, 401)
  if (!hasAdminSupabaseCredentials()) return json({ error: 'service_role_missing' }, 500)

  const supabase = createAdminSupabaseClient()
  const requested = new URL(request.url).searchParams.get('day')
  const today = kzTodayISO()
  const day = requested && /^\d{4}-\d{2}-\d{2}$/.test(requested) && requested < today
    ? requested
    : new Date(Date.parse(`${today}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10)

  const { data: orgs, error } = await supabase.from('organizations').select('id, name, status').in('status', ['active', 'trial'])
  if (error) return json({ error: error.message }, 500)

  const report: Array<Record<string, unknown>> = []
  // По одной организации: сбой одной не ломает остальные.
  for (const org of (orgs || []) as any[]) {
    try {
      const res = await learnOrganizationDay(supabase, String(org.id), day)
      report.push({ org: org.name, ...(res ? { facts: res.memory.facts.length, added: res.added, anomalies: res.anomalies, days: res.memory.daysLearned } : { skipped: true }) })
    } catch (e) {
      report.push({ org: org.name, error: describeError(e) })
      await writeSystemErrorLogSafe({ scope: 'server', area: 'cron:ai-daily-learning', message: `${org.name}: ${describeError(e)}` })
    }
  }
  return json({ ok: true, day, report })
}
