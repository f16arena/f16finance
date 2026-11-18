'use client'

/**
 * Вход в программу детейлинга (<slug>.ordaops.kz/login).
 *
 * Это не общий вход Orda: ни вкладки «Операторы», ни слов про клуб и смены.
 * Человек видит название своего детейлинга и два поля. Мастера в программу
 * не заходят, поэтому вход один — по почте и паролю.
 */

import Link from 'next/link'
import { useState } from 'react'
import { AlertCircle, CalendarDays, CarFront, Eye, EyeOff, Loader2, Wallet, Wrench } from 'lucide-react'

import { supabase } from '@/lib/supabaseClient'

function errorText(message: string | null | undefined) {
  const m = String(message || '').toLowerCase()
  if (m.includes('invalid login credentials') || m.includes('invalid_credentials')) return 'Почта или пароль не подошли. Проверьте раскладку и попробуйте ещё раз.'
  if (m.includes('email not confirmed')) return 'Почта не подтверждена — откройте письмо со ссылкой.'
  if (m.includes('rate limit') || m.includes('too many')) return 'Слишком много попыток. Подождите минуту.'
  if (m.includes('network') || m.includes('fetch')) return 'Нет связи с сервером. Проверьте интернет.'
  return 'Не получилось войти. Попробуйте ещё раз.'
}

const logAttempt = (body: Record<string, unknown>) =>
  fetch('/api/auth/login-attempt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ method: 'email', target: 'staff', ...body }),
  }).catch(() => null)

const INSIDE = [
  { icon: CalendarDays, title: 'Запись на боксы', text: 'Неделя как в блокноте: кто когда стоит.' },
  { icon: Wallet, title: 'Оплаты и долги', text: 'Предоплата, доплата при выдаче, кто ещё должен.' },
  { icon: Wrench, title: 'Мастера и расходы', text: 'Процент мастеру считается сам, видно, сколько осталось вам.' },
]

export function DetailingLogin({ orgName, host }: { orgName: string; host: string }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError(null)
    const identifier = email.trim().toLowerCase()
    try {
      const { error: signInError } = await supabase.auth.signInWithPassword({ email: identifier, password })
      if (signInError) throw signInError

      await logAttempt({ status: 'success', identifier })
      await fetch('/api/auth/login-log', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ method: 'email', target: 'staff' }),
      }).catch(() => null)

      // Куда вести — решает сервер по роли; у владельца это «Сегодня».
      const res = await fetch('/api/auth/session-role').catch(() => null)
      const payload = res?.ok ? await res.json().catch(() => null) : null
      const next = typeof payload?.defaultPath === 'string' && payload.defaultPath.startsWith('/') ? payload.defaultPath : '/welcome'
      window.location.assign(next)
    } catch (err: any) {
      await logAttempt({ status: 'failed', identifier, reason: err?.message || null })
      setError(errorText(err?.message))
      setLoading(false)
    }
  }

  const field =
    'h-12 w-full rounded-xl border border-[#d4dbe6] bg-white px-3.5 text-[15px] text-[#0b1f3b] outline-none transition placeholder:text-[#94a3b8] focus:border-blue-600 focus:ring-2 focus:ring-blue-600/20'

  return (
    <div className="grid min-h-screen bg-[#f3f5f9] text-[#0b1f3b] lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
      {/* Слева — чья это программа. На телефоне сжимается в шапку. */}
      <aside className="flex flex-col gap-8 bg-[#0b1f3b] px-6 py-6 text-white lg:justify-between lg:px-12 lg:py-12">
        <div className="flex items-center gap-3">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-blue-600">
            <CarFront className="h-6 w-6" />
          </span>
          <span className="min-w-0">
            <span className="block truncate font-[family-name:var(--font-manrope)] text-lg font-extrabold">{orgName}</span>
            <span className="block truncate text-xs text-slate-400">{host}</span>
          </span>
        </div>

        <div className="hidden lg:block">
          <h1 className="max-w-md font-[family-name:var(--font-manrope)] text-4xl font-extrabold leading-[1.1] tracking-[-0.02em]">
            Машины, боксы и деньги — в одном месте
          </h1>
          <ul className="mt-9 space-y-5">
            {INSIDE.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex items-start gap-3.5">
                <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-white/10">
                  <Icon className="h-5 w-5 text-blue-300" />
                </span>
                <span>
                  <span className="block text-[15px] font-semibold">{title}</span>
                  <span className="block text-sm leading-6 text-slate-400">{text}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>

        <p className="hidden text-xs text-slate-500 lg:block">работает на Orda</p>
      </aside>

      <main className="flex items-start justify-center px-5 py-8 lg:items-center lg:px-12">
        <form onSubmit={submit} className="w-full max-w-sm space-y-5">
          <div>
            <h2 className="font-[family-name:var(--font-manrope)] text-2xl font-extrabold">Вход</h2>
            <p className="mt-1.5 text-sm text-[#5b6b82]">Почта и пароль, которые вам выдали.</p>
          </div>

          <label className="block space-y-1.5">
            <span className="text-sm font-medium text-[#5b6b82]">Почта</span>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className={field}
              placeholder="name@example.com"
              autoComplete="username"
              inputMode="email"
              required
              autoFocus
            />
          </label>

          <label className="block space-y-1.5">
            <span className="text-sm font-medium text-[#5b6b82]">Пароль</span>
            <span className="relative block">
              <input
                type={show ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={`${field} pr-11`}
                autoComplete="current-password"
                required
              />
              <button
                type="button"
                onClick={() => setShow((v) => !v)}
                aria-label={show ? 'Скрыть пароль' : 'Показать пароль'}
                className="absolute right-1 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-lg text-[#94a3b8] hover:text-[#475569]"
              >
                {show ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </span>
          </label>

          {error ? (
            <div role="alert" className="flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 px-3.5 py-3 text-sm text-red-700">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          ) : null}

          <button
            type="submit"
            disabled={loading}
            className="flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-blue-600 text-[15px] font-bold text-white transition hover:bg-blue-700 disabled:opacity-60"
          >
            {loading ? <Loader2 className="h-5 w-5 animate-spin" /> : null}
            Войти
          </button>

          <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
            <Link href="/forgot-password" className="font-medium text-blue-600 hover:text-blue-700">Забыли пароль?</Link>
            <span className="text-[#94a3b8]">Нет входа — спросите у владельца</span>
          </div>
        </form>
      </main>
    </div>
  )
}
