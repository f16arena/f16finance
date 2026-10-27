// Типы и мелкие помощники ревизий. Вынесены из page.tsx, чтобы список и окно
// деталей жили в своих файлах (page.tsx не может экспортировать ничего, кроме страницы).

export type InventoryLocation = {
  id: string
  name: string
  code: string | null
  location_type: 'warehouse' | 'point_display'
  company?: { id: string; name: string; code: string | null } | null
}

export type InventoryItem = {
  id: string
  name: string
  barcode: string
  unit: string
  item_type: string
  sale_price?: number
  default_purchase_price?: number
}

export type InventoryBalance = {
  location_id: string
  item_id: string
  quantity: number
  item?: InventoryItem | null
}

export type InventoryRevision = {
  id: string
  counted_at: string
  comment: string | null
  created_by?: string | null
  created_by_staff?: { id: string; full_name: string | null; role: string | null } | null
  /** Заполнено, если акт убран в архив. Приходит только суперадминистратору. */
  archived_at?: string | null
  archive_reason?: string | null
  location?: InventoryLocation | null
  items?: Array<{
    id: string
    expected_qty: number
    actual_qty: number
    delta_qty: number
    comment: string | null
    item?: InventoryItem | null
  }>
}

export function formatQty(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2)
}

export function formatDate(value: string | null | undefined) {
  if (!value) return '—'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(parsed)
}

export function actorLabel(staff: { full_name: string | null } | null | undefined, fallbackId: string | null | undefined) {
  if (staff?.full_name) return staff.full_name
  if (fallbackId) return `ID ${String(fallbackId).slice(0, 8)}`
  return '—'
}

/** Недостача/излишек в штуках и ущерб по цене продажи и закупки — как в таблице актов. */
export function revisionTotals(revision: InventoryRevision) {
  const items = revision.items || []
  const shortage = items.reduce((s, i) => s + (Number(i.delta_qty || 0) < 0 ? Math.abs(Number(i.delta_qty || 0)) : 0), 0)
  const surplus = items.reduce((s, i) => s + (Number(i.delta_qty || 0) > 0 ? Number(i.delta_qty || 0) : 0), 0)
  const saleAmount = items.reduce((s, i) => s + Math.abs(Number(i.delta_qty || 0)) * Number(i.item?.sale_price || 0), 0)
  const purchaseAmount = items.reduce((s, i) => s + Math.abs(Number(i.delta_qty || 0)) * Number(i.item?.default_purchase_price || 0), 0)
  const mismatches = items.filter((i) => Number(i.delta_qty || 0) !== 0)
  return { items, shortage, surplus, saleAmount, purchaseAmount, mismatches }
}
