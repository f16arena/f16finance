// Собирает crontab для своего сервера из vercel.json — один источник расписания.
// Vercel считает кроны в UTC, поэтому и здесь CRON_TZ=UTC.
// Запуск: node deploy/vps/gen-crontab.mjs > /etc/cron.d/orda
import { readFileSync } from 'node:fs'

const vercel = JSON.parse(readFileSync(new URL('../../vercel.json', import.meta.url), 'utf8'))
const base = 'http://127.0.0.1:3200'
// Секрет не пишем в файл: cron-run.sh читает CRON_SECRET из /opt/orda/web.env.
const runner = '/opt/orda/app/deploy/vps/cron-run.sh'

const lines = [
  '# Сгенерировано deploy/vps/gen-crontab.mjs из vercel.json — руками не править.',
  'CRON_TZ=UTC',
  'SHELL=/bin/sh',
  'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
]
for (const cron of vercel.crons || []) {
  lines.push(`${cron.schedule} root ${runner} ${base}${cron.path}`)
}
process.stdout.write(lines.join('\n') + '\n')
