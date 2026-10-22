import 'server-only'

/**
 * Доступ к панели состояния сервера (status.commrent.kz, код — deploy/vps/status
 * в репозитории Commrent). У панели свой вход: POST /api/login → кука сессии на
 * несколько дней. Логин и пароль — STATUS_PANEL_* в /opt/orda/web.env; если пароль
 * сменят в панели, его надо поменять и там.
 */

let session: { cookie: string } | null = null
// Панель блокирует адрес после серии неверных паролей — не долбим её входом.
let lastLoginFailAt = 0
const RELOGIN_PAUSE_MS = 10 * 60 * 1000

function config() {
  const url = String(process.env.STATUS_PANEL_URL || '').replace(/\/$/, '')
  const user = String(process.env.STATUS_PANEL_USER || '')
  const password = String(process.env.STATUS_PANEL_PASSWORD || '')
  return url && user && password ? { url, user, password } : null
}

async function login(cfg: NonNullable<ReturnType<typeof config>>) {
  if (Date.now() - lastLoginFailAt < RELOGIN_PAUSE_MS) throw new Error('вход в панель не удался, повтор через 10 минут')
  const res = await fetch(`${cfg.url}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ login: cfg.user, password: cfg.password, remember: true }),
    cache: 'no-store',
    signal: AbortSignal.timeout(8000),
  })
  const cookie = (res.headers.get('set-cookie') || '').split(';')[0]
  if (!res.ok || !cookie) {
    lastLoginFailAt = Date.now()
    throw new Error(res.status === 401 ? 'панель не приняла пароль (STATUS_PANEL_PASSWORD)' : `вход в панель: ${res.status}`)
  }
  session = { cookie }
}

/** GET к панели с её сессией; при истёкшей сессии входит заново один раз. */
export async function statusPanelFetch(path: string, timeoutMs = 8000): Promise<Response> {
  const cfg = config()
  if (!cfg) throw new Error('не настроен доступ к панели (STATUS_PANEL_*)')
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
