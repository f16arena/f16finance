'use client'

import { useState } from 'react'
import { Database, ExternalLink, Loader2, RotateCw } from 'lucide-react'

// Supabase Studio внутри /platform: вход по сессии суперадмина, без пароля Studio
// (lib/server/studio-gate.ts). Сама Studio живёт на studio.ordaops.kz.

const OPEN = '/api/admin/platform/studio/open'

export default function PlatformStudioPage() {
  const [loaded, setLoaded] = useState(false)
  const [key, setKey] = useState(0)
  return (
    <div className="flex h-screen flex-col">
      <div className="flex items-center gap-3 border-b border-slate-200 bg-white/80 px-4 py-2.5 backdrop-blur-xl dark:border-white/[0.06] dark:bg-[#050816]/80">
        <div className="grid h-7 w-7 place-items-center rounded-lg bg-gradient-to-br from-blue-500 to-teal-500"><Database className="h-4 w-4 text-white" /></div>
        <div className="text-sm font-semibold text-slate-900 dark:text-white">Supabase Studio</div>
        <span className="hidden text-xs text-slate-500 sm:inline dark:text-slate-400">таблицы, SQL, Auth, Storage — правки идут прямо в рабочую базу</span>
        <div className="ml-auto flex items-center gap-2">
          <button onClick={() => { setLoaded(false); setKey((k) => k + 1) }} title="Перезагрузить" className="grid h-8 w-8 place-items-center rounded-lg border border-slate-200 text-slate-500 hover:bg-slate-50 dark:border-white/10 dark:hover:bg-white/5"><RotateCw className="h-3.5 w-3.5" /></button>
          <a href={OPEN} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-50 dark:border-white/10 dark:text-slate-300 dark:hover:bg-white/5">В новой вкладке <ExternalLink className="h-3.5 w-3.5" /></a>
        </div>
      </div>
      <div className="relative flex-1">
        {!loaded ? <div className="absolute inset-0 grid place-items-center"><Loader2 className="h-6 w-6 animate-spin text-blue-400" /></div> : null}
        <iframe key={key} src={OPEN} onLoad={() => setLoaded(true)} title="Supabase Studio" className="absolute inset-0 h-full w-full border-0" />
      </div>
    </div>
  )
}
