'use client'

import { useEffect, useMemo, useState } from 'react'
import { Building2, CheckCircle2, Circle, FileText, Info, Loader2, Printer, Receipt, Save, ShieldCheck, Landmark, Cpu } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/ui/native-select'
import { toast } from '@/hooks/use-toast'
import { StoreSection, StoreChip } from '@/components/store/store-ui'
import { Textarea } from '@/components/ui/textarea'
import { Switch } from '@/components/ui/switch'
import { AdminPageHeader } from '@/components/admin/admin-page-header'
import { useCapabilities } from '@/lib/client/use-capabilities'
import { useStoreScope } from '@/components/store/store-scope'
import { isAbortError } from '@/lib/is-abort-error'

type Company = {
  id: string
  name: string
  code: string | null
  organization_id?: string | null
}

type Settings = {
  id?: string
  company_id: string
  organization_id?: string | null
  tax_payer_name: string
  tax_payer_bin: string
  point_address: string
  kkm_factory_number: string
  kkm_registration_number: string
  is_vat_payer: boolean
  vat_rate: number
  ofd_name: string
  ofd_check_url: string
  receipt_language: 'ru' | 'kk' | 'both'
  receipt_footer_text: string
  review_url: string
  require_buyer_iin: boolean
  marking_enabled: boolean
  nkt_enabled: boolean
}

type ApiResponse = {
  ok: boolean
  data?: {
    companies: Company[]
    settings: Settings | null
  }
  error?: string
}

const emptySettings = (companyId: string): Settings => ({
  company_id: companyId,
  tax_payer_name: '',
  tax_payer_bin: '',
  point_address: '',
  kkm_factory_number: '',
  kkm_registration_number: '',
  is_vat_payer: false,
  vat_rate: 12,
  ofd_name: '',
  ofd_check_url: '',
  receipt_language: 'ru',
  receipt_footer_text: '',
  review_url: '',
  require_buyer_iin: false,
  marking_enabled: false,
  nkt_enabled: false,
})

