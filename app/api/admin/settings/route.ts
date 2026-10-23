import { NextResponse } from 'next/server'

import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import { requireCapability, requireStaffCapability, isOwnerActor, canAssignStaffRole } from '@/lib/server/capabilities'
import { createRequestSupabaseClient, getRequestAccessContext } from '@/lib/server/request-auth'
import { resolveCompanyLimit } from '@/lib/server/entitlements'
import { listOrganizationStaffIds } from '@/lib/server/organizations'
import { createCompanyForOrganization } from '@/lib/server/company-create'
import {
  collectCompanyLeftovers,
  companyNameMatches,
  countCompanyHistory,
  describeCompanyHistory,
  removeCompanyLeftovers,
} from '@/lib/server/company-delete'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

type MutationBody =
  | {
      entity: 'company'
      action: 'create'
      payload: { name: string; code?: string | null; show_in_structure?: boolean | null; exclude_from_totals?: boolean | null }
    }
  | {
      entity: 'company'
      action: 'update'
      id: string
      payload: { name: string; code?: string | null; show_in_structure?: boolean | null; exclude_from_totals?: boolean | null }
    }
  | {
      entity: 'company'
      action: 'delete' | 'deletePreview'
      id: string
      /** Название точки, набранное руками, — без него удаление не проходит. */
      confirmName?: string
    }
  | {
      entity: 'staff'
      action: 'create'
      payload: { name: string; phone?: string | null; email?: string | null; role?: string | null }
    }
  | {
      entity: 'staff'
      action: 'update'
      id: string
      payload: { name: string; phone?: string | null; email?: string | null; role?: string | null }
    }
  | {
      entity: 'staff'
      action: 'delete'
      id: string
    }
  | {
      entity: 'expense_category'
      action: 'create'
      payload: { name: string; monthly_budget?: number | null; accounting_group?: string | null }
    }
  | {
      entity: 'expense_category'
      action: 'update'
      id: string
      payload: { name: string; monthly_budget?: number | null; accounting_group?: string | null }
    }
  | {
      entity: 'expense_category'
      action: 'delete'
      id: string
    }

function badRequest(message: string) {
  return NextResponse.json({ error: message }, { status: 400 })
}

function getSupabase(req: Request) {
  return hasAdminSupabaseCredentials()
    ? createAdminSupabaseClient()
    : createRequestSupabaseClient(req)
}

