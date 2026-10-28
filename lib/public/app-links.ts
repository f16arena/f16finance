// Ссылки на приложения Orda Point. Отдельный модуль без 'use client': значения
// из клиентского модуля в серверном компоненте превращаются в client reference.
export const APP_STORE_URL = 'https://apps.apple.com/kz/app/orda-point/id6801461500'
export const GOOGLE_PLAY_URL = 'https://play.google.com/store/apps/details?id=kz.ordaops.android'
export const OPERATOR_DOWNLOAD_URL = process.env.NEXT_PUBLIC_OPERATOR_DOWNLOAD_URL || 'https://github.com/f16arena/f16finance/releases/latest'
