import { readFile } from 'node:fs/promises'
import path from 'node:path'

import { NextResponse } from 'next/server'

import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

export const dynamic = 'force-dynamic'

// Публичная проверка «сайт живой и видит базу» — для deploy.sh (проверка после
// выкладки, откат при сбое) и внешнего сторожа. Отдаёт только статус и версию сборки.
async function buildId() {
  if (process.env.GITHUB_SHA) return process.env.GITHUB_SHA
  try {
    return (await readFile(path.join(process.cwd(), '.next', 'BUILD_ID'), 'utf8')).trim()
  } catch {
    return null
  }
}

export async function GET() {
  const started = Date.now()
  let db = false
  if (hasAdminSupabaseCredentials()) {
    const { error } = await createAdminSupabaseClient()
      .from('organizations')
      .select('id', { head: true, count: 'exact' })
      .limit(1)
    db = !error
  }
  const body = { ok: db, db, build: await buildId(), ms: Date.now() - started }
  return NextResponse.json(body, { status: db ? 200 : 503, headers: { 'Cache-Control': 'no-store' } })
}
