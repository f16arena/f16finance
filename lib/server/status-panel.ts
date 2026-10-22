import 'server-only'

/**
 * Доступ к панели состояния сервера (status.commrent.kz, код — deploy/vps/status
 * в репозитории Commrent). Логин и пароль — STATUS_PANEL_* в /opt/orda/web.env.
 */
export function statusPanelAuth() {
  const url = String(process.env.STATUS_PANEL_URL || '').replace(/\/$/, '')
  const user = String(process.env.STATUS_PANEL_USER || '')
  const password = String(process.env.STATUS_PANEL_PASSWORD || '')
  if (!url || !user || !password) return null
  return { url, header: 'Basic ' + Buffer.from(`${user}:${password}`).toString('base64') }
}
