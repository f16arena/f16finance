/**
 * Общая картинка для /icon, /apple-icon, PWA 192/512 (ImageResponse / next/og).
 * Знак ORDA CONTROL — как на иконке приложений (см. `components/brand/orda-control-mark.tsx`).
 */
export function OgAppBrandIcon({ sizePx }: { sizePx: number }) {
  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(180deg, #173563 0%, #0B1F3B 100%)',
      }}
    >
      <svg width={sizePx} height={sizePx} viewBox="18 18 72 72" fill="none">
        <path stroke="#FFFFFF" strokeWidth="9" d="M54,36.6 a17.4,17.4 0 1,1 0,34.8 a17.4,17.4 0 1,1 0,-34.8" />
        <path stroke="#2563EB" strokeWidth="11.4" strokeLinecap="round" d="M49.79,37.12 A17.4,17.4 0 0,1 70.88,49.79" />
      </svg>
    </div>
  )
}
