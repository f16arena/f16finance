'use client'

import { useEffect, useState } from 'react'

import { NativeSelect } from '@/components/ui/native-select'
import type { BaseRole, PointPosition } from '@/components/admin/point-positions-card'
import { POINT_BASE_ROLE_LABELS as ROLE_LABELS } from '@/lib/core/positions'

let cache: Promise<PointPosition[]> | null = null

/** После правки должностей — следующий usePointPositions прочитает список заново. */
export function invalidatePointPositions() {
  cache = null
}

/** Должности организации — один запрос на страницу, сколько бы списков на ней ни было. */
export function usePointPositions() {
  const [positions, setPositions] = useState<PointPosition[]>([])
  useEffect(() => {
    let cancelled = false
    cache ??= fetch('/api/admin/point-positions', { cache: 'no-store' })
      .then((r) => (r.ok ? r.json() : null))
      .then((json) => ((json?.data || []) as PointPosition[]))
      .catch(() => [])
    cache.then((rows) => !cancelled && setPositions(rows))
    return () => {
      cancelled = true
    }
  }, [])
  return positions
}

export function positionLabel(positions: PointPosition[], positionId: string | null | undefined, role: BaseRole) {
  const position = positionId ? positions.find((p) => p.id === positionId) : null
  return position ? position.name : ROLE_LABELS[role]
}

/**
 * Роль человека на точке: базовая (оператор / старший / старший кассир) или
 * своя должность этой точки. Своя должность приносит и права своей базовой роли.
 */
export function PositionSelect(props: {
  companyId: string
  role: BaseRole
  positionId: string | null | undefined
  positions: PointPosition[]
  onChange: (next: { role: BaseRole; position_id: string | null }) => void
  className?: string
  disabled?: boolean
}) {
  const available = props.positions.filter(
    (p) => (p.is_active || p.id === props.positionId) && (!p.company_id || p.company_id === props.companyId),
  )
  const value = props.positionId ? `pos:${props.positionId}` : `role:${props.role}`

  return (
    <NativeSelect
      className={props.className}
      disabled={props.disabled}
      value={value}
      onChange={(e) => {
        const [kind, id] = e.target.value.split(':')
        if (kind === 'pos') {
          const position = available.find((p) => p.id === id)
          if (position) props.onChange({ role: position.base_role, position_id: position.id })
        } else {
          props.onChange({ role: id as BaseRole, position_id: null })
        }
      }}
    >
      {(Object.keys(ROLE_LABELS) as BaseRole[]).map((role) => (
        <option key={role} value={`role:${role}`}>{ROLE_LABELS[role]}</option>
      ))}
      {available.length > 0 && (
        <optgroup label="Свои должности">
          {available.map((p) => (
            <option key={p.id} value={`pos:${p.id}`}>
              {p.name}
              {p.shift_bonus > 0 ? ` (+${Math.round(p.shift_bonus).toLocaleString('ru-RU')} ₸)` : ''}
            </option>
          ))}
        </optgroup>
      )}
    </NativeSelect>
  )
}
