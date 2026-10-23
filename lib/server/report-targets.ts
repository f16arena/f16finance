import 'server-only'

import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

export type ReportTarget = {
  /** Организация-получатель (null — env-фолбэк без TELEGRAM_OWNER_ORG_ID). */
  organizationId: string | null
  /** Telegram chat id, куда слать отчёт. */
  chatId: string
  /**
   * company_id этой организации для скоупа данных отчёта.
   * null = не скоупить (env-фолбэк single-tenant без указанной орг) → все данные.
   */
  companyIds: string[] | null
  /** 'org' — настроено в БД (per-org); 'env' — фолбэк из переменных окружения. */
  source: 'org' | 'env'
}

/** Только per-org цели из БД (с заданным telegram_owner_chat_id). Пусто → cron должен
 *  использовать своё прежнее env-поведение (чтобы у F16 отчёты не переехали в другой чат). */
export async function listOrgReportTargets(): Promise<ReportTarget[]> {
  return (await listReportTargets()).filter((t) => t.source === 'org')
}

/**
 * Кому слать cron/бот-отчёты с изоляцией по арендаторам.
 *
 * 1) Каждая организация с заданным `telegram_owner_chat_id` → свой чат + свои company_id.
 * 2) Env-фолбэк: `TELEGRAM_OWNER_CHAT_ID` + `TELEGRAM_OWNER_ORG_ID`, если эта орг ещё не
 *    покрыта пунктом 1 (скоуп — её company_id).
 *
 * Использование в cron:
 *   for (const t of await listReportTargets()) {
 *     const data = await collect(t.companyIds)   // companyIds === null → без фильтра
 *     await sendTelegram(t.chatId, render(data))
 *   }
 */
export async function listReportTargets(): Promise<ReportTarget[]> {
  if (!hasAdminSupabaseCredentials()) return []
  const supabase = createAdminSupabaseClient()

  const targets: ReportTarget[] = []
  const coveredOrgs = new Set<string>()

  const { data: orgs } = await supabase
    .from('organizations')
    .select('id, telegram_owner_chat_id')
    .not('telegram_owner_chat_id', 'is', null)

  for (const o of (orgs || []) as any[]) {
    const chatId = String(o.telegram_owner_chat_id || '').trim()
    if (!chatId) continue
    const { data: comps } = await supabase.from('companies').select('id').eq('organization_id', o.id)
    targets.push({
      organizationId: String(o.id),
      chatId,
      companyIds: (comps || []).map((c: any) => String(c.id)),
      source: 'org',
    })
    coveredOrgs.add(String(o.id))
  }

  // Env-фолбэк — только с указанной TELEGRAM_OWNER_ORG_ID. Без неё раньше уходила сводка
  // по всем арендаторам сразу (companyIds = null); у F16 чат теперь в organizations.
  const envChat = (process.env.TELEGRAM_OWNER_CHAT_ID || '').trim()
  const envOrg = (process.env.TELEGRAM_OWNER_ORG_ID || '').trim()
  if (envChat && envOrg && !coveredOrgs.has(envOrg)) {
    const { data: comps } = await supabase.from('companies').select('id').eq('organization_id', envOrg)
    const companyIds = (comps || []).map((c: any) => String(c.id))
    targets.push({ organizationId: envOrg, chatId: envChat, companyIds, source: 'env' })
  }

  return targets
}

/** Чат владельца организации, которой принадлежит точка, и все точки этой организации.
 *  null — у организации чат не настроен (уведомление не шлём никуда). */
export async function getCompanyReportTarget(companyId: string | null | undefined): Promise<ReportTarget | null> {
  if (!companyId || !hasAdminSupabaseCredentials()) return null
  const supabase = createAdminSupabaseClient()
  const { data: company } = await supabase
    .from('companies')
    .select('organization_id')
    .eq('id', companyId)
    .maybeSingle()
  const orgId = String((company as any)?.organization_id || '')
  if (!orgId) return null
  const { data: org } = await supabase
    .from('organizations')
    .select('telegram_owner_chat_id')
    .eq('id', orgId)
    .maybeSingle()
  const chatId = String((org as any)?.telegram_owner_chat_id || '').trim()
  if (!chatId) return null
  const { data: comps } = await supabase.from('companies').select('id').eq('organization_id', orgId)
  return { organizationId: orgId, chatId, companyIds: (comps || []).map((c: any) => String(c.id)), source: 'org' }
}
