import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import { NICHE_ROLES, type NicheRoleCode, isNicheRole } from '@/lib/core/niche-roles'
import { buildTenantHost } from '@/lib/core/tenant-domain'
import { invalidateCapabilitiesCache, requireOwnerOrSuper } from '@/lib/server/capabilities'
import { detailingContext, json, normalizePhone } from '@/lib/server/detailing'
import { assertOrganizationLimitAvailable } from '@/lib/server/organizations'

/**
 * Команда детейлинга: кто заходит в программу и кем работает.
 *
 * Владелец добавляет человека готовой ролью (администратор или бухгалтер) —
 * права собраны заранее, см. lib/core/niche-roles.ts. Вход — по почте; пароль
 * человек придумывает сам по ссылке. Ссылку получает владелец и отправляет как
 * удобно (WhatsApp), поэтому доставка не зависит от почтового сервера.
 *
 * Всё здесь — только владельцу.
 */

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

async function ownerContext(request: Request) {
  const ctx = await detailingContext(request, 'detailing-bookings.view')
  if ('response' in ctx) return ctx
  const denied = requireOwnerOrSuper(ctx.access)
  if (denied) return { response: denied }
  return ctx
}

/** Ссылка, по которой человек задаёт пароль и попадает в программу этого детейлинга. */
async function accessLink(supabase: any, orgId: string, email: string, staffId: string) {
  const { data: org } = await supabase.from('organizations').select('slug').eq('id', orgId).maybeSingle()
  const origin = `https://${buildTenantHost(String((org as any)?.slug || ''))}`
  const redirectTo = `${origin}/auth/callback?next=${encodeURIComponent('/set-password')}`
  const invite = await supabase.auth.admin.generateLink({ type: 'invite', email, options: { redirectTo, data: { staff_id: staffId } } })
  if (!invite.error && invite.data?.properties?.action_link) return String(invite.data.properties.action_link)
  // Почта уже зарегистрирована — даём ссылку на новый пароль.
  const recovery = await supabase.auth.admin.generateLink({ type: 'recovery', email, options: { redirectTo } })
  if (recovery.error || !recovery.data?.properties?.action_link) throw recovery.error || invite.error || new Error('link-failed')
  return String(recovery.data.properties.action_link)
}

