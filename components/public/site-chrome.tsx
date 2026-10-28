import Link from 'next/link'
import { ArrowRight } from 'lucide-react'

import { OrdaControlMark } from '@/components/brand/orda-control-mark'
import { LEGAL_ENTITY, PRODUCT_NAME } from '@/lib/core/legal'
import { APP_STORE_URL, GOOGLE_PLAY_URL, OPERATOR_DOWNLOAD_URL } from '@/lib/public/app-links'

// Общие шапка и подвал публичных страниц: главная, помощь и юридические
// документы выглядят как один сайт ORDA CONTROL. Подвал несёт реквизиты
// ТОО «Turanix» — владельца продукта (из LEGAL_ENTITY, одно место правды).

const wrap = 'mx-auto w-full max-w-[1440px] px-5 sm:px-8 lg:px-12'
const navLink = 'hidden rounded-[11px] px-3 py-2 text-[14px] font-medium text-[#4b5563] transition hover:bg-[#f3f4f6] hover:text-[#0b1f3b] md:inline-flex'

/** Знак + wordmark ORDA / CONTROL. */
export function SiteLogo({ href = '/' }: { href?: string }) {
  return (
    <Link href={href} className="flex items-center gap-2.5">
      <OrdaControlMark size={36} className="shrink-0 drop-shadow-[0_6px_12px_rgba(11,31,59,0.35)]" />
      <div className="leading-none">
        <div className="font-display text-[19px] font-extrabold tracking-[-0.03em] text-[#0b1f3b]">ORDA</div>
        <div className="mt-[3px] text-[9.5px] font-semibold tracking-[0.42em] text-[#6b7280]">CONTROL</div>
      </div>
    </Link>
  )
}

/**
 * Шапка сайта. На главной ссылки ведут на якоря (`#apps`), на остальных
 * страницах — на якоря главной (`/#apps`).
 */
export function SiteHeader({ onHome = false }: { onHome?: boolean }) {
  const a = (hash: string) => (onHome ? hash : `/${hash}`)
  return (
    <header className="sticky top-0 z-50 border-b border-[#e5e7eb] bg-[#fafaf8]/85 backdrop-blur-xl">
      <div className={`${wrap} flex items-center justify-between py-3`}>
        <SiteLogo />
        <nav className="flex items-center gap-1">
          <Link href={a('#apps')} className={navLink}>Приложения</Link>
          <Link href={a('#how')} className={navLink}>Как работает</Link>
          <Link href={a('#features')} className={navLink}>Возможности</Link>
          <Link href={a('#pricing')} className={navLink}>Пакеты</Link>
          <Link href="/help" className={navLink}>Помощь</Link>
          <Link href="/login" className="rounded-[11px] px-3 py-2 text-[14px] font-medium text-[#4b5563] transition hover:bg-[#f3f4f6] hover:text-[#0b1f3b]">Войти</Link>
          <Link href={a('#contact')} className="inline-flex items-center rounded-[12px] bg-[#2563eb] px-5 py-2 text-[14px] font-semibold text-white transition hover:bg-[#1d4ed8]">
            Попробовать<ArrowRight className="ml-1.5 h-4 w-4" />
          </Link>
        </nav>
      </div>
    </header>
  )
}

function FooterCol({ title, links, external }: { title: string; links: Array<{ href: string; label: string }>; external?: boolean }) {
  return (
    <div>
      <div className="text-[12px] font-semibold uppercase tracking-[0.1em] text-[#6b7280]">{title}</div>
      <div className="mt-3.5 flex flex-col gap-2.5 text-[14px] font-medium text-[#4b5563]">
        {links.map((l) =>
          external ? (
            <a key={l.href} href={l.href} target="_blank" rel="noopener noreferrer" className="transition-colors hover:text-[#2563eb]">{l.label}</a>
          ) : (
            <Link key={l.href} href={l.href} className="transition-colors hover:text-[#2563eb]">{l.label}</Link>
          ),
        )}
      </div>
    </div>
  )
}

