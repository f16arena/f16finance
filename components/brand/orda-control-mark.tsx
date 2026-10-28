import { useId } from 'react'

/**
 * Знак ORDA CONTROL — тот же, что на иконке приложений: белое кольцо и синий
 * сегмент на тёмно-синем фоне. Геометрия из `ic_launcher_foreground.xml`
 * (Android) и `OrdaControlMark.swift` (iOS): меняете здесь — меняйте и там.
 */
export function OrdaControlMark({ size = 36, radius = 0.26, className }: { size?: number; radius?: number; className?: string }) {
  const id = useId()
  return (
    <svg width={size} height={size} viewBox="18 18 72 72" className={className} aria-hidden>
      <defs>
        <linearGradient id={`bg-${id}`} x1="0" y1="18" x2="0" y2="90" gradientUnits="userSpaceOnUse">
          <stop stopColor="#173563" />
          <stop offset="1" stopColor="#0B1F3B" />
        </linearGradient>
      </defs>
      <rect x="18" y="18" width="72" height="72" rx={72 * radius} fill={`url(#bg-${id})`} />
      <path stroke="#FFFFFF" strokeWidth="9" fill="none" d="M54,36.6 a17.4,17.4 0 1,1 0,34.8 a17.4,17.4 0 1,1 0,-34.8" />
      <path stroke="#2563EB" strokeWidth="11.4" strokeLinecap="round" fill="none" d="M49.79,37.12 A17.4,17.4 0 0,1 70.88,49.79" />
    </svg>
  )
}
