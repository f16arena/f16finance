import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

// Платформа → «База данных»: обзор базы и список таблиц (только суперадмин, только чтение).
// Цифры считают функции platform_db_* (миграция 20261022_platform_db_browser).

export const dynamic = 'force-dynamic'

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  if (!hasAdminSupabaseCredentials()) return NextResponse.json({ error: 'нет доступа к базе' }, { status: 503 })

  const supabase = createAdminSupabaseClient()
  const [overview, tables] = await Promise.all([
    supabase.rpc('platform_db_overview'),
    supabase.rpc('platform_db_tables'),
  ])
  if (overview.error || tables.error) {
    return NextResponse.json({ error: (overview.error || tables.error)?.message }, { status: 500 })
  }
  return NextResponse.json({ overview: overview.data, tables: tables.data })
}
