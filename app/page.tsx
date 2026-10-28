import type { Metadata } from 'next'
import { Fragment } from 'react'
import Link from 'next/link'
import {
  AlertCircle,
  ArrowRight,
  BadgePercent,
  Bot,
  Boxes,
  Briefcase,
  Building2,
  Camera,
  Check,
  CheckCircle2,
  ChevronDown,
  CloudOff,
  Crown,
  Eye,
  FileCheck,
  HelpCircle,
  LineChart,
  MessageCircle,
  Monitor,
  Package,
  Scale,
  ScanLine,
  ShieldCheck,
  Smartphone,
  Sparkles,
  Tag,
  UserRound,
  Wallet,
} from 'lucide-react'

import { Button } from '@/components/ui/button'
import { OrdaControlMark } from '@/components/brand/orda-control-mark'
import { ContactLeadForm } from '@/components/public/contact-lead-form'
import { FloatingCta } from '@/components/public/floating-cta'
import { FaqStructuredData, WebsiteStructuredData } from '@/components/public/structured-data'
import { CountUp, HeroIn, Reveal, Stagger, StaggerItem } from '@/components/public/landing-motion'
import { CopilotDemo, FeatureMarquee, OfflineDemo, ScrollProgress, TelegramDemo } from '@/components/public/landing-demos'
import { AppsStage, StoreBadge } from '@/components/public/apps-showcase'
import { APP_STORE_URL, GOOGLE_PLAY_URL, OPERATOR_DOWNLOAD_URL } from '@/lib/public/app-links'

export const metadata: Metadata = {
  title: 'Orda Control — касса, склад, зарплаты и AI-управление бизнесом',
  description:
    'Система управления точкой продаж: касса для Windows с офлайн-режимом, приложения для iPhone и Android, склад, смены, зарплаты, Telegram-бот и AI-копилот. Для магазинов, кафе, компьютерных клубов и сервисов в Казахстане.',
}

const PRODUCT = 'Orda Control'

// ─────────── ДИЗАЙН-СИСТЕМА v3 ───────────
// Белый фон, navy #0b1f3b, зелёный #2563eb. Крупные секции, чередование светлых
// и тёмных полос; устройства (касса, iPhone, Android) — главный визуальный герой.

const container = 'mx-auto w-full max-w-[1440px] px-5 sm:px-8 lg:px-12'
const eyebrowClass =
  'inline-flex items-center gap-2 rounded-full border border-[#2563eb]/25 bg-[#2563eb]/[0.07] px-4 py-1.5 text-[12.5px] font-semibold uppercase tracking-[0.1em] text-[#1d4ed8]'
const eyebrowDark =
  'inline-flex items-center gap-2 rounded-full border border-[#60a5fa]/30 bg-[#60a5fa]/10 px-4 py-1.5 text-[12.5px] font-semibold uppercase tracking-[0.1em] text-[#93c5fd]'
const h2Class =
  'font-display text-balance text-[32px] font-extrabold leading-[1.05] tracking-[-0.03em] text-[#0b1f3b] sm:text-[42px] lg:text-[52px]'
const leadClass = 'text-pretty text-[17px] leading-[1.55] text-[#56657d] sm:text-[19px]'
const bentoCard =
  'relative flex h-full flex-col overflow-hidden rounded-[24px] border border-[#dbe3ee] bg-white p-6 shadow-[0_10px_30px_-18px_rgba(15,32,56,0.2)] transition duration-300 hover:-translate-y-1 hover:border-[#2563eb]/35 sm:p-7'
const bentoTitle = 'font-display text-[19px] font-bold leading-[1.25] tracking-[-0.01em] text-[#0b1f3b] sm:text-[21px]'
const bentoText = 'mt-3 text-[14px] leading-[1.55] text-[#56657d]'
const btnPrimary =
  'rounded-[14px] bg-gradient-to-br from-[#3b82f6] to-[#1d4ed8] px-8 py-[15px] text-[16.5px] font-semibold text-white shadow-[0_12px_28px_-8px_rgba(37,99,235,0.5)] transition hover:-translate-y-0.5 hover:shadow-[0_18px_36px_-8px_rgba(37,99,235,0.6)]'
// dark:-переопределения обязательны: у Button.outline есть dark:bg-input/30 и dark:text-foreground,
// из-за них при тёмной теме ОС кнопка на белом лендинге становилась невидимой.
const btnGhost =
  'rounded-[14px] border border-[#c8d1de] bg-white px-8 py-[15px] text-[16.5px] font-semibold text-[#0b1f3b] shadow-none transition hover:bg-white hover:border-[#2563eb]/40 hover:text-[#1d4ed8] dark:border-[#c8d1de] dark:bg-white dark:text-[#0b1f3b] dark:shadow-none dark:hover:bg-white dark:hover:text-[#1d4ed8]'
const navLink =
  'hidden rounded-[11px] text-[14px] font-medium text-[#56657d] hover:bg-[#f3f6fa] hover:text-[#0b1f3b] dark:hover:bg-[#f3f6fa] dark:hover:text-[#0b1f3b] md:inline-flex'

// ─────────────── ДАННЫЕ ───────────────

const marqueeFeatures = [
  'Чеки ККМ и ОФД', 'Печать ценников', 'Сканер штрихкодов', 'Промокоды и акции', 'Бонусные баллы',
  'План закупа', 'Приёмка по фото', 'Срок годности', 'ABC-анализ', 'Слепые ревизии',
  'График смен', 'Авторасчёт зарплат', 'AI-квизы для новичков', 'Задачи и чек-листы', 'Экран покупателя',
  'Налоги ИП и форма 910', 'Cash Flow', 'Оценка бизнеса', 'Финмодель новой точки', 'Журнал аудита',
]

const audiences = ['Магазины', 'Компьютерные клубы', 'Кафе и точки еды', 'Сервисные центры', 'Студии услуг', 'Склады и точки']

