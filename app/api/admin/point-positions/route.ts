import { NextResponse } from 'next/server'

import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import { requireCapability } from '@/lib/server/capabilities'
import { resolveCompanyScope } from '@/lib/server/organizations'
import { getRequestAccessContext } from '@/lib/server/request-auth'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

type BaseRole = 'operator' | 'senior_operator' | 'senior_cashier'
const BASE_ROLES: BaseRole[] = ['operator', 'senior_operator', 'senior_cashier']

type PositionPayload = {
  name?: string
  company_id?: string | null
  base_role?: string
  shift_bonus?: number | string | null
}

type Body =
  | { action: 'create'; payload: PositionPayload }
  | { action: 'update'; id: string; payload: PositionPayload }
  | { action: 'archive' | 'restore'; id: string }

const SELECT = 'id, organization_id, company_id, name, base_role, shift_bonus, is_active, sort_order, created_at'

function json(data: unknown, status = 200) {
  return NextResponse.json(data, { status })
}

// Миграция 20261024_point_positions не применена — должностей просто нет.
function isMissingTable(error: any) {
  const message = String(error?.message || '')
  return error?.code === '42P01' || error?.code === 'PGRST205' || (/point_positions/.test(message) && /(does not exist|Could not find)/i.test(message))
}

function normalizePayload(payload: PositionPayload) {
  const name = String(payload.name || '').trim().replace(/\s+/g, ' ')
  const baseRole = BASE_ROLES.includes(payload.base_role as BaseRole) ? (payload.base_role as BaseRole) : 'operator'
  const bonus = Number(payload.shift_bonus ?? 0)
  return {
    name,
    company_id: payload.company_id ? String(payload.company_id) : null,
    base_role: baseRole,
    shift_bonus: Number.isFinite(bonus) ? Math.round(bonus) : NaN,
  }
}

async function getContext(request: Request) {
  const access = await getRequestAccessContext(request)
  if ('response' in access) return access
  if (!access.isSuperAdmin && !access.staffMember) return { response: json({ error: 'forbidden' }, 403) }
  const organizationId = access.activeOrganization?.id || null
  if (!organizationId) return { response: json({ error: 'Нет активной организации' }, 400) }
  const supabase = hasAdminSupabaseCredentials() ? createAdminSupabaseClient() : access.supabase
  const scope = await resolveCompanyScope({
    activeOrganizationId: organizationId,
    isSuperAdmin: access.isSuperAdmin,
    companyRestriction: access.companyRestriction,
  })
  return { access, organizationId, supabase, allowedCompanyIds: scope.allowedCompanyIds }
}

/** Видна ли должность: общая (на все точки) или на точке из охвата сотрудника. */
function visible(row: any, allowedCompanyIds: string[] | null) {
  return !row.company_id || !allowedCompanyIds || allowedCompanyIds.includes(String(row.company_id))
}

export async function GET(request: Request) {
  try {
    const ctx = await getContext(request)
    if ('response' in ctx) return ctx.response

    const { data, error } = await ctx.supabase
      .from('point_positions')
      .select(SELECT)
      .eq('organization_id', ctx.organizationId)
      .order('sort_order', { ascending: true })
      .order('name', { ascending: true })
    if (error) {
      if (isMissingTable(error)) return json({ ok: true, data: [], migrationPending: true })
      throw error
    }

    return json({ ok: true, data: ((data || []) as any[]).filter((row) => visible(row, ctx.allowedCompanyIds)) })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'api/admin/point-positions GET', message: error?.message || 'error' })
    return json({ error: error?.message || 'Ошибка сервера' }, 500)
  }
}

