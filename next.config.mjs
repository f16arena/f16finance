/** @type {import('next').NextConfig} */
const nextConfig = {
  // Сборка для своего сервера (deploy/vps): самодостаточный server.js без node_modules целиком.
  // На Vercel переменная не задана — сборка как раньше.
  output: process.env.NEXT_OUTPUT_STANDALONE === '1' ? 'standalone' : undefined,
  generateBuildId: async () => process.env.GITHUB_SHA ?? null,
  experimental: {
    // View Transitions ОТКЛЮЧЕНЫ: их снапшот старой→новой страницы кросс-фейдил
    // и с асинхронной загрузкой данных (скелетон→данные) давал мельтешение на
    // каждой странице. Вместо них — простой opacity-фейд .orda-main-enter (globals.css).
    viewTransition: false,
  },
  images: {
    unoptimized: true,
  },
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'X-Content-Type-Options', value: 'nosniff' },
          { key: 'X-Frame-Options', value: 'SAMEORIGIN' },
          { key: 'X-XSS-Protection', value: '1; mode=block' },
          { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
          { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
          // Принудительный HTTPS на 2 года + поддомены (preload-ready).
          { key: 'Strict-Transport-Security', value: 'max-age=63072000; includeSubDomains; preload' },
        ],
      },
    ]
  },
}

export default nextConfig
