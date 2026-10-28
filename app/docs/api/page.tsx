import type { Metadata } from 'next'
import Link from 'next/link'
import type { ReactNode } from 'react'

import { SiteFooter, SiteHeader } from '@/components/public/site-chrome'
import { PRODUCT_SITE } from '@/lib/core/legal'

export const metadata: Metadata = {
  title: 'API — Orda Control',
  description: 'Открытый API Orda Control: ключи, график смен, публикация недели в Telegram. Методы, примеры на Python и JavaScript.',
}

const BASE = `https://${PRODUCT_SITE}/api/v1`

const sections = [
  { id: 'start', title: 'Начало' },
  { id: 'auth', title: 'Ключ и права' },
  { id: 'rules', title: 'Общие правила' },
  { id: 'me', title: 'GET /me' },
  { id: 'operators', title: 'GET /schedule/operators' },
  { id: 'week', title: 'GET /schedule' },
  { id: 'set', title: 'PUT /schedule/shifts' },
  { id: 'delete', title: 'DELETE /schedule/shifts/{id}' },
  { id: 'publish', title: 'POST /schedule/publish' },
  { id: 'errors', title: 'Ошибки' },
  { id: 'example', title: 'Пример: автоплан недели' },
]

function Code({ children, lang }: { children: string; lang?: string }) {
  return (
    <div className="mt-3 overflow-hidden rounded-xl border border-[#0b1f3b]/10 bg-[#0b1f3b]">
      {lang ? (
        <div className="border-b border-white/10 px-4 py-1.5 text-[11px] font-medium uppercase tracking-wider text-white/50">{lang}</div>
      ) : null}
      <pre className="overflow-x-auto px-4 py-3 text-[12.5px] leading-6 text-[#e5edff]">
        <code>{children.trim()}</code>
      </pre>
    </div>
  )
}

const METHOD_TONE: Record<string, string> = {
  GET: 'bg-[#2563eb]/10 text-[#1d4ed8]',
  PUT: 'bg-[#0b1f3b]/10 text-[#0b1f3b]',
  POST: 'bg-[#0b1f3b] text-white',
  DELETE: 'bg-red-500/10 text-red-700',
}

function Endpoint(props: { id: string; method: string; path: string; scope: string; title: string; children: ReactNode }) {
  return (
    <section id={props.id} className="scroll-mt-24 rounded-2xl border border-[#e5e7eb] bg-white p-6 shadow-[0_1px_2px_rgba(11,31,59,0.04)]">
      <div className="flex flex-wrap items-center gap-2.5">
        <span className={`rounded-md px-2 py-0.5 font-mono text-xs font-bold ${METHOD_TONE[props.method]}`}>{props.method}</span>
        <code className="font-mono text-[15px] font-semibold text-[#0b1f3b]">{props.path}</code>
        <span className="ml-auto rounded-full border border-[#e5e7eb] px-2.5 py-0.5 font-mono text-[11px] text-[#6b7280]">{props.scope}</span>
      </div>
      <h3 className="mt-3 text-lg font-semibold text-[#0b1f3b]">{props.title}</h3>
      <div className="mt-2 space-y-3 text-sm leading-7">{props.children}</div>
    </section>
  )
}

function Field({ name, type, children }: { name: string; type: string; children: ReactNode }) {
  return (
    <tr className="border-t border-[#f3f4f6] align-top">
      <td className="py-2 pr-3 font-mono text-[13px] text-[#0b1f3b]">{name}</td>
      <td className="py-2 pr-3 font-mono text-[12px] text-[#6b7280]">{type}</td>
      <td className="py-2 text-[13px]">{children}</td>
    </tr>
  )
}

function Fields({ children }: { children: ReactNode }) {
  return (
    <table className="w-full text-left">
      <thead>
        <tr className="text-[11px] uppercase tracking-wider text-[#9ca3af]">
          <th className="pb-1 pr-3 font-semibold">Поле</th>
          <th className="pb-1 pr-3 font-semibold">Тип</th>
          <th className="pb-1 font-semibold">Что это</th>
        </tr>
      </thead>
      <tbody>{children}</tbody>
    </table>
  )
}

const ic = 'rounded bg-[#f3f4f6] px-1.5 py-0.5 font-mono text-[12.5px] text-[#0b1f3b]'