export async function POST(request: Request) {
  try {
    const ctx = await getContext(request)
    if ('response' in ctx) return ctx.response
    // Должность — это доплата к зарплате: то же право, что и правка правил ЗП.
    const denied = await requireCapability(ctx.access, 'salary-rules.edit')
    if (denied) return denied

    const body = (await request.json().catch(() => null)) as Body | null
    if (!body?.action) return json({ error: 'Неверный формат запроса' }, 400)
    const actorUserId = ctx.access.user?.id || null

    // Существующая должность — только своей организации и своего охвата.
    let existing: any = null
    if (body.action !== 'create') {
      const { data, error } = await ctx.supabase
        .from('point_positions')
        .select(SELECT)
        .eq('id', body.id)
        .eq('organization_id', ctx.organizationId)
        .maybeSingle()
      if (error) throw error
      if (!data || !visible(data, ctx.allowedCompanyIds)) return json({ error: 'Должность не найдена' }, 404)
      existing = data
    }

    if (body.action === 'archive' || body.action === 'restore') {
      const { data, error } = await ctx.supabase
        .from('point_positions')
        .update({ is_active: body.action === 'restore', updated_at: new Date().toISOString() })
        .eq('id', existing.id)
        .eq('organization_id', ctx.organizationId)
        .select(SELECT)
        .single()
      if (error) {
        if (error.code === '23505') return json({ error: 'Активная должность с таким названием уже есть' }, 400)
        throw error
      }
      await writeAuditLog(ctx.supabase, {
        actorUserId,
        entityType: 'point-position',
        entityId: String(existing.id),
        action: body.action,
        payload: { name: existing.name },
      })
      return json({ ok: true, data })
    }

    const payload = normalizePayload(('payload' in body ? body.payload : null) || {})
    if (!payload.name) return json({ error: 'Укажите название должности' }, 400)
    if (payload.name.length > 60) return json({ error: 'Название — до 60 символов' }, 400)
    if (!Number.isFinite(payload.shift_bonus) || payload.shift_bonus < 0) {
      return json({ error: 'Доплата за смену — 0 или больше' }, 400)
    }

    if (payload.company_id) {
      // Бросает, если точка не своей организации или вне охвата сотрудника.
      await resolveCompanyScope({
        activeOrganizationId: ctx.organizationId,
        isSuperAdmin: ctx.access.isSuperAdmin,
        companyRestriction: ctx.access.companyRestriction,
        requestedCompanyId: payload.company_id,
      })
    } else if (ctx.access.companyRestriction) {
      // Общая должность действует на всех точках — включая чужие для управляющего части точек.
      return json({ error: 'Должность на все точки может завести только тот, кто видит все точки' }, 403)
    }

    const row = {
      organization_id: ctx.organizationId,
      company_id: payload.company_id,
      name: payload.name,
      base_role: payload.base_role,
      shift_bonus: payload.shift_bonus,
      updated_at: new Date().toISOString(),
    }
    const query =
      body.action === 'create'
        ? ctx.supabase.from('point_positions').insert([row])
        : ctx.supabase.from('point_positions').update(row).eq('id', existing.id).eq('organization_id', ctx.organizationId)
    const { data, error } = await query.select(SELECT).single()
    if (error) {
      if (isMissingTable(error)) return json({ error: 'Должности ещё не включены: нужна миграция 20261024_point_positions' }, 409)
      if (error.code === '23505') return json({ error: 'Должность с таким названием уже есть' }, 400)
      throw error
    }

    await writeAuditLog(ctx.supabase, {
      actorUserId,
      entityType: 'point-position',
      entityId: String(data.id),
      action: body.action,
      payload: { name: data.name, company_id: data.company_id, base_role: data.base_role, shift_bonus: data.shift_bonus },
    })
    return json({ ok: true, data })
  } catch (error: any) {
    if (error?.message === 'company-out-of-scope') {
      return json({ error: 'forbidden-company' }, 403)
    }
    await writeSystemErrorLogSafe({ scope: 'server', area: 'api/admin/point-positions POST', message: error?.message || 'error' })
    return json({ error: error?.message || 'Ошибка сервера' }, 500)
  }
}
