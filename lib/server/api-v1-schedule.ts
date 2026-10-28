import 'server-only'

import { getOperatorDisplayName } from '@/lib/core/operator-name'
import { createAdminSupabaseClient } from '@/lib/server/supabase'

// График смен для открытого API. Работает строго в пределах точки ключа.
// Наружу отдаём только график: дата, день/ночь, кто. Денежные колонки таблицы
// shifts (cash_amount и др., исторические) не читаем и не меняем.

type Db = ReturnType<typeof createAdminSupabaseClient>
export type ShiftKind = 'day' | 'night'

export class ScheduleError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
  }
}

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

export function parseIsoDate(value: unknown, field: string) {
  const text = String(value || '')
  if (!ISO_DATE.test(text) || Number.isNaN(Date.parse(`${text}T00:00:00Z`))) {
    throw new ScheduleError(400, 'bad_request', `${field}: нужна дата в формате ГГГГ-ММ-ДД`)
  }
  return text
}

/** Понедельник недели, в которую попадает дата. */
export function mondayOf(isoDate: string) {
  const d = new Date(`${isoDate}T00:00:00Z`)
  const shift = (d.getUTCDay() + 6) % 7
  d.setUTCDate(d.getUTCDate() - shift)
  return d.toISOString().slice(0, 10)
}

