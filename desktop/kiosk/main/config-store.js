const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { app } = require('electron')

function configPath() {
  return path.join(app.getPath('userData'), 'config.json')
}

// Настройки читаются раз и держатся в памяти: раньше файл читался и разбирался
// каждую секунду (тик состояния). Меняются они только через saveConfig/clearConfig.
let cached // undefined — ещё не читали; null — файла нет

function loadConfig() {
  if (cached !== undefined) return cached ? { ...cached } : null
  try {
    const raw = fs.readFileSync(configPath(), 'utf8')
    cached = JSON.parse(raw)
  } catch (_) {
    cached = null
  }
  return cached ? { ...cached } : null
}

function saveConfig(cfg) {
  const dir = app.getPath('userData')
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(configPath(), JSON.stringify(cfg, null, 2), 'utf8')
  cached = { ...cfg }
}

function ensureDeviceToken() {
  const cfg = loadConfig() || {}
  if (cfg.deviceToken && String(cfg.deviceToken).trim()) return String(cfg.deviceToken)
  const token = crypto.randomUUID()
  saveConfig({ ...cfg, deviceToken: token })
  return token
}

function clearConfig() {
  cached = null
  try {
    fs.unlinkSync(configPath())
  } catch (_) {
    // ignore if file doesn't exist
  }
}

module.exports = { loadConfig, saveConfig, configPath, ensureDeviceToken, clearConfig }
