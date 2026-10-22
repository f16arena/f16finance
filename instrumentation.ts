// Старт сервера Next: сэмплер ресурсов хоста для /platform/status пишет историю
// с первой минуты после деплоя, а не с первого открытия страницы.
export async function register() {
  if (process.env.NEXT_RUNTIME !== 'nodejs') return
  if (!process.env.CHROMIUM_PATH) return // только на своём сервере (переменная есть лишь в контейнере cloud-001)
  const { ensureHostSampler } = await import('./lib/server/host-status')
  ensureHostSampler()
}