export function addDays(isoDate: string, days: number) {
  const d = new Date(`${isoDate}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/** Сегодня по Алматы (UTC+5). */
export function todayLocal() {
  return new Date(Date.now() + 5 * 3600_000).toISOString().slice(0, 10)
}

/** Понедельник следующей недели — раньше этой даты API график не трогает. */
export function firstOpenDate() {
  return addDays(mondayOf(todayLocal()), 7)
}

/**
 * Текущую неделю ведут люди на месте: через API её не меняют и не публикуют,
 * программа планирует только со следующей недели.
 */
export function assertOpenWeek(isoDate: string) {
  const open = firstOpenDate()
  if (isoDate < open) {
    throw new ScheduleError(422, 'week_locked', `Текущую и прошлые недели через API не меняют — можно с ${open}`)
  }
}

type PointOperator = { id: string; name: string; role: string | null; telegramConnected: boolean }

/** Активные операторы, закреплённые за точкой. */
export async function listPointOperators(db: Db, companyId: string): Promise<PointOperator[]> {
  const { data: assignments, error } = await db
    .from('operator_company_assignments')
    .select('operator_id, role_in_company')
    .eq('company_id', companyId)
    .eq('is_active', true)
  if (error) throw error

  const roleById = new Map<string, string | null>()
  for (const row of assignments || []) roleById.set(String(row.operator_id), row.role_in_company || null)
  if (roleById.size === 0) return []

  const { data: operators, error: opError } = await db
    .from('operators')
    .select('id, name, short_name, telegram_chat_id, operator_profiles(full_name)')
    .in('id', [...roleById.keys()])
    .eq('is_active', true)
  if (opError) throw opError

  return (operators || [])
    .map((op: any) => ({
      id: String(op.id),
      name: getOperatorDisplayName(op, 'Оператор'),
      role: roleById.get(String(op.id)) || null,
      telegramConnected: !!op.telegram_chat_id,
    }))
    .sort((a, b) => a.name.localeCompare(b.name, 'ru'))
}

function toApiShift(row: any) {
  return {
    id: String(row.id),
    date: String(row.date),
    shift: row.shift_type as ShiftKind,
    operatorId: row.operator_id ? String(row.operator_id) : null,
    operatorName: String(row.operator_name || ''),
    comment: row.comment || null,
    // manual — поставил человек, через API не изменить; api — поставил ключ.
    source: row.api_key_id ? 'api' : 'manual',
  }
}

export async function getWeek(db: Db, companyId: string, weekStart: string) {
  const weekEnd = addDays(weekStart, 6)
  const { data, error } = await db
    .from('shifts')
    .select('id, date, shift_type, operator_id, operator_name, comment, api_key_id')
    .eq('company_id', companyId)
    .gte('date', weekStart)
    .lte('date', weekEnd)
    .order('date')
    .order('shift_type')
  if (error) throw error

  const { data: publication } = await db
    .from('shift_week_publications')
    .select('version, published_at')
    .eq('company_id', companyId)
    .eq('week_start', weekStart)
    .order('version', { ascending: false })
    .limit(1)
    .maybeSingle()

  return {
    weekStart,
    weekEnd,
    published: publication ? { version: publication.version, publishedAt: publication.published_at } : null,
    shifts: (data || []).map(toApiShift),
  }
}

/**
 * Смену, поставленную человеком (на сайте, в боте) или другим ключом, API не
 * меняет и не удаляет: менеджер правит график руками, программа — только своё.
 */
function assertOwnShift(row: { api_key_id?: string | null; operator_name?: string | null } | null, apiKeyId: string) {
  if (!row) return
  if (String(row.api_key_id || '') !== apiKeyId) {
    const who = row.operator_name ? ` (${row.operator_name})` : ''
    throw new ScheduleError(409, 'manual_shift', `Эту смену${who} поставили вручную — через API её менять нельзя. Изменить можно на сайте в «Сменах».`)
  }
}

/**
 * Ставит оператора в слот (дата + день/ночь). Слот один на точку: если там уже
 * кто-то стоит — заменяем. Один человек — одна смена в день по всей организации.
 */
export async function setShift(
  db: Db,
  params: {
    companyId: string
    organizationId: string
    apiKeyId: string
    date: string
    shift: ShiftKind
    operatorId: string
    comment?: string | null
  },
) {
  assertOpenWeek(params.date)
  const operators = await listPointOperators(db, params.companyId)
  const operator = operators.find((op) => op.id === params.operatorId)
  if (!operator) {
    throw new ScheduleError(422, 'unknown_operator', 'Такого оператора нет на этой точке — список: GET /api/v1/schedule/operators')
  }

  const { data: slot, error: slotError } = await db
    .from('shifts')
    .select('id, api_key_id, operator_name')
    .eq('company_id', params.companyId)
    .eq('date', params.date)
    .eq('shift_type', params.shift)
    .maybeSingle()
  if (slotError) throw slotError
  assertOwnShift(slot, params.apiKeyId)

  const { data: orgCompanies } = await db.from('companies').select('id').eq('organization_id', params.organizationId)
  const companyIds = (orgCompanies || []).map((c: any) => String(c.id))
  const { data: sameDay, error: conflictError } = await db
    .from('shifts')
    .select('id, operator_id, operator_name')
    .eq('date', params.date)
    .in('company_id', companyIds.length ? companyIds : [params.companyId])
  if (conflictError) throw conflictError
  const targetName = operator.name.trim().toLowerCase()
  const conflict = (sameDay || []).find((row: any) =>
    row.id !== slot?.id &&
    (String(row.operator_id || '') === operator.id || String(row.operator_name || '').trim().toLowerCase() === targetName),
  )
  if (conflict) {
    throw new ScheduleError(409, 'operator_busy', `${operator.name} уже стоит на ${params.date}. Сначала уберите ту смену.`)
  }

  const fields = {
    operator_id: operator.id,
    operator_name: operator.name,
    comment: params.comment?.trim() || null,
    api_key_id: params.apiKeyId,
  }
  if (slot?.id) {
    const { data, error } = await db
      .from('shifts')
      .update(fields)
      .eq('id', slot.id)
      .eq('company_id', params.companyId)
      .select('id, date, shift_type, operator_id, operator_name, comment, api_key_id')
      .single()
    if (error) throw error
    return { mode: 'updated' as const, shift: toApiShift(data) }
  }

  const { data, error } = await db
    .from('shifts')
    .insert({
      company_id: params.companyId,
      date: params.date,
      shift_type: params.shift,
      ...fields,
      cash_amount: 0,
      kaspi_amount: 0,
      card_amount: 0,
      debt_amount: 0,
    })
    .select('id, date, shift_type, operator_id, operator_name, comment, api_key_id')
    .single()
  if (error) throw error
  return { mode: 'created' as const, shift: toApiShift(data) }
}

export async function deleteShift(db: Db, companyId: string, apiKeyId: string, shiftId: string) {
  const { data: row, error } = await db
    .from('shifts')
    .select('id, company_id, api_key_id, date, shift_type, operator_name, cash_amount, kaspi_amount, card_amount, debt_amount, cashless_amount')
    .eq('id', shiftId)
    .maybeSingle()
  if (error) throw error
  if (!row || String(row.company_id) !== companyId) {
    throw new ScheduleError(404, 'not_found', 'Смена не найдена')
  }
  assertOpenWeek(String(row.date))
  assertOwnShift(row, apiKeyId)
  // Старые смены могли нести суммы выручки — такие API не удаляет.
  const money = ['cash_amount', 'kaspi_amount', 'card_amount', 'debt_amount', 'cashless_amount']
    .reduce((sum, key) => sum + Math.abs(Number((row as any)[key]) || 0), 0)
  if (money > 0) {
    throw new ScheduleError(409, 'has_money', 'У этой смены есть суммы выручки — удалить можно только на сайте')
  }

  const { error: deleteError } = await db.from('shifts').delete().eq('id', shiftId).eq('company_id', companyId)
  if (deleteError) throw deleteError
  return { id: shiftId, date: String(row.date), shift: row.shift_type as ShiftKind, operatorName: String(row.operator_name || '') }
}

/** Ошибку графика — в ответ API; остальное — 500 без подробностей наружу. */
export function scheduleErrorResponse(error: unknown, v1Error: (status: number, code: string, message: string) => Response) {
  if (error instanceof ScheduleError) return v1Error(error.status, error.code, error.message)
  console.error('[api/v1/schedule]', error)
  return v1Error(500, 'internal', 'Внутренняя ошибка, попробуйте позже')
}
