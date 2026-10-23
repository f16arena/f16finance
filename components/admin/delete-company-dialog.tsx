'use client'

import { useEffect, useState } from 'react'
import { AlertTriangle, Loader2, ShieldCheck, Trash2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'

type HistoryItem = { label: string; count: number }
type Preview = {
  company: { id: string; name: string; code: string | null }
  history: HistoryItem[]
  canDelete: boolean
  ownerOnly: boolean
}

function normalize(value: string) {
  return value.trim().replace(/\s+/g, ' ').toLowerCase()
}

/**
 * Удаление точки. Сервер (api/admin/settings) сам решает, можно ли:
 * только владелец, только точка без истории, только с набранным названием.
 * Окно лишь показывает его ответ — обойти проверку из браузера нельзя.
 */
export function DeleteCompanyDialog(props: {
  companyId: string | null
  onClose: () => void
  onDeleted: () => void
}) {
  const [preview, setPreview] = useState<Preview | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [typed, setTyped] = useState('')
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  useEffect(() => {
    setPreview(null)
    setLoadError(null)
    setTyped('')
    setDeleteError(null)
    if (!props.companyId) return
    let cancelled = false
    fetch('/api/admin/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ entity: 'company', action: 'deletePreview', id: props.companyId }),
    })
      .then(async (response) => {
        const json = await response.json().catch(() => null)
        if (cancelled) return
        if (!response.ok) setLoadError(json?.error || 'Не удалось проверить точку')
        else setPreview(json as Preview)
      })
      .catch(() => !cancelled && setLoadError('Не удалось проверить точку'))
    return () => {
      cancelled = true
    }
  }, [props.companyId])

  const nameOk = !!preview && normalize(typed) !== '' && normalize(typed) === normalize(preview.company.name)

  async function handleDelete() {
    if (!preview || !nameOk) return
    setDeleting(true)
    setDeleteError(null)
    const response = await fetch('/api/admin/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ entity: 'company', action: 'delete', id: preview.company.id, confirmName: typed }),
    }).catch(() => null)
    const json = await response?.json().catch(() => null)
    setDeleting(false)
    if (!response?.ok) {
      setDeleteError(json?.error || 'Не удалось удалить')
      return
    }
    props.onDeleted()
  }

  const blocked = !!preview && !preview.canDelete

  return (
    <Dialog open={!!props.companyId} onOpenChange={(open) => { if (!open && !deleting) props.onClose() }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            {blocked ? <ShieldCheck className="h-5 w-5 text-emerald-500" /> : <Trash2 className="h-5 w-5 text-rose-500" />}
            {preview ? `Удалить «${preview.company.name}»?` : 'Удаление точки'}
          </DialogTitle>
          <DialogDescription>
            {blocked ? 'Эта точка защищена от удаления.' : 'Точка удаляется навсегда, вернуть её нельзя.'}
          </DialogDescription>
        </DialogHeader>

        {!preview && !loadError && (
          <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Проверяем, что связано с точкой…
          </div>
        )}
        {loadError && <p className="text-sm text-rose-600 dark:text-rose-300">{loadError}</p>}

        {preview && preview.ownerOnly && (
          <p className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2.5 text-sm text-amber-800 dark:text-amber-200">
            Удалить точку может только владелец организации.
          </p>
        )}

        {preview && preview.history.length > 0 && (
          <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 px-3 py-2.5 text-sm">
            <p className="mb-1.5 font-medium text-foreground">У точки есть история — удалить нельзя:</p>
            <ul className="space-y-0.5 text-muted-foreground">
              {preview.history.map((item) => (
                <li key={item.label}>
                  {item.count < 0 ? `${item.label}: не удалось проверить` : `${item.count.toLocaleString('ru-RU')} ${item.label}`}
                </li>
              ))}
            </ul>
            <p className="mt-2 text-xs text-muted-foreground">
              Вместе с точкой пропали бы и эти записи — из отчётов, зарплаты и налогов. Если точка больше не работает, снимите галочку «В структуре» и отключите её кассу.
            </p>
          </div>
        )}

        {preview && preview.canDelete && (
          <div className="space-y-2">
            <p className="flex items-start gap-2 text-sm text-muted-foreground">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
              <span>Чтобы подтвердить, наберите название точки: <b className="text-foreground">{preview.company.name}</b></span>
            </p>
            <Input
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              placeholder={preview.company.name}
              autoFocus
              onKeyDown={(e) => { if (e.key === 'Enter') handleDelete() }}
            />
          </div>
        )}

        {deleteError && <p className="text-sm text-rose-600 dark:text-rose-300">{deleteError}</p>}

        <DialogFooter className="gap-2 sm:gap-2">
          <Button type="button" variant="outline" onClick={props.onClose} disabled={deleting}>
            {blocked ? 'Понятно' : 'Отмена'}
          </Button>
          {preview?.canDelete && (
            <Button type="button" variant="destructive" onClick={handleDelete} disabled={!nameOk || deleting}>
              {deleting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Trash2 className="mr-2 h-4 w-4" />}
              Удалить навсегда
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
