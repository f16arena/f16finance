import { ImageResponse } from 'next/og'

import { SITE_DESCRIPTION, SITE_NAME } from '@/lib/core/site'

export const size = {
  width: 1200,
  height: 630,
}

export const contentType = 'image/png'

export default function OgImage() {
  return new ImageResponse(
    (
      <div
        style={{
          width: '100%',
          height: '100%',
          display: 'flex',
          background:
            'radial-gradient(circle at 20% 10%, rgba(37,99,235,0.22), transparent 26%), radial-gradient(circle at 82% 80%, rgba(59,130,246,0.14), transparent 24%), linear-gradient(180deg, #0b1f3b 0%, #091a33 52%, #08172d 100%)',
          color: '#fff',
          padding: '56px',
          fontFamily: 'Arial, sans-serif',
        }}
      >
        <div
          style={{
            display: 'flex',
            width: '100%',
            borderRadius: '32px',
            border: '1px solid rgba(255,255,255,0.08)',
            background: 'linear-gradient(180deg, rgba(255,255,255,0.06), rgba(255,255,255,0.03))',
            boxShadow: '0 24px 70px rgba(0,0,0,0.34)',
            overflow: 'hidden',
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', flex: 1, padding: '56px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
              <div
                style={{
                  display: 'flex',
                  width: '70px',
                  height: '70px',
                  borderRadius: '20px',
                  background: 'linear-gradient(180deg, #173563 0%, #0b1f3b 100%)',
                  border: '1px solid rgba(255,255,255,0.15)',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <svg width="70" height="70" viewBox="18 18 72 72" fill="none">
                  <path stroke="#FFFFFF" strokeWidth="9" d="M54,36.6 a17.4,17.4 0 1,1 0,34.8 a17.4,17.4 0 1,1 0,-34.8" />
                  <path stroke="#2563EB" strokeWidth="11.4" strokeLinecap="round" d="M49.79,37.12 A17.4,17.4 0 0,1 70.88,49.79" />
                </svg>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <div style={{ color: '#93c5fd', fontSize: 24, textTransform: 'uppercase', letterSpacing: '0.22em' }}>Orda Control</div>
                <div style={{ color: '#cbd5e1', fontSize: 18 }}>Собственная система для смен, точки и управленки</div>
              </div>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
              <div style={{ fontSize: 64, lineHeight: 1.02, fontWeight: 700, maxWidth: '720px' }}>
                Смены, Telegram, зарплата и ОПиУ в одной системе
              </div>
              <div style={{ fontSize: 28, lineHeight: 1.45, color: '#cbd5e1', maxWidth: '760px' }}>{SITE_DESCRIPTION}</div>
            </div>

            <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap' }}>
              {['Point-программа', 'Telegram-отчеты', 'Калькулятор смен', 'ОПиУ и EBITDA'].map((item) => (
                <div
                  key={item}
                  style={{
                    display: 'flex',
                    padding: '12px 18px',
                    borderRadius: '999px',
                    border: '1px solid rgba(251,191,36,0.2)',
                    background: 'rgba(37,99,235,0.14)',
                    color: '#93c5fd',
                    fontSize: 20,
                  }}
                >
                  {item}
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    ),
    size,
  )
}