/** Подвал сайта: разделы, скачать, документы и реквизиты владельца продукта. */
export function SiteFooter({ onHome = false }: { onHome?: boolean }) {
  const a = (hash: string) => (onHome ? hash : `/${hash}`)
  const e = LEGAL_ENTITY
  return (
    <footer className="border-t border-[#e5e7eb] bg-[#f4f5f2]">
      <div className={`${wrap} py-12`}>
        <div className="flex flex-col gap-10 lg:flex-row lg:items-start lg:justify-between">
          <div className="max-w-[360px]">
            <SiteLogo />
            <p className="mt-4 text-[14px] leading-[1.55] text-[#4b5563]">Касса, склад, финансы и AI — на кассе, в браузере и в телефоне.</p>
          </div>
          <div className="flex flex-wrap gap-x-14 gap-y-8">
            <FooterCol title="Продукт" links={[
              { href: a('#apps'), label: 'Приложения' },
              { href: a('#features'), label: 'Возможности' },
              { href: a('#pricing'), label: 'Пакеты' },
              { href: '/club-management-system', label: 'Для клубов' },
              { href: '/login', label: 'Войти' },
            ]} />
            <FooterCol title="Скачать" external links={[
              { href: APP_STORE_URL, label: 'iPhone — App Store' },
              { href: GOOGLE_PLAY_URL, label: 'Android — Google Play' },
              { href: OPERATOR_DOWNLOAD_URL, label: 'Касса для Windows' },
            ]} />
            <FooterCol title="Помощь и документы" links={[
              { href: '/help', label: 'Инструкция' },
              { href: '/help/operator', label: 'Регламент оператора' },
              { href: '/offer', label: 'Публичная оферта' },
              { href: '/terms', label: 'Пользовательское соглашение' },
              { href: '/privacy', label: 'Политика конфиденциальности' },
              { href: '/sla', label: 'SLA' },
              { href: '/cookies', label: 'Cookies' },
            ]} />
          </div>
        </div>

        {/* Владелец продукта */}
        <div className="mt-10 grid gap-6 rounded-[20px] border border-[#e5e7eb] bg-white p-6 text-[13px] leading-[1.6] text-[#4b5563] sm:grid-cols-2 lg:grid-cols-[1.2fr_1fr_1fr]">
          <div>
            <div className="text-[12px] font-semibold uppercase tracking-[0.1em] text-[#6b7280]">Правообладатель</div>
            <div className="mt-2 font-semibold text-[#0b1f3b]">{PRODUCT_NAME} — продукт {e.shortName}</div>
            <div>БИН {e.bin}</div>
            <div>{e.address}</div>
          </div>
          <div>
            <div className="text-[12px] font-semibold uppercase tracking-[0.1em] text-[#6b7280]">Контакты</div>
            <div className="mt-2"><a href={`tel:${e.phone.replace(/\s/g, '')}`} className="hover:text-[#2563eb]">{e.phone}</a></div>
            <div><a href={`mailto:${e.emailInfo}`} className="hover:text-[#2563eb]">{e.emailInfo}</a></div>
            <div>Поддержка: <a href={`mailto:${e.emailSupport}`} className="hover:text-[#2563eb]">{e.emailSupport}</a></div>
          </div>
          <div>
            <div className="text-[12px] font-semibold uppercase tracking-[0.1em] text-[#6b7280]">Банковские реквизиты</div>
            <div className="mt-2">{e.bank}</div>
            <div>ИИК {e.iik}</div>
            <div>БИК {e.bik} · КБе {e.kbe}</div>
            <div>Директор: {e.director}</div>
          </div>
        </div>

        <div className="mt-8 flex flex-col gap-2 border-t border-[#e5e7eb] pt-6 text-[12px] text-[#6b7280] sm:flex-row sm:justify-between">
          <span>© 2026 {e.shortName}. Все права защищены.</span>
          <span>{PRODUCT_NAME} · {e.city}, {e.country}</span>
        </div>
      </div>
    </footer>
  )
}
