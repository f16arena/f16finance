import 'server-only'

import { companyCodeBase, escapeLikePattern, pickFreeCompanyCode } from '@/lib/core/company-code'
import { INDUSTRY_CODES } from '@/lib/core/industries'
import { writeAuditLog } from '@/lib/server/audit'
import { resolveCompanyLimit } from '@/lib/server/entitlements'

export type CreateCompanyInput = {
  name: string
  code?: string | null
  showInStructure?: boolean | null
  excludeFromTotals?: boolean | null
  industry?: string | null
  address?: string | null
}

export type CreateCompanyResult =
  | { ok: true; company: { id: string; name: string; code: string } }
  | { ok: false; status: number; error: string; code?: string }

/**
 * Код точки уникален на всю базу (companies_code_key), а не по организации:
 * у второго клиента «ARENA» уже занят. Подбираем свободный с суффиксом.
 * Сравнение без регистра: старые коды лежат строчными (arena), индекс их не
 * сравнивает с ARENA, а правила зарплаты сводят коды к нижнему регистру.
 */
async function pickFreeCode(supabase: any, base: string) {
  const { data, error } = await supabase.from('companies').select('code').ilike('code', `${base}%`)
  if (error) throw error
  return pickFreeCompanyCode(
    base,
    ((data || []) as any[]).map((row) => row.code),
    Date.now().toString(36).toUpperCase().slice(-5),
  )
}

/** Сколько точек занимает лимит: закрытые (archived_at) — история, место не держат. */
export async function countOpenCompanies(supabase: any, organizationId: string) {
  const { count, error } = await supabase
    .from('companies')
    .select('id', { count: 'exact', head: true })
    .eq('organization_id', organizationId)
    .is('archived_at', null)
  if (error) throw error
  return count ?? 0
}

/**
 * Единственный путь создания точки: лимит по тарифу, свободный код, аудит и
 * доступ создателю. Раньше было два роута, и второй (/api/admin/companies)
 * лимит точек не проверял вовсе.
 */
export async function createCompanyForOrganization(
  supabase: any,
  access: {
    activeOrganization?: { id: string } | null
    isSuperAdmin: boolean
    companyRestriction: string[] | null
    staffMember?: { id?: string | null } | null
    user?: { id?: string | null } | null
  },
  input: CreateCompanyInput,
): Promise<CreateCompanyResult> {
  const name = String(input.name || '').trim()
  if (!name) return { ok: false, status: 400, error: 'Название точки обязательно' }

  const organizationId = access.activeOrganization?.id || null
  // Без организации точка «ничья»: её не видит ни один орг-скоуп.
  if (!organizationId && !access.isSuperAdmin) {
    return { ok: false, status: 400, error: 'Требуется активная организация' }
  }

  if (organizationId) {
    const [{ count: currentCount }, limit] = await Promise.all([
      countOpenCompanies(supabase, organizationId),
      resolveCompanyLimit(organizationId),
    ])
    if (currentCount >= limit) {
      return {
        ok: false,
        status: 402,
        code: 'company-limit-reached',
        error: `Лимит точек исчерпан (${currentCount}/${limit}). Закройте ненужную точку или докупите ещё одну.`,
      }
    }
  }

  const requestedCode = String(input.code || '').trim().toUpperCase().replace(/[^A-Z0-9_-]/g, '')
  if (requestedCode) {
    const { data: clash, error: clashError } = await supabase.from('companies').select('id').ilike('code', escapeLikePattern(requestedCode)).limit(1)
    if (clashError) throw clashError
    if (clash?.length) {
      return { ok: false, status: 400, error: `Код ${requestedCode} уже занят — оставьте поле пустым, подберём сами` }
    }
  }
  const code = requestedCode || (await pickFreeCode(supabase, companyCodeBase(name)))
  const industry = INDUSTRY_CODES.includes(input.industry as (typeof INDUSTRY_CODES)[number]) ? input.industry : null

  const { data, error } = await supabase
    .from('companies')
    .insert([
      {
        name,
        code,
        show_in_structure: input.showInStructure !== false,
        exclude_from_totals: input.excludeFromTotals === true,
        organization_id: organizationId,
        ...(industry ? { industry } : {}),
        ...(input.address?.trim() ? { address: input.address.trim() } : {}),
      },
    ])
    .select('id, name, code')
    .single()

  if (error) {
    if (error.code === '23505' && requestedCode) {
      return { ok: false, status: 400, error: `Код ${requestedCode} уже занят — оставьте поле пустым, подберём сами` }
    }
    throw error
  }

  // Управляющий части точек иначе не увидит точку, которую сам же создал.
  if (access.companyRestriction && access.staffMember?.id) {
    const nextIds = Array.from(new Set([...access.companyRestriction, String(data.id)]))
    const { error: scopeError } = await supabase
      .from('staff')
      .update({ company_ids: nextIds })
      .eq('id', access.staffMember.id)
    if (scopeError) throw scopeError
  }

  await writeAuditLog(supabase, {
    actorUserId: access.user?.id || null,
    entityType: 'company',
    entityId: String(data.id),
    action: 'create',
    payload: { name: data.name, code: data.code, industry, show_in_structure: input.showInStructure !== false },
  })

  return { ok: true, company: { id: String(data.id), name: String(data.name || ''), code: String(data.code || '') } }
}
