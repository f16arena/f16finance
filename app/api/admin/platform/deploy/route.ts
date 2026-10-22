import { readFile, rename, writeFile } from 'node:fs/promises'

import { NextResponse } from 'next/server'

import { writeAuditLog } from '@/lib/server/audit'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { deploys } from '@/lib/server/host-status'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

// Кнопки деплоя на /platform/status (только суперадмин): пересобрать, откатить на
// прошлую версию, вернуть автодеплой. Заявку кладём в /control (это /opt/orda/control
// на сервере), её забирает deploy.sh в течение минуты.

export const dynamic = 'force-dynamic'

const CONTROL = '/control'
// Откат — только на версии, где эти кнопки уже есть: в более старых нет ни папки
// /control, ни кнопки «Вернуть автодеплой» (выход остаётся только с сервера).
const ROLLBACK_SINCE = 1792679000

async function readPin() {
  try { return (await readFile(`${CONTROL}/pin`, 'utf-8')).trim() || null } catch { return null }
}
async function readRequest() {
  try { return JSON.parse(await readFile(`${CONTROL}/request.json`, 'utf-8')) } catch { return null }
}

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  return NextResponse.json({ pin: await readPin(), pending: await readRequest(), rollback_since: ROLLBACK_SINCE })
}

export async function POST(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

  const body = (await req.json().catch(() => null)) as { action?: string; sha?: string } | null
  const action = String(body?.action || '')
  if (!['redeploy', 'rollback', 'unpin'].includes(action)) return NextResponse.json({ error: 'Неизвестное действие' }, { status: 400 })
  if (await readRequest()) return NextResponse.json({ error: 'Предыдущая заявка ещё не взята в работу — подождите минуту' }, { status: 409 })

  let sha: string | undefined
  if (action === 'rollback') {
    sha = String(body?.sha || '').trim()
    // Откатывать можно только на версию из журнала деплоев, которая собралась успешно.
    const target = (await deploys()).find((d) => d.status === 'ok' && sha && (d.sha_full === sha || (d.sha && sha.startsWith(d.sha))))
    if (!target?.sha_full) return NextResponse.json({ error: 'Эта версия не найдена среди успешных деплоев' }, { status: 400 })
    if (target.start < ROLLBACK_SINCE) return NextResponse.json({ error: 'На версии до появления кнопок откатиться отсюда нельзя' }, { status: 400 })
    sha = target.sha_full
  }

  try {
    const tmp = `${CONTROL}/request.json.tmp`
    await writeFile(tmp, JSON.stringify({ action, sha, by: access.user?.email || access.user?.id || null, at: new Date().toISOString() }))
    await rename(tmp, `${CONTROL}/request.json`)
  } catch (e: any) {
    return NextResponse.json({ error: 'Не удалось записать заявку: ' + (e?.message || e) }, { status: 500 })
  }

  await writeAuditLog(createAdminSupabaseClient(), {
    actorUserId: access.user?.id || null,
    entityType: 'platform-deploy',
    entityId: sha || action,
    action,
    payload: { sha: sha || null },
  }).catch(() => null)

  return NextResponse.json({ ok: true })
}
