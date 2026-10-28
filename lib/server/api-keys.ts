import 'server-only'

import { createHash, randomBytes } from 'node:crypto'
import { NextResponse } from 'next/server'

import { createAdminSupabaseClient } from '@/lib/server/supabase'

// Открытый API (/api/v1) авторизуется ключом организации, а не сессией.
// Ключ выдаёт владелец в /settings/api: одна точка + список прав. В базе
// лежит только sha256 ключа, сам ключ показывается один раз.

export const API_SCOPES = ['schedule:read', 'schedule:write', 'schedule:publish'] as const
export type ApiScope = (typeof API_SCOPES)[number]

export const API_SCOPE_LABELS: Record<ApiScope, string> = {
  'schedule:read': 'График: смотреть',
  'schedule:write': 'График: ставить и убирать смены',
  'schedule:publish': 'График: публиковать неделю (Telegram сотрудникам)',
}

export type ApiKeyContext = {
  id: string
  name: string
  organizationId: string
  companyId: string
  scopes: ApiScope[]
}

const KEY_PREFIX = 'orda_'
const RATE_LIMIT_PER_MINUTE = 60

export function hashApiKey(key: string) {
  return createHash('sha256').update(key).digest('hex')
}

export function generateApiKey() {
  const key = `${KEY_PREFIX}${randomBytes(24).toString('base64url')}`
  return { key, prefix: key.slice(0, 12), hash: hashApiKey(key) }
}

export function normalizeScopes(value: unknown): ApiScope[] {
  if (!Array.isArray(value)) return []
  return API_SCOPES.filter((scope) => value.includes(scope))
}

// Внешние программы могут звать API и из браузера: ключ идёт заголовком,
// куки не участвуют, поэтому открытый CORS безопасен.
const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, PUT, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  'Cache-Control': 'no-store',
}

export function v1Json(data: unknown, status = 200) {
  return NextResponse.json(data, { status, headers: CORS_HEADERS })
}

export function v1Error(status: number, code: string, message: string) {
  return v1Json({ error: { code, message } }, status)
}

export function v1Options() {
  return new NextResponse(null, { status: 204, headers: CORS_HEADERS })
}

// Окно в минуту на ключ. Процесс один (cloud-001), поэтому памяти процесса
// достаточно; после рестарта счётчики просто обнуляются.
const rateWindows = new Map<string, { startedAt: number; count: number }>()

function hitRateLimit(keyId: string) {
  const now = Date.now()
  const window = rateWindows.get(keyId)
  if (!window || now - window.startedAt >= 60_000) {
    rateWindows.set(keyId, { startedAt: now, count: 1 })
    return false
  }
  window.count += 1
  return window.count > RATE_LIMIT_PER_MINUTE
}

/**
 * Проверяет `Authorization: Bearer orda_…` и право `scope`.
 * Возвращает контекст ключа или готовый ответ с ошибкой.
 */
export async function requireApiKey(
  request: Request,
  scope: ApiScope,
): Promise<{ key: ApiKeyContext } | { response: Response }> {
  const header = request.headers.get('authorization') || ''
  const match = header.match(/^Bearer\s+(\S+)$/i)
  const raw = match?.[1] || ''
  if (!raw.startsWith(KEY_PREFIX)) {
    return { response: v1Error(401, 'unauthorized', 'Нужен заголовок Authorization: Bearer <ключ>') }
  }

  const supabase = createAdminSupabaseClient()
  const { data, error } = await supabase
    .from('api_keys')
    .select('id, name, organization_id, company_id, scopes, revoked_at, last_used_at')
    .eq('key_hash', hashApiKey(raw))
    .maybeSingle()

  if (error) return { response: v1Error(500, 'internal', 'Не удалось проверить ключ') }
  if (!data || data.revoked_at) {
    return { response: v1Error(401, 'unauthorized', 'Ключ неверный или отозван') }
  }

  // Ключ живёт, пока жива точка: закрытая точка (archived_at) — ключ молчит.
  const { data: company } = await supabase
    .from('companies')
    .select('id, organization_id, archived_at')
    .eq('id', data.company_id)
    .maybeSingle()
  if (!company || company.archived_at || String(company.organization_id) !== String(data.organization_id)) {
    return { response: v1Error(403, 'point_unavailable', 'Точка ключа закрыта или недоступна') }
  }

  const scopes = normalizeScopes(data.scopes)
  if (!scopes.includes(scope)) {
    return { response: v1Error(403, 'forbidden', `У ключа нет права «${scope}»`) }
  }

  if (hitRateLimit(String(data.id))) {
    return { response: v1Error(429, 'rate_limited', `Не больше ${RATE_LIMIT_PER_MINUTE} запросов в минуту`) }
  }

  // last_used_at обновляем не чаще раза в минуту — без записи на каждый запрос.
  const lastUsed = data.last_used_at ? Date.parse(data.last_used_at) : 0
  if (Date.now() - lastUsed > 60_000) {
    await supabase.from('api_keys').update({ last_used_at: new Date().toISOString() }).eq('id', data.id)
  }

  return {
    key: {
      id: String(data.id),
      name: String(data.name),
      organizationId: String(data.organization_id),
      companyId: String(data.company_id),
      scopes,
    },
  }
}
