import 'server-only'

import { createSign } from 'node:crypto'

/**
 * Push на нативное Android-приложение через Firebase Cloud Messaging (HTTP v1).
 *
 * Ключ сервисного аккаунта — в `FIREBASE_SERVICE_ACCOUNT_B64` (JSON в base64,
 * лежит в /opt/orda/web.env). Шлём data-сообщения, а не notification: тогда
 * уведомление рисует само приложение и может добавить «Ответить» прямо в шторке.
 */

type ServiceAccount = { project_id: string; client_email: string; private_key: string; token_uri?: string }

export type FcmPayload = {
  title: string
  body: string
  data?: Record<string, unknown>
  collapseId?: string
}

export type FcmResult = { sent: number; invalidTokens: string[]; reasons: string[]; configError?: string }

/** FCM-токен: длинная строка с двоеточием (`<instance>:APA91b…`). Expo и APNs выглядят иначе. */
export function isFcmToken(token: string): boolean {
  const t = token.trim()
  return t.length >= 100 && t.includes(':') && !/^Expo(nent)?PushToken/.test(t) && !/^[0-9a-fA-F]+$/.test(t)
}

let cachedAccount: ServiceAccount | null | undefined
function serviceAccount(): ServiceAccount | null {
  if (cachedAccount !== undefined) return cachedAccount
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT_B64
  try {
    cachedAccount = raw ? (JSON.parse(Buffer.from(raw, 'base64').toString('utf8')) as ServiceAccount) : null
  } catch {
    cachedAccount = null
  }
  return cachedAccount
}

let cachedToken: { value: string; expiresAt: number } | null = null

/** OAuth-токен Google по ключу сервисного аккаунта: JWT RS256 → access_token, живёт час. */
async function accessToken(account: ServiceAccount): Promise<string> {
  if (cachedToken && cachedToken.expiresAt - Date.now() > 60_000) return cachedToken.value
  const now = Math.floor(Date.now() / 1000)
  const tokenUri = account.token_uri || 'https://oauth2.googleapis.com/token'
  const b64url = (v: string | Buffer) => Buffer.from(v).toString('base64url')
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
  const claims = b64url(
    JSON.stringify({
      iss: account.client_email,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: tokenUri,
      iat: now,
      exp: now + 3600,
    }),
  )
  const signer = createSign('RSA-SHA256')
  signer.update(`${header}.${claims}`)
  const jwt = `${header}.${claims}.${b64url(signer.sign(account.private_key))}`

  const res = await fetch(tokenUri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: jwt }),
  })
  const json = (await res.json().catch(() => ({}))) as { access_token?: string; expires_in?: number; error?: string }
  if (!res.ok || !json.access_token) throw new Error(`google oauth: ${json.error || res.status}`)
  cachedToken = { value: json.access_token, expiresAt: Date.now() + (json.expires_in || 3600) * 1000 }
  return json.access_token
}

/** В data FCM принимает только строки — вложенное кладём JSON-строкой. */
function stringifyData(data: Record<string, unknown> | undefined): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(data || {})) {
    if (v === undefined || v === null) continue
    out[k] = typeof v === 'string' ? v : JSON.stringify(v)
  }
  return out
}

export async function sendFcmPush(tokens: string[], payload: FcmPayload): Promise<FcmResult> {
  const valid = Array.from(new Set(tokens.filter((t) => t && isFcmToken(t))))
  if (valid.length === 0) return { sent: 0, invalidTokens: [], reasons: [] }
  const account = serviceAccount()
  if (!account) return { sent: 0, invalidTokens: [], reasons: [], configError: 'FIREBASE_SERVICE_ACCOUNT_B64 не задан' }

  let token: string
  try {
    token = await accessToken(account)
  } catch (error: any) {
    return { sent: 0, invalidTokens: [], reasons: [], configError: error?.message || 'google oauth' }
  }

  const data = { ...stringifyData(payload.data), title: payload.title, body: payload.body }
  let sent = 0
  const invalidTokens: string[] = []
  const reasons: string[] = []
  // HTTP v1 шлёт по одному адресу; устройств у организации десятки — параллельно.
  await Promise.all(
    valid.map(async (to) => {
      try {
        const res = await fetch(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({
            message: {
              token: to,
              data,
              android: { priority: 'HIGH', ...(payload.collapseId ? { collapse_key: payload.collapseId } : {}) },
            },
          }),
        })
        if (res.ok) {
          sent++
          return
        }
        const err = (await res.json().catch(() => ({}))) as { error?: { status?: string; message?: string } }
        const status = err.error?.status || String(res.status)
        // Приложение удалили или токен сменился — адрес мёртв навсегда.
        if (res.status === 404 || status === 'NOT_FOUND' || status === 'UNREGISTERED') invalidTokens.push(to)
        reasons.push(status)
      } catch (error: any) {
        reasons.push(error?.message || 'fetch')
      }
    }),
  )
  return { sent, invalidTokens, reasons }
}
