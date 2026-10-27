'use client'

import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { usePersistentState } from '@/lib/client/use-persistent-state'
import { invalidateStoreCaches } from '@/lib/client/store-cache'
import type {
  AiParseResult,
  ExpenseCategoryOption,
  InventoryItem,
  InventoryReceiptDraft,
  ReceiptLine,
  ReceiptsResponse,
} from '@/components/store/receipts/types'
import {
  calcMarkupPercent,
  emptyLine,
  nextLineUid,
  parseMoney,
  parseQty,
  parseUnitCost,
} from '@/lib/store/receipts/format'

type ReceiptsData = NonNullable<ReceiptsResponse['data']>

interface UseReceiptFormArgs {
  data: ReceiptsData | null | undefined
  loading: boolean
  storeCompanyId: string | null | undefined
  locationId: string
  setLocationId: (value: string) => void
  setError: (value: string | null) => void
  setSuccess: (value: string | null) => void
  /** Перезагрузка списка приёмок после мутации (page.load в мягком режиме). */
  reload: () => Promise<void>
}

/**
 * Всё состояние и действия формы «Новый документ приёмки»: поставщик, накладная,
 * ИИ-разбор, строки товаров, оплата, шаблоны, черновики. Список документов,
 * загрузка данных и отмена приёмки остаются в page.
 */
