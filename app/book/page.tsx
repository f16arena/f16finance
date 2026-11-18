import type { Metadata } from 'next'
import { headers } from 'next/headers'
import { notFound } from 'next/navigation'

import { OnlineBookingForm } from '@/components/detailing/online-booking-form'
import { resolveHostNiche } from '@/lib/server/tenant-hosts'

/** Онлайн-запись клиента: открывается по ссылке детейлинга, без входа. */

async function hostOrg() {
  const found = await resolveHostNiche((await headers()).get('host')).catch(() => null)
  return found?.niche === 'detailing' ? found.org : null
}

export async function generateMetadata(): Promise<Metadata> {
  const org = await hostOrg()
  return org ? { title: { absolute: `Запись · ${org.name}` }, robots: { index: false } } : {}
}

export default async function BookPage() {
  const org = await hostOrg()
  if (!org) notFound()
  return <OnlineBookingForm orgName={org.name} />
}
