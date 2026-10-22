import 'server-only'

import { createHmac, timingSafeEqual } from 'node:crypto'

// Вход в Supabase Studio по сессии суперадмина Orda, без отдельного пароля.
// studio.ordaops.kz в Caddy: forward_auth → /api/studio-gate/check, потом прокси в
// envoy с паролем Studio, который Caddy подставляет сам (deploy/vps/orda.caddy).
// /platform → /api/admin/platform/studio/open (проверка суперадмина) → одноразовый
// пропуск на минуту → studio.ordaops.kz/__orda/login ставит куку на 12 часов.

export const STUDIO_ORIGIN = 'https://studio.ordaops.kz'
export const STUDIO_COOKIE = 'orda_studio'
export const STUDIO_SESSION_SECONDS = 12 * 3600

function secret() {
  const s = process.env.STUDIO_GATE_SECRET || ''
  if (s.length < 32) throw new Error('STUDIO_GATE_SECRET не задан')
  return s
}

const sign = (purpose: string, payload: string) => createHmac('sha256', secret()).update(`${purpose}:${payload}`).digest('hex')

function verify(purpose: string, value: string | null | undefined) {
  if (!value) return false
  const dot = value.lastIndexOf('.')
  if (dot < 1) return false
  const payload = value.slice(0, dot)
  const exp = Number(payload.split('.')[0])
  if (!Number.isFinite(exp) || exp < Date.now() / 1000) return false
  const expected = Buffer.from(sign(purpose, payload))
  const got = Buffer.from(value.slice(dot + 1))
  return expected.length === got.length && timingSafeEqual(expected, got)
}

function issue(purpose: string, ttlSeconds: number) {
  const payload = `${Math.floor(Date.now() / 1000) + ttlSeconds}.${Math.random().toString(36).slice(2, 10)}`
  return `${payload}.${sign(purpose, payload)}`
}

/** Пропуск из /platform в Studio: живёт минуту, обменивается на куку. */
export const issueStudioPass = () => issue('pass', 60)
export const verifyStudioPass = (v: string | null | undefined) => verify('pass', v)
export const issueStudioSession = () => issue('session', STUDIO_SESSION_SECONDS)
export const verifyStudioSession = (v: string | null | undefined) => verify('session', v)