export function useReceiptForm({
  data,
  loading,
  storeCompanyId,
  locationId,
  setLocationId,
  setError,
  setSuccess,
  reload,
}: UseReceiptFormArgs) {
  const [saving, setSaving] = useState(false)
  const [supplierId, setSupplierId] = useState('')
  const [draftId, setDraftId] = useState<string | null>(null)
  const [supplierMode, setSupplierMode] = useState<'existing' | 'new'>('existing')
  const [supplierName, setSupplierName] = useState('')
  const [supplierOrganizationName, setSupplierOrganizationName] = useState('')
  const [supplierBinIin, setSupplierBinIin] = useState('')
  const [receivedAt, setReceivedAt] = useState(new Date().toISOString().slice(0, 10))
  const [invoiceNumber, setInvoiceNumber] = useState('')
  const [invoiceFileUrl, setInvoiceFileUrl] = useState('')
  const [expenseCategoryId, setExpenseCategoryId] = useState('')
  const [paymentMethod, setPaymentMethod] = useState<'cash' | 'kaspi'>('cash')
  const [paymentMode, setPaymentMode] = useState<'now' | 'deferred'>('now')
  const [paymentReceiptFileUrl, setPaymentReceiptFileUrl] = useState('')
  const [uploadingPaymentReceipt, setUploadingPaymentReceipt] = useState(false)
  const [isConsignment, setIsConsignment] = useState(false)
  const [dueDate, setDueDate] = useState('')
  const [expenseCategoriesFallback, setExpenseCategoriesFallback] = useState<ExpenseCategoryOption[]>([])
  const [uploadingInvoice, setUploadingInvoice] = useState(false)
  const [aiParsing, setAiParsing] = useState(false)
  const [aiParseResult, setAiParseResult] = useState<AiParseResult | null>(null)
  const [comment, setComment] = useState('')
  const [lines, setLines] = useState<ReceiptLine[]>([emptyLine()])
  const [quickQuery, setQuickQuery] = useState('')
  // Повтор товара при быстром добавлении: отдельной строкой (накладная с акцией)
  // или прибавить количество (сканируешь коробку по одной штуке).
  const [repeatMode, setRepeatMode] = usePersistentState<'new-line' | 'increment'>('store.receipts.repeatMode', 'new-line')
  const [quickError, setQuickError] = useState<string | null>(null)
  const quickInputRef = useRef<HTMLInputElement>(null)
  const [templateName, setTemplateName] = useState('')
  const [savedTemplates, setSavedTemplates] = useState<Array<{ name: string; lines: ReceiptLine[] }>>([])
  const [bulkMarkupPercent, setBulkMarkupPercent] = useState('')
  const [bulkSalePrice, setBulkSalePrice] = useState('')
  const [showBulkTools, setShowBulkTools] = useState(false)
  const [showTemplatesTools, setShowTemplatesTools] = useState(false)
  const [formSheetOpen, setFormSheetOpen] = useState(false)

  useEffect(() => {
    try {
      const params = new URLSearchParams(window.location.search)
      const q = params.get('q')
      if (q) setQuickQuery(q)
    } catch { /* ignore query parse errors */ }
  }, [])

  useEffect(() => {
    try {
      const raw = localStorage.getItem('store-receipts-templates-v1')
      if (!raw) return
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) setSavedTemplates(parsed)
    } catch { /* ignore parse errors */ }
  }, [])

  useEffect(() => {
    if (expenseCategoryId) return
    const first = (data?.expense_categories || []).find((c) => String(c.accounting_group || '').trim().toLowerCase() === 'cogs')
      || expenseCategoriesFallback.find((c) => String(c.accounting_group || '').trim().toLowerCase() === 'cogs')
    if (first?.id) setExpenseCategoryId(first.id)
  }, [data?.expense_categories, expenseCategoriesFallback, expenseCategoryId])

  // Auto-pick supplier's preferred COGS category when supplier changes.
  useEffect(() => {
    if (!supplierId) return
    const supplier = (data?.suppliers || []).find((s) => s.id === supplierId)
    const preferred = supplier?.preferred_expense_category_id
    if (!preferred) return
    const isCogs = (data?.expense_categories || []).some(
      (c) => c.id === preferred && String(c.accounting_group || '').trim().toLowerCase() === 'cogs',
    )
    if (isCogs) setExpenseCategoryId(preferred)
  }, [supplierId, data?.suppliers, data?.expense_categories])

  useEffect(() => {
    const serverCogs = (data?.expense_categories || []).filter((c) => String(c.accounting_group || '').trim().toLowerCase() === 'cogs')
    if (serverCogs.length > 0) return
    let cancelled = false
    const loadExpenseCategories = async () => {
      try {
        const response = await fetch('/api/admin/expense-categories', { cache: 'no-store' })
        const json = await response.json().catch(() => null)
        if (!response.ok) return
        if (cancelled) return
        const rows = Array.isArray(json?.data) ? json.data : []
        setExpenseCategoriesFallback(rows as ExpenseCategoryOption[])
      } catch {
        if (!cancelled) setExpenseCategoriesFallback([])
      }
    }
    void loadExpenseCategories()
    return () => { cancelled = true }
  }, [data?.expense_categories])

  useEffect(() => {
    try {
      localStorage.setItem('store-receipts-templates-v1', JSON.stringify(savedTemplates))
    } catch { /* ignore write errors */ }
  }, [savedTemplates])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        quickInputRef.current?.focus()
        quickInputRef.current?.select()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  const receiptTotal = useMemo(() => {
    // Бонусные строки в сумму накладной не идут — товар получен бесплатно.
    return lines.reduce((sum, line) => sum + (line.is_bonus ? 0 : parseQty(line.quantity) * parseUnitCost(line.unit_cost)), 0)
  }, [lines])

  // Возврат/недопоставка по строкам (для сверки с накладной): не идёт на склад и в долг.
  const returnTotal = useMemo(() => {
    return lines.reduce((sum, line) => sum + (line.is_bonus ? 0 : parseQty(line.return_qty || '') * parseUnitCost(line.unit_cost)), 0)
  }, [lines])

  const catalogItems = useMemo(() => data?.items || [], [data?.items])

  const itemsById = useMemo(() => {
    const map = new Map<string, InventoryItem>()
    for (const item of catalogItems) map.set(item.id, item)
    return map
  }, [catalogItems])

  const patchLine = useCallback((uid: string, patch: Partial<ReceiptLine>) => {
    setLines((current) => current.map((line) => (line.uid === uid ? { ...line, ...patch } : line)))
  }, [])

  const removeLine = useCallback((uid: string) => {
    setLines((current) => (current.length === 1 ? current : current.filter((line) => line.uid !== uid)))
  }, [])

  const addLine = useCallback(() => {
    setLines((current) => [...current, emptyLine()])
  }, [])

  /**
   * Та же позиция отдельной строкой.
   * В накладной один товар часто идёт дважды: часть по обычной цене, часть по
   * акции (у поставщика это разные номенклатурные номера, у нас — один товар).
   * Копируем товар и цену продажи, а количество и закупку оставляем пустыми —
   * их заполняют из второй строки накладной.
   */
  const duplicateLine = useCallback((uid: string) => {
    setLines((current) => {
      const index = current.findIndex((line) => line.uid === uid)
      if (index < 0) return current
      const source = current[index]
      const copy: ReceiptLine = {
        ...source,
        uid: nextLineUid(),
        quantity: '',
        unit_cost: '',
        markup_percent: '',
        comment: 'Акция',
        is_bonus: false,
        return_qty: '',
      }
      return [...current.slice(0, index + 1), copy, ...current.slice(index + 1)]
    })
  }, [])

  const quickMatches = useMemo(() => {
    const q = quickQuery.trim().toLowerCase()
    if (!q) return []
    return (data?.items || [])
      .filter((item) => {
        const barcode = String(item.barcode || '').toLowerCase()
        const name = String(item.name || '').toLowerCase()
        return barcode.includes(q) || name.includes(q)
      })
      .slice(0, 8)
  }, [data?.items, quickQuery])

  const upsertReceiptLine = (itemId: string, mode: 'increment' | 'set' | 'new-line' = 'increment') => {
    const item = (data?.items || []).find((row) => row.id === itemId)
    if (!item) return false

    setLines((current) => {
      const existingIndex = current.findIndex((line) => line.item_id === itemId)

      // Товар уже в списке, но человек добавляет его снова: в накладной одна и
      // та же позиция часто идёт двумя строками — часть по обычной цене, часть
      // по акции. Раньше это всегда схлопывалось в «+1 к количеству», и вторую
      // цену внести было невозможно.
      if (existingIndex >= 0 && mode === 'new-line') {
        const source = current[existingIndex]
        const copy: ReceiptLine = {
          uid: nextLineUid(),
          item_id: itemId,
          quantity: '1',
          unit_cost: String(item.default_purchase_price || ''),
          sale_price: source.sale_price || String(item.sale_price || ''),
          markup_percent: calcMarkupPercent(
            String(item.default_purchase_price || ''),
            source.sale_price || String(item.sale_price || ''),
          ),
          comment: '',
        }
        // Ставим рядом с первой строкой того же товара, а не в конец списка —
        // так обе цены видно вместе.
        const lastSameIndex = current.reduce((acc, line, idx) => (line.item_id === itemId ? idx : acc), existingIndex)
        return [...current.slice(0, lastSameIndex + 1), copy, ...current.slice(lastSameIndex + 1)]
      }

      if (existingIndex >= 0) {
        return current.map((line, idx) => {
          if (idx !== existingIndex) return line
          const currentQty = parseQty(line.quantity)
          const nextQty = mode === 'increment' ? currentQty + 1 : Math.max(1, currentQty)
          return {
            ...line,
            quantity: String(nextQty),
            unit_cost: line.unit_cost || String(item.default_purchase_price || ''),
            sale_price: line.sale_price || String(item.sale_price || ''),
            markup_percent:
              line.markup_percent || calcMarkupPercent(line.unit_cost || String(item.default_purchase_price || ''), line.sale_price || String(item.sale_price || '')),
          }
        })
      }

      const nextLine: ReceiptLine = {
        uid: nextLineUid(),
        item_id: itemId,
        quantity: '1',
        unit_cost: String(item.default_purchase_price || ''),
        sale_price: String(item.sale_price || ''),
        markup_percent: calcMarkupPercent(String(item.default_purchase_price || ''), String(item.sale_price || '')),
        comment: '',
      }
      const hasOnlyEmpty = current.length === 1 && !current[0].item_id && !current[0].quantity && !current[0].unit_cost && !current[0].sale_price && !current[0].markup_percent && !current[0].comment
      if (hasOnlyEmpty) return [nextLine]
      return [...current, nextLine]
    })
    return true
  }

  const handleQuickAdd = () => {
    setQuickError(null)
    const q = quickQuery.trim()
    if (!q) return

    const exactBarcode = (data?.items || []).find((item) => String(item.barcode || '').trim() === q)
    if (exactBarcode) {
      upsertReceiptLine(exactBarcode.id, repeatMode)
      setQuickQuery('')
      return
    }

    const byContains = (data?.items || []).filter((item) => {
      const barcode = String(item.barcode || '').toLowerCase()
      const name = String(item.name || '').toLowerCase()
      const query = q.toLowerCase()
      return barcode.includes(query) || name.includes(query)
    })

    if (byContains.length === 1) {
      upsertReceiptLine(byContains[0].id, repeatMode)
      setQuickQuery('')
      return
    }

    if (byContains.length === 0) {
      setQuickError('Товар не найден. Проверь штрихкод или название.')
      return
    }

    setQuickError('Найдено несколько товаров — выбери ниже из подсказок.')
  }

  const createReceipt = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    setSuccess(null)

    const payloadItems = lines
      .map((line) => ({
        item_id: line.item_id,
        quantity: parseQty(line.quantity),
        unit_cost: line.is_bonus ? 0 : parseUnitCost(line.unit_cost),
        sale_price: parseMoney(line.sale_price),
        is_bonus: Boolean(line.is_bonus),
        comment: [line.comment.trim(), parseQty(line.return_qty || '') > 0 ? `возврат/недопоставка ${parseQty(line.return_qty || '')}` : '']
          .filter(Boolean)
          .join(' · ') || null,
        invoice_name: line.invoice_name?.trim() || null,
        production_date: line.production_date?.trim() || null,
        expiry_date: line.expiry_date?.trim() || null,
      }))
      .filter((line) => line.item_id && line.quantity > 0 && line.unit_cost >= 0 && line.sale_price >= 0)

    if (!locationId) {
      setError('Выберите склад для приемки')
      return
    }
    if (!payloadItems.length) {
      setError('Добавьте хотя бы одну товарную строку')
      return
    }
    // Срок годности обязателен, кроме товаров с requires_expiry=false (бургеры/хотдоги).
    {
      const reqById = new Map((data?.items || []).map((it) => [it.id, it.requires_expiry !== false]))
      const nameById = new Map((data?.items || []).map((it) => [it.id, it.name]))
      const missing = payloadItems
        .filter((l) => reqById.get(l.item_id) !== false && !l.expiry_date)
        .map((l) => nameById.get(l.item_id) || 'Товар')
      if (missing.length) {
        setError(`Укажите «годен до» для: ${missing.slice(0, 5).join(', ')}${missing.length > 5 ? '…' : ''}. Товары без срока (бургеры/хотдоги) — снимите галочку «требует срок годности» в каталоге.`)
        return
      }
    }
    if (!invoiceFileUrl) {
      setError('Загрузите файл накладной. Без документа приемка запрещена.')
      return
    }
    if (!expenseCategoryId) {
      setError('Выберите категорию расхода COGS для автодобавления')
      return
    }
    if (supplierMode === 'existing' && !supplierId) {
      setError('Выберите поставщика')
      return
    }
    if (paymentMode === 'now' && !paymentReceiptFileUrl) {
      setError('Загрузите чек об оплате или выберите «В долг / Под реализацию»')
      return
    }
    if (supplierMode === 'new') {
      if (!supplierName.trim()) {
        setError('Введите название поставщика')
        return
      }
      if (!supplierOrganizationName.trim()) {
        setError('Введите название организации')
        return
      }
      const onlyDigits = supplierBinIin.replace(/\D/g, '')
      if (!/^\d{12}$/.test(onlyDigits)) {
        setError('ИИН/БИН должен состоять из 12 цифр')
        return
      }
    }

    setSaving(true)
    try {
      const response = await fetch('/api/admin/store/receipts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'createReceipt',
          company_id: storeCompanyId || undefined,
          draft_id: draftId,
          payload: {
            location_id: locationId,
            supplier_id: supplierMode === 'existing' ? supplierId || null : null,
            supplier_create: supplierMode === 'new'
              ? {
                  name: supplierName.trim(),
                  organization_name: supplierOrganizationName.trim(),
                  bin_iin: supplierBinIin.replace(/\D/g, ''),
                }
              : null,
            received_at: receivedAt,
            invoice_number: invoiceNumber.trim() || null,
            invoice_file_url: invoiceFileUrl,
            expense_category_id: expenseCategoryId || null,
            payment_method: paymentMethod,
            payment_mode: paymentMode,
            payment_receipt_file_url: paymentMode === 'now' ? paymentReceiptFileUrl || null : null,
            is_consignment: isConsignment,
            due_date: paymentMode === 'deferred' && dueDate ? dueDate : null,
            comment: comment.trim() || null,
            items: payloadItems,
          },
        }),
      })

      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось провести приемку')

      setSupplierId('')
      setDraftId(null)
      setSupplierMode('existing')
      setSupplierName('')
      setSupplierOrganizationName('')
      setSupplierBinIin('')
      setInvoiceNumber('')
      setInvoiceFileUrl('')
      setAiParseResult(null)
      setExpenseCategoryId('')
      setPaymentMethod('cash')
      setPaymentMode('now')
      setPaymentReceiptFileUrl('')
      setIsConsignment(false)
      setDueDate('')
      setComment('')
      setLines([emptyLine()])
      if (json.warning) setError(json.warning)
      else setSuccess('Приемка проведена. Остатки и цены обновлены везде.')
      invalidateStoreCaches()
      await reload()
    } catch (err: any) {
      setError(err?.message || 'Не удалось провести приемку')
    } finally {
      setSaving(false)
    }
  }

  const saveDraft = async () => {
    setError(null)
    setSuccess(null)
    const payloadItems = lines
      .map((line) => ({
        item_id: line.item_id,
        quantity: parseQty(line.quantity),
        unit_cost: line.is_bonus ? 0 : parseUnitCost(line.unit_cost),
        sale_price: parseMoney(line.sale_price),
        is_bonus: Boolean(line.is_bonus),
        comment: line.comment.trim() || null,
        production_date: line.production_date?.trim() || null,
        expiry_date: line.expiry_date?.trim() || null,
        return_qty: line.return_qty?.trim() || null,
      }))
      .filter((line) => line.item_id)

    try {
      const response = await fetch('/api/admin/store/receipts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          action: 'saveDraft',
          draft_id: draftId,
          draft_title: invoiceNumber.trim() || `Черновик ${new Date().toLocaleDateString('ru-RU')}`,
          payload: {
            location_id: locationId,
            supplier_id: supplierMode === 'existing' ? supplierId || null : null,
            supplier_create: supplierMode === 'new'
              ? {
                  name: supplierName.trim(),
                  organization_name: supplierOrganizationName.trim(),
                  bin_iin: supplierBinIin.replace(/\D/g, ''),
                }
              : null,
            received_at: receivedAt,
            invoice_number: invoiceNumber.trim() || null,
            invoice_file_url: invoiceFileUrl || null,
            expense_category_id: expenseCategoryId || null,
            payment_method: paymentMethod,
            payment_mode: paymentMode,
            payment_receipt_file_url: paymentReceiptFileUrl || null,
            is_consignment: isConsignment,
            due_date: dueDate || null,
            comment: comment.trim() || null,
            items: payloadItems,
          },
        }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось сохранить черновик')
      setDraftId(String(json?.data?.id || draftId || ''))
      setSuccess('Черновик сохранен')
      invalidateStoreCaches()
      await reload()
    } catch (err: any) {
      setError(err?.message || 'Не удалось сохранить черновик')
    }
  }

  const applyDraft = (draft: InventoryReceiptDraft) => {
    const payload = draft.payload || {}
    const items = Array.isArray(payload.items) ? payload.items : []
    setDraftId(draft.id)
    setLocationId(String(payload.location_id || ''))
    setReceivedAt(String(payload.received_at || new Date().toISOString().slice(0, 10)))
    setInvoiceNumber(String(payload.invoice_number || ''))
    setInvoiceFileUrl(String(payload.invoice_file_url || ''))
    setAiParseResult(null)
    setExpenseCategoryId(String(payload.expense_category_id || ''))
    setPaymentMethod(payload.payment_method === 'kaspi' ? 'kaspi' : 'cash')
    setPaymentMode((payload as any).payment_mode === 'deferred' ? 'deferred' : 'now')
    setPaymentReceiptFileUrl(String((payload as any).payment_receipt_file_url || ''))
    setIsConsignment(Boolean((payload as any).is_consignment))
    setDueDate(String((payload as any).due_date || ''))
    setComment(String(payload.comment || ''))
    if (payload.supplier_create?.bin_iin || payload.supplier_create?.organization_name || payload.supplier_create?.name) {
      setSupplierMode('new')
      setSupplierName(String(payload.supplier_create?.name || ''))
      setSupplierOrganizationName(String(payload.supplier_create?.organization_name || ''))
      setSupplierBinIin(String(payload.supplier_create?.bin_iin || ''))
      setSupplierId('')
    } else {
      setSupplierMode('existing')
      setSupplierId(String(payload.supplier_id || ''))
      setSupplierName('')
      setSupplierOrganizationName('')
      setSupplierBinIin('')
    }
    const mappedLines: ReceiptLine[] = items.length > 0
      ? items.map((item) => {
          const isBonus = Boolean((item as any).is_bonus)
          return {
            uid: nextLineUid(),
            item_id: String(item.item_id || ''),
            quantity: String(item.quantity ?? ''),
            unit_cost: isBonus ? '0' : String(item.unit_cost ?? ''),
            sale_price: String(item.sale_price ?? ''),
            markup_percent: isBonus ? '' : calcMarkupPercent(String(item.unit_cost ?? ''), String(item.sale_price ?? '')),
            comment: String(item.comment || ''),
            is_bonus: isBonus,
            production_date: String((item as any).production_date || ''),
            expiry_date: String((item as any).expiry_date || ''),
            return_qty: String((item as any).return_qty || ''),
          }
        })
      : [emptyLine()]
    setLines(mappedLines)
    setFormSheetOpen(true)
    setSuccess('Черновик загружен в форму')
  }

  const deleteDraft = async (id: string) => {
    try {
      const response = await fetch('/api/admin/store/receipts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'deleteDraft', draft_id: id }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось удалить черновик')
      if (draftId === id) setDraftId(null)
      invalidateStoreCaches()
      await reload()
    } catch (err: any) {
      setError(err?.message || 'Не удалось удалить черновик')
    }
  }

  const saveTemplate = () => {
    const name = templateName.trim()
    if (!name) {
      setError('Введите название шаблона')
      return
    }
    const nonEmptyLines = lines.filter((line) => line.item_id && parseQty(line.quantity) > 0)
    if (nonEmptyLines.length === 0) {
      setError('Нет заполненных строк для шаблона')
      return
    }
    setSavedTemplates((prev) => {
      const rest = prev.filter((tpl) => tpl.name !== name)
      return [{ name, lines: nonEmptyLines }, ...rest].slice(0, 25)
    })
    setTemplateName('')
    setSuccess(`Шаблон «${name}» сохранён`)
  }

  const applyTemplate = (name: string) => {
    const tpl = savedTemplates.find((item) => item.name === name)
    if (!tpl) return
    setLines(tpl.lines.map((line) => ({ ...line, uid: nextLineUid() })))
    setSuccess(`Шаблон «${name}» применён`)
  }

  const deleteTemplate = (name: string) => {
    setSavedTemplates((prev) => prev.filter((tpl) => tpl.name !== name))
  }

  const applyBulkMarkupPercent = () => {
    const pct = parseMoney(bulkMarkupPercent)
    setLines((prev) =>
      prev.map((line) => {
        if (!line.item_id) return line
        const base = parseUnitCost(line.unit_cost)
        const sale = base > 0 ? String(Math.round((base * (1 + pct / 100) + Number.EPSILON) * 100) / 100) : line.sale_price
        return { ...line, markup_percent: String(pct), sale_price: sale }
      }),
    )
  }

  const applyBulkSalePrice = () => {
    const sale = parseMoney(bulkSalePrice)
    setLines((prev) =>
      prev.map((line) => {
        if (!line.item_id) return line
        return {
          ...line,
          sale_price: String(sale),
          markup_percent: calcMarkupPercent(line.unit_cost, String(sale)),
        }
      }),
    )
  }

  const exportCsv = () => {
    const rows = lines
      .filter((line) => line.item_id)
      .map((line) => {
        const item = (data?.items || []).find((i) => i.id === line.item_id)
        return {
          name: item?.name || '',
          barcode: item?.barcode || '',
          quantity: line.quantity,
          unit_cost: line.unit_cost,
          sale_price: line.sale_price,
          markup_percent: line.markup_percent,
          comment: line.comment || '',
        }
      })
    if (rows.length === 0) return
    const headers = ['name', 'barcode', 'quantity', 'unit_cost', 'sale_price', 'markup_percent', 'comment']
    const csv = [headers.join(',')]
      .concat(rows.map((r) => headers.map((h) => `"${String((r as any)[h] ?? '').replace(/"/g, '""')}"`).join(',')))
      .join('\n')
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `receipts-${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  const uploadInvoice = async (file: File | null) => {
    if (!file) return
    setError(null)
    setUploadingInvoice(true)
    try {
      const formData = new FormData()
      formData.append('file', file)
      const response = await fetch('/api/admin/store/receipts/upload', {
        method: 'POST',
        body: formData,
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось загрузить накладную')
      setInvoiceFileUrl(String(json.document_url || ''))
      setAiParseResult(null)
    } catch (err: any) {
      setError(err?.message || 'Не удалось загрузить накладную')
    } finally {
      setUploadingInvoice(false)
    }
  }

  const uploadPaymentReceipt = async (file: File | null) => {
    if (!file) return
    setError(null)
    setUploadingPaymentReceipt(true)
    try {
      const formData = new FormData()
      formData.append('file', file)
      const response = await fetch('/api/admin/store/receipts/upload', {
        method: 'POST',
        body: formData,
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось загрузить чек')
      setPaymentReceiptFileUrl(String(json.document_url || ''))
    } catch (err: any) {
      setError(err?.message || 'Не удалось загрузить чек')
    } finally {
      setUploadingPaymentReceipt(false)
    }
  }

  const runAiInvoiceParse = async () => {
    setError(null)
    setSuccess(null)
    if (!invoiceFileUrl) {
      setError('Сначала загрузите накладную, потом запустите ИИ-распознавание.')
      return
    }
    if (supplierMode === 'existing' && !supplierId) {
      setError('Выберите поставщика — без него ИИ не может использовать обученные алиасы.')
      return
    }
    if (supplierMode === 'new' && !supplierName.trim()) {
      setError('Заполните данные поставщика — без него ИИ не может использовать обученные алиасы.')
      return
    }
    setAiParsing(true)
    try {
      const response = await fetch('/api/admin/store/receipts/ai-parse', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          invoice_file_url: invoiceFileUrl,
          supplier_id: supplierMode === 'existing' ? supplierId || null : null,
        }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok || !json?.data) throw new Error(json?.error || 'ИИ не смог распознать накладную')
      const normalized = json.data as AiParseResult
      setAiParseResult({
        ...normalized,
        items: (normalized.items || []).map((item) => ({
          ...item,
          manual_item_id: item.matched_item_id || null,
        })),
      })
      setSuccess('ИИ распознал накладную. Проверьте и примените данные.')
    } catch (err: any) {
      setAiParseResult(null)
      setError(err?.message || 'Не удалось распознать накладную')
    } finally {
      setAiParsing(false)
    }
  }

  const applyAiParseResult = () => {
    if (!aiParseResult) return
    if (aiParseResult.supplier_name) {
      const normalizedName = aiParseResult.supplier_name.trim().toLowerCase()
      const existingSupplier = (data?.suppliers || []).find((s) => String(s.name || '').trim().toLowerCase() === normalizedName)
      if (existingSupplier) {
        setSupplierMode('existing')
        setSupplierId(existingSupplier.id)
        setSupplierName('')
        setSupplierOrganizationName('')
        setSupplierBinIin('')
      } else {
        setSupplierMode('new')
        setSupplierId('')
        setSupplierName(aiParseResult.supplier_name)
        setSupplierOrganizationName(aiParseResult.supplier_name)
      }
    }
    if (aiParseResult.invoice_number) setInvoiceNumber(aiParseResult.invoice_number)
    if (aiParseResult.invoice_date) setReceivedAt(aiParseResult.invoice_date)
    if (aiParseResult.cogs_suggestion?.recommended_category_id) {
      setExpenseCategoryId(aiParseResult.cogs_suggestion.recommended_category_id)
    }

    const parsedLines = (aiParseResult.items || [])
      .map((item) => ({ ...item, resolved_item_id: item.manual_item_id || item.matched_item_id }))
      .filter((item) => item.resolved_item_id && Number(item.quantity || 0) > 0)
      .map((item) => {
        const catalog = (data?.items || []).find((row) => row.id === item.resolved_item_id)
        const unitCost = Number(item.unit_cost || 0)
        const lastUnit = item.last_unit_cost != null ? Number(item.last_unit_cost) : null
        const lastSale = item.last_sale_price != null ? Number(item.last_sale_price) : null
        const fallbackCost = String(catalog?.default_purchase_price || '')
        const finalUnit = unitCost > 0 ? String(unitCost) : (lastUnit && lastUnit > 0 ? String(lastUnit) : fallbackCost)
        const finalSale = lastSale && lastSale > 0
          ? String(lastSale)
          : String(Number(catalog?.sale_price || 0) || '')
        return {
          uid: nextLineUid(),
          item_id: String(item.resolved_item_id),
          quantity: String(item.quantity || ''),
          unit_cost: finalUnit,
          sale_price: finalSale,
          markup_percent: calcMarkupPercent(finalUnit, finalSale),
          comment: '',
          invoice_name: item.invoice_name || '',
          last_unit_cost: lastUnit,
        } as ReceiptLine
      })

    if (parsedLines.length > 0) {
      setLines(parsedLines)
      setSuccess(`Применено ${parsedLines.length} строк из распознанной накладной.`)
    } else {
      setError('ИИ не нашел сопоставленных товаров. Добавьте строки вручную.')
    }
  }

  const setAiManualItem = (index: number, itemId: string) => {
    setAiParseResult((current) => {
      if (!current) return current
      const nextItems = current.items.map((item, i) => (i === index ? { ...item, manual_item_id: itemId || null } : item))
      const matchedCount = nextItems.filter((item) => item.manual_item_id || item.matched_item_id).length
      return {
        ...current,
        items: nextItems,
        matched_count: matchedCount,
        unmatched_count: nextItems.length - matchedCount,
      }
    })
  }

  const applyAiCogsCategory = () => {
    const categoryId = aiParseResult?.cogs_suggestion?.recommended_category_id || ''
    if (!categoryId) return
    setExpenseCategoryId(categoryId)
    setSuccess('Категория COGS из AI-подсказки применена.')
  }

  const applySpecificCogsCategory = (categoryId: string, categoryName: string) => {
    if (!categoryId) return
    setExpenseCategoryId(categoryId)
    setSuccess(`Категория COGS «${categoryName}» применена.`)
  }
  const cogsConfidence = aiParseResult?.cogs_suggestion?.confidence || null
  const cogsConfidenceLabel = cogsConfidence === 'high'
    ? 'Высокая уверенность'
    : cogsConfidence === 'medium'
      ? 'Средняя уверенность'
      : cogsConfidence === 'low'
        ? 'Низкая уверенность'
        : 'Уверенность не определена'
  const cogsConfidenceClass = cogsConfidence === 'high'
    ? 'border-emerald-300/30 bg-emerald-400/15 text-emerald-700 dark:text-emerald-100'
    : cogsConfidence === 'medium'
      ? 'border-amber-300/30 bg-amber-400/15 text-amber-700 dark:text-amber-100'
      : 'border-rose-300/30 bg-rose-400/15 text-rose-700 dark:text-rose-100'

  const cogsExpenseCategories = useMemo(() => {
    const fromServer = (data?.expense_categories || []).filter((c) => String(c.accounting_group || '').trim().toLowerCase() === 'cogs')
    if (fromServer.length > 0) return fromServer
    return expenseCategoriesFallback.filter((c) => String(c.accounting_group || '').trim().toLowerCase() === 'cogs')
  }, [data?.expense_categories, expenseCategoriesFallback])

  /** «Очистить форму» из меню действий. */
  const clearBasics = () => {
    setSupplierId('')
    setInvoiceNumber('')
    setComment('')
    setLines([emptyLine()])
  }

  return {
    saving, loading, locationId, setLocationId,
    supplierId, setSupplierId, supplierMode, setSupplierMode,
    supplierName, setSupplierName, supplierOrganizationName, setSupplierOrganizationName,
    supplierBinIin, setSupplierBinIin,
    receivedAt, setReceivedAt, invoiceNumber, setInvoiceNumber, invoiceFileUrl,
    expenseCategoryId, setExpenseCategoryId,
    paymentMethod, setPaymentMethod, paymentMode, setPaymentMode,
    paymentReceiptFileUrl, uploadingPaymentReceipt, isConsignment, setIsConsignment,
    dueDate, setDueDate, uploadingInvoice, aiParsing, aiParseResult,
    comment, setComment, lines,
    quickQuery, setQuickQuery, repeatMode, setRepeatMode, quickError, setQuickError, quickInputRef,
    templateName, setTemplateName, savedTemplates,
    bulkMarkupPercent, setBulkMarkupPercent, bulkSalePrice, setBulkSalePrice,
    showBulkTools, setShowBulkTools, showTemplatesTools, setShowTemplatesTools,
    formSheetOpen, setFormSheetOpen,
    receiptTotal, returnTotal, catalogItems, itemsById,
    patchLine, removeLine, addLine, duplicateLine,
    quickMatches, upsertReceiptLine, handleQuickAdd,
    createReceipt, saveDraft, applyDraft, deleteDraft,
    saveTemplate, applyTemplate, deleteTemplate,
    applyBulkMarkupPercent, applyBulkSalePrice, exportCsv,
    uploadInvoice, uploadPaymentReceipt, runAiInvoiceParse, applyAiParseResult,
    setAiManualItem, applyAiCogsCategory, applySpecificCogsCategory,
    cogsConfidenceLabel, cogsConfidenceClass, cogsExpenseCategories,
    clearBasics,
  }
}

export type ReceiptForm = ReturnType<typeof useReceiptForm>
