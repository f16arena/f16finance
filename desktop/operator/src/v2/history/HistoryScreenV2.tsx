import { useRef } from 'react'
import { CloudOff, Loader2, Printer, ReceiptText, RefreshCw, Search, UserCircle2, X } from 'lucide-react'

import { buildReceiptHtmlForPreview, printReceiptFromIframe } from '@/lib/receipt-html'
import type { AppConfig, OperatorSession } from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'
import { ReceiptModal } from '../ui/ReceiptModal'
import { formatSoldAt, useHistoryEngine } from './useHistoryEngine'

type Props = {
  config: AppConfig
  session: OperatorSession
  onLogout?: () => void
  onSwitchToShift?: () => void
  onSwitchToSale?: () => void
  onSwitchToReturn?: () => void
  onSwitchToScanner?: () => void
  onSwitchToRequest?: () => void
  onSwitchToArena?: () => void
  onOpenCabinet?: () => void
}

function money(value: number) {
  return Math.round(value).toLocaleString('ru-RU') + ' ₸'
}

/** Шторка снизу поверх экрана. */
function Sheet(props: { title: string; subtitle?: string; onClose: () => void; children: React.ReactNode; wide?: boolean }) {
  return (
    <div className="absolute inset-0 z-30 grid place-items-end justify-items-center p-3" style={{ background: 'rgba(0,0,0,0.35)' }} onClick={props.onClose}>
      <div
        onClick={(ev) => ev.stopPropagation()}
        className={`sheet-in glass flex max-h-full w-full flex-col rounded-[24px] p-5 ${props.wide ? 'max-w-xl' : 'max-w-lg'}`}
        style={{ boxShadow: 'var(--shadow-lift)' }}
      >
        <div className="mb-4 flex shrink-0 items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="text-[18px] font-bold">{props.title}</h3>
            {props.subtitle ? <p className="muted text-[13px]">{props.subtitle}</p> : null}
          </div>
          <button type="button" onClick={props.onClose} className="press fill grid h-11 w-11 shrink-0 place-items-center rounded-full" aria-label="Закрыть">
            <X className="h-4 w-4" />
          </button>
        </div>
        {props.children}
      </div>
    </div>
  )
}

