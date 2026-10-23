'use client'

import { useState } from 'react'
import { MapPin } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { AppModal } from '@/components/ui/app-modal'
import { toast } from '@/components/ui/use-toast'

/**
 * Охват сотрудника по точкам: «все точки» или выбранные. Задаёт владелец — сервер
 * откажет остальным. Сотрудник с охватом видит только свои точки: доходы, расходы,
 * склад, смены, операторов этих точек.
 */
export function StaffCompaniesButton(props: {
  staffId: string
  staffName: string
  role: string | null
  companyIds: string[] | null
  /** Точки организации — со страницы, один запрос на всех. */
  companies: Array<{ id: string; name: string }>
  onSaved: () => void
}) {
  const { companies } = props
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const [saving, setSaving] = useState(false)

  if (props.role === 'owner') return null

  const count = props.companyIds?.length || 0
  const label = count === 0 ? 'Все точки' : count === 1 ? '1 точка' : `${count} точки`

  function openModal() {
    setSelected(props.companyIds || [])
    setOpen(true)
  }

  function toggle(id: string) {
    setSelected((current) => (current.includes(id) ? current.filter((value) => value !== id) : [...current, id]))
  }

  async function save() {
    setSaving(true)
    try {
      const res = await fetch('/api/admin/staff', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'setStaffCompanies',
          staffId: props.staffId,
          // Отмечены все — это то же, что «все точки»: новая точка тоже будет видна.
          companyIds: selected.length === 0 || selected.length === companies.length ? null : selected,
        }),
      })
      const body = await res.json().catch(() => null)
      if (!res.ok) throw new Error(body?.error || 'Не сохранилось')
      toast({ title: 'Точки сотрудника сохранены' })
      setOpen(false)
      props.onSaved()
    } catch (err: any) {
      toast({ title: 'Не получилось', description: err.message, variant: 'destructive' })
    }
    setSaving(false)
  }

  return (
    <>
      <Button
        size="sm"
        variant="outline"
        className="h-8 gap-1.5 border-border bg-white px-3 text-xs dark:bg-white/[0.03]"
        onClick={openModal}
        title="Какие точки видит сотрудник"
      >
        <MapPin className="h-3.5 w-3.5" />
        {label}
      </Button>
      <AppModal
        open={open}
        onClose={() => setOpen(false)}
        title={`Точки: ${props.staffName}`}
        description="Сотрудник видит только отмеченные точки — их доходы, расходы, склад, смены и операторов. Ничего не отмечено — все точки."
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setOpen(false)}>Отмена</Button>
            <Button onClick={save} disabled={saving} className="bg-blue-600 hover:bg-blue-700">
              {saving ? 'Сохраняю…' : 'Сохранить'}
            </Button>
          </div>
        }
      >
        <div className="space-y-2">
          {companies.map((company) => (
            <label key={company.id} className="flex cursor-pointer items-center gap-3 rounded-xl border border-border px-3 py-2 text-sm">
              <input type="checkbox" checked={selected.includes(company.id)} onChange={() => toggle(company.id)} />
              <span className="text-foreground">{company.name}</span>
            </label>
          ))}
          {companies.length === 0 && <p className="text-sm text-muted-foreground">Точек нет.</p>}
        </div>
      </AppModal>
    </>
  )
}