export async function GET(req: Request) {
  try {
    const access = await getRequestAccessContext(req)
    if ('response' in access) return access.response
    // Гейта на роль не было вовсе: выдача содержит ФИО, телефоны и почту всей
    // команды тенанта, а сюда проходил любой аутентифицированный субъект той же
    // организации — оператор и клиент гостевого контура в том числе.
    if (!access.isSuperAdmin && !access.staffMember) return NextResponse.json({ error: 'forbidden' }, { status: 403 })

    // И своё право сверху: выдача — это ФИО, телефоны и почта всей команды.
    // «Любой сотрудник» тут слишком широко, а право у сотрудника есть, пока
    // владелец его не снял.
    const denied = await requireStaffCapability(access, 'settings.view')
    if (denied) return denied

    const supabase = getSupabase(req)

    // Изоляция: не-супер-админ видит только компании/команду/категории своей орг.
    // (service-role обходит RLS → фильтруем явно по organization_id.) NEVER-pattern:
    // без валидной орг — пустой uuid → ничего.
    const orgId = access.activeOrganization?.id || null
    const scopeOrg = orgId || (access.isSuperAdmin ? null : '00000000-0000-0000-0000-000000000000')

    let companiesQuery = supabase.from('companies').select('id, name, code, show_in_structure, exclude_from_totals').order('name')
    let staffQuery = supabase.from('staff').select('id, full_name, phone, email, role, is_active').eq('is_active', true).order('full_name')
    let categoriesQuery = supabase.from('expense_categories').select('id, name, monthly_budget, accounting_group').order('name')
    if (scopeOrg) {
      companiesQuery = companiesQuery.eq('organization_id', scopeOrg)
      staffQuery = staffQuery.eq('organization_id', scopeOrg)
      categoriesQuery = categoriesQuery.eq('organization_id', scopeOrg)
    }
    // Охват по точкам: управляющий части точек видит только их и их сотрудников.
    if (access.companyRestriction) {
      companiesQuery = companiesQuery.in('id', access.companyRestriction)
      const visibleStaffIds = await listOrganizationStaffIds({
        activeOrganizationId: orgId,
        isSuperAdmin: access.isSuperAdmin,
        companyRestriction: access.companyRestriction,
      })
      staffQuery = staffQuery.in('id', visibleStaffIds.length ? visibleStaffIds : ['00000000-0000-0000-0000-000000000000'])
    }

    const [companiesRes, staffRes, categoriesRes] = await Promise.allSettled([
      companiesQuery,
      staffQuery,
      categoriesQuery,
    ])

    const companies =
      companiesRes.status === 'fulfilled' && !companiesRes.value.error
        ? companiesRes.value.data || []
        : []
    const staff =
      staffRes.status === 'fulfilled' && !staffRes.value.error
        ? staffRes.value.data || []
        : []
    const categories =
      categoriesRes.status === 'fulfilled' && !categoriesRes.value.error
        ? categoriesRes.value.data || []
        : []

    const readErrors = [
      companiesRes.status === 'fulfilled' ? companiesRes.value.error : companiesRes.reason,
      staffRes.status === 'fulfilled' ? staffRes.value.error : staffRes.reason,
      categoriesRes.status === 'fulfilled' ? categoriesRes.value.error : categoriesRes.reason,
    ].filter(Boolean)

    if (readErrors.length > 0) {
      await writeSystemErrorLogSafe({
        scope: 'server',
        area: 'api/admin/settings GET partial',
        message: readErrors
          .map((entry: any) => entry?.message || String(entry))
          .join(' | '),
      })
    }

    // Лимит точек орг (для баннера «точек N из M» на клиенте). null — без лимита
    // (супер-админ в платформенном контексте). Вычисляется из пакета+аддонов, с
    // ручным company_limit как override/fallback (resolveCompanyLimit).
    let companyLimit: number | null = null
    if (orgId) {
      companyLimit = await resolveCompanyLimit(orgId)
    }

    return NextResponse.json({
      companies,
      staff,
      categories,
      companyLimit,
    })
  } catch (error: any) {
    console.error('Admin settings read error', error)
    await writeSystemErrorLogSafe({
      scope: 'server',
      area: 'api/admin/settings GET',
      message: error?.message || 'Admin settings read error',
    })
    return NextResponse.json({ error: error?.message || 'Ошибка сервера' }, { status: 500 })
  }
}

