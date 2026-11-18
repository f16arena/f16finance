import { writeAuditLog, writeSystemErrorLogSafe } from '@/lib/server/audit'
import { detailingContext, isIsoDate, json, pickCompany } from '@/lib/server/detailing'
import { ensureExpenseCategory, listExpenseCategories } from '@/lib/server/detailing-expenses'

/**
 * Расход детейлинга в два нажатия: сумма и на что. GET — статьи для кнопок,
 * POST — записать. Править и удалять — в общем журнале расходов.
 */

const MARK = 'detailing'

export async function GET(request: Request) {
  try {
    const ctx = await detailingContext(request, ['expenses.create', 'expenses.view'])
    if ('response' in ctx) return ctx.response
    return json({ ok: true, data: { categories: await listExpenseCategories(ctx.supabase, ctx.orgId) } })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-expense:get', message: error?.message || 'Detailing expense GET error' })
    return json({ error: 'Не удалось загрузить статьи' }, 500)
  }
}

export async function POST(request: Request) {
  const body = (await request.json().catch(() => null)) as any
  try {
    const ctx = await detailingContext(request, 'expenses.create')
    if ('response' in ctx) return ctx.response
    const company = pickCompany(ctx, body?.company_id)
    if (!company) return json({ error: 'Точка не найдена' }, 404)

    const amount = Math.round(Number(body?.amount) || 0)
    const method = String(body?.method || '')
    if (!(amount > 0)) return json({ error: 'Укажите сумму' }, 400)
    if (!['cash', 'kaspi', 'card'].includes(method)) return json({ error: 'Выберите, чем платили' }, 400)
    const today = new Date().toISOString().slice(0, 10)
    const date = isIsoDate(body?.date) ? body.date : today
    const category = await ensureExpenseCategory(ctx.supabase, ctx.orgId, String(body?.category || ''))
    const comment = String(body?.comment || '').trim().slice(0, 300) || null

    // В журнале две колонки: наличные и безнал. Kaspi и карта — безнал.
    const { data, error } = await ctx.supabase
      .from('expenses')
      .insert({
        date,
        company_id: company.id,
        category,
        cash_amount: method === 'cash' ? amount : 0,
        kaspi_amount: method === 'cash' ? 0 : amount,
        comment,
        status: 'confirmed',
      })
      .select('id')
      .single()
    if (error) throw error
    await writeAuditLog(ctx.supabase, {
      actorUserId: ctx.access.user?.id || null,
      entityType: 'expense',
      entityId: String(data.id),
      action: 'create',
      payload: { source: MARK, company_id: company.id, category, amount, method, date },
    })
    return json({ ok: true, data: { id: data.id, category } })
  } catch (error: any) {
    await writeSystemErrorLogSafe({ scope: 'server', area: 'admin-detailing-expense:post', message: error?.message || 'Detailing expense POST error' })
    return json({ error: 'Не удалось записать расход' }, 500)
  }
}
