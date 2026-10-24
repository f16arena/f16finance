import { useRef } from 'react'
import { Loader2, Minus, Plus, Printer, RefreshCw, RotateCcw, Search, ShoppingBag, X } from 'lucide-react'

import { buildReceiptHtmlForPreview, formatShiftLabel, printReceiptFromIframe } from '@/lib/receipt-html'
import { formatDate } from '@/lib/utils'
import type { AppConfig, BootstrapData, OperatorSession } from '@/types'

import { V2Shell, type V2Nav } from '../ui/V2Shell'
import { ReceiptModal } from '../ui/ReceiptModal'
import { paymentBadge, roundQty, useReturnsEngine } from './useReturnsEngine'

type Props = {
  config: AppConfig
  bootstrap: BootstrapData
  session: OperatorSession
  onLogout: () => void
  onSwitchToShift?: () => void
  onSwitchToSale?: () => void
  onSwitchToHistory?: () => void
  onSwitchToScanner?: () => void
  onSwitchToRequest?: () => void
  onSwitchToArena?: () => void
  onOpenCabinet?: () => void
}

function money(value: number) {
  return Math.round(value).toLocaleString('ru-RU') + ' ₸'
}

/** Шторка снизу поверх экрана. */
function Sheet(props: { title: string; subtitle?: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="absolute inset-0 z-30 grid place-items-end justify-items-center p-3" style={{ background: 'rgba(0,0,0,0.35)' }} onClick={props.onClose}>
      <div
        onClick={(ev) => ev.stopPropagation()}
        className="sheet-in glass flex max-h-full w-full max-w-3xl flex-col rounded-[24px] p-5"
        style={{ boxShadow: 'var(--shadow-lift)' }}
      >
        <div className="mb-4 flex shrink-0 items-center justify-between gap-3">
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

export default function ReturnsScreenV2(props: Props) {
  const e = useReturnsEngine({ config: props.config, session: props.session })
  const receiptRef = useRef<HTMLIFrameElement | null>(null)

  const nav: V2Nav = {
    shift: props.onSwitchToShift,
    sale: props.onSwitchToSale,
    history: props.onSwitchToHistory,
    scanner: props.onSwitchToScanner,
    request: props.onSwitchToRequest,
    arena: props.onSwitchToArena,
    cabinet: props.onOpenCabinet,
  }

  const refund = e.refund
  const receipt = e.lastReceipt

  return (
    <V2Shell
      active="return"
      nav={nav}
      companyName={props.session.company?.name || 'Точка'}
      operatorName={e.operatorName}
      shiftLabel={formatShiftLabel(e.runtimeShift.shift)}
      onLogout={props.onLogout}
    >
      <div className="relative grid h-full min-h-0 grid-cols-[250px_1fr_380px] gap-4 p-4">
        {/* Чеки */}
        <aside className="surface flex min-h-0 flex-col rounded-[22px]">
          <div className="flex shrink-0 items-center justify-between px-4 pb-2 pt-4">
            <h2 className="text-[17px] font-bold">Чеки{e.currentShiftSales.length > 0 ? ` · ${e.currentShiftSales.length}` : ''}</h2>
            <button type="button" onClick={() => void e.load(e.selectedSaleId)} disabled={e.loading} className="press fill grid h-11 w-11 place-items-center rounded-full" title="Обновить" aria-label="Обновить">
              <RefreshCw className={`h-4 w-4 ${e.loading ? 'animate-spin' : ''}`} />
            </button>
          </div>
          <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 pb-3">
            {e.error ? (
              <div className="rounded-[14px] p-3 text-[13px]" style={{ background: 'color-mix(in srgb, var(--red) 12%, transparent)', color: 'var(--red)' }}>
                {e.error}
              </div>
            ) : null}
            {e.loading ? (
              <div className="muted grid h-24 place-items-center">
                <Loader2 className="h-5 w-5 animate-spin" />
              </div>
            ) : e.currentShiftSales.length === 0 ? (
              <p className="muted px-2 py-8 text-center text-[14px]">Нет продаж для возврата</p>
            ) : (
              e.currentShiftSales.map((sale) => {
                const list = Array.isArray(sale.items) ? sale.items : []
                const remaining = list.reduce((sum, line) => sum + Number(line.returnable_qty || 0), 0)
                const selected = sale.id === e.selectedSaleId
                return (
                  <button
                    key={sale.id}
                    type="button"
                    onClick={() => e.selectSale(sale.id)}
                    className={`press w-full rounded-[16px] p-3 text-left ${selected ? '' : 'surface-2'}`}
                    style={selected ? { background: 'color-mix(in srgb, var(--red) 12%, transparent)', boxShadow: 'inset 0 0 0 2px var(--red)' } : undefined}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-[17px] font-bold">{money(Number(sale.total_amount || 0))}</span>
                      <span className="fill rounded-full px-2 py-0.5 text-[12px] font-medium">{paymentBadge(sale.payment_method)}</span>
                    </div>
                    <p className="muted mt-1 text-[13px]">
                      {formatDate(sale.sale_date)} · {new Date(sale.sold_at).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}
                    </p>
                    <p className="muted text-[13px]">Вернуть: {roundQty(remaining)}</p>
                  </button>
                )
              })
            )}
          </div>
        </aside>

        {/* Позиции чека */}
        <main className="flex min-h-0 flex-col gap-3">
          <div className="flex items-center gap-3">
            <div className="surface flex h-12 flex-1 items-center gap-2 rounded-[14px] px-4">
              <Search className="muted h-5 w-5 shrink-0" />
              <input
                value={e.search}
                onChange={(ev) => e.setSearch(ev.target.value)}
                disabled={!e.selectedSale}
                placeholder="Поиск по названию или штрихкоду"
                className="placeholder-muted h-full flex-1 bg-transparent text-[15px] outline-none"
              />
              {e.search ? (
                <button type="button" onClick={() => e.setSearch('')} className="press fill grid h-8 w-8 place-items-center rounded-full" aria-label="Очистить поиск">
                  <X className="h-4 w-4" />
                </button>
              ) : null}
            </div>
            {e.selectedSale ? <div className="surface flex h-12 items-center rounded-[14px] px-4 text-[15px] font-semibold">Чек {money(Number(e.selectedSale.total_amount || 0))}</div> : null}
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto">
            {!e.selectedSale ? (
              <div className="muted grid h-full place-items-center text-center text-[15px]">Выберите чек слева</div>
            ) : e.saleItems.length === 0 ? (
              <div className="muted grid h-full place-items-center text-center text-[15px]">По чеку нечего возвращать</div>
            ) : (
              <div className="grid grid-cols-2 gap-3 xl:grid-cols-3">
                {e.saleItems.map((line) => {
                  const returnable = roundQty(Number(line.returnable_qty || 0))
                  const sold = roundQty(Number(line.quantity || 0))
                  const inCart = e.cart.find((c) => c.item_id === line.id)?.quantity || 0
                  return (
                    <button
                      key={line.id}
                      type="button"
                      disabled={returnable <= 0}
                      onClick={() => e.addToCart(line.id)}
                      className="press lift surface relative flex min-h-[104px] flex-col justify-between rounded-[18px] p-4 text-left"
                    >
                      <p className="line-clamp-2 pr-8 text-[15px] font-semibold leading-tight">{line.item?.name || (line as any).universal_name || 'Товар'}</p>
                      <div>
                        <p className="text-[17px] font-bold">{money(Number(line.unit_price || 0))}</p>
                        <p className="muted text-[13px]">Продано: {sold} · можно вернуть: {returnable}</p>
                      </div>
                      {inCart > 0 ? (
                        <span key={inCart} className="pop badge absolute right-3 top-3 grid h-7 min-w-7 place-items-center rounded-full px-2 text-[13px] font-bold" style={{ background: 'var(--red)' }}>
                          {inCart}
                        </span>
                      ) : null}
                    </button>
                  )
                })}
              </div>
            )}
          </div>
        </main>

        {/* Возврат */}
        <aside className="surface flex min-h-0 flex-col rounded-[22px]">
          <div className="flex shrink-0 items-center justify-between px-4 pb-2 pt-4">
            <h2 className="text-[17px] font-bold">Возврат{e.cartDetailed.length > 0 ? ` · ${e.cartDetailed.length}` : ''}</h2>
            {e.cartDetailed.length > 0 ? (
              <button type="button" onClick={() => e.setCart([])} className="press t-red h-11 px-3 text-[14px] font-medium">
                Очистить
              </button>
            ) : null}
          </div>
          <div className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3">
            {e.cartDetailed.length === 0 ? (
              <div className="muted grid h-32 place-items-center px-4 text-center text-[14px]">
                <div className="flex flex-col items-center gap-2">
                  <ShoppingBag className="h-8 w-8 opacity-40" />
                  Выберите позиции из чека
                </div>
              </div>
            ) : (
              e.cartDetailed.map((line) => {
                const max = e.getReturnableQty(line.item_id)
                return (
                  <div key={line.item_id} className="slide-in surface-2 rounded-[16px] p-3">
                    <div className="flex items-start justify-between gap-2">
                      <p className="min-w-0 text-[14px] font-medium leading-tight">{line.saleLine.item?.name || (line.saleLine as any).universal_name || 'Товар'}</p>
                      <p className="t-red shrink-0 text-[15px] font-bold">{money(line.total)}</p>
                    </div>
                    <div className="mt-2 flex items-center justify-between">
                      <div className="fill flex items-center rounded-full">
                        <button type="button" onClick={() => e.changeQty(line.item_id, line.quantity - 1)} className="press grid h-11 w-11 place-items-center rounded-full" aria-label="Меньше">
                          <Minus className="h-4 w-4" />
                        </button>
                        <span className="min-w-[2.5rem] text-center text-[16px] font-semibold">{line.quantity}</span>
                        <button type="button" onClick={() => e.changeQty(line.item_id, line.quantity + 1)} disabled={line.quantity >= max} className="press grid h-11 w-11 place-items-center rounded-full" aria-label="Больше">
                          <Plus className="h-4 w-4" />
                        </button>
                      </div>
                      <p className="muted text-[13px]">макс. {max}</p>
                    </div>
                  </div>
                )
              })
            )}
          </div>

          <div className="shrink-0 space-y-3 p-4">
            <textarea
              value={e.comment}
              onChange={(ev) => e.setComment(ev.target.value)}
              rows={2}
              placeholder="Причина возврата"
              className="surface-2 placeholder-muted w-full resize-none rounded-[14px] px-3 py-2.5 text-[14px] outline-none"
            />
            <div className="surface-2 rounded-[16px] p-3">
              {refund.cashAmount > 0 ? (
                <div className="muted flex justify-between text-[13px]">
                  <span>Наличными</span>
                  <span>{money(refund.cashAmount)}</span>
                </div>
              ) : null}
              {refund.kaspiAmount > 0 ? (
                <div className="muted flex justify-between text-[13px]">
                  <span>Безналичный</span>
                  <span>{money(refund.kaspiAmount)}</span>
                </div>
              ) : null}
              <div className="mt-1 flex items-end justify-between">
                <div>
                  <p className="muted text-[12px] uppercase tracking-wide">К возврату</p>
                  <p className="t-red text-[30px] font-bold leading-tight">{money(e.cartTotal)}</p>
                </div>
                <span className="fill rounded-full px-2.5 py-1 text-[12px] font-medium">{paymentBadge(refund.paymentMethod)}</span>
              </div>
            </div>
            <button
              type="button"
              onClick={() => void e.submit()}
              disabled={e.saving || e.cartDetailed.length === 0 || !e.selectedSale}
              className="press flex h-14 w-full items-center justify-center gap-2 rounded-[16px] text-[17px] font-semibold text-white"
              style={{ background: 'var(--red)' }}
            >
              {e.saving ? <Loader2 className="h-5 w-5 animate-spin" /> : <RotateCcw className="h-5 w-5" />}
              Провести возврат
            </button>
          </div>
        </aside>

        {/* Чек возврата */}
        {receipt ? (
          <ReceiptModal
            success
            title="Возврат оформлен"
            subtitle={`Возврат к чеку #${receipt.originalSaleId?.slice(-6) || '—'} · ${money(receipt.totalAmount)}`}
            html={buildReceiptHtmlForPreview(receipt)}
            onClose={() => e.setLastReceipt(null)}
          />
        ) : null}
      </div>
    </V2Shell>
  )
}
