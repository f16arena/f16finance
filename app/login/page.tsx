import type { Metadata } from 'next'
import { headers } from 'next/headers'

import { DetailingLogin } from '@/components/detailing/detailing-login'
import { resolveHostNiche } from '@/lib/server/tenant-hosts'

import LoginForm from './LoginForm'

/** Чей это адрес. На поддомене ниши вход свой — человек сразу видит свою программу. */
async function hostNiche() {
  const host = (await headers()).get('host')
  const found = await resolveHostNiche(host).catch(() => null)
  return { host: String(host || '').split(':')[0], found }
}

export async function generateMetadata(): Promise<Metadata> {
  const { found } = await hostNiche()
  if (found?.niche === 'detailing') return { title: { absolute: `Вход · ${found.org.name}` } }
  return {}
}

export default async function LoginPage() {
  const { host, found } = await hostNiche()
  if (found?.niche === 'detailing') return <DetailingLogin orgName={found.org.name} host={host} />
  return <LoginForm hostOrg={null} isTenantSubdomain={false} platformUrl="https://ordaops.kz" />
}
