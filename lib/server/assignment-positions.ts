import 'server-only'

import type { SalaryOperatorCompanyAssignment } from '@/lib/domain/salary'

/**
 * Дописывает к назначениям доплату своей должности (point_positions.shift_bonus).
 *
 * Отдельным запросом, а не join в основном select: пока миграция
 * 20261024_point_positions не применена, колонки position_id нет, и join уронил
 * бы весь расчёт зарплаты. Здесь любая ошибка значит «должностей нет» —
 * зарплата считается как раньше, по базовой роли.
 */
export async function attachPositionBonuses<T extends SalaryOperatorCompanyAssignment>(
  supabase: any,
  assignments: T[],
): Promise<T[]> {
  if (assignments.length === 0) return assignments
  const operatorIds = Array.from(new Set(assignments.map((row) => String(row.operator_id))))

  const { data: links, error: linksError } = await supabase
    .from('operator_company_assignments')
    .select('operator_id, company_id, position_id')
    .in('operator_id', operatorIds)
    .not('position_id', 'is', null)
  if (linksError || !links?.length) return assignments

  const positionIds = Array.from(new Set((links as any[]).map((row) => String(row.position_id))))
  const { data: positions, error: positionsError } = await supabase
    .from('point_positions')
    .select('id, shift_bonus, is_active')
    .in('id', positionIds)
  if (positionsError || !positions?.length) return assignments

  // Должность в архиве больше не платит — человек считается по базовой роли.
  const bonusByPosition = new Map(
    (positions as any[]).filter((row) => row.is_active !== false).map((row) => [String(row.id), Number(row.shift_bonus || 0)]),
  )
  const bonusByAssignment = new Map<string, number>()
  for (const link of links as any[]) {
    const bonus = bonusByPosition.get(String(link.position_id))
    if (bonus != null) bonusByAssignment.set(`${link.operator_id}_${link.company_id}`, bonus)
  }

  return assignments.map((row) => {
    const bonus = bonusByAssignment.get(`${row.operator_id}_${row.company_id}`)
    return bonus == null ? row : { ...row, position_bonus: bonus }
  })
}

let positionColumnKnown = false

/** Есть ли уже колонка position_id (миграция применена). Положительный ответ кэшируется. */
export async function hasAssignmentPositionColumn(supabase: any) {
  if (positionColumnKnown) return true
  const { error } = await supabase.from('operator_company_assignments').select('position_id').limit(1)
  if (!error) positionColumnKnown = true
  return !error
}

export type AssignmentPosition = {
  id: string
  company_id: string | null
  base_role: SalaryOperatorCompanyAssignment['role_in_company']
  name: string
  is_active: boolean
}

/**
 * Должности для назначений — только своей организации. Возвращает null, если
 * какой-то нет: назначение тогда не сохраняется. Архивные тоже возвращаются —
 * их можно оставить на прежнем назначении, но не выдать заново (решает вызывающий).
 */
export async function loadAssignablePositions(supabase: any, organizationId: string | null, ids: string[]) {
  const unique = Array.from(new Set(ids.filter(Boolean)))
  if (unique.length === 0) return new Map<string, AssignmentPosition>()
  if (!organizationId) return null
  const { data, error } = await supabase
    .from('point_positions')
    .select('id, company_id, base_role, name, is_active')
    .in('id', unique)
    .eq('organization_id', organizationId)
  if (error) throw error
  const map = new Map<string, AssignmentPosition>(((data || []) as any[]).map((row) => [String(row.id), row as AssignmentPosition]))
  return map.size === unique.length ? map : null
}