export default function HistoryScreenV2(props: Props) {
  const e = useHistoryEngine({ config: props.config, session: props.session })
  const printRef = useRef<HTMLIFrameElement | null>(null)

  const nav: V2Nav = {
    shift: props.onSwitchToShift,
    sale: props.onSwitchToSale,
    return: props.onSwitchToReturn,
    scanner: props.onSwitchToScanner,
    request: props.onSwitchToRequest,
    arena: props.onSwitchToArena,
    cabinet: props.onOpenCabinet,
  }

  const sale = e.selectedSale
  const preview = e.printPreview

  return (
    <V2Shell
      active="history"
      nav={nav}
      companyName={props.session.company?.name || 'Точка'}
      operatorName={e.operatorName}
      shiftLabel="История"
      onLogout={props.onLogout}
    >
      <div className="relative flex h-full min-h-0 flex-col gap-3 p-4">
        <div className="mx-auto flex w-full max-w-3xl shrink-0 items-center gap-3">
          <div className="surface flex h-12 flex-1 items-center gap-2 rounded-[14px] px-4">
            <Search className="muted h-5 w-5 shrink-0" />
            <input
              value={e.query}
              onChange={(ev) => e.setQuery(ev.target.value)}
              inputMode="numeric"
              autoFocus
              placeholder="Сумма чека — например 750"
              className="placeholder-muted h-full flex-1 bg-transparent text-[16px] outline-none"
            />
            {e.query ? (
              <button type="button" onClick={() => e.setQuery('')} className="press fill grid h-8 w-8 place-items-center rounded-full" aria-label="Очистить поиск">
                <X className="h-4 w-4" />
              </button>
            ) : null}
          </div>
          <button type="button" onClick={() => void e.reload()} disabled={e.loading} className="press surface grid h-12 w-12 place-items-center rounded-[14px]" title="Обновить историю" aria-label="Обновить историю">
            <RefreshCw className={`h-5 w-5 ${e.loading ? 'animate-spin' : ''}`} />
          </button>
        </div>
        <p className="muted mx-auto w-full max-w-3xl shrink-0 px-1 text-[13px]">История продаж · последние 7 дней · все кассиры</p>

        {e.offlineMode ? (
          <div className="mx-auto flex w-full max-w-3xl shrink-0 items-center gap-2 rounded-[14px] px-4 py-3 text-[13px]" style={{ background: 'color-mix(in srgb, var(--orange) 14%, transparent)', color: 'var(--orange)' }}>
            <CloudOff className="h-4 w-4 shrink-0" />
            <span>Сервер недоступен — офлайн: показаны только продажи этой кассы (очередь на отправку и последний кэш).</span>
          </div>
        ) : null}

        <div className="min-h-0 flex-1 overflow-y-auto">
          <div className="mx-auto w-full max-w-3xl space-y-2">
            {e.initialLoading ? (
              <div className="muted flex h-40 items-center justify-center gap-2">
                <Loader2 className="h-5 w-5 animate-spin" />
                Загружаем историю продаж…
              </div>
            ) : e.sales.length === 0 ? (
              <div className="muted flex h-60 flex-col items-center justify-center gap-3 text-center">
                <ReceiptText className="h-12 w-12 opacity-30" />
                <p className="text-[15px]">{e.query.trim() ? 'Чеков с такой суммой не найдено' : 'Чеков за последние 7 дней нет'}</p>
                {e.query.trim() ? <p className="text-[13px]">Проверьте сумму — поиск ищет точное совпадение суммы чека.</p> : null}
              </div>
            ) : (
              <>
                <div className="muted flex items-center justify-between px-1 text-[13px]">
                  <span>Чеков: {e.sales.length}</span>
                  <span>На сумму: {money(e.totalShown)}</span>
                </div>
                {e.sales.map((row) => (
                  <button key={row.id} type="button" onClick={() => e.setSelectedSale(row)} className="press lift surface flex min-h-[64px] w-full items-center justify-between gap-3 rounded-[18px] p-4 text-left">
                    <div className="min-w-0">
                      <div className="flex flex-wrap items-center gap-2">
                        <p className="text-[15px] font-semibold">{formatSoldAt(row.sold_at, row.sale_date)}</p>
                        <span className="fill rounded-full px-2 py-0.5 text-[12px] font-medium">{e.paymentLabel(row.payment_method)}</span>
                        {row.pending ? (
                          <span className="rounded-full px-2 py-0.5 text-[12px] font-medium text-white" style={{ background: 'var(--orange)' }}>
                            ждёт отправки
                          </span>
                        ) : null}
                      </div>
                      <p className="muted mt-1 flex items-center gap-1.5 text-[13px]">
                        <UserCircle2 className="h-4 w-4 shrink-0" />
                        <span className="truncate">
                          {row.operator_name || 'Кассир не указан'} · {row.items.length} поз. · #{row.id.slice(-6)}
                        </span>
                      </p>
                    </div>
                    <p className="shrink-0 text-[22px] font-bold">{money(Number(row.total_amount || 0))}</p>
                  </button>
                ))}
              </>
            )}
          </div>
        </div>

        {/* Детальный вид чека */}
        {sale && !preview ? (
          <Sheet
            wide
            title={`Чек #${sale.id.slice(-6)}`}
            subtitle={`${formatSoldAt(sale.sold_at, sale.sale_date)} · ${sale.operator_name || 'кассир не указан'}${sale.pending ? ' · ещё не отправлен на сервер' : ''}`}
            onClose={() => e.setSelectedSale(null)}
          >
            <div className="max-h-72 min-h-0 overflow-y-auto">
              {sale.items.length === 0 ? (
                <p className="muted py-6 text-center text-[14px]">Позиции чека недоступны</p>
              ) : (
                sale.items.map((line, idx) => (
                  <div key={`${sale.id}-${idx}`} className="flex items-baseline justify-between gap-3 py-2" style={idx > 0 ? { borderTop: '1px solid var(--line)' } : undefined}>
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[15px] font-medium">{line.name}</p>
                      <p className="muted text-[13px]">
                        {line.quantity} {line.unit || 'шт'} × {money(line.unit_price)}
                      </p>
                    </div>
                    <p className="shrink-0 text-[15px] font-semibold">{money(line.total_price)}</p>
                  </div>
                ))
              )}
            </div>

            <div className="surface-2 mt-4 shrink-0 rounded-[16px] p-4">
              <div className="flex items-baseline justify-between">
                <span className="text-[15px] font-semibold">{e.paymentLabel(sale.payment_method)}</span>
                <span className="text-[26px] font-bold">{money(Number(sale.total_amount || 0))}</span>
              </div>
              {sale.payment_method === 'mixed' ? (
                <div className="muted mt-2 space-y-0.5 pt-2 text-[13px]" style={{ borderTop: '1px solid var(--line)' }}>
                  <div className="flex justify-between">
                    <span>Наличные</span>
                    <span>{money(Number(sale.cash_amount || 0))}</span>
                  </div>
                  <div className="flex justify-between">
                    <span>{e.providerName}</span>
                    <span>{money(Number(sale.kaspi_amount || 0))}</span>
                  </div>
                </div>
              ) : null}
              {sale.discount_amount > 0 ? <p className="muted mt-2 text-[13px]">Скидка: −{money(Number(sale.discount_amount || 0))}</p> : null}
              {sale.comment ? <p className="muted mt-2 text-[13px]">Комментарий: {sale.comment}</p> : null}
            </div>

            <div className="mt-4 flex shrink-0 gap-3">
              <button type="button" onClick={() => e.setSelectedSale(null)} className="press fill h-12 flex-1 rounded-[14px] text-[15px] font-semibold">
                Закрыть
              </button>
              <button type="button" onClick={() => e.handlePrintCopy(sale)} disabled={sale.items.length === 0} className="press btn-blue flex h-12 flex-1 items-center justify-center gap-2 rounded-[14px] text-[15px] font-semibold">
                <Printer className="h-4 w-4" />
                Печать копии
              </button>
            </div>
          </Sheet>
        ) : null}

        {/* Превью копии чека + печать */}
        {preview ? (
          <ReceiptModal
            title={`Копия чека #${preview.saleId?.slice(-6) || '—'}`}
            subtitle="На чеке будет пометка «КОПИЯ» — это повторная печать"
            html={buildReceiptHtmlForPreview(preview)}
            onClose={() => e.setPrintPreview(null)}
            printLabel="Печатать копию"
          />
        ) : null}
      </div>
    </V2Shell>
  )
}
