'use client'

import React, { useEffect, useMemo, useState } from 'react'
import { pointProfile } from '@/lib/core/point-profile'
import {
  Building2,
  Crown,
  Loader2,
  Mail,
  Network,
  PencilLine,
  Phone,
  Plus,
  ShieldCheck,
  Sparkles,
  X,
} from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { PositionSelect, positionLabel, usePointPositions } from '@/components/admin/position-select'
import { getOperatorDisplayName } from '@/lib/core/operator-name'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { useModalEscape } from '@/lib/client/use-modal-escape'
import Avatar from '../hr/Avatar'
import { Search } from 'lucide-react'

type StaffRole = string
type CompanyOperatorRole = 'operator' | 'senior_operator' | 'senior_cashier'

type StaffMember = {
  id: string
  full_name: string | null
  short_name: string | null
  role: StaffRole
  monthly_salary: number | null
  phone: string | null
  email: string | null
  is_active: boolean
}

type Company = {
  id: string
  name: string
  code: string | null
  industry?: string | null
}

type Operator = {
  id: string
  name: string
  short_name: string | null
  is_active: boolean
  telegram_chat_id: string | null
  operator_profiles?: Array<{
    full_name?: string | null
    phone?: string | null
    email?: string | null
    position?: string | null
    photo_url?: string | null
    hire_date?: string | null
  }> | null
}

type Assignment = {
  id: string
  operator_id: string
  company_id: string
  role_in_company: CompanyOperatorRole
  is_primary: boolean
  is_active: boolean
  notes: string | null
  created_at: string
  updated_at: string
  position_id?: string | null
}

type CareerLink = {
  id: string
  operator_id: string
  staff_id: string
  assigned_role: StaffRole | 'other'
  assigned_at: string
  updated_at: string
  operator?: {
    id: string
    name: string
    short_name: string | null
    operator_profiles?: Array<{
      full_name?: string | null
      hire_date?: string | null
      position?: string | null
    }> | null
  } | null
  staff?: {
    id: string
    full_name: string | null
    short_name: string | null
    role: StaffRole | 'other'
    monthly_salary: number | null
    is_active: boolean
  } | null
}

type AssignmentEditorRow = {
  id?: string
  company_id: string
  role_in_company: CompanyOperatorRole
  /** Своя должность; ключа нет — сервер оставляет прежнюю. */
  position_id?: string | null
  is_primary: boolean
  is_active: boolean
  notes: string
}

type StructureResponse = {
  ok: boolean
  data?: {
    staff: StaffMember[]
    companies: Company[]
    operators: Operator[]
    assignments: Assignment[]
    careerLinks: CareerLink[]
  }
  error?: string
}

const STAFF_ROLE_LABEL: Record<StaffRole, string> = {
  owner: 'Владелец',
  manager: 'Руководитель',
  marketer: 'Маркетолог',
}

const COMPANY_ROLE_LABEL: Record<CompanyOperatorRole, string> = {
  operator: 'Оператор',
  senior_operator: 'Старший оператор',
  senior_cashier: 'Старший кассир',
}

function formatMoney(value: number | null | undefined) {
  if (value == null) return 'Не задан'
  return `${Number(value).toLocaleString('ru-RU')} ₸`
}

function getPersonName(person: { full_name?: string | null; short_name?: string | null; name?: string | null }) {
  return person.full_name?.trim() || person.name?.trim() || person.short_name?.trim() || 'Без имени'
}

function getCompanyLeadTitle(company: Company) {
  return pointProfile(company).seniorLabel
}

function getStaffRoleLabel(role: StaffRole | 'other') {
  if (role === 'other') return 'Сотрудник'
  return STAFF_ROLE_LABEL[role]
}

function formatTenure(hireDate?: string | null) {
  if (!hireDate) return 'Стаж не указан'
  const start = new Date(`${hireDate}T00:00:00`)
  const now = new Date()
  let months = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth())
  if (months < 0) months = 0
  const years = Math.floor(months / 12)
  const restMonths = months % 12
  if (years > 0 && restMonths > 0) return `${years} г ${restMonths} мес`
  if (years > 0) return `${years} г`
  return `${restMonths || 1} мес`
}

/** Строка админ-команды: роль, имя, контакты, оклад — одной строкой. */
function StaffNode({ member }: { member: StaffMember; tone?: 'owner' | 'manager' | 'marketer' }) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-2.5">
      <div className="flex min-w-0 flex-1 items-center gap-3">
        <Avatar name={getPersonName(member)} size="sm" />
        <div className="min-w-0">
          <div className="flex items-center gap-1.5">
            {member.role === 'owner' ? <Crown className="h-3.5 w-3.5 shrink-0 text-amber-500" /> : null}
            <span className="truncate text-sm font-medium text-foreground">{getPersonName(member)}</span>
          </div>
          <div className="text-xs text-muted-foreground">{STAFF_ROLE_LABEL[member.role] || member.role}</div>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
        {member.phone ? (
          <span className="inline-flex items-center gap-1.5"><Phone className="h-3 w-3" />{member.phone}</span>
        ) : null}
        {member.email ? (
          <span className="inline-flex max-w-[220px] items-center gap-1.5 truncate"><Mail className="h-3 w-3 shrink-0" />{member.email}</span>
        ) : null}
        {member.monthly_salary ? (
          <span className="tabular-nums">Оклад <span className="font-medium text-foreground">{formatMoney(member.monthly_salary)}</span></span>
        ) : null}
      </div>
    </div>
  )
}

