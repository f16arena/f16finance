/**
 * Утренняя сводка поломок владельцу платформы: ошибки сервера за сутки
 * (audit_log, entity_type 'system-error') и кроны, ответившие не 2xx
 * (/hostlogs/cron.log). Ничего не сломалось — ничего не шлём.
 *
 * Получатель — TELEGRAM_SUPERADMIN_CHAT_ID: это чат владельца платформы,
 * ошибки всех организаций туда можно.
 */

import { NextResponse } from 'next/server'

import { verifyCronRequest } from '@/lib/server/cron-auth'
import { HOST_LOGS, tailFile } from '@/lib/server/host-status'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'
import { sendTelegram } from '@/lib/server/telegram'

export const runtime = 'nodejs'

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status })
}

function escapeHtml(s: string) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

type Group = { area: string; count: number; message: string }

export async function GET(request: Request) {
  if (!verifyCronRequest(request)) return json({ error: 'unauthorized' }, 401)
  if (!hasAdminSupabaseCredentials()) return json({ error: 'no admin supabase' }, 503)

  const since = new Date(Date.now() - 24 * 3600 * 1000)
  const supabase = createAdminSupabaseClient()
  const { data, error } = await supabase
    .from('audit_log')
    .select('entity_id, payload, created_at')
    .eq('entity_type', 'system-error')
    .gte('created_at', since.toISOString())
    .order('created_at', { ascending: false })
    .limit(1000)
  if (error) return json({ error: error.message }, 500)

  const groups = new Map<string, Group>()
  for (const row of (data || []) as any[]) {
    const area = String(row.payload?.area || row.entity_id || 'без места')
    const group = groups.get(area)
    if (group) group.count += 1
    // Строки идут от новых к старым: первая — последняя ошибка этого места.
    else groups.set(area, { area, count: 1, message: String(row.payload?.message || '').slice(0, 160) })
  }
  const errors = [...groups.values()].sort((a, b) => b.count - a.count)
  const errorTotal = errors.reduce((sum, g) => sum + g.count, 0)

  // Строка лога кронов: «2026-10-23T04:00:01Z 500 http://127.0.0.1:3200/api/cron/x».
  const failedCrons = new Map<string, { count: number; code: string }>()
  for (const line of (await tailFile(HOST_LOGS.cron, 5000).catch(() => '')).split('\n')) {
    const [at, code, url] = line.trim().split(' ')
    if (!at || !code || !url || code.startsWith('2')) continue
    if (new Date(at) < since) continue
    const name = url.replace(/^https?:\/\/[^/]+\/api\/cron\//, '')
    const item = failedCrons.get(name)
    if (item) {
      item.count += 1
      item.code = code
    } else failedCrons.set(name, { count: 1, code })
  }

  if (errorTotal === 0 && failedCrons.size === 0) return json({ ok: true, sent: false })

  const chatId = process.env.TELEGRAM_SUPERADMIN_CHAT_ID
  const parts: string[] = ['<b>Поломки за сутки</b>']
  if (errorTotal > 0) {
    parts.push(
      `\nОшибок сервера: ${errorTotal}, мест: ${errors.length}\n` +
        errors
          .slice(0, 6)
          .map((g) => `• <b>${escapeHtml(g.area)}</b> ×${g.count}${g.message ? ` — ${escapeHtml(g.message)}` : ''}`)
          .join('\n') +
        (errors.length > 6 ? `\n…и ещё ${errors.length - 6} мест` : ''),
    )
  }
  if (failedCrons.size > 0) {
    parts.push(
      '\nКроны с ошибкой:\n' +
        [...failedCrons.entries()].map(([name, c]) => `• ${escapeHtml(name)} — код ${c.code}${c.count > 1 ? ` ×${c.count}` : ''}`).join('\n'),
    )
  }
  parts.push('\nПодробности: /platform/status и журнал ошибок.')
  if (chatId) await sendTelegram(parts.join('\n'), chatId)

  return json({ ok: true, sent: !!chatId, errors: errorTotal, places: errors.length, failedCrons: failedCrons.size })
}
