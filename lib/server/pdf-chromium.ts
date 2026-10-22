// Флаги запуска Chromium для PDF. Набор @sparticuz/chromium (--single-process, --no-zygote и т.п.)
// рассчитан на его урезанную сборку под Vercel — системный Chromium с ним падает на старте.
// На своём сервере (CHROMIUM_PATH) — обычный headless в контейнере: без песочницы
// (контейнер и так изолирован) и без /dev/shm (в Docker он всего 64 МБ).
const SYSTEM_CHROMIUM_ARGS = ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage']

export function pdfChromiumArgs(sparticuzArgs: string[]): string[] {
  return process.env.CHROMIUM_PATH ? SYSTEM_CHROMIUM_ARGS : sparticuzArgs
}