function OperatorChip({
  operator,
  role,
  isPrimary,
  onEdit,
}: {
  operator: Operator
  role: CompanyOperatorRole
  isPrimary: boolean
  onEdit?: () => void
}) {
  const profile = operator.operator_profiles?.[0]
  const displayName = getOperatorDisplayName({
    ...operator,
    full_name: profile?.full_name || null,
  })
  return (
    <div className="group flex items-center gap-2.5 px-3 py-2">
      <Avatar name={displayName} photoUrl={profile?.photo_url || null} size="sm" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm text-foreground" title={displayName}>{displayName}</p>
        <p className="truncate text-[11px] text-muted-foreground">
          {COMPANY_ROLE_LABEL[role]}
          {profile?.phone ? ` · ${profile.phone}` : ''}
        </p>
      </div>
      {isPrimary ? (
        <span title="Основная точка" className="shrink-0 rounded-md bg-amber-400/15 px-1.5 py-0.5 text-[10px] font-medium text-amber-700 dark:text-amber-300">
          основная
        </span>
      ) : null}
      {onEdit ? (
        <button
          type="button"
          onClick={onEdit}
          title="Изменить точки"
          aria-label={`Изменить точки: ${displayName}`}
          className="shrink-0 rounded-lg p-1.5 text-muted-foreground opacity-60 transition hover:bg-surface-hover hover:text-foreground group-hover:opacity-100"
        >
          <PencilLine className="h-3.5 w-3.5" />
        </button>
      ) : null}
    </div>
  )
}

function CompanyBranch({
  company,
  assignments,
  operatorsById,
  onEditOperator,
  canEdit = true,
}: {
  company: Company
  assignments: Assignment[]
  operatorsById: Map<string, Operator>
  onEditOperator?: (operator: Operator) => void
  canEdit?: boolean
}) {
  // Считаем только тех, кого показываем: закрепления уволенных/выключенных
  // операторов остаются в базе, и счётчик «12» при 6 строках путал.
  const shown = assignments.filter((item) => operatorsById.has(item.operator_id))
  const leadAssignments = shown.filter((item) => item.role_in_company !== 'operator')
  const operatorAssignments = shown.filter((item) => item.role_in_company === 'operator')
  const row = (assignment: Assignment) => {
    const operator = operatorsById.get(assignment.operator_id)
    if (!operator) return null
    return (
      <OperatorChip
        key={assignment.id}
        operator={operator}
        role={assignment.role_in_company}
        isPrimary={assignment.is_primary}
        onEdit={canEdit && onEditOperator ? () => onEditOperator(operator) : undefined}
      />
    )
  }

  return (
    <Card className="overflow-hidden border-border bg-white p-0 text-foreground dark:bg-white/[0.03]">
      <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
        <div className="flex min-w-0 items-center gap-2">
          <Building2 className="h-4 w-4 shrink-0 text-muted-foreground" />
          <h3 className="truncate text-sm font-semibold">{company.name}</h3>
        </div>
        <span className="shrink-0 rounded-full bg-surface-muted px-2 py-0.5 text-xs tabular-nums text-muted-foreground">{shown.length} чел.</span>
      </div>

      <div className="px-1 pt-2">
        <p className="px-3 pb-1 text-[11px] font-medium text-muted-foreground">{getCompanyLeadTitle(company)}</p>
        {leadAssignments.length > 0 ? (
          <div>{leadAssignments.map(row)}</div>
        ) : (
          <p className="px-3 py-2 text-xs text-amber-700 dark:text-amber-300">Не назначен</p>
        )}
      </div>

      <div className="px-1 pb-2 pt-2">
        <p className="px-3 pb-1 text-[11px] font-medium text-muted-foreground">Операторы · {operatorAssignments.length}</p>
        {operatorAssignments.length > 0 ? (
          <div className="divide-y divide-border/60">{operatorAssignments.map(row)}</div>
        ) : (
          <p className="px-3 py-2 text-xs text-muted-foreground">Пока никого</p>
        )}
      </div>
    </Card>
  )
}

// ===================== ORG CHART =====================

/** Узел схемы: одинаковая ширина у всех узлов одного уровня — дерево ровное. */
function OrgNode({
  label,
  name,
  sub,
  tone = 'plain',
  icon,
}: {
  label: string
  name: string
  sub?: string
  tone?: 'owner' | 'lead' | 'plain'
  icon?: React.ReactNode
}) {
  const toneClass =
    tone === 'owner'
      ? 'border-amber-400/50 bg-amber-50 dark:bg-amber-500/10'
      : tone === 'lead'
        ? 'border-border bg-white dark:bg-white/[0.04]'
        : 'border-border bg-white dark:bg-white/[0.03]'
  return (
    <div className={`w-56 rounded-xl border px-3 py-2.5 shadow-sm ${toneClass}`}>
      <div className="flex items-center gap-2.5">
        <Avatar name={name} size="sm" />
        <div className="min-w-0">
          <div className="flex items-center gap-1 text-[11px] text-muted-foreground">
            {icon}
            {label}
          </div>
          <div className="truncate text-sm font-semibold text-foreground" title={name}>{name}</div>
          {sub ? <div className="text-[11px] text-muted-foreground">{sub}</div> : null}
        </div>
      </div>
    </div>
  )
}

