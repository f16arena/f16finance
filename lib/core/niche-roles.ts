/**
 * Готовые роли ниши «Детейлинг» — раздел «Команда».
 *
 * Владелец не собирает права галочками: выбирает, кем человек работает, и
 * получает ровно этот набор. Набор зашит здесь и не зависит от таблиц
 * role_capabilities: обычная роль штата начинает с ПОЛНОГО каталога прав
 * (fail-open), а администратору детейлинга нельзя видеть прибыль ни при какой
 * ошибке в настройке. Поэтому у ролей ниши — закрытый список, без добавок.
 *
 * Мастеру вход не обязателен (его заводят в «Мастерах» одним именем). Если
 * владелец даёт ему вход — он видит один экран: свои машины и свой заработок.
 */

export type NicheRoleCode = 'detailing_admin' | 'detailing_accountant' | 'detailing_master'

export type NicheRole = {
  code: NicheRoleCode
  label: string
  summary: string
  /** Что может — для карточки роли. */
  can: string[]
  /** Чего не видит и не может. */
  cannot: string[]
  capabilities: string[]
  /** Страницы вне каталога прав, куда роль пускает прокси. */
  paths: string[]
  /** Куда человек попадает после входа. */
  home: string
}

const all = (page: string) => [`${page}.view`, `${page}.create`, `${page}.edit`, `${page}.delete`]

export const NICHE_ROLES: Record<NicheRoleCode, NicheRole> = {
  detailing_admin: {
    code: 'detailing_admin',
    label: 'Администратор',
    summary: 'Записывает машины и принимает оплату',
    can: ['Запись и клиенты', 'Принять деньги', 'Записать расход'],
    cannot: ['Прибыль и итоги месяца', 'Мастера, прайс и команда'],
    capabilities: [
      'dashboard.view',
      'welcome.view',
      ...all('detailing-bookings'),
      'detailing-bookings.take_payment',
      ...all('detailing-clients'),
      'detailing-prices.view',
      'expenses.create',
    ],
    paths: ['/dashboard', '/welcome', '/detailing', '/detailing/*'],
    home: '/dashboard',
  },
  detailing_accountant: {
    code: 'detailing_accountant',
    label: 'Бухгалтер',
    summary: 'Смотрит деньги и записывает расходы',
    can: ['Доходы, расходы, итоги месяца', 'Записать расход', 'Записи и клиенты — только смотреть'],
    cannot: ['Менять записи и прайс', 'Платить мастерам и менять команду'],
    capabilities: [
      'dashboard.view',
      'welcome.view',
      'detailing-bookings.view',
      'detailing-clients.view',
      'detailing-prices.view',
      'detailing-money.view',
      'detailing-masters.view',
      'income.view',
      'expenses.view',
      'expenses.create',
    ],
    paths: ['/dashboard', '/welcome', '/detailing', '/detailing/*', '/income', '/expenses'],
    home: '/dashboard',
  },
  detailing_master: {
    code: 'detailing_master',
    label: 'Мастер',
    summary: 'Видит только свои машины и свой заработок',
    can: ['Свои машины на сегодня и ближайшие дни', 'Сколько заработал за месяц'],
    cannot: ['Чужие машины и клиентов', 'Деньги детейлинга'],
    capabilities: ['detailing-my.view'],
    paths: ['/detailing/my'],
    home: '/detailing/my',
  },
}

export function isNicheRole(role: string | null | undefined): role is NicheRoleCode {
  return !!role && Object.prototype.hasOwnProperty.call(NICHE_ROLES, role)
}

/** Закрытый набор прав роли ниши; null — роль обычная, права считаются как всегда. */
export function nicheRoleCapabilities(role: string | null | undefined): string[] | null {
  return isNicheRole(role) ? NICHE_ROLES[role].capabilities : null
}