export default function ApiDocsPage() {
  return (
    <main className="min-h-screen bg-[#fafaf8] text-[#374151]">
      <SiteHeader />

      <div className="mx-auto max-w-screen-2xl px-6 py-12 sm:px-8 lg:px-10">
        <div className="grid gap-10 lg:grid-cols-[260px_1fr] lg:items-start">
          <aside className="lg:sticky lg:top-24">
            <div className="rounded-2xl border border-[#e5e7eb] bg-white p-5 shadow-[0_1px_2px_rgba(11,31,59,0.04),0_10px_30px_-18px_rgba(11,31,59,0.18)]">
              <div className="text-xs font-semibold uppercase tracking-wider text-[#2563eb]">Содержание</div>
              <ul className="mt-3 space-y-1 text-sm">
                {sections.map((s) => (
                  <li key={s.id}>
                    <a href={`#${s.id}`} className="block rounded-lg px-2 py-1 font-mono text-[12.5px] text-[#4b5563] transition hover:bg-[#2563eb]/[0.07] hover:text-[#2563eb]">
                      {s.title}
                    </a>
                  </li>
                ))}
              </ul>
              <div className="mt-4 text-xs text-[#6b7280]">
                <Link href="/help" className="hover:text-[#2563eb]">→ Инструкция пользователя</Link>
              </div>
            </div>
          </aside>

          <article className="max-w-3xl space-y-8">
            <header id="start" className="scroll-mt-24">
              <div className="text-xs font-semibold uppercase tracking-wider text-[#2563eb]">API · v1</div>
              <h1 className="mt-2 text-3xl font-semibold leading-tight tracking-[-0.02em] text-[#0b1f3b] sm:text-4xl">Открытый API</h1>
              <p className="mt-4 text-sm leading-7">
                Через API ваша программа работает с Orda Control напрямую: читает и заполняет график смен точки и публикует
                неделю — сотрудники получают свои смены в Telegram. Деньги, выручка, зарплата и склад через API недоступны.
              </p>
              <div className="mt-5 rounded-xl border border-[#e5e7eb] bg-white px-4 py-3 text-sm">
                Адрес всех методов: <code className={ic}>{BASE}</code>
              </div>
              <p className="mt-4 text-sm leading-7">Самый короткий путь — проверить ключ одной командой:</p>
              <Code lang="bash">{`
curl ${BASE}/me \\
  -H "Authorization: Bearer orda_ВАШ_КЛЮЧ"
`}</Code>
            </header>

            <section id="auth" className="scroll-mt-24">
              <h2 className="text-2xl font-semibold tracking-tight text-[#0b1f3b]">Ключ и права</h2>
              <div className="mt-3 space-y-3 text-sm leading-7">
                <p>
                  Ключ выпускает владелец организации: <b>Настройки → API-ключи</b>. Ключ привязан к одной точке — все методы
                  работают только с ней. Сам ключ показывается один раз; если потерялся — владелец отзывает его и выпускает новый.
                </p>
                <p>Ключ передаётся в каждом запросе заголовком:</p>
                <Code>{`Authorization: Bearer orda_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`}</Code>
                <p>У ключа есть права. Метод без нужного права отвечает <code className={ic}>403</code>.</p>
                <Fields>
                  <Field name="schedule:read" type="право">Смотреть график и список операторов точки</Field>
                  <Field name="schedule:write" type="право">Ставить и убирать смены</Field>
                  <Field name="schedule:publish" type="право">Публиковать неделю — сотрудникам уходит Telegram</Field>
                </Fields>
                <p className="rounded-xl border border-amber-500/30 bg-amber-500/5 px-4 py-3">
                  Ключ — как пароль. Не выкладывайте его в GitHub и не вставляйте в код страницы сайта: храните в переменной
                  окружения (<code className={ic}>ORDA_API_KEY</code>) или файле <code className={ic}>.env</code>.
                </p>
              </div>
            </section>

            <section id="rules" className="scroll-mt-24">
              <h2 className="text-2xl font-semibold tracking-tight text-[#0b1f3b]">Общие правила</h2>
              <ul className="mt-3 list-disc space-y-1.5 pl-6 text-sm leading-7">
                <li>Запросы и ответы — JSON, кодировка UTF-8. Для PUT и POST нужен заголовок <code className={ic}>Content-Type: application/json</code>.</li>
                <li>Даты — строкой <code className={ic}>ГГГГ-ММ-ДД</code>, по времени Казахстана. Неделя — с понедельника по воскресенье.</li>
                <li>Смена — <code className={ic}>&quot;day&quot;</code> (дневная) или <code className={ic}>&quot;night&quot;</code> (ночная). На точке в каждый день одна дневная и одна ночная смена.</li>
                <li>Не больше <b>60 запросов в минуту</b> на ключ. Больше — ответ <code className={ic}>429</code>, подождите минуту.</li>
                <li>Каждое изменение пишется в журнал действий организации с именем ключа — владелец видит, что сделала программа.</li>
              </ul>
            </section>

            <h2 className="pt-2 text-2xl font-semibold tracking-tight text-[#0b1f3b]">Методы</h2>

            <Endpoint id="me" method="GET" path="/me" scope="schedule:read" title="Проверить ключ">
              <p>Какая точка у ключа и какие права. Удобно вызвать первым.</p>
              <Code lang="Ответ 200">{`
{
  "key": { "name": "Планировщик смен", "scopes": ["schedule:read", "schedule:write", "schedule:publish"] },
  "point": { "id": "6f1c…", "name": "F16 Arena" }
}
`}</Code>
            </Endpoint>

            <Endpoint id="operators" method="GET" path="/schedule/operators" scope="schedule:read" title="Кто работает на точке">
              <p>Активные операторы, закреплённые за точкой. Ставки и зарплаты сюда не входят.</p>
              <Code lang="Ответ 200">{`
{
  "operators": [
    { "id": "a1b2…", "name": "Айдана", "role": "operator", "telegramConnected": true },
    { "id": "c3d4…", "name": "Ерлан", "role": "senior_operator", "telegramConnected": false }
  ]
}
`}</Code>
              <p>
                <code className={ic}>telegramConnected: false</code> — человек не подключил бота: смены ему поставить можно, но
                сообщение о публикации он не получит.
              </p>
            </Endpoint>

            <Endpoint id="week" method="GET" path="/schedule?week=2026-11-02" scope="schedule:read" title="График недели">
              <p>
                <code className={ic}>week</code> — любая дата нужной недели (сервер сам найдёт понедельник). Без параметра —
                текущая неделя.
              </p>
              <Code lang="Ответ 200">{`
{
  "weekStart": "2026-11-02",
  "weekEnd": "2026-11-08",
  "published": { "version": 2, "publishedAt": "2026-10-30T14:05:11Z" },
  "shifts": [
    { "id": "9e8f…", "date": "2026-11-02", "shift": "day",
      "operatorId": "a1b2…", "operatorName": "Айдана", "comment": null }
  ]
}
`}</Code>
              <p><code className={ic}>published: null</code> — неделю ещё не публиковали.</p>
            </Endpoint>

            <Endpoint id="set" method="PUT" path="/schedule/shifts" scope="schedule:write" title="Поставить на смену">
              <p>Ставит оператора на дату и смену. Если там уже кто-то стоит — заменяет.</p>
              <Fields>
                <Field name="date" type="string">Дата смены, <code className={ic}>2026-11-02</code></Field>
                <Field name="shift" type="string"><code className={ic}>day</code> или <code className={ic}>night</code></Field>
                <Field name="operatorId" type="string">id из <code className={ic}>/schedule/operators</code></Field>
                <Field name="comment" type="string?">Необязательно, до 500 символов</Field>
              </Fields>
              <Code lang="Запрос">{`
PUT ${BASE}/schedule/shifts
{ "date": "2026-11-02", "shift": "night", "operatorId": "a1b2…" }
`}</Code>
              <Code lang="Ответ 201 (новая) или 200 (замена)">{`
{ "mode": "created",
  "shift": { "id": "9e8f…", "date": "2026-11-02", "shift": "night",
             "operatorId": "a1b2…", "operatorName": "Айдана", "comment": null } }
`}</Code>
              <p>
                Один человек — одна смена в день по всей организации. Если он уже стоит в этот день (на любой точке), ответ{' '}
                <code className={ic}>409 operator_busy</code>.
              </p>
              <p>Сотрудникам при этом ничего не отправляется — сообщение уходит только при публикации недели.</p>
            </Endpoint>

            <Endpoint id="delete" method="DELETE" path="/schedule/shifts/{id}" scope="schedule:write" title="Убрать смену">
              <p>Удаляет смену из графика по её id.</p>
              <Code lang="Ответ 200">{`
{ "deleted": { "id": "9e8f…", "date": "2026-11-02", "shift": "night", "operatorName": "Айдана" } }
`}</Code>
            </Endpoint>

            <Endpoint id="publish" method="POST" path="/schedule/publish" scope="schedule:publish" title="Опубликовать неделю">
              <p>
                Каждому, кто стоит в графике недели, в Telegram приходят его смены и кнопки «Подтверждаю неделю» / «Есть
                проблема». Это то же самое, что кнопка «Опубликовать» на сайте. Повторная публикация — новая версия, людям
                придёт обновлённый график.
              </p>
              <Code lang="Запрос">{`
POST ${BASE}/schedule/publish
{ "week": "2026-11-02" }
`}</Code>
              <Code lang="Ответ 200">{`
{
  "weekStart": "2026-11-02", "weekEnd": "2026-11-08", "version": 1,
  "delivered": 7, "missingTelegram": 1, "failed": 0,
  "details": [
    { "name": "Айдана", "status": "sent", "reason": null },
    { "name": "Ерлан", "status": "missing_telegram", "reason": "У оператора не заполнен telegram_chat_id." }
  ]
}
`}</Code>
            </Endpoint>

            <section id="errors" className="scroll-mt-24">
              <h2 className="text-2xl font-semibold tracking-tight text-[#0b1f3b]">Ошибки</h2>
              <p className="mt-3 text-sm leading-7">Любая ошибка приходит в одном виде:</p>
              <Code>{`{ "error": { "code": "operator_busy", "message": "Айдана уже стоит на 2026-11-02. Сначала уберите ту смену." } }`}</Code>
              <div className="mt-4 text-sm">
                <Fields>
                  <Field name="400 bad_request" type="код">Неверные данные: дата не в том формате, нет обязательного поля</Field>
                  <Field name="401 unauthorized" type="код">Нет ключа, ключ неверный или отозван</Field>
                  <Field name="403 forbidden" type="код">У ключа нет нужного права, или точка закрыта</Field>
                  <Field name="404 not_found" type="код">Смена не найдена (или она с другой точки)</Field>
                  <Field name="409 operator_busy" type="код">Оператор уже стоит в этот день</Field>
                  <Field name="422 unknown_operator" type="код">Такого оператора нет на точке</Field>
                  <Field name="422 nothing_to_publish" type="код">На неделе нет смен — публиковать нечего</Field>
                  <Field name="429 rate_limited" type="код">Больше 60 запросов в минуту</Field>
                </Fields>
              </div>
            </section>

            <section id="example" className="scroll-mt-24">
              <h2 className="text-2xl font-semibold tracking-tight text-[#0b1f3b]">Пример: автоплан недели</h2>
              <p className="mt-3 text-sm leading-7">
                Простейший планировщик на Python: раздаёт дневные и ночные смены по кругу и публикует неделю. Нужна библиотека{' '}
                <code className={ic}>requests</code> (<code className={ic}>pip install requests</code>).
              </p>
              <Code lang="python">{`
import os, datetime, requests

BASE = "${BASE}"
HEADERS = {"Authorization": f"Bearer {os.environ['ORDA_API_KEY']}"}

def api(method, path, **kw):
    r = requests.request(method, BASE + path, headers=HEADERS, timeout=20, **kw)
    data = r.json()
    if not r.ok:
        raise RuntimeError(f"{r.status_code} {data['error']['code']}: {data['error']['message']}")
    return data

operators = api("GET", "/schedule/operators")["operators"]
print("На точке:", [o["name"] for o in operators])

# следующий понедельник
today = datetime.date.today()
monday = today + datetime.timedelta(days=7 - today.weekday())

i = 0
for d in range(7):
    day = (monday + datetime.timedelta(days=d)).isoformat()
    for shift in ("day", "night"):
        op = operators[i % len(operators)]
        i += 1
        api("PUT", "/schedule/shifts", json={"date": day, "shift": shift, "operatorId": op["id"]})
        print(day, shift, "→", op["name"])

result = api("POST", "/schedule/publish", json={"week": monday.isoformat()})
print("Отправлено в Telegram:", result["delivered"], "· без Telegram:", result["missingTelegram"])
`}</Code>
              <p className="mt-4 text-sm leading-7">То же самое на JavaScript (Node 18+ или браузер):</p>
              <Code lang="javascript">{`
const BASE = "${BASE}"
const KEY = process.env.ORDA_API_KEY

async function api(method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { Authorization: \`Bearer \${KEY}\`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  })
  const data = await r.json()
  if (!r.ok) throw new Error(\`\${r.status} \${data.error.code}: \${data.error.message}\`)
  return data
}

const { operators } = await api("GET", "/schedule/operators")
const week = await api("GET", "/schedule")
console.log(operators.length, "операторов,", week.shifts.length, "смен на этой неделе")
`}</Code>
              <p className="mt-4 text-sm leading-7">
                Совет: перед публикацией проверьте график на сайте в разделе «Смены» — публикация сразу уходит людям.
              </p>
            </section>
          </article>
        </div>
      </div>

      <SiteFooter />
    </main>
  )
}
