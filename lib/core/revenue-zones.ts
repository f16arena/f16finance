/**
 * Зоны выручки точки: PS5, VR, бильярд, кальян — что угодно, по чему владелец
 * хочет видеть выручку отдельно. Хранятся в companies.revenue_zones (jsonb).
 *
 * Ключ зоны пишется в incomes.zone и больше не меняется: переименование меняет
 * только подпись, выключение прячет зону из формы прихода, но история по ней
 * остаётся в отчётах. Поэтому зоны не удаляют, а выключают.
 *
 * Не задано (null) — как раньше: PS-зал получает PS5 + VR, остальные точки
 * вносят выручку одной суммой.
 */

import { transliterate } from './company-code'
import { pointProfile } from './point-profile'

export type RevenueZone = { key: string; label: string; active: boolean }

export const MAX_REVENUE_ZONES = 12

const DEFAULT_PS_ZONES: RevenueZone[] = [
  { key: 'ps5', label: 'PS5', active: true },
  { key: 'vr', label: 'VR', active: true },
]

type ZoneCompany = { code?: string | null; industry?: string | null; revenue_zones?: unknown }

export function companyZones(company: ZoneCompany | null | undefined): RevenueZone[] {
  if (!company) return []
  if (Array.isArray(company.revenue_zones)) {
    return company.revenue_zones
      .filter((z: any) => z && typeof z.key === 'string' && z.key)
      .map((z: any) => ({ key: String(z.key), label: String(z.label || z.key), active: z.active !== false }))
  }
  return pointProfile(company).splitPsVr ? DEFAULT_PS_ZONES.map((z) => ({ ...z })) : []
}

export function activeZones(company: ZoneCompany | null | undefined) {
  return companyZones(company).filter((z) => z.active)
}

/** Ключ из названия: «Бильярд» → billyard. Латиница, цифры и _, не длиннее 24. */
export function zoneKeyFromLabel(label: string, taken: Iterable<string>) {
  const used = new Set(taken)
  const base =
    transliterate(label)
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '_')
      .replace(/^_+|_+$/g, '')
      .slice(0, 24) || 'zone'
  if (!used.has(base)) return base
  for (let i = 2; ; i += 1) {
    const next = `${base.slice(0, 21)}_${i}`
    if (!used.has(next)) return next
  }
}

/**
 * Проверка списка, пришедшего с клиента. Ключи уже существующих зон сохраняются
 * как были (на них ссылается история), новым ключ выдаётся из названия.
 */
export function sanitizeZones(
  input: unknown,
  existing: RevenueZone[],
): { ok: true; zones: RevenueZone[] } | { ok: false; error: string } {
  if (!Array.isArray(input)) return { ok: false, error: 'Список зон не прочитан' }
  if (input.length > MAX_REVENUE_ZONES) return { ok: false, error: `Не больше ${MAX_REVENUE_ZONES} зон на точку` }

  const knownKeys = new Set(existing.map((z) => z.key))
  const out: RevenueZone[] = []
  const labels = new Set<string>()
  for (const raw of input as any[]) {
    const label = String(raw?.label || '').trim().replace(/\s+/g, ' ').slice(0, 40)
    if (!label) return { ok: false, error: 'У зоны должно быть название' }
    const labelKey = label.toLowerCase()
    if (labels.has(labelKey)) return { ok: false, error: `Зона «${label}» указана дважды` }
    labels.add(labelKey)

    const requestedKey = String(raw?.key || '')
    const key = knownKeys.has(requestedKey)
      ? requestedKey
      : zoneKeyFromLabel(label, [...existing.map((z) => z.key), ...out.map((z) => z.key)])
    if (out.some((z) => z.key === key)) return { ok: false, error: `Зона «${label}» указана дважды` }
    out.push({ key, label, active: raw?.active !== false })
  }
  // Зону, по которой есть история, нельзя «потерять» — только выключить.
  for (const old of existing) {
    if (!out.some((z) => z.key === old.key)) out.push({ ...old, active: false })
  }
  return { ok: true, zones: out }
}