type ComparisonColumnKey = 'excel' | 'oneC' | 'poster' | 'orda'
const comparisonColumns: Array<{ key: ComparisonColumnKey; label: string; subtitle: string; highlight?: boolean }> = [
  { key: 'excel', label: 'Excel', subtitle: 'Таблицы' },
  { key: 'oneC', label: '1С', subtitle: 'Бухучёт' },
  { key: 'poster', label: 'Poster / Wipon', subtitle: 'POS' },
  { key: 'orda', label: 'Orda Control', subtitle: 'Управление', highlight: true },
]
const comparisonRows: Array<{ criterion: string; values: Record<ComparisonColumnKey, string> }> = [
  { criterion: 'Финансы в реальном времени', values: { excel: 'Вручную', oneC: 'Задним числом', poster: 'Частично', orda: 'Каждый день' } },
  { criterion: 'Касса без интернета', values: { excel: '—', oneC: 'Нет', poster: 'Частично', orda: 'Полный офлайн' } },
  { criterion: 'Контроль недостач', values: { excel: 'Вручную', oneC: '—', poster: '—', orda: 'Слепые ревизии' } },
  { criterion: 'Расчёт зарплат', values: { excel: 'Руками', oneC: 'Настройка', poster: 'Интеграции', orda: 'Ставка · % · KPI' } },
  { criterion: 'AI-анализ и действия', values: { excel: '—', oneC: '—', poster: 'Минимально', orda: 'Анализ + копилот' } },
  { criterion: 'Telegram-бот', values: { excel: '—', oneC: '—', poster: '—', orda: 'Отчёты и управление' } },
]

const pricingPlans = [
  { name: 'Orda Finance', levelLabel: 'Финансы', price: 9900, description: 'Контроль владельца поверх любой кассы.', features: ['Финансовый дашборд', 'Доходы и расходы', 'Отчёты по периодам', 'Telegram-отчёты владельцу', 'AI-разборы показателей'], cta: 'Попробовать', highlight: false },
  { name: 'Orda Service', levelLabel: 'Услуги / сервис', price: 16900, description: 'СТО, автомойки, ремонт, барбершопы.', features: ['Всё из Finance', 'Смены и сверка кассы', 'Зарплата: ставка, %, KPI', 'Операционные задачи'], cta: 'Выбрать', highlight: false },
  { name: 'Orda Shop', levelLabel: 'Магазин', price: 19900, description: 'Магазины и небольшие сети.', features: ['Всё из Finance', 'Магазин, склад, приёмка по фото', 'Смены и зарплата', 'Ревизии и остатки'], cta: 'Выбрать', highlight: false },
  { name: 'Orda Club', levelLabel: 'Клуб / игровая', price: 19900, description: 'Компьютерные клубы и игровые точки.', features: ['Всё из Finance', 'Смены и зарплата', 'Операционная работа', 'Команда и пространство'], cta: 'Выбрать Club', highlight: true, badge: 'Популярный' },
  { name: 'Orda Restaurant', levelLabel: 'Кафе / ресторан', price: 24900, description: 'Кафе, бар, пиццерия, dark kitchen.', features: ['Всё из Finance', 'Склад и техкарты', 'Себестоимость блюд', 'Смены и зарплата'], cta: 'Выбрать', highlight: false },
  { name: 'Магазин + Финансы', levelLabel: 'Комплект', price: 28000, description: 'Магазин и расширенные финансы вместе.', features: ['Всё из Shop', 'Углублённая финмодель', 'Приоритетная поддержка', 'Помощь с внедрением'], cta: 'Связаться', highlight: false },
]

const faqItems = [
  { question: 'Работает ли касса без интернета?', answer: 'Да. При потере сети касса продолжает продавать: продажи, возвраты и отчёты копятся в локальную очередь и синхронизируются, когда интернет вернётся. Чек печатается и офлайн.' },
  { question: 'Чеки легальны для налоговой?', answer: 'Чеки печатаются с фискальными реквизитами ККМ по требованиям приказа МФ РК №626: БИН/ИИН, номера ККМ, НДС, ОФД. Плюс встроенный калькулятор налогов ИП: упрощёнка и форма 910.' },
  { question: `Чем ${PRODUCT} отличается от 1С и Poster?`, answer: `1С — учёт для бухгалтерии, Poster — касса. ${PRODUCT} — управление бизнесом целиком: касса, склад, смены, зарплаты, AI и Telegram в одной системе. Можно работать и в дополнение к ним.` },
  { question: 'AI правда работает?', answer: 'AI видит ваши данные: выручку, расходы, маржу, склад. Отвечает на вопросы, находит аномалии и выполняет действия: премии, промокоды, приёмки по фото накладной. Всё с учётом прав и записью в журнал.' },
  { question: 'Что нужно установить?', answer: `На кассе (Windows) — наша программа, она обновляется сама. Владельцу и сотрудникам — приложение Orda Point для iPhone (App Store) или Android (Google Play), либо просто браузер. ${PRODUCT} полностью облачный.` },
  { question: 'Есть пробный период?', answer: 'Первая точка — 2 недели бесплатно с полным доступом. Не подойдёт — данные можно выгрузить. Без обязательств.' },
]

/** Путь одной продажи по системе — секция «Как это работает». */
const flowSteps = [
  { icon: ScanLine, title: 'Кассир пробил чек', text: 'На кассе Windows — со сканером, сдачей и чеком ККМ. Даже без интернета.', tag: 'Касса' },
  { icon: Package, title: 'Остатки списались', text: 'Товар ушёл с витрины, план закупа пересчитался, ревизия это увидит.', tag: 'Склад' },
  { icon: Wallet, title: 'Смена и зарплата', text: 'Выручка легла в смену, процент кассира и бонусы посчитались сами.', tag: 'Команда' },
  { icon: Smartphone, title: 'Владелец видит прибыль', text: 'На iPhone или Android — выручка, маржа и итоги дня с пушем.', tag: 'Телефон' },
]

