import { NextResponse } from 'next/server'

import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

// Строки одной таблицы для просмотра в /platform (только суперадмин, только чтение):
// страницы по 50, сортировка по колонке, фильтр «колонка содержит / равно».

export const dynamic = 'force-dynamic'

const PAGE = 50

export async function GET(req: Request) {
  const access = await getRequestAccessContext(req)
  if ('response' in access) return access.response
  if (!access.isSuperAdmin) return NextResponse.json({ error: 'forbidden' }, { status: 403 })
  if (!hasAdminSupabaseCredentials()) return NextResponse.json({ error: 'нет доступа к базе' }, { status: 503 })

  const url = new URL(req.url)
  const name = url.searchParams.get('name') || ''
  const page = Math.max(0, Number(url.searchParams.get('page')) || 0)
  const sort = url.searchParams.get('sort') || ''
  const dir = url.searchParams.get('dir') === 'asc'
  const col = url.searchParams.get('col') || ''
  const q = (url.searchParams.get('q') || '').trim()

  const supabase = createAdminSupabaseClient()
  // Имя таблицы и колонок — только из схемы, не из запроса как есть.
  const { data: columns, error: colError } = await supabase.rpc('platform_db_columns', { p_table: name })
  if (colError) return NextResponse.json({ error: colError.message }, { status: 500 })
  const cols = (columns || []) as Array<{ name: string; type: string; pk: boolean }>
  if (!cols.length) return NextResponse.json({ error: 'Таблица не найдена' }, { status: 404 })
  const colNames = new Set(cols.map((c) => c.name))

  let query: any = supabase.from(name).select('*', { count: 'estimated' })
  if (col && q && colNames.has(col)) {
    const type = cols.find((c) => c.name === col)!.type
    if (/char|text/.test(type)) query = query.ilike(col, `%${q}%`)
    else if (/json/.test(type)) return NextResponse.json({ error: 'Фильтр по JSON-колонке не поддерживается — выберите другую колонку', columns: cols }, { status: 400 })
    else query = query.eq(col, q)
  }
  const sortCol = colNames.has(sort) ? sort : (['created_at', 'updated_at'].find((c) => colNames.has(c)) || cols.find((c) => c.pk)?.name || cols[0].name)
  const sortAsc = colNames.has(sort) ? dir : false
  query = query.order(sortCol, { ascending: sortAsc, nullsFirst: false }).range(page * PAGE, page * PAGE + PAGE - 1)

  const { data, error, count } = await query
  if (error) return NextResponse.json({ error: error.message, columns: cols }, { status: 400 })
  return NextResponse.json({ columns: cols, rows: data || [], count: count ?? null, page, pageSize: PAGE, sort: sortCol, asc: sortAsc })
}
