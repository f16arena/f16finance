/**
 * Заголовки авторизации для внутреннего запроса к своему же API.
 *
 * PDF-роуты собирают данные, вызывая соседний роут (branch-report,
 * weekly-act). Раньше пробрасывались только cookies — это работает для
 * сайта, но приложение входит по Bearer-токену и выбирает организацию
 * заголовком `x-organization-id`: без них внутренний запрос уходил
 * анонимным, и выгрузка из приложения падала с 401.
 *
 * Host пробрасываем тоже: запрос идёт на внутренний адрес (internalApiUrl),
 * а организация тенанта определяется по хосту (<slug>.ordaops.kz).
 */
export function forwardAuthHeaders(req: Request): Record<string, string> {
  const headers: Record<string, string> = {}
  const host = req.headers.get('host')
  if (host) headers.host = host
  const cookie = req.headers.get('cookie')
  if (cookie) headers.cookie = cookie
  const authorization = req.headers.get('authorization')
  if (authorization) headers.authorization = authorization
  const organization = req.headers.get('x-organization-id')
  if (organization) headers['x-organization-id'] = organization
  return headers
}

/**
 * Адрес своего же API для запроса с сервера.
 *
 * Не `new URL(req.url).origin`: за Caddy Next собирает его из
 * x-forwarded-proto и внутреннего порта (`https://…:3000`), а в контейнере
 * на 3000 обычный http — fetch падал с «fetch failed». Ходим напрямую
 * в свой процесс, мимо прокси.
 */
export function internalApiUrl(path: string): URL {
  const port = process.env.PORT || '3000'
  return new URL(path, `http://127.0.0.1:${port}`)
}
