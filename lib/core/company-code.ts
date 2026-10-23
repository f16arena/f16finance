// Латиница из кириллицы и казахского — код точки виден в отчётах и служит
// ключом правил зарплаты, поэтому он только из A-Z и цифр.
const TRANSLIT: Record<string, string> = {
  а: 'A', б: 'B', в: 'V', г: 'G', д: 'D', е: 'E', ё: 'E', ж: 'ZH', з: 'Z', и: 'I', й: 'Y',
  к: 'K', л: 'L', м: 'M', н: 'N', о: 'O', п: 'P', р: 'R', с: 'S', т: 'T', у: 'U', ф: 'F',
  х: 'H', ц: 'TS', ч: 'CH', ш: 'SH', щ: 'SCH', ъ: '', ы: 'Y', ь: '', э: 'E', ю: 'YU', я: 'YA',
  ә: 'A', ғ: 'G', қ: 'K', ң: 'N', ө: 'O', ұ: 'U', ү: 'U', һ: 'H', і: 'I',
}

export function transliterate(text: string) {
  return Array.from(text.toLowerCase())
    .map((ch) => (ch in TRANSLIT ? TRANSLIT[ch] : ch.toUpperCase()))
    .join('')
}

export function companyCodeBase(name: string) {
  return transliterate(name).replace(/[^A-Z0-9]/g, '').slice(0, 10) || 'POINT'
}

/** Первый свободный из base, base2 … base9; занятые сравниваются без регистра. */
export function pickFreeCompanyCode(base: string, taken: Iterable<string>, fallbackSuffix: string) {
  const takenSet = new Set(Array.from(taken, (code) => String(code || '').toUpperCase()))
  const candidates = companyCodeCandidates(base)
  return candidates.find((code) => !takenSet.has(code)) || `${base}${fallbackSuffix}`
}

export function companyCodeCandidates(base: string) {
  return [base, ...Array.from({ length: 8 }, (_, i) => `${base}${i + 2}`)]
}