const LINE = 'border-slate-300 dark:border-white/15'

/** Вертикальная линия от родителя вниз. */
function Stem() {
  return <div className={`h-5 w-px border-l ${LINE}`} />
}

/**
 * Дети узла в ряд с правильными линиями: у каждого ребёнка сверху «плечи»
 * влево и вправо до соседей и вертикаль к нему. Линии считает сама вёрстка,
 * поэтому они совпадают при любой ширине и числе детей.
 */
function Branch({ children }: { children: React.ReactNode[] }) {
  const items = children.filter(Boolean)
  return (
    <div className="flex items-start justify-center">
      {items.map((child, idx) => (
        <div key={idx} className="flex flex-col items-center px-2">
          <div className="relative h-5 w-full">
            {items.length > 1 && idx > 0 ? <div className={`absolute left-0 top-0 w-1/2 border-t ${LINE}`} /> : null}
            {items.length > 1 && idx < items.length - 1 ? <div className={`absolute right-0 top-0 w-1/2 border-t ${LINE}`} /> : null}
            <div className={`absolute left-1/2 top-0 h-full border-l ${LINE}`} />
          </div>
          {child}
        </div>
      ))}
    </div>
  )
}

function OrgChart({
  owners,
  managers,
  marketers,
  companies,
  assignments,
  operatorsById,
}: {
  owners: StaffMember[]
  managers: StaffMember[]
  marketers: StaffMember[]
  companies: Company[]
  assignments: Assignment[]
  operatorsById: Map<string, Operator>
}) {
  // Только живые закрепления и только те точки, где кто-то есть.
  const byCompany = companies
    .map((company) => {
      const list = assignments.filter((a) => a.company_id === company.id && a.is_active && operatorsById.has(a.operator_id))
      return {
        company,
        leads: list.filter((a) => a.role_in_company !== 'operator'),
        ops: list.filter((a) => a.role_in_company === 'operator'),
        total: list.length,
      }
    })
    .filter((item) => item.total > 0)

  const points = byCompany.length ? (
    <Branch>
      {byCompany.map(({ company, leads, ops, total }) => (
        <div key={company.id} className="w-60 overflow-hidden rounded-xl border border-border bg-white shadow-sm dark:bg-white/[0.03]">
          <div className="flex items-center justify-between gap-2 border-b border-border bg-surface-muted/60 px-3 py-2">
            <div className="flex min-w-0 items-center gap-1.5">
              <Building2 className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
              <span className="truncate text-sm font-semibold text-foreground">{company.name}</span>
            </div>
            <span className="shrink-0 text-[11px] tabular-nums text-muted-foreground">{total} чел.</span>
          </div>
          {leads.map((a) => {
            const op = operatorsById.get(a.operator_id)!
            return (
              <div key={a.id} className="flex items-center gap-2 border-b border-border bg-emerald-500/5 px-3 py-2">
                <Avatar name={getOperatorDisplayName(op)} size="sm" />
                <div className="min-w-0">
                  <div className="truncate text-sm font-medium text-foreground">{getOperatorDisplayName(op)}</div>
                  <div className="text-[11px] text-emerald-700 dark:text-emerald-300">{COMPANY_ROLE_LABEL[a.role_in_company]}</div>
                </div>
              </div>
            )
          })}
          {leads.length === 0 ? (
            <div className="border-b border-border px-3 py-2 text-[11px] text-amber-700 dark:text-amber-300">{getCompanyLeadTitle(company)} не назначен</div>
          ) : null}
          <div className="py-1">
            {ops.map((a) => {
              const op = operatorsById.get(a.operator_id)!
              return (
                <div key={a.id} className="truncate px-3 py-1 text-[13px] text-body" title={getOperatorDisplayName(op)}>
                  {getOperatorDisplayName(op)}
                </div>
              )
            })}
            {ops.length === 0 ? <div className="px-3 py-1 text-[11px] text-muted-foreground">Операторов нет</div> : null}
          </div>
        </div>
      ))}
    </Branch>
  ) : null

  const second = [
    ...managers.map((m) => (
      <div key={m.id} className="flex flex-col items-center">
        <OrgNode label="Руководитель" name={getPersonName(m)} tone="lead" icon={<ShieldCheck className="h-3 w-3" />} />
      </div>
    )),
    ...marketers.map((m) => (
      <OrgNode key={m.id} label="Маркетолог" name={getPersonName(m)} tone="lead" icon={<Sparkles className="h-3 w-3" />} />
    )),
  ]

  return (
    <section className="space-y-4">
      <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
        <Network className="h-5 w-5 text-amber-600 dark:text-amber-300" />
        Организационная схема
      </h2>

      {owners.length === 0 && managers.length === 0 && marketers.length === 0 ? (
        <div className="py-12 text-center text-sm text-slate-500">Нет данных о сотрудниках для отображения схемы</div>
      ) : (
        <Card className="overflow-x-auto border-border bg-white p-6 dark:bg-white/[0.02]">
          <div className="mx-auto flex min-w-max flex-col items-center">
            {owners.length > 0 ? (
              <div className="flex gap-4">
                {owners.map((o) => (
                  <OrgNode key={o.id} label="Владелец" name={getPersonName(o)} tone="owner" icon={<Crown className="h-3 w-3 text-amber-500" />} />
                ))}
              </div>
            ) : null}

            {second.length > 0 ? (
              <>
                {owners.length > 0 ? <Stem /> : null}
                <Branch>{second}</Branch>
              </>
            ) : null}

            {/* Точки подчиняются руководству: одна вертикаль от уровня руководства. */}
            {points ? (
              <>
                <Stem />
                {points}
              </>
            ) : null}
          </div>
        </Card>
      )}
    </section>
  )
}

