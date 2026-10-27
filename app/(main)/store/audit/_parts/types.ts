export type Loc = { id: string; name: string; location_type: string; company?: { name?: string | null } | null }
export type ActListRow = {
  id: string
  status: string
  comment: string | null
  opened_at: string
  closed_at: string | null
  locationName: string
  totalItems: number
  countedItems: number
  companyId?: string | null
  locationType?: string | null
  /** Итог проведения по закупу: минус = недостача */
  resultValue?: number | null
  /** Сколько из итога дало обнуление непосчитанного */
  zeroedValue?: number | null
}

// Ревизия второй локации той же точки (витрина ↔ склад), закрытая в пределах 3 дней:
// товар, лежащий не на своём месте, даёт минус в одной и плюс в другой — по точке ноль.
export function pairedAct(a: ActListRow, all: ActListRow[]): ActListRow | null {
  if (a.status !== 'closed' || a.resultValue == null || !a.companyId || !a.closed_at) return null
  const at = new Date(a.closed_at).getTime()
  let best: ActListRow | null = null
  for (const b of all) {
    if (b.id === a.id || b.status !== 'closed' || b.resultValue == null || !b.closed_at) continue
    if (b.companyId !== a.companyId || !b.locationType || b.locationType === a.locationType) continue
    const diff = Math.abs(new Date(b.closed_at).getTime() - at)
    if (diff > 3 * 86_400_000) continue
    if (!best || diff < Math.abs(new Date(best.closed_at as string).getTime() - at)) best = b
  }
  return best
}

export type FormData = { operators: Array<{ id: string; name: string }>; otherOperators?: Array<{ id: string; name: string }>; categories: Array<{ id: string; name: string }> }
export type Assignment = { operator_id: string; category_id: string | null }
export type ReportRow = { item_id: string; name: string; expected: number; counted: number; variance: number; purchase_price?: number; countedBy: string | null; conflict?: boolean; counts?: Array<{ qty: number; by: string | null }> }
export type CloseRow = { item_id: string; name: string; expected: number; counted: number; movedIn: number; movedOut: number; final: number; variance: number; shrinkage: number; surplus: number; purchase_price?: number; zeroed?: boolean }
export type CloseSummary = { movedItems: number; movedIn: number; movedOut: number; shrinkageItems: number; shrinkageQty: number; surplusItems: number; surplusQty: number; shrinkageValue?: number; surplusValue?: number; zeroedItems?: number; zeroedQty?: number; zeroedValue?: number }
export type UncountedRow = { item_id: string; name: string; expected: number; purchase_price: number; sold_recent?: number; other_location_qty?: number }
export type Detail = {
  act: { id: string; status: string; comment: string | null; opened_at: string; closed_at: string | null }
  location: Loc | null
  assignments: Array<{ id: string; operatorName: string; categoryName: string | null }>
  progress?: Array<{ operatorName: string; counted: number; total: number }>
  totalItems: number
  countedItems: number
  report: ReportRow[]
  uncounted?: UncountedRow[]
  otherLocationType?: string | null
}

// Порог «громкого» предупреждения: обнуление такого объёма почти всегда недосчёт, а не пропажа
export const isBigZeroing = (rows: UncountedRow[], totalItems: number) => {
  const value = rows.reduce((s, r) => s + r.expected * (r.purchase_price || 0), 0)
  return rows.length > Math.max(5, totalItems * 0.05) || value >= 50_000
}

export const fmt = (n: number) => (Number.isInteger(n) ? String(n) : n.toFixed(2))
export const fmtDate = (s: string | null) => (s ? new Date(s).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—')
export const locLabel = (l: Loc | null) => (l ? `${l.company?.name ? l.company.name + ' · ' : ''}${l.location_type === 'point_display' ? 'Витрина' : l.location_type === 'warehouse' ? 'Склад' : l.name}` : '—')
