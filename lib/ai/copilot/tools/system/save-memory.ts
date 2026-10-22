/**
 * AI tool: запомнить факт для AI (long-term memory).
 * Capability: нет — доступно всем staff.
 *
 * Сохраняет в ai_memory — оттуда engine при каждом LLM-вызове подгружает
 * последние 30 фактов в системный промпт. Так AI помнит о бизнесе.
 */

import type { CopilotTool } from '../../types'

function slugify(text: string): string {
  // Транслит + первые 40 символов в kebab-case для использования как key
  const map: Record<string, string> = {
    а: 'a', б: 'b', в: 'v', г: 'g', д: 'd', е: 'e', ё: 'e', ж: 'zh', з: 'z',
    и: 'i', й: 'y', к: 'k', л: 'l', м: 'm', н: 'n', о: 'o', п: 'p', р: 'r',
    с: 's', т: 't', у: 'u', ф: 'f', х: 'h', ц: 'c', ч: 'ch', ш: 'sh', щ: 'sch',
    ъ: '', ы: 'y', ь: '', э: 'e', ю: 'yu', я: 'ya',
  }
  const lower = text.toLowerCase()
  let slug = ''
  for (const ch of lower) slug += map[ch] ?? ch
  slug = slug.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return slug.slice(0, 40) || 'fact'
}

export const saveMemoryTool: CopilotTool = {
  name: 'save_to_memory',
  category: 'system',
  description: 'Запомнить факт чтобы использовать в будущих диалогах',
  requiredCapability: 'expenses.view',
  severity: 'low',
  params: [
    {
      name: 'fact',
      label: 'Что запомнить',
      type: 'string',
      required: true,
      description: 'Что нужно запомнить (правило, контакт, предпочтение и т.п.)',
      extractHint: 'Алтынбек — старший на Arena',
    },
  ],
  handler: async (input, ctx) => {
    const fact = String(input.fact || '').trim()
    if (!fact) return { ok: false, message: 'Что запомнить?' }

    const baseKey = slugify(fact)
    const key = `${baseKey}-${Date.now().toString(36).slice(-4)}`

    const { error } = await ctx.supabase
      .from('ai_memory')
      .insert([{
        key,
        value: fact,
        source: 'user',
        created_by: ctx.userId,
        organization_id: ctx.organizationId || null,
      }])
    // Запасной записи в telegram_chat_history нет: там одна строка history на чат,
    // а не строки role/content — раньше «запомнил (fallback)» ничего не сохранял.
    if (error) return { ok: false, message: `Не удалось запомнить: ${error.message}` }

    return { ok: true, message: `📝 Запомнил: "${fact}"` }
  },
}
