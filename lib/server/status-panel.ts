import 'server-only'

import { readFile, writeFile } from 'node:fs/promises'

/**
 * Доступ к панели состояния сервера (status.commrent.kz, код — deploy/vps/status
 * в репозитории Commrent). Основной путь — ключ только для чтения STATUS_PANEL_TOKEN.
 * Запасной — вход паролем (POST /api/login → кука): STATUS_PANEL_USER/PASSWORD
 * в /opt/orda/web.env, ломается при смене пароля в панели.
 */

let session: { cookie: string } | null = null
// Панель блокирует адрес после серии неверных паролей. Паузу храним в /control,
// чтобы перезапуск контейнера (каждый деплой) не начинал попытки заново.
const RELOGIN_PAUSE_MS = 30 * 60 * 1000
const FAIL_FILE = '/control/status-panel-login-failed'
async function lastLoginFailAt() {
  try { return Number(await readFile(FAIL_FILE, 'utf-8')) || 0 } catch { return 0 }
}

function config() {
  const url = String(process.env.STATUS_PANEL_URL || '').replace(/\/$/, '')
  const user = String(process.env.STATUS_PANEL_USER || '')
  const password = String(process.env.STATUS_PANEL_PASSWORD || '')
  const token = String(process.env.STATUS_PANEL_TOKEN || '')
  return url && (token || (user && password)) ? { url, user, password, token } : null
}

async function login(cfg: NonNullable<ReturnType<typeof config>>) {
  if (Date.now() - (await lastLoginFailAt()) < RELOGIN_PAUSE_MS) throw new Error('панель не приняла пароль — нужен новый STATUS_PANEL_PASSWORD или ключ STATUS_PANEL_TOKEN')
  const res = await fetch(`${cfg.url}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ login: cfg.user, password: cfg.password, remember: true }),
    cache: 'no-store',
    signal: AbortSignal.timeout(8000),
  })
  const cookie = (res.headers.get('set-cookie') || '').split(';')[0]
  if (!res.ok || !cookie) {
    await writeFile(FAIL_FILE, String(Date.now())).catch(() => null)
    throw new Error(res.status === 401 ? 'панель не приняла пароль (STATUS_PANEL_PASSWORD)' : `вход в панель: ${res.status}`)
  }
  session = { cookie }
}

/** GET к панели с её сессией; при истёкшей сессии входит заново один раз. */
export async function statusPanelFetch(path: string, timeoutMs = 8000): Promise<Response> {
  const cfg = config()
  if (!cfg) throw new Error('не настроен доступ к панели (STATUS_PANEL_*)')
  if (cfg.token) {
    return fetch(`${cfg.url}${path}`, {
      headers: { authorization: `Bearer ${cfg.token}`, 'x-status-token': cfg.token },
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    })
  }
  if (!session) await login(cfg)
  const get = () => fetch(`${cfg.url}${path}`, { headers: { cookie: session!.cookie }, cache: 'no-store', signal: AbortSignal.timeout(timeoutMs) })
  let res = await get()
  if (res.status === 401) {
    session = null
    await login(cfg)
    res = await get()
  }
  return res
}
