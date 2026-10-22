/**
 * AI tools: одобрить / отклонить заявку на пополнение витрины.
 * Capability: store-requests.approve / store-requests.decline
 */

import type { CopilotTool } from '../../types'
import { writeAuditLog } from '@/lib/server/audit'
import { scopedCompanyIds } from '../../query-helpers'
import { decideInventoryRequest } from '@/lib/server/repositories/inventory'

/**
 * Мультитенантная изоляция: проверяем, что заявка принадлежит своей организации
 * (по requesting_company_id). Возвращает текст ошибки или null если доступ есть.
 */
async function assertRequestInScope(ctx: any, reqId: string): Promise<string | null> {
  const { data: req, error } = await ctx.supabase
    .from('inventory_requests')
    .select('id, requesting_company_id')
    .eq('id', reqId)
    .single()
  if (error || !req) return 'Заявка не найдена.'
  const ids = await scopedCompanyIds(ctx)
  if (ids && req.requesting_company_id && !ids.includes(String(req.requesting_company_id))) {
    return 'Заявка не найдена.'
  }
  return null
}

async function getPendingRequests(ctx: any) {
  let q = ctx.supabase
    .from('inventory_requests')
    .select('id, status, created_at, requesting_company_id, comment')
    .in('status', ['new', 'disputed'])
    .order('created_at', { ascending: false })
  // У inventory_requests нет organization_id — скоуп по точке-заявителю
  const scope = await scopedCompanyIds(ctx)
  if (scope) q = q.in('requesting_company_id', scope)
  const { data } = await q
  const rows = data || []
  if (rows.length === 0) return []

  const ids = Array.from(new Set(rows.map((r: any) => r.requesting_company_id).filter(Boolean)))
  const companyMap = new Map<string, string>()
  if (ids.length > 0) {
    const { data: cos } = await ctx.supabase.from('companies').select('id, name').in('id', ids)
    for (const c of (cos || []) as any[]) companyMap.set(String(c.id), c.name || '')
  }

  return rows.map((r: any) => {
    const coName = companyMap.get(String(r.requesting_company_id)) || ''
    const date = r.created_at ? new Date(r.created_at).toLocaleDateString('ru-RU') : ''
    return {
      value: String(r.id),
      label: `${coName} · ${date}` + (r.status === 'disputed' ? ' ⚠️' : ''),
    }
  })
}

export const approveRequestTool: CopilotTool = {
  name: 'approve_inventory_request',
  category: 'inventory',
  description: 'Одобрить заявку на пополнение витрины',
  requiredCapability: 'store-requests.approve',
  severity: 'high',
  params: [
    {
      name: 'request_id',
      label: 'Какую заявку',
      type: 'select',
      required: true,
      description: 'ID заявки из ожидающих',
      getOptions: getPendingRequests,
    },
  ],
  handler: async (input, ctx) => {
    const reqId = String(input.request_id || '')
    if (!reqId) return { ok: false, message: 'Не выбрана заявка.' }

    const scopeErr = await assertRequestInScope(ctx, reqId)
    if (scopeErr) return { ok: false, message: scopeErr }

    // Как на сайте: одобряем всё запрошенное, RPC inventory_decide_request резервирует товар на складе
    const { data: lines, error: linesErr } = await ctx.supabase
      .from('inventory_request_items')
      .select('id, requested_qty')
      .eq('request_id', reqId)
    if (linesErr) return { ok: false, message: `Не удалось одобрить: ${linesErr.message}` }
    try {
      await decideInventoryRequest(ctx.supabase, {
        request_id: reqId,
        approved: true,
        decision_comment: 'Одобрено через AI Copilot',
        actor_user_id: ctx.userId,
        items: (lines || []).map((l: any) => ({ request_item_id: String(l.id), approved_qty: Number(l.requested_qty || 0) })),
      })
    } catch (error: any) {
      return { ok: false, message: `Не удалось одобрить: ${error?.message || 'ошибка'}` }
    }

    try {
      await writeAuditLog(ctx.supabase, {
        actorUserId: ctx.userId,
        // Тегируем событие организацией: иначе запись уходит в «общий» пул
        // audit_log и её читают копилоты других клиентов.
        organizationId: ctx.organizationId || null,
        entityType: 'inventory-request',
        entityId: reqId,
        action: 'approve',
        payload: { via: 'copilot', source: ctx.source },
      })
    } catch {}

    return { ok: true, message: '✅ Заявка одобрена. Товар зарезервирован на складе и перейдёт на витрину, когда точка подтвердит приёмку.' }
  },
}

export const declineRequestTool: CopilotTool = {
  name: 'decline_inventory_request',
  category: 'inventory',
  description: 'Отклонить заявку на пополнение витрины',
  requiredCapability: 'store-requests.decline',
  severity: 'high',
  params: [
    {
      name: 'request_id',
      label: 'Какую заявку',
      type: 'select',
      required: true,
      description: 'ID заявки',
      getOptions: getPendingRequests,
    },
    {
      name: 'reason',
      label: 'Причина отказа',
      type: 'string',
      required: true,
      description: 'Почему отклоняем',
      extractHint: 'не хватает на складе',
    },
  ],
  handler: async (input, ctx) => {
    const reqId = String(input.request_id || '')
    const reason = String(input.reason || '').trim()
    if (!reqId || !reason) return { ok: false, message: 'Нужны заявка и причина.' }

    const scopeErr = await assertRequestInScope(ctx, reqId)
    if (scopeErr) return { ok: false, message: scopeErr }

    try {
      await decideInventoryRequest(ctx.supabase, {
        request_id: reqId,
        approved: false,
        decision_comment: reason,
        actor_user_id: ctx.userId,
      })
    } catch (error: any) {
      return { ok: false, message: `Не удалось отклонить: ${error?.message || 'ошибка'}` }
    }

    try {
      await writeAuditLog(ctx.supabase, {
        actorUserId: ctx.userId,
        // Тегируем событие организацией: иначе запись уходит в «общий» пул
        // audit_log и её читают копилоты других клиентов.
        organizationId: ctx.organizationId || null,
        entityType: 'inventory-request',
        entityId: reqId,
        action: 'decline',
        payload: { reason, via: 'copilot', source: ctx.source },
      })
    } catch {}

    return { ok: true, message: `❌ Заявка отклонена. Причина: ${reason}` }
  },
}
