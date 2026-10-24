/**
 * DEMO: заглушка window.electron (то, что в проде даёт preload.cjs).
 * Всё хранится в памяти страницы. Должна быть установлена ДО импорта App,
 * потому что lib/config.ts, cache.ts и offline.ts читают `window.electron` на уровне модуля.
 */
import { BOOTSTRAP, SESSION } from './fixtures'

type AnyRec = Record<string, unknown>

export function installElectronStub(opts: { loggedIn: boolean }) {
  const config: AnyRec = {
    apiUrl: 'https://demo.local',
    serverBaseUrl: 'https://demo.local',
    deviceToken: 'demo-device-token-not-real',
  }

  // Кэш приложения: bootstrap + сохранённая сессия оператора => App сразу «залогинен».
  let cache: AnyRec = {
    bootstrap: BOOTSTRAP,
    ...(opts.loggedIn
      ? { operatorSession: { session: SESSION, savedAt: new Date().toISOString() } }
      : {}),
  }

  type QItem = { id: number; type: string; payload: unknown; status: string; local_ref: string | null; attempts: number; last_error: string | null; last_attempt_at: string | null; created_at: string }
  let queue: QItem[] = []
  let queueSeq = 1

  const updaterState = {
    status: 'idle', currentVersion: '2.19.0-demo', latestVersion: null, releaseNotes: null,
    releaseDate: null, progress: null, error: null,
  }

  const listeners = new Set<(s: unknown) => void>()

  ;(window as unknown as { electron: unknown }).electron = {
    config: {
      get: async () => ({ ...config }),
      set: async (c: AnyRec) => { Object.assign(config, c); return { ok: true } },
    },
    queue: {
      add: async (d: { type: string; payload: unknown; localRef?: string }) => {
        const id = queueSeq++
        queue.push({
          id, type: d.type, payload: d.payload, status: 'pending', local_ref: d.localRef ?? null,
          attempts: 0, last_error: null, last_attempt_at: null, created_at: new Date().toISOString(),
        })
        return { id }
      },
      list: async (o?: { status?: string }) => (o?.status ? queue.filter((q) => q.status === o.status) : [...queue]),
      update: async (d: { id: number; status: string; error?: string; lastAttemptAt?: string | null; countAttempt?: boolean }) => {
        const q = queue.find((x) => x.id === d.id)
        if (q) {
          q.status = d.status
          if (d.error !== undefined) q.last_error = d.error
          if (d.lastAttemptAt !== undefined) q.last_attempt_at = d.lastAttemptAt
          if (d.countAttempt) q.attempts += 1
        }
        return { ok: true }
      },
      done: async (d: { id: number }) => { queue = queue.filter((x) => x.id !== d.id); return { ok: true } },
      count: async () => queue.filter((q) => q.status === 'pending').length,
      counts: async () => ({
        pending: queue.filter((q) => q.status === 'pending' || q.status === 'processing').length,
        attention: queue.filter((q) => q.status === 'attention' || q.status === 'failed').length,
      }),
    },
    dialog: { openFile: async () => null },
    file: { readBuffer: async () => new Uint8Array(0) },
    cache: {
      get: async () => ({ ...cache }),
      set: async (d: AnyRec) => { cache = { ...d }; return { ok: true } },
    },
    app: { version: async () => '2.19.0-demo' },
    updater: {
      getState: async () => ({ ...updaterState }),
      check: async () => ({ ...updaterState }),
      download: async () => ({ ...updaterState }),
      install: async () => ({ ok: true }),
      openReleases: async () => ({ ok: true }),
      onStateChange: (cb: (s: unknown) => void) => { listeners.add(cb); return () => { listeners.delete(cb) } },
    },
    shell: { openExternal: async (url: string) => { console.info('[demo] openExternal', url) } },
    customerDisplay: {
      available: async () => false,
      open: async () => ({ ok: false, reason: 'demo' }),
      close: async () => ({ ok: true }),
      push: () => { /* no-op */ },
      onState: () => () => {},
      onRequest: () => () => {},
    },
  }
}
