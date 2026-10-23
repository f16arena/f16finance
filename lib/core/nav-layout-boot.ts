// Ключи вида меню и скрипт для <head>: применяет сохранённый выбор (сверху/слева,
// свёрнуто) до отрисовки, чтобы страница не моргала. Отдельно от
// lib/client/nav-layout.ts: серверный app/layout.tsx не может брать значения из
// модуля с 'use client'.

export const NAV_LAYOUT_KEY = 'orda.navLayout'
export const NAV_COLLAPSED_KEY = 'orda.navCollapsed'

export const NAV_LAYOUT_BOOT_SCRIPT = `try{var d=document.documentElement;if(localStorage.getItem('${NAV_LAYOUT_KEY}')==='side'){d.dataset.nav='side'}if(localStorage.getItem('${NAV_COLLAPSED_KEY}')==='1'){d.dataset.navCollapsed='1'}}catch(e){}`
