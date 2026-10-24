/**
 * Cron: поздравление команды с праздниками.
 *
 * Каждый день проверяет — не сегодня ли праздник у организации. Список, тексты и
 * свои даты — в её настройках (lib/core/holidays.ts, /settings → «Праздники»);
 * по умолчанию казахстанские праздники с прежними текстами. Поздравление уходит
 * активным операторам с telegram_chat_id, владельцу — счётчик.
 *
 * Расписание: каждое утро 9:00 Алматы.
 */

import { NextResponse } from 'next/server'
import { requiredEnv } from '@/lib/server/env'
import { listOrgReportTargets } from '@/lib/server/report-targets'
import { createAdminSupabaseClient } from '@/lib/server/supabase'
import { sendTelegramMessage } from '@/lib/telegram/send'
import { holidaysOn } from '@/lib/core/holidays'
import { getOrgSettings } from '@/lib/server/org-settings'

export const runtime = 'nodejs'

export async function GET(request: Request) {
  const auth = request.headers.get('authorization') || ''
  const cronSecret = requiredEnv('CRON_SECRET')
  if (auth !== `Bearer ${cronSecret}`) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  // Сегодня по Казахстану: сервер в UTC, а крон утренний.
  const today = new Date(Date.now() + 5 * 3600_000)
  const todayMonth = today.getUTCMonth() + 1
  const todayDay = today.getUTCDate()

  const supabase = createAdminSupabaseClient()
  const { data: operators, error } = await supabase
    .from('operators')
    .select('id, name, short_name, telegram_chat_id, is_active, organization_id, operator_profiles(full_name)')
    .eq('is_active', true)
    .not('telegram_chat_id', 'is', null)

  if (error) return NextResponse.json({ error: error.message }, { status: 500 })

  // Поздравляем только команды организаций с настроенным Telegram: остальным владелец
  // рассылку не включал (раньше бот писал операторам всех арендаторов).
  const orgTargets = await listOrgReportTargets()
  const orgChatMap = new Map(orgTargets.map((t) => [t.organizationId, t.chatId]))
  // Праздники сегодня — у каждой организации свои.
  const holidaysByOrg = new Map<string, ReturnType<typeof holidaysOn>>()
  for (const t of orgTargets) {
    if (!t.organizationId) continue
    const list = holidaysOn((await getOrgSettings(t.organizationId)).holidays, todayMonth, todayDay)
    if (list.length > 0) holidaysByOrg.set(t.organizationId, list)
  }
  if (holidaysByOrg.size === 0) return NextResponse.json({ ok: true, holiday: null })

  const sent: { name: string }[] = []
  const sentByOrg = new Map<string | null, number>() // orgId → сколько поздравлено
  for (const op of (operators as any[]) || []) {
    if (!op.organization_id || !orgChatMap.has(op.organization_id)) continue
    const holiday = holidaysByOrg.get(op.organization_id)?.[0]
    if (!holiday) continue
    const profile = Array.isArray(op.operator_profiles) ? op.operator_profiles[0] : op.operator_profiles
    const displayName = profile?.full_name || op.short_name || op.name || 'друг'
    const chat = String(op.telegram_chat_id || '')
    if (!chat) continue

    try {
      await sendTelegramMessage(chat, holiday.greeting.replace('{name}', displayName), { parseMode: 'HTML' })
      sent.push({ name: displayName })
      const orgId = op.organization_id || null
      sentByOrg.set(orgId, (sentByOrg.get(orgId) || 0) + 1)
    } catch (e: any) {
      console.error(`[holiday-greetings] failed for ${displayName}:`, e?.message)
    }
  }

  // Уведомляем владельца: каждой организации — счётчик по её команде в её чат.
  for (const t of orgTargets) {
    const count = sentByOrg.get(t.organizationId) || 0
    const holiday = t.organizationId ? holidaysByOrg.get(t.organizationId)?.[0] : undefined
    if (count === 0 || !holiday) continue
    try {
      await sendTelegramMessage(
        t.chatId,
        `${holiday.emoji} Сегодня <b>${holiday.name}</b>. Команде разослано поздравление (${count} человек).`,
        { parseMode: 'HTML' },
      )
    } catch {}
  }

  return NextResponse.json({ ok: true, organizations: holidaysByOrg.size, sent: sent.length })
}
