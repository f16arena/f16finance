/**
 * AI tool: создать заявку на пополнение витрины со склада.
 * Capability: inventory-requests.create  (или store/requests.create — проверим)
 */

import type { CopilotTool } from '../../types'
import { companyOptions, scopedCompanyIds, isOrgRowAllowed } from '../../query-helpers'
import { writeAuditLog } from '@/lib/server/audit'
import { createInventoryRequest } from '@/lib/server/repositories/inventory'

export const createInventoryRequestTool: CopilotTool = {
  name: 'create_inventory_request',
  category: 'inventory',
  description: 'Создать заявку на пополнение витрины со склада',
  requiredCapability: 'store-requests.create',
  severity: 'medium',
  params: [
    {
      name: 'company_id',
      label: 'Точка',
      type: 'select',
      required: true,
      description: 'Точка для которой делаем заявку',
      getOptions: async (ctx) => companyOptions(ctx),
    },
    {
      name: 'item_id',
      label: 'Товар',
      type: 'select',
      required: true,
      description: 'Какой товар',
      getOptions: async (ctx) => {
        let q = ctx.supabase
          .from('inventory_items')
          .select('id, name')
          .order('name')
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
      description: 'Сколько штук перенести со склада на витрину',
    },
    {
      name: 'comment',
      label: 'Комментарий',
      type: 'string',
      required: false,
      description: 'Опционально',
    },
  ],
  handler: async (input, ctx) => {
    const companyId = String(input.company_id || '')
    const itemId = String(input.item_id || '')
    const quantity = Number(input.quantity || 0)
    const comment = String(input.comment || '').trim() || null

    if (!companyId || !itemId || quantity <= 0) {
      return { ok: false, message: 'Не хватает данных.' }
    }

    // Мультитенантная изоляция: заявку можно создать только для точки своей организации.
    const ids = await scopedCompanyIds(ctx)
    if (ids && !ids.includes(companyId)) return { ok: false, message: 'Точка не найдена.' }

    // Товар тоже per-org: заявка не должна ссылаться на чужой каталог.
    if (!(await isOrgRowAllowed(ctx, 'inventory_items', itemId))) {
      return { ok: false, message: 'Товар не найден.' }
    }

    // Как на сайте (/store/showcase → createRequest): склад точки → витрина точки,
    // заявка с позициями создаётся одним RPC inventory_create_request.
    const { data: locs } = await ctx.supabase
      .from('inventory_locations')
      .select('id, location_type')
      .eq('company_id', companyId)
      .in('location_type', ['warehouse', 'point_display'])
    const warehouse = (locs || []).find((l: any) => l.location_type === 'warehouse')
    const showcase = (locs || []).find((l: any) => l.location_type === 'point_display')
    if (!warehouse || !showcase) {
      return { ok: false, message: 'У точки нет склада или витрины — создайте заявку на странице /store/showcase.' }
    }

    let requestId: string | null = null
    try {
      const created = await createInventoryRequest(ctx.supabase, {
        source_location_id: String(warehouse.id),
        target_location_id: String(showcase.id),
        requesting_company_id: companyId,
        comment,
        created_by: ctx.actorUserId,
        items: [{ item_id: itemId, requested_qty: quantity }],
      })
      requestId = created ? String(created) : null
    } catch (error: any) {
      return { ok: false, message: `Не удалось создать заявку: ${error?.message || 'ошибка'}` }
    }
    const req = { id: requestId }

    try {
      await writeAuditLog(ctx.supabase, {
        actorUserId: ctx.actorUserId,
        // Тегируем событие организацией: иначе запись уходит в «общий» пул
        // audit_log и её читают копилоты других клиентов.
        organizationId: ctx.organizationId || null,
        entityType: 'inventory-request',
        entityId: req?.id || 'unknown',
        action: 'create',
        payload: { company_id: companyId, item_id: itemId, quantity, via: 'copilot', source: ctx.source },
      })
    } catch {}

    return {
      ok: true,
      message: `Заявка создана: ${quantity} шт. Ожидает одобрения на странице /store/requests.`,
      data: { requestId: req?.id },
    }
  },
}
