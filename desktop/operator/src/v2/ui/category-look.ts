import { Clock, Cookie, CupSoda, Package, UtensilsCrossed, type LucideIcon } from 'lucide-react'

/**
 * Иконка и цвет категории по её названию — одни и те же на продаже, заявке и
 * везде, где показываются товары. Неизвестная категория получает постоянный
 * цвет из палитры по названию.
 */

const PALETTE = ['#2563eb', '#ff9500', '#ff3b30', '#af52de', '#34c759', '#ff2d55', '#5ac8fa', '#ffcc00']

export function categoryLook(name: string | null | undefined): { icon: LucideIcon; color: string } {
  const n = String(name || '').toLowerCase()
  if (!n) return { icon: Package, color: '#8e8e93' }
  if (/напит|вода|сок|кофе|чай|drink/.test(n)) return { icon: CupSoda, color: '#2563eb' }
  if (/снек|чипс|слад|шокол|snack/.test(n)) return { icon: Cookie, color: '#ff9500' }
  if (/еда|кухн|блюд|пицц|рамен|food/.test(n)) return { icon: UtensilsCrossed, color: '#ff3b30' }
  if (/врем|час|услуг|тариф|пакет/.test(n)) return { icon: Clock, color: '#af52de' }
  let h = 0
  for (const ch of n) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return { icon: Package, color: PALETTE[h % PALETTE.length] }
}
