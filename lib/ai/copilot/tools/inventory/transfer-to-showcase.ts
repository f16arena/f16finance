/**
 * AI tool: прямой перенос товара со склада на витрину
 * (без создания заявки — для админа).
 * Capability: store-showcase.move
 */

import type { CopilotTool } from '../../types'
import { companyOptions, scopedCompanyIds, isOrgRowAllowed } from '../../query-helpers'
import { writeAuditLog } from '@/lib/server/audit'
import { createInventoryRequest, decideInventoryRequest, issueInventoryRequest } from '@/lib/server/repositories/inventory'

export const transferToShowcaseTool: CopilotTool = {
  name: 'transfer_to_showcase',
  category: 'inventory',
  description: 'Перенести товар со склада на витрину',
  requiredCapability: 'store-showcase.move',
  severity: 'medium',
  params: [
    {
      name: 'company_id',
      label: 'Точка',
      type: 'select',
      required: true,
      description: 'Точка',
      getOptions: async (ctx) => companyOptions(ctx),
    },
    {
      name: 'item_id',
      label: 'Товар',
      type: 'select',
      required: true,
      description: 'Какой товар перенести',
      getOptions: async (ctx) => {
        let q = ctx.supabase.from('inventory_items').select('id, name').order('name')
        if (ctx.organizationId) q = q.eq('organization_id', ctx.organizationId)
        const { data } = await q
        return (data || []).map((i: any) => ({ value: i.id, label: i.name }))
      },
    },
    {
      name: 'quantity',
      label: 'Количество',
      type: 'number',
      required: true,
      description: 'Сколько перенести',
    },
  ],
  handler: async (input, ctx) => {
    const companyId = String(input.company_id || '')
    const itemId = String(input.item_id || '')
    const quantity = Number(input.quantity || 0)
    if (!companyId || !itemId || quantity <= 0) return { ok: false, message: 'Не хватает данных.' }

    // Мультитенантная изоляция: переносить можно только на точку своей организации.
    const ids = await scopedCompanyIds(ctx)
    if (ids && !ids.includes(companyId)) return { ok: false, message: 'Точка не найдена.' }

    // Товар тоже per-org: иначе на свою витрину переносится чужая позиция.
    if (!(await isOrgRowAllowed(ctx, 'inventory_items', itemId))) {
      return { ok: false, message: 'Товар не найден.' }
    }

    // Отдельной RPC «склад → витрина» нет. Как выдача расходников на сайте
    // (/api/admin/inventory/consumables): заявка → одобрение → выдача → приёмка,
    // каждый шаг — существующей функцией, логику остатков не дублируем.
    const { data: locs } = await ctx.supabase
      .from('inventory_locations')
      .select('id, location_type')
      .eq('company_id', companyId)
      .in('location_type', ['warehouse', 'point_display'])
    const warehouse = (locs || []).find((l: any) => l.location_type === 'warehouse')
    const showcase = (locs || []).find((l: any) => l.location_type === 'point_display')
    if (!warehouse || !showcase) {
      return { ok: false, message: 'У точки нет склада или витрины — перенесите товар на странице /store/showcase.' }
    }

    const comment = 'Перенос со склада на витрину через AI Copilot'
    let requestId = ''
    try {
      requestId = String(await createInventoryRequest(ctx.supabase, {
        source_location_id: String(warehouse.id),
        target_location_id: String(showcase.id),
        requesting_company_id: companyId,
        comment,
        created_by: ctx.userId,
        items: [{ item_id: itemId, requested_qty: quantity }],
      }))
      const { data: lines, error: linesErr } = await ctx.supabase
        .from('inventory_request_items')
        .select('id, requested_qty')
        .eq('request_id', requestId)
      if (linesErr) throw linesErr
      await decideInventoryRequest(ctx.supabase, {
        request_id: requestId,
        approved: true,
        decision_comment: comment,
        actor_user_id: ctx.userId,
        items: (lines || []).map((l: any) => ({ request_item_id: String(l.id), approved_qty: Number(l.requested_qty || 0) })),
      })
      await issueInventoryRequest(ctx.supabase, requestId, ctx.userId)
      const { error: rpcErr } = await ctx.supabase.rpc('inventory_receive_request', {
        p_request_id: requestId,
        p_actor_user_id: ctx.userId,
      })
      if (rpcErr) throw rpcErr
    } catch (error: any) {
      const tail = requestId ? ` Заявка создана — доведите её на странице /store/requests.` : ''
      return { ok: false, message: `Не удалось перенести: ${error?.message || 'ошибка'}.${tail}` }
    }

    try {
      await writeAuditLog(ctx.supabase, {
        actorUserId: ctx.userId,
        // Тегируем событие организацией: иначе запись уходит в «общий» пул
        // audit_log и её читают копилоты других клиентов.
        organizationId: ctx.organizationId || null,
        entityType: 'inventory-transfer',
        entityId: requestId,
        action: 'warehouse-to-showcase',
        payload: { company_id: companyId, item_id: itemId, quantity, via: 'copilot', source: ctx.source },
      })
    } catch {}

    return { ok: true, message: `✅ Перенесено ${quantity} шт. со склада на витрину.` }
  },
}
