let supabaseClient = null
let clientKey = ''
let currentChannel = null
// Номер подписки: статусы старого канала (CLOSED при его удалении) приходят
// после новой подписки и раньше сбрасывали её — киоск переподключался каждые
// 10 с и плодил клиентов с открытыми соединениями.
let generation = 0

/**
 * Returns true if subscription reached SUBSCRIBED, false otherwise.
 * Caller should set realtimeReady = false and retry on next heartbeat if false.
 */
async function setupRealtime({ supabaseUrl, supabaseAnonKey, stationId, onCommand, onStatusChange, logLine }) {
  if (!supabaseUrl || !supabaseAnonKey || !stationId) return false

  try {
    // Lazy-require to avoid issues if package not installed
    const { createClient } = require('@supabase/supabase-js')

    // Один клиент на всё время работы: новый на каждый повтор держал старое
    // соединение и таймер обновления сессии — память и процессор росли.
    const key = `${supabaseUrl}|${supabaseAnonKey}`
    if (!supabaseClient || clientKey !== key) {
      if (supabaseClient) {
        try { await supabaseClient.removeAllChannels() } catch (_) {}
      }
      supabaseClient = createClient(supabaseUrl, supabaseAnonKey, {
        realtime: { params: { eventsPerSecond: 10 } },
        auth: { autoRefreshToken: false, persistSession: false, detectSessionInUrl: false },
      })
      clientKey = key
    }
    const myGeneration = ++generation
    if (currentChannel) {
      const stale = currentChannel
      currentChannel = null
      try { await supabaseClient.removeChannel(stale) } catch (_) {}
    }

    return await new Promise((resolve) => {
      let settled = false
      // Timeout — if no SUBSCRIBED within 10s, treat as failure
      const timeout = setTimeout(() => {
        if (!settled) {
          settled = true
          if (logLine) logLine(`Realtime subscribe timeout for kiosk:${stationId}`)
          resolve(false)
        }
      }, 10000)

      currentChannel = supabaseClient
        .channel(`kiosk:${stationId}`)
        .on('broadcast', { event: 'command' }, ({ payload }) => {
          if (payload && onCommand) {
            if (logLine) logLine(`Realtime command: ${payload.type}`)
            onCommand(payload)
          }
        })
        .subscribe((status) => {
          if (myGeneration !== generation) return // статус старой подписки — не наш
          if (logLine) logLine(`Realtime status: ${status}`)
          if (onStatusChange) onStatusChange(status)
          if (status === 'SUBSCRIBED') {
            if (!settled) { settled = true; clearTimeout(timeout); resolve(true) }
          } else if (status === 'CHANNEL_ERROR' || status === 'TIMED_OUT' || status === 'CLOSED') {
            if (!settled) { settled = true; clearTimeout(timeout); resolve(false) }
          }
        })
    })
  } catch (error) {
    if (logLine) logLine(`Realtime setup failed: ${error.message}`)
    return false
  }
}

async function closeRealtime() {
  try {
    if (supabaseClient) await supabaseClient.removeAllChannels()
  } catch (_) {}
  supabaseClient = null
  clientKey = ''
  currentChannel = null
  generation += 1
}

module.exports = { setupRealtime, closeRealtime }
