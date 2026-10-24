import 'server-only'

import { getIndustry } from '@/lib/core/industries'
import { officePositionLabel } from '@/lib/core/positions'
import { getOrgSettings } from '@/lib/server/org-settings'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

/**
 * «Паспорт бизнеса» для ИИ: чем занимается организация, какие у неё точки и кто
 * на какой должности. Раньше подсказки начинались с «ты аналитик игрового
 * клуба» — у кофейни или салона ИИ отвечал про чужой бизнес, а «владельцем»
 * считал того, кто спрашивает. Владелец — тот, у кого должность «Владелец».
 *
 * Только справочные факты, без денег: цифры ИИ получает отдельно, в DATA.
 */
export async function buildAiBusinessContext(organizationId: string | null | undefined): Promise<string> {
  if (!organizationId || !hasAdminSupabaseCredentials()) return ''
  const supabase = createAdminSupabaseClient()
  const [orgRes, companiesRes, ownersRes, settings] = await Promise.all([
    supabase.from('organizations').select('name').eq('id', organizationId).maybeSingle(),
    supabase.from('companies').select('name, industry').eq('organization_id', organizationId).is('archived_at', null).order('name'),
    supabase.from('staff').select('full_name, role').eq('organization_id', organizationId).eq('is_active', true).eq('role', 'owner'),
    getOrgSettings(organizationId),
  ])

  const orgName = String((orgRes.data as any)?.name || '').trim()
  const points = ((companiesRes.data || []) as any[]).map((c) => {
    const kind = getIndustry(c.industry)?.label
    return kind ? `${c.name} (${kind.toLowerCase()})` : String(c.name)
  })
  const ownerTitle = officePositionLabel('owner', settings.positionLabels.owner)
  const owners = ((ownersRes.data || []) as any[]).map((s) => String(s.full_name || '').trim()).filter(Boolean)

  const lines = [
    orgName ? `Организация: ${orgName}.` : '',
    points.length ? `Точки: ${points.join(', ')}.` : '',
    owners.length ? `${ownerTitle}: ${owners.join(', ')} — это должность, а не тот, кто сейчас спрашивает.` : '',
  ].filter(Boolean)
  if (lines.length === 0) return ''
  return `О БИЗНЕСЕ (справка, не данные для расчётов):\n${lines.join('\n')}\nНе считай бизнес игровым клубом, если точек такого типа нет.`
}

/** Как обращаться к спрашивающему: по его должности, а не «владелец» всем подряд. */
export function aiAudienceLine(params: { staffRole?: string | null; positionLabel?: string | null; isSuperAdmin?: boolean }) {
  if (params.isSuperAdmin && !params.staffRole) return 'Спрашивает администратор платформы.'
  if (!params.staffRole) return ''
  return `Спрашивает сотрудник с должностью «${params.positionLabel || officePositionLabel(params.staffRole)}».`
}
