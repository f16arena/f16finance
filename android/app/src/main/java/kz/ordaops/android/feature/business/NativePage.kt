package kz.ordaops.android.feature.business

import android.content.Context
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import kz.ordaops.android.BuildConfig

/**
 * Реестр нативных экранов кабинета: `pageID` из каталога прав → экран.
 *
 * Как `NativePage.swift`, но с запасным путём: раздел без нативного экрана
 * открывается сайтом во вкладке Chrome (`openWeb`), а не заглушкой. Следующие
 * волны переноса добавляют экраны сюда — строкой в [screens]; плитки сервисов,
 * главная, уведомления и ссылки подхватывают их сами.
 */
object NativePage {
    /** Нативные экраны. Волна 1 — только оболочка, экранов разделов ещё нет. */
    private val screens: Map<String, @Composable () -> Unit> = emptyMap()

    fun isNative(pageId: String): Boolean = pageId in screens

    /** Экран раздела; звать только для [isNative]. */
    @Composable
    fun Screen(pageId: String) {
        screens[pageId]?.invoke()
    }

    /** Адрес страницы сайта: `https://www.ordaops.kz` + `/income`. */
    fun webUrl(path: String, baseUrl: String = BuildConfig.API_BASE_URL): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * Открыть сайт во вкладке Chrome. Вход на сайте отдельный — приложение
     * передать туда сессию не может, поэтому при первом открытии сайт просит пароль.
     */
    fun openWeb(context: Context, path: String) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        runCatching { intent.launchUrl(context, webUrl(path).toUri()) }
    }
}

/** Куда вести человека по идентификатору раздела (уведомление, ссылка, быстрое действие). */
sealed interface BusinessRoute {
    data object Home : BusinessRoute
    data object Analytics : BusinessRoute
    data object Platform : BusinessRoute
    data object Talk : BusinessRoute
    data class Page(val pageId: String) : BusinessRoute

    companion object {
        /**
         * Разбор идентификатора как в `OwnerTabs.route`: у главной, аналитики,
         * платформы и общения своя вкладка, остальное — раздел поверх главной.
         *
         * @param talk вкладка общения, если она есть: `"team-chat"` или `"tasks"`
         */
        fun resolve(pageId: String, hasPlatform: Boolean, talk: String?): BusinessRoute = when {
            pageId == "home.dashboard" || pageId == "business.dashboard" -> Home
            pageId == "owner.analytics" || pageId == "business.analytics" -> Analytics
            hasPlatform && (pageId == "platform.overview" || pageId == "platform.organizations") -> Platform
            talk != null && pageId == talk -> Talk
            else -> Page(pageId)
        }
    }
}