export async function POST(req: Request) {
  try {
    const access = await getRequestAccessContext(req)
    if ('response' in access) return access.response
    // Доступ к настройкам — для super_admin или любого staff с нужной capability
    // (capability проверяется ниже per-entity)
    if (!access.isSuperAdmin && !access.staffRole) {
      return NextResponse.json({ error: 'forbidden' }, { status: 403 })
    }

    const body = (await req.json().catch(() => null)) as MutationBody | null
    if (!body?.entity || !body?.action) return badRequest('Неверный формат запроса')

    const supabase = getSupabase(req)
    const actorUserId = access.user?.id || null

    // Изоляция: update/delete по присланному id обязаны ограничиваться своей орг,
    // иначе менеджер орг A может изменить/удалить компанию/сотрудника/категорию орг B.
    const orgId = access.activeOrganization?.id || null
    if (!access.isSuperAdmin && !orgId) return badRequest('Нет активной организации')
    // Скоуп по активной орг даже для суперадмина (на субдомене tenant'а). Без фильтра —
    // только когда активной орг нет вовсе (платформенный контекст суперадмина).
    const scopeOrg = <T,>(q: T): T => (orgId ? ((q as any).eq('organization_id', orgId) as T) : q)

    if (body.entity === 'company') {
      // Капабилити-проверка зависит от действия
      const capForCompany =
        body.action === 'delete' || body.action === 'deletePreview' ? 'settings.delete_company' : 'settings.manage_companies'
      const denied = await requireCapability(access, capForCompany)
      if (denied) return denied as any

      if (body.action === 'create') {
        const created = await createCompanyForOrganization(supabase, access, {
          name: body.payload.name,
          code: body.payload.code,
          showInStructure: body.payload.show_in_structure,
          excludeFromTotals: body.payload.exclude_from_totals,
        })
        if (!created.ok) {
          return NextResponse.json({ error: created.error, code: created.code }, { status: created.status })
        }
        return NextResponse.json({ ok: true, company: created.company })
      }

      if (!body.id) return badRequest('id обязателен')
      // Управляющий части точек правит только свои — как и при удалении ниже.
      if (access.companyRestriction && !access.companyRestriction.includes(body.id)) {
        return NextResponse.json({ error: 'forbidden-company' }, { status: 403 })
      }

      if (body.action === 'update') {
        if (!body.payload.name?.trim()) return badRequest('Название компании обязательно')
        const { data, error } = await scopeOrg(
          supabase
            .from('companies')
            .update({
              name: body.payload.name.trim(),
              code: body.payload.code?.trim() || null,
              show_in_structure: body.payload.show_in_structure !== false,
              // Не прислали (старая форма) — не трогаем: иначе сбросили бы флаг F16 Extra.
              ...(typeof body.payload.exclude_from_totals === 'boolean'
                ? { exclude_from_totals: body.payload.exclude_from_totals }
                : {}),
            })
            .eq('id', body.id),
        )
          .select('id,name,code,show_in_structure,exclude_from_totals')
          .single()
        if (error) throw error
        await writeAuditLog(supabase, {
          actorUserId,
          entityType: 'company',
          entityId: String(data.id),
          action: 'update',
          payload: { name: data.name, code: data.code, show_in_structure: data.show_in_structure, exclude_from_totals: data.exclude_from_totals },
        })
        return NextResponse.json({ ok: true })
      }

      // Удаление: только владелец, только пустая точка и только с названием,
      // набранным руками. Каскад по 55 таблицам стирает историю без следа.
      const { data: target, error: targetError } = await scopeOrg(
        supabase.from('companies').select('id, name, code').eq('id', body.id),
      ).maybeSingle()
      if (targetError) throw targetError
      if (!target) return NextResponse.json({ error: 'Точка не найдена' }, { status: 404 })
      if (access.companyRestriction && !access.companyRestriction.includes(String(target.id))) {
        return NextResponse.json({ error: 'forbidden-company' }, { status: 403 })
      }

      const history = await countCompanyHistory(supabase, String(target.id))
      if (body.action === 'deletePreview') {
        return NextResponse.json({
          ok: true,
          company: { id: target.id, name: target.name, code: target.code },
          history: history.items,
          canDelete: !history.hasHistory && isOwnerActor(access),
          ownerOnly: !isOwnerActor(access),
        })
      }

      if (!isOwnerActor(access)) {
        return NextResponse.json({ error: 'Удалить точку может только владелец' }, { status: 403 })
      }
      if (!companyNameMatches(String(target.name || ''), body.confirmName)) {
        return badRequest('Для удаления введите название точки точно так, как оно написано')
      }
      if (history.hasHistory) {
        return NextResponse.json(
          {
            error: `У точки есть история (${describeCompanyHistory(history.items)}) — удалять нельзя, иначе она пропадёт из отчётов.`,
            code: 'company-has-history',
            history: history.items,
          },
          { status: 409 },
        )
      }

      const leftovers = await collectCompanyLeftovers(supabase, { id: String(target.id), code: target.code })
      const { error } = await scopeOrg(supabase.from('companies').delete().eq('id', body.id))
      if (error) throw error
      await removeCompanyLeftovers(supabase, leftovers)
      await writeAuditLog(supabase, {
        actorUserId,
        entityType: 'company',
        entityId: body.id,
        action: 'delete',
        payload: { name: target.name, code: target.code, removed_projects: leftovers.projectIds },
      })
      return NextResponse.json({ ok: true })
    }

    if (body.entity === 'staff') {
      // Создание/правка сотрудника — staff + управление ролями.
      const deniedStaff = await requireStaffCapability(access, 'access.manage_staff_roles')
      if (deniedStaff) return deniedStaff

      // НАЗНАЧЕНИЕ РОЛИ — только владелец (или супер-админ). Иначе менеджер (у него
      // при fail-open есть access.manage_staff_roles) мог бы повысить сотрудника —
      // или себя — до owner прямо здесь, в обход owner-only контура /access.
      // Не-владелец может править ФИО/почту/телефон, но не роль. Безопасные базовые
      // роли ('operator'/'other') назначать можно (это не админ-доступ).
      if (body.action === 'create') {
        if (!body.payload.name?.trim()) return badRequest('Имя сотрудника обязательно')
        const requestedRole = body.payload.role?.trim() || 'operator'
        if (!canAssignStaffRole(access, requestedRole)) {
          return NextResponse.json(
            { error: 'Назначать административную должность может только владелец', reason: 'owner-only-role' },
            { status: 403 },
          )
        }
        const { data, error } = await supabase.from('staff').insert([
          {
            full_name: body.payload.name.trim(),
            phone: body.payload.phone?.trim() || null,
            email: body.payload.email?.trim() || null,
            role: requestedRole,
            organization_id: access.activeOrganization?.id || null,
            // Сотрудник, заведённый управляющим части точек, получает его охват — иначе видел бы всё.
            ...(access.companyRestriction ? { company_ids: access.companyRestriction } : {}),
          },
        ]).select('id,full_name,email,role').single()
        if (error) throw error
        await writeAuditLog(supabase, {
          actorUserId,
          entityType: 'staff',
          entityId: String(data.id),
          action: 'create',
          payload: { full_name: data.full_name, email: data.email, role: data.role },
        })
        return NextResponse.json({ ok: true })
      }

      if (!body.id) return badRequest('id обязателен')

      // Чужого охвата не трогаем: управляющий части точек видит только своих
      // (как в GET), а карточку владельца правит только владелец.
      const { data: targetStaff, error: targetStaffError } = await scopeOrg(
        supabase.from('staff').select('id, role').eq('id', body.id),
      ).maybeSingle()
      if (targetStaffError) throw targetStaffError
      if (!targetStaff) return NextResponse.json({ error: 'Сотрудник не найден' }, { status: 404 })
      if (String((targetStaff as any).role || '') === 'owner' && !isOwnerActor(access)) {
        return NextResponse.json({ error: 'Карточку владельца меняет только владелец' }, { status: 403 })
      }
      if (access.companyRestriction) {
        const visibleStaffIds = await listOrganizationStaffIds({
          activeOrganizationId: orgId,
          isSuperAdmin: access.isSuperAdmin,
          companyRestriction: access.companyRestriction,
        })
        if (!visibleStaffIds.includes(body.id)) return NextResponse.json({ error: 'forbidden-staff' }, { status: 403 })
      }

      if (body.action === 'update') {
        if (!body.payload.name?.trim()) return badRequest('Имя сотрудника обязательно')
        const newRole = body.payload.role?.trim() || 'operator'
        // Смена роли — только владелец. Не-владелец сохраняет текущую роль.
        const { data: curStaff } = await scopeOrg(
          supabase.from('staff').select('role').eq('id', body.id),
        ).single()
        const currentRole = String((curStaff as any)?.role || '')
        if (!isOwnerActor(access) && newRole !== currentRole) {
          return NextResponse.json(
            { error: 'Менять должность сотрудника может только владелец', reason: 'owner-only-role' },
            { status: 403 },
          )
        }
        const { data, error } = await scopeOrg(
          supabase
            .from('staff')
            .update({
              full_name: body.payload.name.trim(),
              phone: body.payload.phone?.trim() || null,
              email: body.payload.email?.trim() || null,
              role: newRole,
            })
            .eq('id', body.id),
        )
          .select('id,full_name,email,role')
          .single()
        if (error) throw error
        await writeAuditLog(supabase, {
          actorUserId,
          entityType: 'staff',
          entityId: String(data.id),
          action: 'update',
          payload: { full_name: data.full_name, email: data.email, role: data.role },
        })
        return NextResponse.json({ ok: true })
      }

      const { error } = await scopeOrg(supabase.from('staff').delete().eq('id', body.id))
      if (error) throw error
      await writeAuditLog(supabase, {
        actorUserId,
        entityType: 'staff',
        entityId: body.id,
        action: 'delete',
      })
      return NextResponse.json({ ok: true })
    }

    if (body.entity === 'expense_category') {
      const denied = await requireCapability(access, 'settings.manage_categories')
      if (denied) return denied as any

      if (body.action === 'create') {
        if (!body.payload.name?.trim()) return badRequest('Название категории обязательно')
        const { data, error } = await supabase.from('expense_categories').insert([
          {
            name: body.payload.name.trim(),
            monthly_budget: body.payload.monthly_budget ?? null,
            accounting_group: body.payload.accounting_group || null,
            organization_id: access.activeOrganization?.id || null,
          },
        ]).select('id,name,monthly_budget,accounting_group').single()
        if (error) throw error
        await writeAuditLog(supabase, {
          actorUserId,
          entityType: 'expense_category',
          entityId: String(data.id),
          action: 'create',
          payload: { name: data.name, monthly_budget: data.monthly_budget, accounting_group: data.accounting_group },
        })
        return NextResponse.json({ ok: true })
      }

      if (!body.id) return badRequest('id обязателен')

      if (body.action === 'update') {
        if (!body.payload.name?.trim()) return badRequest('Название категории обязательно')
        const { data, error } = await scopeOrg(
          supabase
            .from('expense_categories')
            .update({
              name: body.payload.name.trim(),
              monthly_budget: body.payload.monthly_budget ?? null,
              accounting_group: body.payload.accounting_group || null,
            })
            .eq('id', body.id),
        )
          .select('id,name,monthly_budget,accounting_group')
          .single()
        if (error) throw error
        await writeAuditLog(supabase, {
          actorUserId,
          entityType: 'expense_category',
          entityId: String(data.id),
          action: 'update',
          payload: { name: data.name, monthly_budget: data.monthly_budget, accounting_group: data.accounting_group },
        })
        return NextResponse.json({ ok: true })
      }

      const { error } = await scopeOrg(
        supabase.from('expense_categories').delete().eq('id', body.id),
      )
      if (error) throw error
      await writeAuditLog(supabase, {
        actorUserId,
        entityType: 'expense_category',
        entityId: body.id,
        action: 'delete',
      })
      return NextResponse.json({ ok: true })
    }

    return NextResponse.json({ error: 'Неизвестный entity' }, { status: 400 })
  } catch (error: any) {
    console.error('Admin settings mutation error', error)
    await writeSystemErrorLogSafe({
      scope: 'server',
      area: 'api/admin/settings',
      message: error?.message || 'Admin settings mutation error',
    })
    return NextResponse.json({ error: error?.message || 'Ошибка сервера' }, { status: 500 })
  }
}