export default function ReceiptSettingsPage() {
  const { can } = useCapabilities()
  const [companies, setCompanies] = useState<Company[]>([])
  const [selectedCompanyId, setSelectedCompanyId] = useState('')
  const { storeCompanyId } = useStoreScope()
  useEffect(() => { if (storeCompanyId) setSelectedCompanyId(storeCompanyId) }, [storeCompanyId])
  const [settings, setSettings] = useState<Settings | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [savedJson, setSavedJson] = useState('')
  const dirty = !!settings && JSON.stringify(settings) !== savedJson

  const selectedCompany = useMemo(
    () => companies.find((c) => c.id === selectedCompanyId) || null,
    [companies, selectedCompanyId],
  )

  const load = async (companyId: string | null, signal?: AbortSignal) => {
    setLoading(true)
    setError(null)
    try {
      const url = companyId
        ? `/api/admin/store/receipt-settings?company_id=${encodeURIComponent(companyId)}`
        : '/api/admin/store/receipt-settings'
      const response = await fetch(url, { cache: 'no-store', signal })
      const json = (await response.json().catch(() => null)) as ApiResponse | null
      if (signal?.aborted) return
      if (!response.ok || !json?.ok || !json.data) throw new Error(json?.error || 'Не удалось загрузить настройки')
      setCompanies(json.data.companies || [])
      if (json.data.settings) {
        setSettings(json.data.settings)
        setSavedJson(JSON.stringify(json.data.settings))
        setSelectedCompanyId(json.data.settings.company_id)
      } else if (json.data.companies.length > 0) {
        const firstId = json.data.companies[0].id
        setSettings(emptySettings(firstId))
        setSavedJson(JSON.stringify(emptySettings(firstId)))
        setSelectedCompanyId(firstId)
      } else {
        setSettings(null)
      }
    } catch (err: any) {
      if (isAbortError(err) || signal?.aborted) return
      setError(err?.message || 'Не удалось загрузить настройки')
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }

  useEffect(() => {
    const ac = new AbortController()
    void load(null, ac.signal)
    return () => ac.abort()
  }, [])

  useEffect(() => {
    if (!selectedCompanyId) return
    const ac = new AbortController()
    void load(selectedCompanyId, ac.signal)
    return () => ac.abort()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedCompanyId])

  const patch = <K extends keyof Settings>(key: K, value: Settings[K]) => {
    setSettings((current) => (current ? { ...current, [key]: value } : current))
  }

  const handleSave = async () => {
    if (!settings) return
    setSaving(true)
    setError(null)
    setSuccess(null)
    try {
      const response = await fetch('/api/admin/store/receipt-settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          company_id: settings.company_id,
          settings,
        }),
      })
      const json = await response.json().catch(() => null)
      if (!response.ok || !json?.ok) throw new Error(json?.error || 'Не удалось сохранить')
      setSuccess('Реквизиты чека сохранены')
      setSavedJson(JSON.stringify(settings))
      toast({ title: 'Реквизиты чека сохранены' })
      setTimeout(() => setSuccess(null), 2500)
    } catch (err: any) {
      setError(err?.message || 'Не удалось сохранить')
    } finally {
      setSaving(false)
    }
  }

  const checks: Array<{ ok: boolean; label: string }> = settings
    ? [
        { ok: !!settings.tax_payer_name.trim(), label: 'Наименование налогоплательщика' },
        { ok: settings.tax_payer_bin.trim().length === 12, label: 'БИН/ИИН (12 цифр)' },
        { ok: !!settings.point_address.trim(), label: 'Адрес точки' },
        { ok: !!settings.kkm_registration_number.trim(), label: 'Рег. номер ККМ' },
        { ok: !!settings.ofd_name.trim(), label: 'Оператор фискальных данных' },
        { ok: !settings.is_vat_payer || Number(settings.vat_rate) > 0, label: 'Ставка НДС (если плательщик)' },
      ]
    : []
  const done = checks.filter((c) => c.ok).length
  const allDone = checks.length > 0 && done === checks.length

  return (
    <div className="app-page-wide space-y-5">
      <AdminPageHeader
        title="Реквизиты чека ККМ"
        description="Приказ Министра финансов РК №626 от 24.10.2025 (действует с 01.01.2026)"
        icon={<Receipt className="h-5 w-5" />}
        accent="emerald"
        backHref="/"
        actions={
          !storeCompanyId ? (
            <NativeSelect
              className="w-full sm:w-auto sm:min-w-[220px]"
              value={selectedCompanyId}
              onChange={(e) => setSelectedCompanyId(e.target.value)}
              aria-label="Точка"
            >
              <option value="" disabled>Выберите точку</option>
              {companies.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                  {c.code ? ` · ${c.code}` : ''}
                </option>
              ))}
            </NativeSelect>
          ) : undefined
        }
      />

      {error ? (
        <div className="rounded-xl border border-rose-500/20 bg-rose-500/10 px-4 py-2.5 text-sm text-rose-700 dark:text-rose-300">{error}</div>
      ) : null}

      {loading || !settings ? (
        <StoreSection title="Загружаем настройки…">
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Подождите секунду
          </div>
        </StoreSection>
      ) : (
        <>
          {/* Готовность к №626 */}
          <StoreSection
            title="Готовность чека к Приказу МФ РК №626"
            subtitle={allDone ? 'Все обязательные реквизиты заполнены' : 'Без обязательных реквизитов чек не соответствует закону'}
            icon={<ShieldCheck className={`h-4 w-4 ${allDone ? 'text-emerald-600 dark:text-emerald-300' : 'text-amber-600 dark:text-amber-300'}`} />}
            action={<StoreChip tone={allDone ? 'emerald' : 'amber'}>{done} из {checks.length}</StoreChip>}
          >
            <div className="grid gap-x-4 gap-y-1.5 text-sm sm:grid-cols-2 lg:grid-cols-3">
              {checks.map((c) => (
                <span key={c.label} className={`flex items-center gap-2 ${c.ok ? 'text-foreground' : 'text-amber-700 dark:text-amber-300'}`}>
                  {c.ok
                    ? <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-500" />
                    : <Circle className="h-4 w-4 shrink-0 text-amber-500" />}
                  {c.label}
                </span>
              ))}
            </div>
            <details className="mt-3 text-xs text-muted-foreground">
              <summary className="flex cursor-pointer items-center gap-1.5 font-medium text-slate-600 dark:text-slate-300">
                <Info className="h-3.5 w-3.5" /> Что должно быть на чеке по приказу №626
              </summary>
              <ul className="mt-2 list-inside list-disc space-y-0.5 leading-relaxed">
                <li>Наименование налогоплательщика и его БИН/ИИН</li>
                <li>Адрес торговой точки</li>
                <li>Заводской и регистрационный номера ККМ</li>
                <li>Наименование товара (НКТ — код по Национальному каталогу — пока не обязательно, появится позже)</li>
                <li>Маркировка товара (только для маркируемых — пока не обязательно)</li>
                <li>Ставка и сумма НДС (если плательщик)</li>
                <li>Дата и время покупки, фискальный признак</li>
                <li>Штриховой код для проверки чека</li>
                <li>Наименование ОФД и ссылка на его портал</li>
                <li>По необходимости — ИИН покупателя</li>
                <li>Чек на казахском и/или русском языке</li>
              </ul>
            </details>
          </StoreSection>

          <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
            <div className="space-y-5">
              <StoreSection title="Налогоплательщик" subtitle="Печатается в шапке чека" icon={<Building2 className="h-4 w-4" />}>
                <div className="space-y-4">
                  <div className="space-y-1.5">
                    <Label>Наименование</Label>
                    <Input value={settings.tax_payer_name} onChange={(e) => patch('tax_payer_name', e.target.value)} placeholder="ТОО / ИП «...»" />
                  </div>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <div className="space-y-1.5">
                      <Label>БИН / ИИН</Label>
                      <Input
                        value={settings.tax_payer_bin}
                        onChange={(e) => patch('tax_payer_bin', e.target.value.replace(/\D/g, '').slice(0, 12))}
                        placeholder="12 цифр"
                        inputMode="numeric"
                      />
                      <p className="text-xs text-muted-foreground">Только цифры, ровно 12.</p>
                    </div>
                    <div className="space-y-1.5">
                      <Label>Адрес торговой точки</Label>
                      <Input value={settings.point_address} onChange={(e) => patch('point_address', e.target.value)} placeholder="Город, улица, дом, помещение" />
                    </div>
                  </div>
                </div>
              </StoreSection>

              <StoreSection title="Контрольно-кассовая машина" subtitle="Номера из документов на ККМ" icon={<Cpu className="h-4 w-4" />}>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label>Заводской номер ККМ</Label>
                    <Input value={settings.kkm_factory_number} onChange={(e) => patch('kkm_factory_number', e.target.value)} placeholder="С шильдика устройства" />
                    <p className="text-xs text-muted-foreground">Указан на корпусе устройства.</p>
                  </div>
                  <div className="space-y-1.5">
                    <Label>Регистрационный номер ККМ</Label>
                    <Input value={settings.kkm_registration_number} onChange={(e) => patch('kkm_registration_number', e.target.value)} placeholder="Присвоен налоговым органом" />
                    <p className="text-xs text-muted-foreground">Выдаётся при регистрации в налоговой.</p>
                  </div>
                </div>
              </StoreSection>

              <StoreSection title="НДС" icon={<Landmark className="h-4 w-4" />}>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="flex items-center justify-between gap-3 rounded-xl border border-border bg-surface-muted px-3 py-2.5">
                    <div>
                      <Label className="cursor-pointer">Плательщик НДС</Label>
                      <p className="text-xs text-muted-foreground">Если ИП или юрлицо платит НДС</p>
                    </div>
                    <Switch checked={settings.is_vat_payer} onCheckedChange={(v) => patch('is_vat_payer', v)} />
                  </div>
                  <div className="space-y-1.5">
                    <Label>Ставка НДС, %</Label>
                    <Input
                      type="number"
                      min={0}
                      max={100}
                      step="0.01"
                      value={String(settings.vat_rate)}
                      onChange={(e) => patch('vat_rate', Number(e.target.value) || 0)}
                      disabled={!settings.is_vat_payer}
                    />
                    <p className="text-xs text-muted-foreground">{settings.is_vat_payer ? 'Печатается в строке «в т.ч. НДС».' : 'Включите «Плательщик НДС», чтобы указать ставку.'}</p>
                  </div>
                </div>
              </StoreSection>

              <StoreSection title="Оператор фискальных данных (ОФД)" subtitle="Куда уходят чеки" icon={<FileText className="h-4 w-4" />}>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label>Наименование ОФД</Label>
                    <Input value={settings.ofd_name} onChange={(e) => patch('ofd_name', e.target.value)} placeholder="Например: АО «Казахтелеком»" />
                  </div>
                  <div className="space-y-1.5">
                    <Label>Портал ОФД для проверки чека</Label>
                    <Input value={settings.ofd_check_url} onChange={(e) => patch('ofd_check_url', e.target.value)} placeholder="https://…" />
                  </div>
                </div>
              </StoreSection>

              <StoreSection title="Параметры чека" icon={<Printer className="h-4 w-4" />}>
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label>Язык чека</Label>
                    <NativeSelect
                      value={settings.receipt_language}
                      onChange={(e) => patch('receipt_language', e.target.value as Settings['receipt_language'])}
                    >
                      <option value="ru">Русский</option>
                      <option value="kk">Қазақша</option>
                      <option value="both">Қазақша / Русский</option>
                    </NativeSelect>
                  </div>
                  <div className="flex items-center justify-between gap-3 rounded-xl border border-border bg-surface-muted px-3 py-2.5">
                    <div>
                      <Label className="cursor-pointer">Запрашивать ИИН покупателя</Label>
                      <p className="text-xs text-muted-foreground">По требованию, для юрлиц</p>
                    </div>
                    <Switch checked={settings.require_buyer_iin} onCheckedChange={(v) => patch('require_buyer_iin', v)} />
                  </div>
                  <div className="space-y-1.5 sm:col-span-2">
                    <Label>Подпись чека (нижний колонтитул)</Label>
                    <Textarea
                      value={settings.receipt_footer_text}
                      onChange={(e) => patch('receipt_footer_text', e.target.value)}
                      rows={2}
                      placeholder="Например: «Спасибо за покупку! Возврат в течение 14 дней по чеку.»"
                    />
                  </div>
                  <div className="space-y-1.5 sm:col-span-2">
                    <Label>Ссылка на отзывы (2GIS / Google Maps)</Label>
                    <Input value={settings.review_url} onChange={(e) => patch('review_url', e.target.value)} placeholder="https://2gis.kz/almaty/firm/..." />
                    <p className="text-xs text-muted-foreground">QR «Оцените нас» на экране покупателя после оплаты. Пусто — QR не показывается.</p>
                  </div>
                </div>
              </StoreSection>

              <StoreSection title="Маркировка и НКТ" subtitle="Появятся отдельным релизом — переключатели пока недоступны" icon={<Info className="h-4 w-4" />}>
                <p className="mb-3 text-xs text-muted-foreground">
                  Печать кода маркируемого товара и кода по Национальному каталогу товаров станет обязательной частью чека.
                </p>
                <div className="grid gap-3 sm:grid-cols-2">
                  <div className="flex items-center justify-between rounded-xl border border-border bg-surface-muted px-3 py-2 opacity-60">
                    <Label>Печать маркировки на чеке</Label>
                    <Switch checked={settings.marking_enabled} onCheckedChange={(v) => patch('marking_enabled', v)} disabled />
                  </div>
                  <div className="flex items-center justify-between rounded-xl border border-border bg-surface-muted px-3 py-2 opacity-60">
                    <Label>Код товара по НКТ</Label>
                    <Switch checked={settings.nkt_enabled} onCheckedChange={(v) => patch('nkt_enabled', v)} disabled />
                  </div>
                </div>
              </StoreSection>
            </div>

            <div className="xl:sticky xl:top-4">
              <StoreSection title="Предпросмотр чека" subtitle="Как чек увидит покупатель" icon={<FileText className="h-4 w-4" />}>
                <div className="flex justify-center">
              <div className="w-full max-w-[300px] rounded-md border border-slate-300 bg-white p-4 font-mono text-[12px] leading-relaxed text-black shadow-sm dark:border-white/15">
                <div className="text-center">
                  <div className="font-bold">{settings.tax_payer_name || 'ТОО / ИП «…»'}</div>
                  <div>БИН/ИИН {settings.tax_payer_bin || '____________'}</div>
                  {settings.point_address ? <div className="text-[11px]">{settings.point_address}</div> : null}
                  <div className="my-1 border-t border-dashed border-black" />
                  <div className="font-bold">КАССОВЫЙ ЧЕК</div>
                  <div className="text-[11px]">
                    ККМ рег.№ {settings.kkm_registration_number || '—'}
                    {settings.kkm_factory_number ? ` · зав.№ ${settings.kkm_factory_number}` : ''}
                  </div>
                </div>
                <div className="my-1 border-t border-dashed border-black" />
                <div className="flex justify-between text-[11px]"><span>Чек №000123</span><span>25.07.2026 19:40</span></div>
                <div className="my-1 border-t border-dashed border-black" />
                <div className="flex justify-between"><span>Кофе латте ×1</span><span>1 200</span></div>
                <div className="flex justify-between"><span>Круассан ×2</span><span>1 400</span></div>
                <div className="my-1 border-t border-dashed border-black" />
                <div className="flex justify-between"><span>Подытог</span><span>2 600 ₸</span></div>
                {settings.is_vat_payer ? (
                  <div className="flex justify-between text-[11px]"><span>в т.ч. НДС {settings.vat_rate}%</span><span>{Math.round(2600 - 2600 / (1 + Number(settings.vat_rate || 0) / 100)).toLocaleString('ru-RU')} ₸</span></div>
                ) : (
                  <div className="text-[11px]">Без НДС</div>
                )}
                <div className="flex justify-between font-bold"><span>ИТОГО</span><span>2 600 ₸</span></div>
                <div className="my-1 border-t border-dashed border-black" />
                {settings.ofd_name ? <div className="text-center text-[11px]">ОФД: {settings.ofd_name}</div> : null}
                {settings.ofd_check_url ? <div className="text-center text-[10px] break-all">Проверка чека: {settings.ofd_check_url}</div> : null}
                <div className="mx-auto my-2 grid h-16 w-16 place-items-center border border-black text-[9px] text-slate-500">QR</div>
                {settings.receipt_footer_text ? <div className="text-center text-[11px]">{settings.receipt_footer_text}</div> : null}
                <div className="mt-1 text-center text-[10px] text-slate-500">
                  Язык: {settings.receipt_language === 'kk' ? 'Қазақша' : settings.receipt_language === 'both' ? 'Қазақша / Русский' : 'Русский'}
                </div>
              </div>
            </div>
                <p className="mt-3 text-center text-[11px] text-muted-foreground">Пример с фиктивными товарами. Реальные данные подставятся при продаже.</p>
              </StoreSection>
            </div>
          </div>
        </>
      )}

      {selectedCompany ? (
        <p className="text-xs text-muted-foreground">
          Настройки относятся к точке: <span className="text-foreground">{selectedCompany.name}</span>
          {selectedCompany.code ? ` · ${selectedCompany.code}` : ''}
        </p>
      ) : null}

      {/* Панель сохранения всегда на виду */}
      {can('store-receipt-settings.edit') && (
        <div className="sticky bottom-3 z-10 flex flex-wrap items-center gap-3 rounded-2xl border border-border bg-white/95 px-4 py-3 shadow-lg backdrop-blur dark:bg-slate-900/90">
          {dirty
            ? <StoreChip tone="amber">Есть несохранённые изменения</StoreChip>
            : success
              ? <StoreChip tone="emerald">{success}</StoreChip>
              : <span className="text-xs text-muted-foreground">Все изменения сохранены</span>}
          <Button onClick={handleSave} disabled={saving || loading || !settings || !dirty} className="ml-auto bg-blue-600 text-white hover:bg-blue-700">
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
            Сохранить
          </Button>
        </div>
      )}
    </div>
  )
}
