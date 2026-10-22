/**
 * AI tool: создать задачу для оператора.
 * Capability: tasks.create
 */

import type { CopilotTool } from '../../types'
import { scopedCompanyIds, scopedOperatorIds, scopedOperatorRows } from '../../query-helpers'
import { writeAuditLog } from '@/lib/server/audit'

export const createTaskTool: CopilotTool = {
  name: 'create_task',
  category: 'tasks',
  description: 'Создать задачу для оператора',
  requiredCapability: 'tasks.create',
  severity: 'medium',
  params: [
    {
      name: 'operator_id',
      label: 'Кому ставим задачу',
      type: 'select',
      required: true,
      description: 'ID оператора',
      getOptions: async (ctx) => {
        const data = await scopedOperatorRows(ctx)
        return (data || []).map((op: any) => ({ value: op.id, label: op.short_name || op.name }))
      },
    },
    {
      name: 'title',
      label: 'Что нужно сделать',
      type: 'string',
      required: true,
      description: 'Короткое описание задачи',
      extractHint: 'Проверить кассу',
    },
    {
      name: 'description',
      label: 'Подробности',
      type: 'string',
      required: false,
      description: 'Детальное описание (опционально)',
    },
    {
      name: 'due_date',
      label: 'Срок (YYYY-MM-DD)',
      type: 'date',
      required: false,
      description: 'Дедлайн, если есть',
    },
  ],
  handler: async (input, ctx) => {
    const operatorId = String(input.operator_id || '')
    const title = String(input.title || '').trim()
    const description = String(input.description || '').trim() || null
    const dueDate = String(input.due_date || '').trim() || null

    if (!operatorId || !title) return { ok: false, message: 'Не хватает данных (оператор, заголовок).' }

    // Мультитенантная изоляция: ставить задачу можно только оператору своей организации.
    const allowedOps = await scopedOperatorIds(ctx)
    if (allowedOps && !allowedOps.includes(operatorId)) return { ok: false, message: 'Оператор не найден.' }

    // Точка задачи — основная точка оператора в своей организации: без company_id
    // задача не видна в списках, которые фильтруются по точкам организации.
    const scope = await scopedCompanyIds(ctx)
    let assignQ = ctx.supabase
      .from('operator_company_assignments')
      .select('company_id, is_primary')
      .eq('operator_id', operatorId)
      .eq('is_active', true)
    if (scope) assignQ = assignQ.in('company_id', scope)
    const { data: assigns } = await assignQ
    const assign = (assigns || []).find((a: any) => a.is_primary) || (assigns || [])[0]
    const companyId = assign?.company_id ? String(assign.company_id) : null

    // created_by ссылается на staff — ставим, только если актор и есть сотрудник
    const { data: staffRow } = await ctx.supabase.from('staff').select('id').eq('id', ctx.userId).maybeSingle()

    // Номер задачи — как на сайте: следующий после максимального
    const { data: last } = await ctx.supabase
      .from('tasks')
      .select('task_number')
      .order('task_number', { ascending: false })
      .limit(1)
      .maybeSingle()

    const { data, error } = await ctx.supabase
      .from('tasks')
      .insert([
        {
          operator_id: operatorId,
          company_id: companyId,
          task_number: Number(last?.task_number || 0) + 1,
          title,
          description,
          due_date: dueDate,
          status: 'todo',
          priority: 'medium',
          created_by: staffRow?.id || null,
        },
      ])
      .select('id')
      .single()
    if (error) return { ok: false, message: `Не удалось создать задачу: ${error.message}` }

    try {
      await writeAuditLog(ctx.supabase, {
        actorUserId: ctx.actorUserId,
        // Тегируем событие организацией: иначе запись уходит в «общий» пул
        // audit_log и её читают копилоты других клиентов.
        organizationId: ctx.organizationId || null,
        entityType: 'task',
        entityId: data?.id || 'unknown',
        action: 'create',
        payload: { operator_id: operatorId, title, due_date: dueDate, via: 'copilot', source: ctx.source },
      })
    } catch {}

    return {
      ok: true,
      message: `Задача "${title}" создана${dueDate ? ` (срок ${dueDate})` : ''}.`,
      data: { taskId: data?.id },
    }
  },
}
