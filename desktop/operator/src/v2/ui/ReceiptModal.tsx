import { useEffect, useRef, type ReactNode } from 'react'
import { Check, Printer, X } from 'lucide-react'

import { printReceiptFromIframe } from '@/lib/receipt-html'

/**
 * Окно чека — по центру экрана, на всю высоту, чтобы чек был виден целиком,
 * а не в сплющенной шторке снизу. Одно на все экраны: продажа, возврат,
 * копия чека, сменный отчёт, чек зала. Esc закрывает.
 */
export function ReceiptModal(props: {
  title: string
  subtitle?: string
  /** HTML чека (buildReceiptHtmlForPreview / buildShiftReportHtml и т. п.). */
  html: string
  onClose: () => void
  printLabel?: string
  closeLabel?: string
  /** Зелёная галочка в шапке — для «Оплачено», «Возврат оформлен». */
  success?: boolean
  /** Доп. кнопки/блоки под чеком (например, отправка на почту). */
  extra?: ReactNode
}) {
  const iframeRef = useRef<HTMLIFrameElement>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') props.onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  return (
    <div
      className="fixed inset-0 z-50 grid place-items-center p-6"
      style={{ background: 'rgba(0,0,0,0.45)', backdropFilter: 'blur(6px)', WebkitBackdropFilter: 'blur(6px)' }}
      onClick={props.onClose}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="sheet-in surface flex h-full max-h-[min(92vh,900px)] w-full max-w-[480px] flex-col rounded-[28px] p-5"
        style={{ boxShadow: 'var(--shadow-lift)' }}
        role="dialog"
        aria-modal="true"
        aria-label={props.title}
      >
        <div className="flex shrink-0 items-center gap-3">
          {props.success ? (
            <div className="grid h-11 w-11 shrink-0 place-items-center rounded-full" style={{ background: 'var(--green)' }}>
              <Check className="h-6 w-6 text-white" strokeWidth={3} />
            </div>
          ) : null}
          <div className="min-w-0 flex-1">
            <div className="truncate text-[19px] font-bold leading-tight">{props.title}</div>
            {props.subtitle ? <div className="muted truncate text-[14px]">{props.subtitle}</div> : null}
          </div>
          <button type="button" onClick={props.onClose} className="press fill grid h-10 w-10 shrink-0 place-items-center rounded-full" aria-label="Закрыть">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-4 min-h-0 flex-1 overflow-hidden rounded-[18px] bg-white">
          <iframe ref={iframeRef} title={props.title} srcDoc={props.html} className="h-full w-full" style={{ border: 'none' }} />
        </div>

        {props.extra ? <div className="mt-3 shrink-0">{props.extra}</div> : null}

        <div className="mt-4 grid shrink-0 grid-cols-2 gap-3">
          <button type="button" onClick={props.onClose} className="press fill h-14 rounded-[16px] text-[16px] font-semibold">
            {props.closeLabel || 'Закрыть'}
          </button>
          <button
            type="button"
            onClick={() => printReceiptFromIframe(iframeRef.current)}
            className="press btn-blue flex h-14 items-center justify-center gap-2 rounded-[16px] text-[16px] font-semibold"
          >
            <Printer className="h-5 w-5" /> {props.printLabel || 'Печать'}
          </button>
        </div>
      </div>
    </div>
  )
}