export default function StructurePage() {
  const { can } = useCapabilities()
  const canSaveAssignments = can('structure.save_assignments')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [staff, setStaff] = useState<StaffMember[]>([])
  const [companies, setCompanies] = useState<Company[]>([])
  const [operators, setOperators] = useState<Operator[]>([])
  const [assignments, setAssignments] = useState<Assignment[]>([])
  const [careerLinks, setCareerLinks] = useState<CareerLink[]>([])
  const [editingOperator, setEditingOperator] = useState<Operator | null>(null)
  useModalEscape(!!editingOperator, () => setEditingOperator(null))
  const [editorRows, setEditorRows] = useState<AssignmentEditorRow[]>([])
  const pointPositions = usePointPositions()
  const [savingAssignments, setSavingAssignments] = useState(false)
  const [companyFilter, setCompanyFilter] = useState('all')
  const [roleFilter, setRoleFilter] = useState<'all' | CompanyOperatorRole>('all')
  const [searchQuery, setSearchQuery] = useState('')
  const [viewMode, setViewMode] = useState<'tree' | 'career' | 'schema'>('tree')

  useEffect(() => {
    let ignore = false

    const loadStructure = async () => {
      try {
        setLoading(true)
        setError(null)

        const response = await fetch('/api/admin/structure', { cache: 'no-store' }).catch(() => null)
        const json = (await response?.json().catch(() => null)) as StructureResponse | null
        if (ignore) return

        if (!response?.ok || !json?.ok || !json.data) {
          setError(json?.error || 'Не удалось загрузить структуру')
          return
        }

        setStaff(json.data.staff || [])
        setCompanies(json.data.companies || [])
        setOperators(json.data.operators || [])
        setAssignments(json.data.assignments || [])
        setCareerLinks((json.data.careerLinks || []) as CareerLink[])
      } catch (loadError: any) {
        if (!ignore) setError(loadError?.message || 'Не удалось загрузить структуру')
      } finally {
        if (!ignore) setLoading(false)
      }
    }

    loadStructure()
    return () => {
      ignore = true
    }
  }, [])

  const owners = useMemo(() => staff.filter((member) => member.role === 'owner'), [staff])
  const managers = useMemo(() => staff.filter((member) => member.role === 'manager'), [staff])
  const marketers = useMemo(() => staff.filter((member) => member.role === 'marketer'), [staff])
  const operatorsById = useMemo(() => new Map(operators.map((operator) => [operator.id, operator])), [operators])
  const companiesById = useMemo(() => new Map(companies.map((company) => [company.id, company])), [companies])
  const assignmentsByCompany = useMemo(() => {
    const q = searchQuery.trim().toLowerCase()
    const map = new Map<string, Assignment[]>()
    for (const assignment of assignments) {
      if (companyFilter !== 'all' && assignment.company_id !== companyFilter) continue
      if (roleFilter !== 'all' && assignment.role_in_company !== roleFilter) continue
      if (q) {
        const op = operatorsById.get(assignment.operator_id)
        if (!op) continue
        const profile = op.operator_profiles?.[0]
        const hay = `${op.name || ''} ${op.short_name || ''} ${profile?.full_name || ''} ${profile?.phone || ''} ${profile?.email || ''}`.toLowerCase()
        if (!hay.includes(q)) continue
      }
      const bucket = map.get(assignment.company_id) || []
      bucket.push(assignment)
      map.set(assignment.company_id, bucket)
    }
    return map
  }, [assignments, companyFilter, roleFilter, searchQuery, operatorsById])
  const visibleCompanies = useMemo(
    () => companies.filter((company) => companyFilter === 'all' || company.id === companyFilter),
    [companies, companyFilter],
  )

  // Операторы у которых нет ни одного назначения на точку (потеряшки)
  const unassignedOperators = useMemo(() => {
    if (companyFilter !== 'all') return [] as Operator[]
    const assignedIds = new Set(assignments.map((a) => a.operator_id))
    const q = searchQuery.trim().toLowerCase()
    return operators.filter((op) => {
      if (assignedIds.has(op.id)) return false
      if (q) {
        const profile = op.operator_profiles?.[0]
        const hay = `${op.name || ''} ${op.short_name || ''} ${profile?.full_name || ''} ${profile?.phone || ''} ${profile?.email || ''}`.toLowerCase()
        if (!hay.includes(q)) return false
      }
      return true
    })
  }, [operators, assignments, companyFilter, searchQuery])
  const leadRoster = useMemo(() => {
    return assignments
      .filter((assignment) => assignment.role_in_company !== 'operator')
      .map((assignment) => {
        const operator = operatorsById.get(assignment.operator_id)
        const company = companiesById.get(assignment.company_id)
        return {
          assignment,
          operator,
          company,
        }
      })
      .filter((item) => item.operator && item.company)
      .sort((a, b) => {
        const companyCompare = String(a.company?.name || '').localeCompare(String(b.company?.name || ''), 'ru')
        if (companyCompare !== 0) return companyCompare
        return String(a.operator?.name || '').localeCompare(String(b.operator?.name || ''), 'ru')
      })
  }, [assignments, operatorsById, companiesById])
  const openOperatorEditor = (operator: Operator) => {
    const rows = assignments
      .filter((assignment) => assignment.operator_id === operator.id)
      .slice(0, 2)
      .map((assignment) => ({
        id: assignment.id,
        company_id: assignment.company_id,
        role_in_company: assignment.role_in_company,
        ...(assignment.position_id !== undefined ? { position_id: assignment.position_id ?? null } : {}),
        is_primary: assignment.is_primary,
        is_active: assignment.is_active,
        notes: assignment.notes || '',
      }))

    setEditingOperator(operator)
    setEditorRows(rows.length > 0 ? rows : [{ company_id: '', role_in_company: 'operator', is_primary: true, is_active: true, notes: '' }])
  }

  const closeOperatorEditor = () => {
    if (savingAssignments) return
    setEditingOperator(null)
    setEditorRows([])
  }

  const saveOperatorAssignments = async () => {
    if (!editingOperator) return

    try {
      setSavingAssignments(true)
      const response = await fetch('/api/admin/operator-company-assignments', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'saveAssignments',
          operatorId: editingOperator.id,
          assignments: editorRows,
        }),
      })

      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) {
        throw new Error(json?.error || 'Не удалось сохранить структуру оператора')
      }

      const nextRows = (json.data || []) as Assignment[]
      setAssignments((prev) => [
        ...prev.filter((item) => item.operator_id !== editingOperator.id),
        ...nextRows.map((row: any) => ({
          id: row.id,
          operator_id: row.operator_id,
          company_id: row.company_id,
          role_in_company: row.role_in_company,
          position_id: row.position_id,
          is_primary: row.is_primary,
          is_active: row.is_active,
          notes: row.notes,
          created_at: row.created_at,
          updated_at: row.updated_at,
        })),
      ])
      closeOperatorEditor()
    } catch (saveError: any) {
      setError(saveError?.message || 'Не удалось сохранить структуру оператора')
    } finally {
      setSavingAssignments(false)
    }
  }

  return (
    <>
        <div className="app-page-wide space-y-6">
          <AdminPageHeader
            title="Структура команды"
            description="Управленческая и операционная иерархия: кто отвечает за клуб, кто ведёт маркетинг и кто старший по каждой точке."
            icon={<Network className="h-5 w-5" />}
            accent="amber"
            backHref="/"
            toolbar={
              <div className="flex flex-wrap gap-2 text-xs">
                {[
                  { label: 'Команда', value: operators.length + staff.length },
                  { label: 'Точек', value: companies.length },
                  { label: 'Руководство', value: owners.length + managers.length + marketers.length },
                  { label: 'Операторов', value: operators.length },
                ].map((item) => (
                  <span key={item.label} className="inline-flex items-baseline gap-1.5 rounded-full border border-border bg-surface-muted px-3 py-1 text-muted-foreground">
                    {item.label}
                    <span className="text-sm font-semibold tabular-nums text-foreground">{item.value}</span>
                  </span>
                ))}
              </div>
            }
          />

          {loading && staff.length === 0 ? (
            <Card className="border-border bg-white dark:bg-slate-950/65 p-8 text-foreground">
              <div className="flex items-center gap-3 text-body">
                <Loader2 className="h-5 w-5 animate-spin text-amber-600 dark:text-amber-300" />
                Загружаем оргструктуру...
              </div>
            </Card>
          ) : error ? (
            <Card className="border-rose-500/20 bg-rose-500/10 p-6 text-rose-100">
              {error}
            </Card>
          ) : (
            <>
              <Card className="border-border bg-white dark:bg-slate-950/60 p-4 text-foreground">
                <div className="flex flex-wrap gap-2">
                  <Button variant={viewMode === 'tree' ? 'default' : 'outline'} onClick={() => setViewMode('tree')}>
                    Древо точек
                  </Button>
                  <Button variant={viewMode === 'career' ? 'default' : 'outline'} onClick={() => setViewMode('career')}>
                    Карьерный рост
                  </Button>
                  <Button variant={viewMode === 'schema' ? 'default' : 'outline'} onClick={() => setViewMode('schema')}>
                    Схема
                  </Button>
                </div>
              </Card>

              {viewMode === 'schema' ? (
                <OrgChart
                  owners={owners}
                  managers={managers}
                  marketers={marketers}
                  companies={companies}
                  assignments={assignments}
                  operatorsById={operatorsById}
                />
              ) : viewMode === 'tree' ? (
                <>
              <Card className="border-border bg-white dark:bg-slate-950/60 p-4 text-foreground">
                <div className="grid gap-3 md:grid-cols-4">
                  <div className="md:col-span-2">
                    <label className="mb-2 block text-xs uppercase tracking-[0.16em] text-slate-500">Поиск</label>
                    <div className="relative">
                      <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-500" />
                      <input
                        value={searchQuery}
                        onChange={(e) => setSearchQuery(e.target.value)}
                        placeholder="ФИО, телефон, email..."
                        className="h-10 w-full rounded-xl border border-border bg-white dark:bg-slate-900/70 pl-9 pr-3 text-sm text-foreground outline-none focus:border-amber-400/50"
                      />
                    </div>
                  </div>
                  <div>
                    <label className="mb-2 block text-xs uppercase tracking-[0.16em] text-slate-500">Точка</label>
                    <select
                      value={companyFilter}
                      onChange={(event) => setCompanyFilter(event.target.value)}
                      className="h-10 w-full rounded-xl border border-border bg-white dark:bg-slate-900/70 px-3 text-sm text-foreground outline-none focus:border-amber-400/50"
                    >
                      <option value="all">Все точки</option>
                      {companies.map((company) => (
                        <option key={company.id} value={company.id}>
                          {company.name}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="mb-2 block text-xs uppercase tracking-[0.16em] text-slate-500">Роль в точке</label>
                    <select
                      value={roleFilter}
                      onChange={(event) => setRoleFilter(event.target.value as 'all' | CompanyOperatorRole)}
                      className="h-10 w-full rounded-xl border border-border bg-white dark:bg-slate-900/70 px-3 text-sm text-foreground outline-none focus:border-amber-400/50"
                    >
                      <option value="all">Все роли</option>
                      <option value="operator">Оператор</option>
                      <option value="senior_operator">Старший оператор</option>
                      <option value="senior_cashier">Старший кассир</option>
                    </select>
                  </div>
                </div>
                {(searchQuery || companyFilter !== 'all' || roleFilter !== 'all') && (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        setCompanyFilter('all')
                        setRoleFilter('all')
                        setSearchQuery('')
                      }}
                    >
                      Сбросить фильтры
                    </Button>
                    {/* Чипы — компактно показывают что выбрано */}
                    {searchQuery && (
                      <span className="px-2.5 py-1 rounded-full bg-amber-500/10 border border-amber-500/30 text-amber-700 dark:text-amber-300 text-xs">
                        🔍 «{searchQuery}»
                      </span>
                    )}
                    {companyFilter !== 'all' && (
                      <span className="px-2.5 py-1 rounded-full bg-blue-500/10 border border-blue-500/30 text-blue-700 dark:text-blue-300 text-xs">
                        🏢 {companies.find((c) => c.id === companyFilter)?.name}
                      </span>
                    )}
                    {roleFilter !== 'all' && (
                      <span className="px-2.5 py-1 rounded-full bg-amber-500/10 border border-amber-500/30 text-amber-700 dark:text-amber-300 text-xs">
                        👔 {COMPANY_ROLE_LABEL[roleFilter as CompanyOperatorRole]}
                      </span>
                    )}
                  </div>
                )}
              </Card>

              <section className="space-y-4">
                <div className="flex items-center gap-2">
                  <Crown className="h-5 w-5 text-amber-600 dark:text-amber-300" />
                  <h2 className="text-base font-semibold text-foreground">Руководство</h2>
                </div>

                <Card className="overflow-hidden border-border bg-white p-0 dark:bg-white/[0.03]">
                  <div className="divide-y divide-border">
                    {[...owners, ...managers, ...marketers].map((member) => (
                      <StaffNode key={member.id} member={member} />
                    ))}
                    {managers.length === 0 ? <p className="px-4 py-2.5 text-xs text-muted-foreground">Руководитель пока не назначен.</p> : null}
                    {marketers.length === 0 ? <p className="px-4 py-2.5 text-xs text-muted-foreground">Маркетолог пока не назначен.</p> : null}
                  </div>
                </Card>
              </section>

              <section className="space-y-4">
                <div className="flex items-center gap-2">
                  <Building2 className="h-5 w-5 text-amber-600 dark:text-amber-300" />
                  <h2 className="text-base font-semibold text-foreground">Точки</h2>
                </div>

                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                  {visibleCompanies.map((company) => (
                    <CompanyBranch
                      key={company.id}
                      company={company}
                      assignments={assignmentsByCompany.get(company.id) || []}
                      operatorsById={operatorsById}
                      onEditOperator={canSaveAssignments ? openOperatorEditor : undefined}
                      canEdit={canSaveAssignments}
                    />
                  ))}
                </div>

                {unassignedOperators.length > 0 && (
                  <Card className="border-orange-500/30 bg-orange-500/5 p-5 text-foreground mt-5">
                    <div className="flex items-center gap-2 mb-3">
                      <span className="text-orange-400">⚠</span>
                      <h3 className="text-base font-semibold">Без назначения на точку — {unassignedOperators.length}</h3>
                    </div>
                    <p className="text-sm text-slate-400 mb-4">
                      Эти операторы созданы, но не привязаны ни к одной точке. Открой профиль и выбери точки.
                    </p>
                    <div className="grid grid-cols-1 gap-1 md:grid-cols-2 lg:grid-cols-4">
                      {unassignedOperators.map((op) => (
                        <OperatorChip
                          key={op.id}
                          operator={op}
                          role="operator"
                          isPrimary={false}
                          onEdit={canSaveAssignments ? () => openOperatorEditor(op) : undefined}
                        />
                      ))}
                    </div>
                  </Card>
                )}
              </section>

                </>
              ) : (
                <section className="space-y-5">
                  <div className="grid gap-4 xl:grid-cols-2">
                    <Card className="border-border bg-white dark:bg-slate-950/60 p-5 text-foreground">
                      <div className="flex items-center gap-2">
                        <ShieldCheck className="h-5 w-5 text-amber-600 dark:text-amber-300" />
                        <h2 className="text-xl font-semibold">Старшие по точкам</h2>
                      </div>
                      <div className="mt-4 space-y-3">
                        {leadRoster.length > 0 ? (
                          leadRoster.map(({ assignment, operator, company }) => (
                            <div key={assignment.id} className="rounded-2xl border border-border bg-slate-50 dark:bg-black/20 p-4">
                              <div className="flex items-start justify-between gap-3">
                                <div>
                                  <div className="text-base font-semibold text-foreground">
                                    {getOperatorDisplayName({
                                      ...(operator as Operator),
                                      full_name: operator?.operator_profiles?.[0]?.full_name || null,
                                    })}
                                  </div>
                                  <div className="mt-1 text-sm text-muted-foreground">
                                    {company?.name} • {positionLabel(pointPositions, assignment.position_id, assignment.role_in_company)}
                                  </div>
                                </div>
                                {assignment.is_primary ? (
                                  <span className="rounded-full border border-amber-400/20 bg-amber-400/10 px-2 py-1 text-[10px] uppercase tracking-[0.16em] text-amber-700 dark:text-amber-300">
                                    main
                                  </span>
                                ) : null}
                              </div>
                              <div className="mt-3 grid gap-2 sm:grid-cols-2">
                                <div className="rounded-xl border border-border bg-white dark:bg-white/[0.03] px-3 py-2">
                                  <div className="text-[11px] uppercase tracking-[0.16em] text-slate-500">Должность</div>
                                  <div className="mt-1 text-sm text-body">
                                    {operator?.operator_profiles?.[0]?.position || 'Оператор точки'}
                                  </div>
                                </div>
                                <div className="rounded-xl border border-border bg-white dark:bg-white/[0.03] px-3 py-2">
                                  <div className="text-[11px] uppercase tracking-[0.16em] text-slate-500">Стаж</div>
                                  <div className="mt-1 text-sm text-body">
                                    {formatTenure(operator?.operator_profiles?.[0]?.hire_date || null)}
                                  </div>
                                </div>
                              </div>
                            </div>
                          ))
                        ) : (
                          <div className="text-sm text-slate-500">Старшие роли по точкам ещё не назначены.</div>
                        )}
                      </div>
                    </Card>

                    <Card className="border-border bg-white dark:bg-slate-950/60 p-5 text-foreground">
                      <div className="flex items-center gap-2">
                        <Sparkles className="h-5 w-5 text-amber-600 dark:text-amber-300" />
                        <h2 className="text-xl font-semibold">Рост в staff</h2>
                      </div>
                      <div className="mt-4 space-y-3">
                        {careerLinks.length > 0 ? (
                          careerLinks.map((link) => (
                            <div key={link.id} className="rounded-2xl border border-border bg-slate-50 dark:bg-black/20 p-4">
                              <div className="text-base font-semibold text-foreground">
                                {getOperatorDisplayName({
                                  id: link.operator?.id || '',
                                  name: link.operator?.name || 'Оператор',
                                  short_name: link.operator?.short_name || null,
                                  full_name: link.operator?.operator_profiles?.[0]?.full_name || null,
                                } as any)}
                              </div>
                              <div className="mt-1 text-sm text-muted-foreground">
                                Переведён в staff как {getStaffRoleLabel(link.assigned_role)}
                              </div>
                              <div className="mt-3 grid gap-2 sm:grid-cols-2">
                                <div className="rounded-xl border border-border bg-white dark:bg-white/[0.03] px-3 py-2">
                                  <div className="text-[11px] uppercase tracking-[0.16em] text-slate-500">Дата повышения</div>
                                  <div className="mt-1 text-sm text-body">
                                    {new Date(link.updated_at || link.assigned_at).toLocaleDateString('ru-RU')}
                                  </div>
                                </div>
                                <div className="rounded-xl border border-border bg-white dark:bg-white/[0.03] px-3 py-2">
                                  <div className="text-[11px] uppercase tracking-[0.16em] text-slate-500">Оклад staff</div>
                                  <div className="mt-1 text-sm text-body">{formatMoney(link.staff?.monthly_salary)}</div>
                                </div>
                              </div>
                            </div>
                          ))
                        ) : (
                          <div className="text-sm text-slate-500">Переходов из оператора в staff пока нет.</div>
                        )}
                      </div>
                    </Card>
                  </div>

                </section>
              )}
            </>
          )}
        </div>

      {editingOperator ? (
        <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm overflow-y-auto"
          onClick={(e) => { if (e.target === e.currentTarget) setEditingOperator(null) }}>
          <Card className="w-full max-w-3xl max-h-[calc(100vh-2rem)] overflow-y-auto border-border bg-white dark:bg-slate-950/96 p-6 text-foreground shadow-[0_24px_80px_rgba(0,0,0,0.5)]">
            <div className="flex items-start justify-between gap-4">
              <div>
                <div className="text-[11px] uppercase tracking-[0.18em] text-slate-500">Редактор дерева</div>
                <h3 className="mt-2 text-2xl font-semibold tracking-[-0.03em]">
                  {getOperatorDisplayName({
                    ...editingOperator,
                    full_name: editingOperator.operator_profiles?.[0]?.full_name || null,
                  })}
                </h3>
                <p className="mt-2 text-sm text-muted-foreground">
                  Назначьте оператору до двух точек и укажите роль внутри каждой точки прямо из структуры.
                </p>
              </div>
              <Button variant="ghost" size="icon" onClick={closeOperatorEditor} disabled={savingAssignments}>
                <X className="h-4 w-4" />
              </Button>
            </div>

            <div className="mt-6 space-y-4">
              {editorRows.map((row, index) => (
                <div key={`${row.id || 'new'}-${index}`} className="rounded-2xl border border-border bg-surface-muted p-4">
                  <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                    <div>
                      <label className="mb-2 block text-xs uppercase tracking-[0.16em] text-slate-500">Точка</label>
                      <select
                        value={row.company_id}
                        onChange={(event) =>
                          setEditorRows((prev) =>
                            prev.map((item, itemIndex) =>
                              itemIndex === index ? { ...item, company_id: event.target.value } : item,
                            ),
                          )
                        }
                        className="h-10 w-full rounded-xl border border-border bg-white dark:bg-slate-900/70 px-3 text-sm text-foreground outline-none focus:border-amber-400/50"
                      >
                        <option value="">Выберите точку</option>
                        {companies.map((company) => (
                          <option key={company.id} value={company.id}>
                            {company.name}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div>
                      <label className="mb-2 block text-xs uppercase tracking-[0.16em] text-slate-500">Роль</label>
<PositionSelect
                        companyId={row.company_id}
                        role={row.role_in_company}
                        positionId={row.position_id}
                        positions={pointPositions}
                        onChange={(next) =>
                          setEditorRows((prev) =>
                            prev.map((item, itemIndex) =>
                              itemIndex === index ? { ...item, role_in_company: next.role, position_id: next.position_id } : item,
                            ),
                          )
                        }
                        className="h-10 rounded-xl"
                      />
                    </div>

                    <label className="flex items-center gap-3 rounded-xl border border-border bg-white dark:bg-slate-900/70 px-3 text-sm text-body">
                      <input
                        type="checkbox"
                        checked={row.is_primary}
                        onChange={(event) =>
                          setEditorRows((prev) =>
                            prev.map((item, itemIndex) => ({
                              ...item,
                              is_primary: itemIndex === index ? event.target.checked : event.target.checked ? false : item.is_primary,
                            })),
                          )
                        }
                      />
                      Основная точка
                    </label>

                    <div className="flex items-center justify-end">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setEditorRows((prev) => prev.filter((_, itemIndex) => itemIndex !== index))}
                        disabled={editorRows.length === 1}
                      >
                        Удалить
                      </Button>
                    </div>
                  </div>

                  <textarea
                    value={row.notes}
                    onChange={(event) =>
                      setEditorRows((prev) =>
                        prev.map((item, itemIndex) => (itemIndex === index ? { ...item, notes: event.target.value } : item)),
                      )
                    }
                    rows={2}
                    placeholder="Комментарий по роли или точке"
                    className="mt-3 w-full rounded-xl border border-border bg-white dark:bg-slate-900/70 px-3 py-2 text-sm text-foreground outline-none placeholder:text-slate-500 focus:border-amber-400/50"
                  />
                </div>
              ))}
            </div>

            <div className="mt-4 flex flex-wrap gap-3">
              <Button
                variant="outline"
                onClick={() =>
                  setEditorRows((prev) =>
                    prev.length >= 2
                      ? prev
                      : [...prev, { company_id: '', role_in_company: 'operator', is_primary: prev.length === 0, is_active: true, notes: '' }],
                  )
                }
                disabled={editorRows.length >= 2 || savingAssignments}
              >
                <Plus className="mr-2 h-4 w-4" />
                Добавить точку
              </Button>

              <div className="ml-auto flex flex-wrap gap-3">
                <Button variant="ghost" onClick={closeOperatorEditor} disabled={savingAssignments}>
                  Отмена
                </Button>
                {canSaveAssignments && (
                  <Button onClick={saveOperatorAssignments} disabled={savingAssignments}>
                    {savingAssignments ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
                    Сохранить дерево
                  </Button>
                )}
              </div>
            </div>
          </Card>
        </div>
      ) : null}
    </>
  )
}
