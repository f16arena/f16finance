/**
 * Праздники для поздравлений команды — настройка организации
 * (organizations.settings.holidays). По умолчанию — казахстанский список с
 * прежними текстами, поэтому у тех, кто ничего не менял, рассылка та же.
 * Владелец может выключить рассылку, убрать праздник, переписать текст или
 * добавить свою дату (день рождения клуба).
 *
 * В тексте {name} заменяется именем сотрудника.
 */

export type Holiday = { key: string; month: number; day: number; name: string; emoji: string; greeting: string; note?: string }

export const KZ_HOLIDAYS: Holiday[] = [
  { key: 'new_year', month: 1, day: 1, emoji: '🎄', name: 'Новый год', greeting: 'С Новым годом, {name}! 🎄\n\nПусть этот год принесёт здоровье, успех и много добрых моментов. Спасибо что ты с нами!' },
  { key: 'new_year_2', month: 1, day: 2, emoji: '🎄', name: 'Новый год (2-й день)', greeting: 'Продолжаем праздник! 🎉 С новогодними каникулами, {name}! Отдыхай как следует.' },
  { key: 'women_day', month: 3, day: 8, emoji: '💐', name: 'Международный женский день', greeting: 'С 8 Марта, {name}! 💐\n\nСпасибо за тепло, заботу и труд. Пусть весна принесёт радость. Ты прекрасна!' },
  { key: 'nauryz', month: 3, day: 21, emoji: '🌷', name: 'Наурыз', greeting: 'Наурыз құтты болсын, {name}! 🌷\n\nЖаңа жыл жаңа сәттіліктер әкелсін! Здоровья тебе и твоим близким.' },
  { key: 'nauryz_2', month: 3, day: 22, emoji: '🌷', name: 'Наурыз (2-й день)', greeting: 'Наурыз мейрамын құттықтаймыз, {name}! 🌷 Берекелі күндер тілейміз.' },
  { key: 'nauryz_3', month: 3, day: 23, emoji: '🌷', name: 'Наурыз (3-й день)', greeting: 'Наурыз көрісу күні құтты болсын, {name}! 🌷' },
  { key: 'unity_day', month: 5, day: 1, emoji: '🤝', name: 'Праздник единства народа Казахстана', greeting: 'С праздником единства народа Казахстана, {name}! 🤝\n\nМы — одна команда, одна семья. Мира и согласия!' },
  { key: 'defender_day', month: 5, day: 7, emoji: '🛡', name: 'День защитника Отечества', greeting: 'С Днём защитника Отечества, {name}! 🛡 Спасибо за службу всем кто защищал и защищает.' },
  { key: 'victory_day', month: 5, day: 9, emoji: '🌹', name: 'День Победы', greeting: 'С Днём Победы, {name}! 🌹\n\nПомним подвиг тех, кто отстоял мир. Низкий поклон ветеранам.' },
  { key: 'capital_day', month: 7, day: 6, emoji: '🇰🇿', name: 'День Столицы', greeting: 'С Днём Столицы, {name}! 🇰🇿\n\nАстана — наша гордость. Желаю тебе процветания и здоровья!' },
  { key: 'constitution_day', month: 8, day: 30, emoji: '🇰🇿', name: 'День Конституции', greeting: 'С Днём Конституции, {name}! 🇰🇿\n\nС праздником, желаю мира и стабильности.' },
  { key: 'republic_day', month: 10, day: 25, emoji: '🇰🇿', name: 'Республика күні', greeting: 'Республика күні құтты болсын, {name}! 🇰🇿\n\nБейбітшілік пен берекелі тірлік тілеймін.' },
  { key: 'first_president_day', month: 12, day: 1, emoji: '🌟', name: 'День Первого Президента', greeting: 'С Днём Первого Президента, {name}! 🌟', note: 'с 2022 года не государственный праздник' },
  { key: 'independence_day', month: 12, day: 16, emoji: '🇰🇿', name: 'День Независимости', greeting: 'С Днём Независимости, {name}! 🇰🇿\n\nПусть мир, процветание и независимость всегда будут с нами. Гордимся нашей страной!' },
  { key: 'independence_day_2', month: 12, day: 17, emoji: '🇰🇿', name: 'День Независимости (2-й день)', greeting: 'С праздником Независимости, {name}! 🇰🇿', note: 'с 2022 года не выходной' },
]

export type CustomHoliday = { id: string; month: number; day: number; name: string; greeting: string }

export type HolidaySettings = {
  /** Поздравлять команду вообще. */
  enabled: boolean
  /** Ключи праздников из списка, которые не поздравляем. */
  disabled: string[]
  /** Свои тексты вместо стандартных: { nauryz: '...' }. */
  greetings: Record<string, string>
  /** Свои даты. */
  custom: CustomHoliday[]
}

export const DEFAULT_HOLIDAY_SETTINGS: HolidaySettings = { enabled: true, disabled: [], greetings: {}, custom: [] }

const KEYS = new Set(KZ_HOLIDAYS.map((h) => h.key))
const clean = (value: unknown, max: number) => String(value ?? '').trim().slice(0, max)

function validDate(month: number, day: number) {
  if (!Number.isInteger(month) || !Number.isInteger(day) || month < 1 || month > 12 || day < 1) return false
  return day <= new Date(Date.UTC(2024, month, 0)).getUTCDate() // 2024 — високосный: 29 февраля можно
}

export function normalizeHolidaySettings(raw: unknown): HolidaySettings {
  const v = (raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {}) as Record<string, any>
  const greetings: Record<string, string> = {}
  for (const [key, text] of Object.entries(v.greetings && typeof v.greetings === 'object' ? v.greetings : {})) {
    const t = clean(text, 600)
    if (KEYS.has(key) && t) greetings[key] = t
  }
  const custom: CustomHoliday[] = []
  for (const item of Array.isArray(v.custom) ? v.custom.slice(0, 30) : []) {
    const month = Number(item?.month)
    const day = Number(item?.day)
    const name = clean(item?.name, 80)
    if (!name || !validDate(month, day)) continue
    custom.push({
      id: clean(item?.id, 40) || `c${month}-${day}-${custom.length}`,
      month,
      day,
      name,
      greeting: clean(item?.greeting, 600) || `С праздником «${name}», {name}! 🎉`,
    })
  }
  return {
    enabled: v.enabled !== false,
    disabled: Array.isArray(v.disabled) ? v.disabled.map(String).filter((k: string) => KEYS.has(k)) : [],
    greetings,
    custom,
  }
}

/** Что поздравляем в этот день у организации: стандартные включённые + свои. */
export function holidaysOn(settings: HolidaySettings, month: number, day: number): Array<{ name: string; emoji: string; greeting: string }> {
  if (!settings.enabled) return []
  const standard = KZ_HOLIDAYS.filter((h) => h.month === month && h.day === day && !settings.disabled.includes(h.key)).map((h) => ({
    name: h.name,
    emoji: h.emoji,
    greeting: settings.greetings[h.key] || h.greeting,
  }))
  const own = settings.custom.filter((h) => h.month === month && h.day === day).map((h) => ({ name: h.name, emoji: '🎉', greeting: h.greeting }))
  return [...standard, ...own]
}