/** Что получает каждый в команде — секция «Для кого». */
const roles = [
  { icon: Crown, role: 'Владелец', where: 'iPhone · Android · браузер', points: ['Прибыль и маржа каждый день', 'Итоги дня в Telegram', 'AI-разбор: что не так и что делать'] },
  { icon: Briefcase, role: 'Управляющий', where: 'Браузер · Android', points: ['Приёмка, заявки и ревизии', 'График смен и задачи', 'Расходы на одобрение'] },
  { icon: Monitor, role: 'Кассир', where: 'Касса Windows', points: ['Быстрый чек со сканером', 'Работает без интернета', 'Сдача и долги сотрудников'] },
  { icon: UserRound, role: 'Сотрудник', where: 'Телефон', points: ['Своя зарплата и смены', 'Задачи и регламенты', 'AI-квизы для обучения'] },
]

// ─────────────────────── СТРАНИЦА ───────────────────────

export default function MarketingHomePage() {
  return (
    <main className="min-h-screen bg-[#fafaf8] pb-[76px] text-[#0b1f3b] sm:pb-0">
      <WebsiteStructuredData />
      <FaqStructuredData faq={faqItems} />
      <ScrollProgress />

      {/* Шапка */}
      <header className="sticky top-0 z-50 border-b border-[#e5e7eb] bg-[#fafaf8]/85 backdrop-blur-xl">
        <div className={`${container} flex items-center justify-between py-3`}>
          <Link href="/" className="flex items-center gap-2.5">
            <OrdaControlMark size={36} className="shrink-0 drop-shadow-[0_6px_12px_rgba(11,31,59,0.35)]" />
            <div className="leading-none">
              <div className="font-display text-[19px] font-extrabold tracking-[-0.03em] text-[#0b1f3b]">ORDA</div>
              <div className="mt-[3px] text-[9.5px] font-semibold tracking-[0.42em] text-[#6b7280]">CONTROL</div>
            </div>
          </Link>
          <nav className="flex items-center gap-1">
            <Button asChild variant="ghost" className={navLink}><Link href="#apps">Приложения</Link></Button>
            <Button asChild variant="ghost" className={navLink}><Link href="#how">Как работает</Link></Button>
            <Button asChild variant="ghost" className={navLink}><Link href="#features">Возможности</Link></Button>
            <Button asChild variant="ghost" className={navLink}><Link href="#pricing">Пакеты</Link></Button>
            <Button asChild variant="ghost" className="rounded-[11px] text-[14px] font-medium text-[#56657d] hover:bg-[#f3f6fa] hover:text-[#0b1f3b] dark:hover:bg-[#f3f6fa] dark:hover:text-[#0b1f3b]">
              <Link href="/login">Войти</Link>
            </Button>
            <Button asChild className="rounded-[12px] bg-none bg-[#2563eb] px-5 py-2 text-[14px] font-semibold text-white hover:bg-[#1d4ed8]">
              <Link href="#contact">Попробовать<ArrowRight className="ml-1.5 h-4 w-4" /></Link>
            </Button>
          </nav>
        </div>
      </header>

      {/* HERO: заголовок + сцена со всеми устройствами */}
      <section className="relative overflow-hidden">
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div
            className="absolute inset-0"
            style={{
              backgroundImage: 'radial-gradient(circle, rgba(15,32,56,0.08) 1px, transparent 1px)',
              backgroundSize: '26px 26px',
              maskImage: 'radial-gradient(75% 60% at 50% 0%, black, transparent)',
              WebkitMaskImage: 'radial-gradient(75% 60% at 50% 0%, black, transparent)',
            }}
          />
          <div className="absolute -top-32 left-1/2 h-[480px] w-[900px] -translate-x-1/2 rounded-full bg-[#2563eb]/[0.1] blur-[130px]" />
        </div>
        <div className={`${container} relative pb-16 pt-14 lg:pb-24 lg:pt-20`}>
          <HeroIn className="mx-auto max-w-[940px] text-center">
            <div className={eyebrowClass}><Sparkles className="h-3.5 w-3.5" />Касса · iPhone · Android · браузер</div>
            <h1 className="mt-6 font-display text-[44px] font-extrabold leading-[1.02] tracking-[-0.035em] text-[#0b1f3b] sm:text-[64px] lg:text-[80px]">
              Одна система.<br />
              <span className="bg-gradient-to-r from-[#2563eb] to-[#3b82f6] bg-clip-text text-transparent">Все устройства.</span>
            </h1>
            <p className={`mx-auto mt-6 max-w-[660px] ${leadClass}`}>
              Кассир продаёт на кассе, управляющий принимает товар, а вы видите прибыль в телефоне. Склад, смены, зарплаты и AI — в одной системе.
            </p>
            <div className="mt-8 flex flex-wrap justify-center gap-3">
              <Button asChild size="lg" className={btnPrimary}><Link href="#contact">Начать бесплатно<ArrowRight className="ml-2 h-5 w-5" /></Link></Button>
              <Button asChild size="lg" variant="outline" className={btnGhost}><Link href="#apps">Смотреть приложения</Link></Button>
            </div>
            <div className="mt-6 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-[14px] font-medium text-[#5b6b82]">
              <span className="flex items-center gap-1.5"><CheckCircle2 className="h-4 w-4 text-[#2563eb]" />2 недели бесплатно</span>
              <span className="flex items-center gap-1.5"><CheckCircle2 className="h-4 w-4 text-[#2563eb]" />Касса работает без интернета</span>
              <span className="flex items-center gap-1.5"><CheckCircle2 className="h-4 w-4 text-[#2563eb]" />Чеки по требованиям РК</span>
            </div>
          </HeroIn>
          <HeroIn delay={0.15} className="mt-12 lg:mt-16">
            <div id="apps" className="scroll-mt-24">
              <AppsStage />
            </div>
          </HeroIn>
        </div>
      </section>

      {/* СОЦ-ДОКАЗАТЕЛЬСТВО + MARQUEE */}
      <div className="border-y border-[#e2e8f0] bg-[#f4f5f2] py-5">
        <Reveal className={`${container} flex flex-wrap items-center justify-center gap-x-3 gap-y-1 text-center text-[14.5px] font-medium text-[#5b6b82]`}>
          <span className="h-2 w-2 rounded-full bg-[#2563eb]" />
          Уже считает финансы сети <span className="font-bold text-[#0b1f3b]">F16</span>
          <span className="text-[#cbd3e0]">·</span>
          <span className="font-semibold text-[#475569]">Arena · Ramen · Extra</span>
        </Reveal>
        <div className="mt-4"><FeatureMarquee items={marqueeFeatures} /></div>
      </div>

      {/* КАК ЭТО РАБОТАЕТ: путь одной продажи */}
      <section id="how" className="scroll-mt-20">
        <div className={`${container} py-20 lg:py-28`}>
          <Reveal className="max-w-[760px]">
            <div className={eyebrowClass}><Boxes className="h-3.5 w-3.5" />Как это работает</div>
            <h2 className={`mt-4 ${h2Class}`}>Одна продажа — и всё уже посчитано</h2>
            <p className={`mt-4 ${leadClass}`}>Не нужно сводить кассу, склад и зарплату в Excel. Каждое действие сразу попадает туда, где оно важно.</p>
          </Reveal>
          <Stagger className="relative mt-14 grid gap-5 md:grid-cols-2 lg:grid-cols-4">
            <div aria-hidden className="absolute left-0 right-0 top-[34px] hidden h-px bg-gradient-to-r from-transparent via-[#2563eb]/40 to-transparent lg:block" />
            {flowSteps.map((s, i) => {
              const Icon = s.icon
              return (
                <StaggerItem key={s.title}>
                  <div className="relative h-full">
                    <div className="relative z-10 flex items-center gap-3">
                      <span className="grid h-[68px] w-[68px] place-items-center rounded-[20px] border border-[#2563eb]/25 bg-white text-[#1d4ed8] shadow-[0_12px_30px_-14px_rgba(37,99,235,0.5)]">
                        <Icon className="h-7 w-7" />
                      </span>
                      <span className="font-display text-[44px] font-extrabold leading-none text-[#e2e8f0]">0{i + 1}</span>
                    </div>
                    <div className="mt-5 inline-flex rounded-full bg-[#f3f6fa] px-3 py-1 text-[12px] font-semibold text-[#56657d]">{s.tag}</div>
                    <h3 className="mt-3 font-display text-[21px] font-bold leading-[1.25] text-[#0b1f3b]">{s.title}</h3>
                    <p className="mt-2 text-[15px] leading-[1.55] text-[#56657d]">{s.text}</p>
                  </div>
                </StaggerItem>
              )
            })}
          </Stagger>
        </div>
      </section>

      {/* ДЛЯ КОГО: роли в команде */}
      <section className="relative overflow-hidden bg-[#0b1f3b] text-white">
        <div aria-hidden className="pointer-events-none absolute inset-0" style={{ backgroundImage: 'radial-gradient(circle, rgba(255,255,255,0.06) 1px, transparent 1px)', backgroundSize: '26px 26px' }} />
        <div aria-hidden className="pointer-events-none absolute -right-40 top-0 h-[500px] w-[500px] rounded-full bg-[#3b82f6]/15 blur-[140px]" />
        <div className={`${container} relative py-20 lg:py-28`}>
          <Reveal className="max-w-[760px]">
            <div className={eyebrowDark}><Building2 className="h-3.5 w-3.5" />Для всей команды</div>
            <h2 className="mt-4 font-display text-balance text-[32px] font-extrabold leading-[1.05] tracking-[-0.03em] sm:text-[42px] lg:text-[52px]">Каждый видит своё — и ничего лишнего</h2>
            <p className="mt-4 text-[17px] leading-[1.55] text-[#c7d3e3] sm:text-[19px]">Права настраиваются по ролям: кассир не увидит прибыль, сотрудник — чужую зарплату. Все действия пишутся в журнал.</p>
          </Reveal>
          <Stagger className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {roles.map((r) => {
              const Icon = r.icon
              return (
                <StaggerItem key={r.role}>
                  <div className="flex h-full flex-col rounded-[24px] border border-white/10 bg-white/[0.05] p-6 backdrop-blur transition duration-300 hover:-translate-y-1 hover:border-[#60a5fa]/40 hover:bg-white/[0.08]">
                    <span className="grid h-12 w-12 place-items-center rounded-[14px] bg-[#60a5fa]/15 text-[#60a5fa]"><Icon className="h-6 w-6" /></span>
                    <div className="mt-5 font-display text-[22px] font-bold">{r.role}</div>
                    <div className="mt-1 text-[13px] font-medium text-[#93c5fd]">{r.where}</div>
                    <ul className="mt-5 space-y-2.5 border-t border-white/10 pt-5">
                      {r.points.map((p) => (
                        <li key={p} className="flex items-start gap-2 text-[14px] leading-[1.5] text-[#dbe6f4]"><Check className="mt-0.5 h-4 w-4 shrink-0 text-[#60a5fa]" />{p}</li>
                      ))}
                    </ul>
                  </div>
                </StaggerItem>
              )
            })}
          </Stagger>
        </div>
      </section>

      {/* ВОЗМОЖНОСТИ: bento */}
      <section id="features" className="scroll-mt-20">
        <div className={`${container} py-20 lg:py-28`}>
          <Reveal className="mx-auto max-w-[800px] text-center">
            <div className={eyebrowClass}><Sparkles className="h-3.5 w-3.5" />Возможности</div>
            <h2 className={`mt-4 ${h2Class}`}>Одна система вместо пяти программ</h2>
            <p className={`mt-4 ${leadClass}`}>Касса, склад, команда, финансы и AI связаны между собой: продажа сразу меняет остатки, смену и прибыль.</p>
          </Reveal>

          <Stagger className="mt-12 grid gap-4 md:grid-cols-2 lg:grid-cols-6">
            <StaggerItem className="md:col-span-2 lg:col-span-3">
              <div className={bentoCard}>
                <BentoHead icon={Bot} title="AI-копилот: не советует — делает" badge="110+ действий" />
                <p className={bentoText}>Напишите как человеку: начислит премию, создаст промокод, оприходует накладную, объяснит падение маржи.</p>
                <div className="mt-auto pt-5"><CopilotDemo /></div>
              </div>
            </StaggerItem>

            <StaggerItem className="md:col-span-2 lg:col-span-3">
              <div className={bentoCard}>
                <BentoHead icon={MessageCircle} title="Telegram-бот: бизнес в мессенджере" />
                <p className={bentoText}>Фото накладной → готовая приёмка. Итоги дня — каждое утро. Зарплата — сотрудникам в личку.</p>
                <div className="mt-auto pt-5"><TelegramDemo /></div>
              </div>
            </StaggerItem>

            <StaggerItem className="lg:col-span-2">
              <div className={bentoCard}>
                <BentoHead icon={CloudOff} title="Касса без интернета" />
                <p className={bentoText}>Сеть упала — торговля идёт: продажи копятся в очередь и синхронизируются сами. Чеки ККМ и ОФД — приказ МФ РК №626.</p>
                <div className="mt-auto pt-5"><OfflineDemo /></div>
              </div>
            </StaggerItem>

            <StaggerItem className="lg:col-span-2">
              <div className="relative flex h-full flex-col overflow-hidden rounded-[24px] border border-[#0b1f3b] bg-[#0b1f3b] p-6 shadow-[0_10px_30px_-18px_rgba(15,32,56,0.5)] sm:p-7">
                <div aria-hidden className="pointer-events-none absolute inset-0" style={{ backgroundImage: 'radial-gradient(circle, rgba(255,255,255,0.07) 1px, transparent 1px)', backgroundSize: '24px 24px' }} />
                <div className="relative flex h-full flex-col">
                  <div className="flex items-center gap-3">
                    <span className="grid h-10 w-10 shrink-0 place-items-center rounded-[12px] bg-[#60a5fa]/15 text-[#60a5fa]"><ShieldCheck className="h-5 w-5" /></span>
                    <h3 className="font-display text-[19px] font-bold leading-[1.25] text-white sm:text-[20px]">Недостачу не спрятать</h3>
                  </div>
                  <ul className="mt-auto space-y-3 pt-5">
                    {[
                      { icon: Eye, text: 'Слепые ревизии: считают, не видя остаток' },
                      { icon: Wallet, text: 'Недостача — долгом из зарплаты' },
                      { icon: AlertCircle, text: 'Сигнал о воровстве владельцу' },
                      { icon: FileCheck, text: 'Журнал аудита: кто что менял' },
                    ].map((f) => {
                      const Icon = f.icon
                      return (
                        <li key={f.text} className="flex items-start gap-2.5 rounded-[11px] bg-white/[0.06] px-3.5 py-2.5 text-[13px] leading-[1.5] text-[#dbe6f4]">
                          <Icon className="mt-0.5 h-4 w-4 shrink-0 text-[#60a5fa]" />{f.text}
                        </li>
                      )
                    })}
                  </ul>
                </div>
              </div>
            </StaggerItem>

            <StaggerItem className="lg:col-span-2">
              <div className={bentoCard}>
                <BentoHead icon={Camera} title="Склад сам говорит, что закупать" />
                <p className={bentoText}>AI распознаёт фото накладной. План закупа — по продажам и остаткам, с автозаявками поставщикам.</p>
                <div className="mt-auto space-y-2 pt-5">
                  {[
                    { name: 'Энергетик 0,45 л', note: 'осталось на 3 дня', qty: '4 уп.' },
                    { name: 'Вода 0,5 л', note: 'точка дозаказа', qty: '6 уп.' },
                    { name: 'Жев. резинка', note: 'спрос +18%', qty: '2 уп.' },
                  ].map((l) => (
                    <div key={l.name} className="flex items-center justify-between rounded-[11px] bg-[#f4f5f2] px-3.5 py-2.5 text-[13px]">
                      <div>
                        <div className="font-semibold text-[#0b1f3b]">{l.name}</div>
                        <div className="text-[11.5px] text-[#94a3b8]">{l.note}</div>
                      </div>
                      <span className="font-semibold text-[#1d4ed8] tabular-nums">{l.qty}</span>
                    </div>
                  ))}
                </div>
              </div>
            </StaggerItem>

            <StaggerItem className="lg:col-span-2">
              <div className={bentoCard}>
                <BentoHead icon={Wallet} title="Зарплата и смены — без споров" />
                <p className={bentoText}>Ставка, процент, KPI, бонусы и штрафы считаются сами. Сотрудник видит свою зарплату в телефоне.</p>
                <div className="mt-auto space-y-2 pt-5">
                  <div className="rounded-[14px] border border-[#e2e8f0] bg-[#f4f5f2] p-4">
                    <div className="text-[11px] font-semibold uppercase tracking-[0.06em] text-[#64748b]">К выплате за неделю</div>
                    <div className="mt-1 font-display text-[24px] font-extrabold text-[#0b1f3b] tabular-nums">86 400 ₸</div>
                    <div className="mt-0.5 text-[11.5px] text-[#64748b]">5 смен · бонус за оборот · −1 долг</div>
                  </div>
                  <div className="flex items-center justify-between rounded-[11px] bg-[#f4f5f2] px-3.5 py-2.5 text-[13px]">
                    <span className="font-semibold text-[#0b1f3b]">Квиз по стандартам смены</span>
                    <span className="rounded-full bg-[#2563eb]/10 px-2.5 py-0.5 text-[11px] font-bold text-[#1d4ed8]">5/5</span>
                  </div>
                </div>
              </div>
            </StaggerItem>

            <StaggerItem className="lg:col-span-2">
              <div className={bentoCard}>
                <BentoHead icon={LineChart} title="Цифры уровня финдиректора" />
                <p className={bentoText}>ОПиУ, Cash Flow, налоги ИП (форма 910), оценка бизнеса и финмодель новой точки.</p>
                <div className="mt-auto space-y-2 pt-5">
                  {[
                    { name: 'Выручка', sum: '12 480 000 ₸', strong: false },
                    { name: 'Расходы и ФОТ', sum: '−10 520 000 ₸', strong: false },
                    { name: 'Чистая прибыль', sum: '1 960 000 ₸', strong: true },
                  ].map((l) => (
                    <div key={l.name} className={`flex items-center justify-between rounded-[11px] px-3.5 py-2.5 text-[13px] ${l.strong ? 'bg-[#2563eb]/[0.07] font-bold text-[#1d4ed8]' : 'bg-[#f4f5f2] text-[#0b1f3b]'}`}>
                      <span>{l.name}</span>
                      <span className="font-semibold tabular-nums">{l.sum}</span>
                    </div>
                  ))}
                </div>
              </div>
            </StaggerItem>

            <StaggerItem className="lg:col-span-2">
              <div className={bentoCard}>
                <BentoHead icon={BadgePercent} title="Клиенты возвращаются" />
                <p className={bentoText}>Бонусные баллы, промокоды, акции и смешанная оплата. Чек — на почту покупателю.</p>
                <div className="mt-auto space-y-2 pt-5">
                  <div className="flex items-center justify-between rounded-[14px] border border-[#e2e8f0] bg-[#f4f5f2] p-4 text-[13px]">
                    <div>
                      <div className="font-semibold text-[#0b1f3b]">Чек 3 640 ₸ · нал + Kaspi</div>
                      <div className="mt-0.5 text-[11.5px] text-[#1d4ed8]">Клиенту начислено 36 бонусов</div>
                    </div>
                    <CheckCircle2 className="h-5 w-5 shrink-0 text-[#2563eb]" />
                  </div>
                  <div className="flex items-center justify-between rounded-[11px] bg-[#f4f5f2] px-3.5 py-2.5 text-[13px]">
                    <span className="font-semibold text-[#0b1f3b]">Промокод на выходные −10%</span>
                    <span className="rounded-full bg-[#2563eb]/10 px-2.5 py-0.5 text-[11px] font-bold text-[#1d4ed8]">Активен</span>
                  </div>
                </div>
              </div>
            </StaggerItem>
          </Stagger>

          {/* Цифры-факты */}
          <Stagger className="mt-4 grid grid-cols-2 gap-4 lg:grid-cols-4">
            {[
              { value: 4, suffix: '', label: 'приложения: касса, iPhone, Android, браузер' },
              { value: 110, suffix: '+', label: 'действий AI-копилота' },
              { value: 90, suffix: '+', label: 'экранов управления' },
              { value: 24, suffix: '/7', label: 'Telegram-бот на связи' },
            ].map((n) => (
              <StaggerItem key={n.label}>
                <div className="flex h-full items-center gap-4 rounded-[18px] border border-[#dbe3ee] bg-[#f4f5f2] px-5 py-4">
                  <span className="font-display text-[30px] font-extrabold tracking-[-0.02em] text-[#1d4ed8] sm:text-[34px]"><CountUp value={n.value} suffix={n.suffix} /></span>
                  <span className="text-[13px] font-medium leading-[1.35] text-[#56657d]">{n.label}</span>
                </div>
              </StaggerItem>
            ))}
          </Stagger>
        </div>
      </section>

      {/* СРАВНЕНИЕ */}
      <section className="border-y border-[#e2e8f0] bg-[#f4f5f2]">
        <div className={`${container} py-20 lg:py-28`}>
          <Reveal className="mx-auto max-w-[800px] text-center">
            <div className={eyebrowClass}><Scale className="h-3.5 w-3.5" />Сравнение</div>
            <h2 className={`mt-4 ${h2Class}`}>Excel, 1С и кассы решают по куску</h2>
            <p className={`mt-4 ${leadClass}`}>Orda собирает всё в одном месте — и подходит: {audiences.join(' · ').toLowerCase()}.</p>
          </Reveal>
          <Reveal className="mt-12 hidden overflow-hidden rounded-[20px] border border-[#d6dde8] bg-white lg:block">
            <div className="grid" style={{ gridTemplateColumns: `minmax(220px,1.2fr) repeat(${comparisonColumns.length}, minmax(150px,1fr))` }}>
              <div className="border-b border-[#e2e8f0] px-5 py-4"><span className="text-[11px] font-semibold uppercase tracking-[0.1em] text-[#64748b]">Критерий</span></div>
              {comparisonColumns.map((col) => (
                <div key={col.key} className={'border-b border-l border-[#e2e8f0] px-5 py-4 ' + (col.highlight ? 'bg-[#2563eb]/[0.06]' : '')}>
                  <div className={'font-display text-[16px] font-bold ' + (col.highlight ? 'text-[#1d4ed8]' : 'text-[#0b1f3b]')}>{col.label}</div>
                  <div className="mt-0.5 text-[11px] text-[#64748b]">{col.subtitle}</div>
                </div>
              ))}
              {comparisonRows.map((row, ri) => (
                <Fragment key={row.criterion}>
                  <div className={'px-5 py-4 text-[13.5px] font-semibold text-[#0b1f3b] ' + (ri < comparisonRows.length - 1 ? 'border-b border-[#e2e8f0]' : '')}>{row.criterion}</div>
                  {comparisonColumns.map((col) => (
                    <div key={col.key} className={'border-l border-[#e2e8f0] px-5 py-4 text-[13px] ' + (ri < comparisonRows.length - 1 ? 'border-b ' : '') + (col.highlight ? 'bg-[#2563eb]/[0.04] font-medium text-[#0b1f3b]' : 'text-[#56657d]')}>
                      {col.highlight ? <span className="inline-flex items-start gap-1.5"><CheckCircle2 className="mt-[1px] h-3.5 w-3.5 shrink-0 text-[#2563eb]" />{row.values[col.key]}</span> : row.values[col.key]}
                    </div>
                  ))}
                </Fragment>
              ))}
            </div>
          </Reveal>
          <Stagger className="mt-10 grid gap-3 sm:grid-cols-2 lg:hidden">
            {comparisonColumns.map((col) => (
              <StaggerItem key={col.key}>
                <div className={col.highlight ? 'h-full rounded-[18px] border-2 border-[#2563eb]/40 bg-white p-5' : 'h-full rounded-[18px] border border-[#d6dde8] bg-white p-5'}>
                  <div className={'font-display text-[18px] font-bold ' + (col.highlight ? 'text-[#1d4ed8]' : 'text-[#0b1f3b]')}>{col.label}</div>
                  <dl className="mt-3 grid gap-2 border-t border-[#e2e8f0] pt-3">
                    {comparisonRows.map((row) => (
                      <div key={row.criterion} className="grid grid-cols-[1fr_auto] gap-3">
                        <dt className="text-[12px] text-[#64748b]">{row.criterion}</dt>
                        <dd className="text-right text-[12.5px] text-[#0b1f3b]">{row.values[col.key]}</dd>
                      </div>
                    ))}
                  </dl>
                </div>
              </StaggerItem>
            ))}
          </Stagger>
        </div>
      </section>

      {/* ТАРИФЫ */}
      <section id="pricing" className="scroll-mt-20">
        <div className={`${container} py-20 lg:py-28`}>
          <Reveal className="mx-auto max-w-[800px] text-center">
            <div className={eyebrowClass}><Tag className="h-3.5 w-3.5" />Пакеты</div>
            <h2 className={`mt-4 ${h2Class}`}>Выберите пакет под свой бизнес</h2>
            <p className={`mt-4 ${leadClass}`}>Готовые пакеты по типу бизнеса. Цена за организацию, модули докупаются сверху.</p>
          </Reveal>
          <Stagger className="mt-12 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {pricingPlans.map((plan) => (
              <StaggerItem key={plan.name}>
                <div className={plan.highlight
                  ? 'relative flex h-full flex-col rounded-[22px] border-2 border-[#2563eb]/45 bg-white p-7 shadow-[0_24px_54px_-18px_rgba(37,99,235,0.45)] transition-transform duration-300 hover:-translate-y-3 xl:-translate-y-2'
                  : 'flex h-full flex-col rounded-[22px] border border-[#d6dde8] bg-white p-7 shadow-[0_12px_34px_-16px_rgba(15,32,56,0.18)] transition duration-300 hover:-translate-y-1'}>
                  {plan.highlight && plan.badge ? <span className="absolute right-5 top-5 rounded-full bg-gradient-to-br from-[#fb923c] to-[#f97316] px-3 py-1 text-[10px] font-semibold uppercase tracking-[0.08em] text-white shadow-[0_6px_16px_-6px_rgba(249,115,22,0.6)]">{plan.badge}</span> : null}
                  <div className={'text-[11px] font-semibold uppercase tracking-[0.12em] ' + (plan.highlight ? 'text-[#1d4ed8]' : 'text-[#64748b]')}>{plan.levelLabel}</div>
                  <div className="mt-1.5 font-display text-[26px] font-bold text-[#0b1f3b]">{plan.name}</div>
                  <div className="mt-2 flex items-baseline gap-1">
                    <span className="font-display text-[30px] font-extrabold tabular-nums text-[#0b1f3b]">{plan.price.toLocaleString('ru-RU')}</span>
                    <span className="text-[14px] font-medium text-[#64748b]">₸ / мес</span>
                  </div>
                  <p className="mt-2 text-[14px] leading-[1.5] text-[#56657d]">{plan.description}</p>
                  <ul className="mt-5 flex-1 space-y-2.5 border-t border-[#e2e8f0] pt-5">
                    {plan.features.map((f) => (
                      <li key={f} className="flex items-start gap-2 text-[14px] leading-[1.5] text-[#475569]"><Check className="mt-0.5 h-4 w-4 shrink-0 text-[#2563eb]" /><span>{f}</span></li>
                    ))}
                  </ul>
                  <Button asChild className={plan.highlight
                    ? 'mt-6 w-full rounded-[12px] bg-none bg-[#2563eb] py-3 text-[14px] font-semibold text-white hover:bg-[#1d4ed8]'
                    : 'mt-6 w-full rounded-[12px] bg-none bg-[#f3f6fa] py-3 text-[14px] font-semibold text-[#0b1f3b] hover:bg-[#e9eef4]'}>
                    <Link href="#contact">{plan.cta}</Link>
                  </Button>
                </div>
              </StaggerItem>
            ))}
          </Stagger>
        </div>
      </section>

      {/* СКАЧАТЬ: большая полоса с магазинами */}
      <section className="px-5 sm:px-8 lg:px-12">
        <Reveal className="relative mx-auto max-w-[1440px] overflow-hidden rounded-[36px] bg-gradient-to-br from-[#1d4ed8] via-[#2563eb] to-[#3b82f6] px-6 py-14 text-white sm:px-12 lg:px-16 lg:py-20">
          <div aria-hidden className="pointer-events-none absolute inset-0" style={{ backgroundImage: 'radial-gradient(circle, rgba(255,255,255,0.12) 1px, transparent 1px)', backgroundSize: '24px 24px' }} />
          <div aria-hidden className="pointer-events-none absolute -right-24 -top-24 h-[380px] w-[380px] rounded-full bg-white/20 blur-[100px]" />
          <div className="relative flex flex-col gap-8 lg:flex-row lg:items-center lg:justify-between">
            <div className="max-w-[620px]">
              <h2 className="font-display text-[34px] font-extrabold leading-[1.05] tracking-[-0.03em] sm:text-[44px] lg:text-[54px]">Скачайте Orda Point</h2>
              <p className="mt-4 text-[17px] leading-[1.55] text-white/85 sm:text-[19px]">Приложение для владельца и команды — бесплатно в App Store и Google Play. Касса — для Windows, обновляется сама.</p>
            </div>
            <div className="flex flex-wrap gap-3">
              <StoreBadge href={APP_STORE_URL} kind="apple" />
              <StoreBadge href={GOOGLE_PLAY_URL} kind="google" />
              <StoreBadge href={OPERATOR_DOWNLOAD_URL} kind="windows" />
            </div>
          </div>
        </Reveal>
      </section>

      {/* FAQ + ЗАЯВКА */}
      <section id="contact" className="scroll-mt-20">
        <div className={`${container} py-20 lg:py-28`}>
          <div className="grid gap-10 lg:grid-cols-[1.1fr_0.9fr] lg:gap-14">
            <div>
              <Reveal>
                <div className={eyebrowClass}><HelpCircle className="h-3.5 w-3.5" />Частые вопросы</div>
                <h2 className={`mt-4 ${h2Class}`}>Что спрашивают чаще всего</h2>
              </Reveal>
              <Stagger className="mt-8 space-y-3">
                {faqItems.map((item) => (
                  <StaggerItem key={item.question}>
                    <details className="group rounded-[16px] border border-[#d6dde8] bg-white px-5 py-4 transition-colors duration-300 hover:border-[#2563eb]/30 open:border-[#2563eb]/30 [&_summary::-webkit-details-marker]:hidden">
                      <summary className="flex cursor-pointer list-none items-center justify-between gap-3">
                        <h3 className="font-display text-[16px] font-bold leading-[1.35] text-[#0b1f3b]">{item.question}</h3>
                        <ChevronDown className="h-5 w-5 shrink-0 text-[#2563eb] transition-transform duration-300 group-open:rotate-180" />
                      </summary>
                      <p className="mt-2.5 text-[14px] leading-[1.6] text-[#56657d]">{item.answer}</p>
                    </details>
                  </StaggerItem>
                ))}
              </Stagger>
            </div>
            <Reveal delay={0.1}>
              <div className="lg:sticky lg:top-24">
                <div className="rounded-[24px] border border-[#2563eb]/25 bg-white p-7 shadow-[0_24px_60px_-28px_rgba(37,99,235,0.4)] sm:p-8">
                  <div className={eyebrowClass}><Sparkles className="h-3.5 w-3.5" />Начните сегодня</div>
                  <h3 className="mt-4 font-display text-[24px] font-bold leading-[1.2] text-[#0b1f3b] sm:text-[27px]">Покажем систему на ваших данных</h3>
                  <p className="mt-2 text-[14px] leading-[1.55] text-[#56657d]">Оставьте контакты — ответим в течение рабочего дня и подскажем пакет. Без спама и звонков-роботов.</p>
                  <div className="mt-5"><ContactLeadForm /></div>
                  <div className="mt-5 space-y-2 border-t border-[#e2e8f0] pt-4">
                    {[
                      { icon: Wallet, text: 'Прибыль и маржа в реальном времени' },
                      { icon: CloudOff, text: 'Касса работает даже без интернета' },
                      { icon: Smartphone, text: 'Приложения для iPhone и Android' },
                      { icon: ShieldCheck, text: 'Данные изолированы, журнал аудита' },
                    ].map((f) => {
                      const Icon = f.icon
                      return (
                        <div key={f.text} className="flex items-center gap-2.5 text-[13px] font-medium text-[#475569]">
                          <Icon className="h-4 w-4 shrink-0 text-[#2563eb]" />{f.text}
                        </div>
                      )
                    })}
                  </div>
                </div>
              </div>
            </Reveal>
          </div>
        </div>
      </section>

      {/* ФУТЕР */}
      <footer className="border-t border-[#e2e8f0] bg-[#f4f5f2]">
        <div className={`${container} py-12`}>
          <div className="flex flex-col gap-8 lg:flex-row lg:items-start lg:justify-between">
            <div className="max-w-[340px]">
              <div className="flex items-center gap-2.5">
                <OrdaControlMark size={36} className="shrink-0 drop-shadow-[0_6px_12px_rgba(11,31,59,0.35)]" />
                <div className="font-display text-[16px] font-bold text-[#0b1f3b]">{PRODUCT}</div>
              </div>
              <p className="mt-3 text-[14px] leading-[1.5] text-[#5b6b82]">Касса, склад, финансы и AI — на кассе, в браузере и в телефоне.</p>
            </div>
            <div className="flex flex-wrap gap-x-14 gap-y-7">
              <FooterCol title="Продукт" links={[
                { href: '#apps', label: 'Приложения' },
                { href: '#features', label: 'Возможности' },
                { href: '#pricing', label: 'Пакеты' },
                { href: '/club-management-system', label: 'Для клубов' },
                { href: '/login', label: 'Войти' },
              ]} />
              <FooterCol title="Скачать" external links={[
                { href: APP_STORE_URL, label: 'iPhone — App Store' },
                { href: GOOGLE_PLAY_URL, label: 'Android — Google Play' },
                { href: OPERATOR_DOWNLOAD_URL, label: 'Касса для Windows' },
              ]} />
              <FooterCol title="Документы" links={[
                { href: '/offer', label: 'Оферта' },
                { href: '/help', label: 'Инструкция' },
                { href: '/help/operator', label: 'Регламент оператора' },
                { href: '/privacy', label: 'Политика' },
                { href: '/terms', label: 'Соглашение' },
                { href: '/sla', label: 'SLA' },
              ]} />
              <FooterCol title="Связь" links={[
                { href: '#contact', label: 'Оставить заявку' },
                { href: '/cookies', label: 'Cookies' },
              ]} />
            </div>
          </div>
          <div className="mt-10 border-t border-[#e2e8f0] pt-6 text-[12px] text-[#64748b]">© 2026 {PRODUCT} — все права защищены</div>
        </div>
      </footer>

      <FloatingCta />
    </main>
  )
}

// ─────────────── ВСПОМОГАТЕЛЬНЫЕ ───────────────

/** Единая шапка bento-карты: иконка-чип + заголовок (+ бейдж справа). */
function BentoHead({ icon: Icon, title, badge }: { icon: any; title: string; badge?: string }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div className="flex items-center gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-[12px] bg-[#2563eb]/10 text-[#1d4ed8]"><Icon className="h-5 w-5" /></span>
        <h3 className={bentoTitle}>{title}</h3>
      </div>
      {badge ? <span className="hidden shrink-0 rounded-full bg-[#2563eb]/10 px-3 py-1.5 text-[12px] font-bold text-[#1d4ed8] xl:block">{badge}</span> : null}
    </div>
  )
}

function FooterCol({ title, links, external }: { title: string; links: Array<{ href: string; label: string }>; external?: boolean }) {
  return (
    <div>
      <div className="text-[12px] font-semibold uppercase tracking-[0.1em] text-[#64748b]">{title}</div>
      <div className="mt-3.5 flex flex-col gap-2.5 text-[14px] font-medium text-[#5b6b82]">
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
