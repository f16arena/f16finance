import 'server-only'

import { DEFAULT_ORG_SETTINGS, normalizeOrgSettings, type OrgSettings } from '@/lib/core/org-settings'
import { createAdminSupabaseClient, hasAdminSupabaseCredentials } from '@/lib/server/supabase'

/**
 * Настройки организации из organizations.settings. Нет организации (суперадмин без
 * выбранной) или нет базы — значения F16 по умолчанию.
 */
export async function getOrgSettings(organizationId: string | null | undefined): Promise<OrgSettings> {
  if (!organizationId || !hasAdminSupabaseCredentials()) return DEFAULT_ORG_SETTINGS
  const { data } = await createAdminSupabaseClient()
    .from('organizations')
    .select('settings')
    .eq('id', organizationId)
    .maybeSingle()
  return normalizeOrgSettings((data as any)?.settings)
}
