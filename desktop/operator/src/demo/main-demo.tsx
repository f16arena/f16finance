/**
 * DEMO-вход: `npm run demo` -> http://localhost:5180/demo.html
 *
 * Приложение стартует сразу залогиненным оператором «Айдар Demo» на точке «Demo Club».
 * Вход по логину тоже работает: ЛЮБОЙ логин/пароль принимается моком.
 * Открыть экран логина: http://localhost:5180/demo.html?login
 * Экран покупателя: ?role=customer (он ждёт данные от кассы через IPC, в демо пустой).
 *
 * Порядок важен: заглушки ставятся ДО динамического импорта App, потому что
 * lib/config.ts, cache.ts и offline.ts читают window.electron при загрузке модуля.
 * Сетевых вызовов к реальному серверу нет — см. mock-api.ts.
 */
import React from 'react'
import ReactDOM from 'react-dom/client'
import { installElectronStub } from './electron-stub'
import { installMockApi } from './mock-api'
import '../index.css'

const params = new URLSearchParams(window.location.search)
installElectronStub({ loggedIn: !params.has('login') })
installMockApi()

async function start() {
  const [{ default: App }, { default: CustomerDisplay }, { default: Toaster }, { applyAllPreferences }] =
    await Promise.all([
      import('../App'),
      import('../pages/CustomerDisplay'),
      import('../components/Toaster'),
      import('../lib/preferences'),
    ])

  applyAllPreferences()

  window.addEventListener('unhandledrejection', (event) => {
    if (event.reason?.message?.includes('Tabs cannot be edited')) event.preventDefault()
  })

  const role = params.get('role')

  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      {role === 'customer' ? (
        <CustomerDisplay />
      ) : (
        <>
          <App />
          <Toaster />
        </>
      )}
    </React.StrictMode>,
  )
}

void start()
