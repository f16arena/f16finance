import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import { detailingContext, isIsoDate, json, pickCompany, pickCompanyForRead } from '@/lib/server/detailing'
import { ensureExpenseCategory } from '@/lib/server/detailing-expenses'
import { isMissingMastersTable, loadMasterPay, monthStart } from '@/lib/server/detailing-masters'
import { assertOrganizationLimitAvailable } from '@/lib/server/organizations'

/**
 * Мастера детейлинга: кто работает, из чего складывается оплата (оклад, бонус,
 * процент с цены минус материалы), сколько начислено за месяц и выплаты.
 *
 * Мастер — сотрудник точки без входа в программу. Заводится здесь одним именем,
 * чтобы владельцу не ходить в клубный раздел «Операторы».
 */

const MONTH_NAMES = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь']
const NEED_MIGRATION = 'Раздел «Мастера» ещё не включён в базе — нужна миграция 20261118_detailing_masters'

export async function GET(request: Request) {
  try {
    const ctx = await detailingContext(request, 'detailing-masters.view')
    if ('response' in ctx) return ctx.response
    const url = new URL(request.url)
    const company = pickCompanyForRead(ctx, url.searchParams.get('company_id'))
    if (!company) return json({ ok: true, data: { companies: ctx.companies, company: null } })
    const today = new Date().toISOString().slice(0, 10)
    const month = monthStart(url.searchParams.get('month'), today)
    const { masters } = await loadMasterPay(ctx.supabase, { companyIds: [company.id], month })
    return json({ ok: true, data: { companies: ctx.companies, company, month, masters } })
  } catch (error: any) {
    if (isMissingMastersTable(error)) return json({ error: NEED_MIGRATION }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-masters:get', message: error?.message || 'Detailing masters GET error' })
    return json({ error: 'Не удалось загрузить мастеров' }, 500)
  }
}

const CAPABILITY_BY_ACTION: Record<string, string> = {
  add: 'detailing-masters.create',
  terms: 'detailing-masters.edit',
  rename: 'detailing-masters.edit',
  remove: 'detailing-masters.delete',
  payout: 'detailing-masters.pay',
  unpayout: 'detailing-masters.pay',
}

const amountOf = (value: unknown) => Math.max(0, Math.round(Number(value) || 0))

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as any
  const action = String(body?.action || '')
  const capability = CAPABILITY_BY_ACTION[action]
  if (!capability) return json({ error: 'invalid-action' }, 400)
  try {
    const ctx = await detailingContext(request, capability)
    if ('response' in ctx) return ctx.response
    const company = pickCompany(ctx, body?.company_id)
    if (!company) return json({ error: 'Точка не найдена' }, 404)
    const actorUserId = ctx.access.user?.id || null
    const audit = (entityId: string, act: string, payload: Record<string, unknown>) =>
      writeAuditLog(ctx.supabase, { actorUserId, entityType: 'detailing-master', entityId, action: act, payload })

    if (action === 'add') {
      const name = String(body?.name || '').trim().slice(0, 80)
      if (name.length < 2) return json({ error: 'Напишите имя мастера' }, 400)
      try {
        await assertOrganizationLimitAvailable({
          activeOrganizationId: ctx.orgId,
          isSuperAdmin: ctx.access.isSuperAdmin,
          activeSubscription: ctx.access.activeSubscription,
          key: 'operators',
        })
      } catch (limitError: any) {
        return json({ error: limitError?.message || 'Лимит сотрудников по тарифу исчерпан' }, 403)
      }
      const { data: operator, error } = await ctx.supabase
        .from('operators')
        .insert({ name, short_name: name, is_active: true, organization_id: ctx.orgId })
        .select('id')
        .single()
      if (error) throw error
      const operatorId = String(operator.id)
      // Карточка сотрудника и назначение на точку — как при обычном создании.
      await ctx.supabase.from('operator_profiles').insert({ operator_id: operatorId, full_name: name, position: 'Мастер' })
      const { error: assignError } = await ctx.supabase
        .from('operator_company_assignments')
        .insert({ operator_id: operatorId, company_id: company.id, is_primary: true, is_active: true })
      if (assignError) {
        await ctx.supabase.from('operators').delete().eq('id', operatorId)
        throw assignError
      }
      await audit(operatorId, 'create', { name, company_id: company.id })
      return json({ ok: true, data: { id: operatorId } })
    }

    // Дальше — над мастером этой точки.
    const operatorId = String(body?.operator_id || '').trim()
    const { data: assignment } = operatorId
      ? await ctx.supabase
          .from('operator_company_assignments')
          .select('id, operator:operators(id, name, short_name)')
          .eq('operator_id', operatorId)
          .eq('company_id', company.id)
          .eq('is_active', true)
          .maybeSingle()
      : { data: null }
    if (!assignment) return json({ error: 'Мастер не найден' }, 404)
    const masterName = String((assignment as any).operator?.short_name || (assignment as any).operator?.name || 'Мастер')

    if (action === 'rename') {
      const name = String(body?.name || '').trim().slice(0, 80)
      if (name.length < 2) return json({ error: 'Напишите имя мастера' }, 400)
      const { error } = await ctx.supabase.from('operators').update({ name, short_name: name }).eq('id', operatorId).eq('organization_id', ctx.orgId)
      if (error) throw error
      await audit(operatorId, 'rename', { name })
      return json({ ok: true })
    }

    if (action === 'remove') {
      // Мастера не удаляем: на нём выданные машины и выплаты. Убираем с точки.
      const { error } = await ctx.supabase.from('operator_company_assignments').update({ is_active: false }).eq('id', (assignment as any).id)
      if (error) throw error
      await audit(operatorId, 'remove', { company_id: company.id })
      return json({ ok: true })
    }

    if (action === 'terms') {
      const percent = Math.min(100, Math.max(0, Math.round(Number(body?.percent) * 100) / 100 || 0))
      const row = {
        operator_id: operatorId,
        company_id: company.id,
        organization_id: ctx.orgId,
        salary_on: body?.salary_on === true,
        salary_amount: amountOf(body?.salary_amount),
        bonus_on: body?.bonus_on === true,
        bonus_amount: amountOf(body?.bonus_amount),
        percent_on: body?.percent_on === true,
        percent,
        note: String(body?.note || '').trim().slice(0, 120) || null,
        updated_at: new Date().toISOString(),
      }
      const { error } = await ctx.supabase.from('detailing_master_terms').upsert(row, { onConflict: 'operator_id,company_id' })
      if (error) throw error
      await audit(operatorId, 'terms', row)
      return json({ ok: true })
    }

    if (action === 'payout') {
      const amount = amountOf(body?.amount)
      const method = String(body?.method || '')
      if (!(amount > 0)) return json({ error: 'Укажите сумму' }, 400)
      if (!['cash', 'kaspi', 'card'].includes(method)) return json({ error: 'Выберите, чем платите' }, 400)
      const today = new Date().toISOString().slice(0, 10)
      const month = monthStart(body?.month, today)
      const paidOn = isIsoDate(body?.paid_on) ? body.paid_on : today
      const { masters } = await loadMasterPay(ctx.supabase, { companyIds: [company.id], month })
      const pay = masters.find((m) => m.id === operatorId)
      if (!pay) return json({ error: 'Мастер не найден' }, 404)
      if (amount > pay.due) return json({ error: `Больше, чем начислено: к выплате ${pay.due.toLocaleString('ru-RU')} ₸` }, 400)

      // Расход «Зарплата» в общий журнал — по нему сходится касса.
      const category = await ensureExpenseCategory(ctx.supabase, ctx.orgId, 'Зарплата')
      const { data: expense, error: expenseError } = await ctx.supabase
        .from('expenses')
        .insert({
          date: paidOn,
          company_id: company.id,
          operator_id: operatorId,
          category,
          cash_amount: method === 'cash' ? amount : 0,
          kaspi_amount: method === 'cash' ? 0 : amount,
          comment: `Мастеру ${masterName} за ${MONTH_NAMES[Number(month.slice(5, 7)) - 1]}`,
          status: 'confirmed',
        })
        .select('id')
        .single()
      if (expenseError) throw expenseError
      const { data: payout, error } = await ctx.supabase
        .from('detailing_master_payouts')
        .insert({
          organization_id: ctx.orgId,
          company_id: company.id,
          operator_id: operatorId,
          period_month: month,
          paid_on: paidOn,
          amount,
          method,
          expense_id: expense.id,
          created_by: actorUserId,
        })
        .select('id')
        .single()
      if (error) {
        await ctx.supabase.from('expenses').delete().eq('id', expense.id)
        throw error
      }
      await audit(operatorId, 'payout', { payout_id: payout.id, amount, method, month, expense_id: expense.id })
      return json({ ok: true, data: { id: payout.id } })
    }

    // unpayout
    const payoutId = String(body?.payout_id || '')
    const { data: payout } = await ctx.supabase
      .from('detailing_master_payouts')
      .select('id, expense_id, amount')
      .eq('id', payoutId)
      .eq('operator_id', operatorId)
      .eq('company_id', company.id)
      .maybeSingle()
    if (!payout) return json({ error: 'Выплата не найдена' }, 404)
    if (payout.expense_id) {
      const { error: expErr } = await ctx.supabase.from('expenses').delete().eq('id', payout.expense_id).eq('company_id', company.id)
      if (expErr) throw expErr
    }
    const { error } = await ctx.supabase.from('detailing_master_payouts').delete().eq('id', payout.id)
    if (error) throw error
    await audit(operatorId, 'unpayout', { payout_id: payout.id, amount: payout.amount })
    return json({ ok: true })
  } catch (error: any) {
    if (isMissingMastersTable(error)) return json({ error: NEED_MIGRATION }, 503)
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-masters:post', message: error?.message || 'Detailing masters POST error' })
    return json({ error: 'Не удалось сохранить' }, 500)
  }
}
