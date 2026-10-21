// Имя cookie/ключа сессии Supabase закреплено за облачным проектом (ref tmudsqgagblmdctaosgw).
// По умолчанию supabase-js выводит его из URL базы: после переезда на db.ordaops.kz ключ стал бы
// sb-db-auth-token, и все браузерные сессии слетели бы разом. Не менять — это разлогинит всех.
export const SUPABASE_AUTH_STORAGE_KEY = 'sb-tmudsqgagblmdctaosgw-auth-token'

export const supabaseCookieOptions = { name: SUPABASE_AUTH_STORAGE_KEY }
