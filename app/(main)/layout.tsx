import { ConfirmDialogHost } from '@/components/ui/confirm-dialog'
import { DocumentTitle } from '@/components/document-title'
import { ScrollToTop } from '@/components/scroll-to-top'
import { KeyboardShortcuts } from '@/components/keyboard-shortcuts'
import { PageEntitlementGuard } from '@/components/page-entitlement-guard'
import { SubscriptionGate } from '@/components/subscription-gate'
import { AppFrame } from '@/components/app-frame'
import { CashlessProvider } from '@/lib/client/use-cashless-labels'

export default function MainLayout({ children }: { children: React.ReactNode }) {
  return (
    <CashlessProvider>
      <div className="app-shell-layout">
        <SubscriptionGate />
        <PageEntitlementGuard />
        <DocumentTitle />
        <KeyboardShortcuts />
        <AppFrame>{children}</AppFrame>
        <ConfirmDialogHost />
        <ScrollToTop />
      </div>
    </CashlessProvider>
  )
}