export async function GET(request: Request) {
  try {
    const ctx = await ownerContext(request)
    if ('response' in ctx) return ctx.response
    const { data, error } = await ctx.supabase
      .from('staff')
      .select('id, full_name, role, email, phone, is_active, created_at')
      .eq('organization_id', ctx.orgId)
      .eq('is_active', true)
      .order('created_at')
    if (error) throw error
    const myEmail = String(ctx.access.user?.email || '').toLowerCase()
    const people = ((data || []) as any[]).map((s) => ({
      id: String(s.id),
      name: String(s.full_name || ''),
      role: String(s.role || ''),
      roleLabel: s.role === 'owner' ? 'Владелец' : isNicheRole(s.role) ? NICHE_ROLES[s.role as NicheRoleCode].label : 'Сотрудник',
      email: s.email || null,
      phone: s.phone || null,
      editable: isNicheRole(s.role),
      roleLocked: s.role === 'detailing_master',
      me: !!s.email && String(s.email).toLowerCase() === myEmail,
    }))
    const roles = Object.values(NICHE_ROLES).map((r) => ({ code: r.code, label: r.label, summary: r.summary, can: r.can, cannot: r.cannot }))

    // Мастера точек и у кого из них уже есть вход.
    const companyIds = ctx.companies.map((c) => c.id)
    const { data: assigned } = companyIds.length
      ? await ctx.supabase.from('operator_company_assignments').select('operator:operators(id, name, short_name, is_active)').in('company_id', companyIds).eq('is_active', true)
      : { data: [] }
    const masterRows = new Map<string, string>()
    for (const row of (assigned || []) as any[]) {
      if (row.operator && row.operator.is_active !== false) masterRows.set(String(row.operator.id), String(row.operator.short_name || row.operator.name || 'Мастер'))
    }
    const staffIds = people.map((p) => p.id)
    const { data: links } = staffIds.length && masterRows.size
      ? await ctx.supabase.from('operator_staff_links').select('operator_id, staff_id').in('staff_id', staffIds)
      : { data: [] }
    const linked = new Set(((links || []) as any[]).map((l) => String(l.operator_id)))
    const masters = [...masterRows.entries()].map(([id, name]) => ({ id, name, hasLogin: linked.has(id) })).sort((a, b) => a.name.localeCompare(b.name, 'ru'))

    return json({ ok: true, data: { people, roles, masters } })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-team:get', message: error?.message || 'Detailing team GET error' })
    return json({ error: 'Не удалось загрузить команду' }, 500)
  }
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as any
  const action = String(body?.action || '')
  if (!['add', 'link', 'role', 'remove'].includes(action)) return json({ error: 'invalid-action' }, 400)
  try {
    const ctx = await ownerContext(request)
    if ('response' in ctx) return ctx.response
    const actorUserId = ctx.access.user?.id || null
    const audit = (entityId: string, act: string, payload: Record<string, unknown>) =>
      writeAuditLog(ctx.supabase, { actorUserId, entityType: 'detailing-team', entityId, action: act, payload })

    if (action === 'add') {
      const name = String(body?.name || '').trim().slice(0, 120)
      const email = String(body?.email || '').trim().toLowerCase()
      const role = String(body?.role || '')
      const phone = normalizePhone(String(body?.phone || '')) || null
      if (name.length < 2) return json({ error: 'Напишите имя' }, 400)
      if (!EMAIL_RE.test(email)) return json({ error: 'Проверьте почту — по ней человек будет входить' }, 400)
      if (!isNicheRole(role)) return json({ error: 'Выберите, кем работает' }, 400)

      // Вход мастеру даётся существующему мастеру точки: по нему считаются его машины и процент.
      const operatorId = role === 'detailing_master' ? String(body?.operator_id || '').trim() : ''
      if (role === 'detailing_master') {
        const companyIds = ctx.companies.map((c) => c.id)
        const { data: own } = operatorId && companyIds.length
          ? await ctx.supabase.from('operator_company_assignments').select('id').eq('operator_id', operatorId).in('company_id', companyIds).eq('is_active', true).limit(1).maybeSingle()
          : { data: null }
        if (!own) return json({ error: 'Сначала добавьте мастера в разделе «Мастера»' }, 400)
        const { data: busy } = await ctx.supabase.from('operator_staff_links').select('id').eq('operator_id', operatorId).maybeSingle()
        if (busy) return json({ error: 'У этого мастера вход уже есть' }, 409)
      }
      const linkMaster = async (staffId: string) => {
        if (!operatorId) return
        const { error: linkError } = await ctx.supabase.from('operator_staff_links').insert({ operator_id: operatorId, staff_id: staffId, assigned_role: role })
        if (linkError) throw linkError
      }

      // Человек связывается с учёткой по почте, поэтому одна почта — одна строка
      // сотрудника во всей базе, считая убранных. Убранного из этой же команды
      // возвращаем той же строкой, а не заводим вторую.
      const { data: sameEmail } = await ctx.supabase.from('staff').select('id, role, is_active, organization_id').ilike('email', email).limit(5)
      const rows = (sameEmail || []) as any[]
      const returning = rows.length === 1 && rows[0].is_active === false && String(rows[0].organization_id) === ctx.orgId && isNicheRole(rows[0].role) ? rows[0] : null
      if (rows.length && !returning) return json({ error: 'Эта почта уже занята другим сотрудником. Возьмите другую.' }, 409)
      if (returning) {
        const staffId = String(returning.id)
        const { error: backError } = await ctx.supabase.from('staff').update({ full_name: name, short_name: name, role, phone, is_active: true }).eq('id', staffId)
        if (backError) throw backError
        await ctx.supabase.from('organization_members').update({ role, status: 'active' }).eq('staff_id', staffId).eq('organization_id', ctx.orgId)
        await ctx.supabase.from('operator_staff_links').delete().eq('staff_id', staffId)
        await linkMaster(staffId)
        invalidateCapabilitiesCache()
        await audit(staffId, 'return', { name, email, role })
        const link = await accessLink(ctx.supabase, ctx.orgId, email, staffId).catch(() => null)
        return json({ ok: true, data: { id: staffId, link, phone, name } })
      }

      try {
        await assertOrganizationLimitAvailable({
          activeOrganizationId: ctx.orgId,
          isSuperAdmin: ctx.access.isSuperAdmin,
          activeSubscription: ctx.access.activeSubscription,
          key: 'staff',
        })
      } catch (limitError: any) {
        return json({ error: limitError?.message || 'Лимит сотрудников по тарифу исчерпан' }, 403)
      }

      const { data: staff, error } = await ctx.supabase
        .from('staff')
        .insert({ full_name: name, short_name: name, role, email, phone, monthly_salary: 0, is_active: true, organization_id: ctx.orgId })
        .select('id')
        .single()
      if (error) throw error
      const staffId = String(staff.id)
      const { error: memberError } = await ctx.supabase.from('organization_members').insert({
        organization_id: ctx.orgId,
        staff_id: staffId,
        email,
        role,
        status: 'active',
        is_default: true,
        metadata: { bootstrap_source: 'detailing-team' },
      })
      if (memberError) {
        await ctx.supabase.from('staff').delete().eq('id', staffId)
        throw memberError
      }
      try {
        await linkMaster(staffId)
      } catch (linkError) {
        await ctx.supabase.from('staff').delete().eq('id', staffId)
        throw linkError
      }
      await audit(staffId, 'add', { name, email, role, operator_id: operatorId || null })
      // Человек уже добавлен; не вышла ссылка — её можно получить кнопкой ещё раз.
      const link = await accessLink(ctx.supabase, ctx.orgId, email, staffId).catch(() => null)
      return json({ ok: true, data: { id: staffId, link, phone, name } })
    }

    // Дальше — над человеком этой организации с ролью ниши (владельца здесь не трогаем).
    const staffId = String(body?.staff_id || '').trim()
    const { data: person } = staffId
      ? await ctx.supabase.from('staff').select('id, full_name, role, email, phone').eq('id', staffId).eq('organization_id', ctx.orgId).eq('is_active', true).maybeSingle()
      : { data: null }
    if (!person || !isNicheRole(person.role)) return json({ error: 'Человек не найден' }, 404)

    if (action === 'link') {
      if (!person.email) return json({ error: 'У человека нет почты' }, 400)
      const link = await accessLink(ctx.supabase, ctx.orgId, String(person.email).toLowerCase(), staffId)
      await audit(staffId, 'link', {})
      return json({ ok: true, data: { link, phone: person.phone || null, name: person.full_name } })
    }

    if (action === 'role') {
      const role = String(body?.role || '')
      if (!isNicheRole(role)) return json({ error: 'Выберите, кем работает' }, 400)
      // Мастер привязан к своим машинам: его не «повышают» сменой роли, а добавляют заново.
      if (role === 'detailing_master' || person.role === 'detailing_master') return json({ error: 'Роль мастера так не меняется. Уберите вход и добавьте человека заново.' }, 400)
      const { error } = await ctx.supabase.from('staff').update({ role }).eq('id', staffId)
      if (error) throw error
      await ctx.supabase.from('organization_members').update({ role }).eq('staff_id', staffId).eq('organization_id', ctx.orgId)
      invalidateCapabilitiesCache()
      await audit(staffId, 'role', { from: person.role, to: role })
      return json({ ok: true })
    }

    // remove: вход закрывается сразу — неактивного сотрудника сервер не узнаёт.
    const { error } = await ctx.supabase.from('staff').update({ is_active: false }).eq('id', staffId)
    if (error) throw error
    await ctx.supabase.from('organization_members').update({ status: 'inactive' }).eq('staff_id', staffId).eq('organization_id', ctx.orgId)
    // Сам мастер остаётся в «Мастерах» — убираем только его вход.
    await ctx.supabase.from('operator_staff_links').delete().eq('staff_id', staffId)
    invalidateCapabilitiesCache()
    await audit(staffId, 'remove', { name: person.full_name, role: person.role })
    return json({ ok: true })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-team:post', message: error?.message || 'Detailing team POST error' })
    return json({ error: 'Не удалось сохранить' }, 500)
  }
}
