package kz.ordaops.android.core.access

/**
 * Какие страницы каталога открыты человеку: права + модули организации + ручные
 * выключения. Зеркало `canSee`/`visibleGroups` из AccessResolver.swift.
 *
 * Скрытая кнопка — не защита: настоящую проверку делает сервер, а приложение
 * обязано пережить 403.
 */
class PageAccess(val resolver: AccessResolver, val catalog: CapabilityCatalog) {

    /** Модуль маршрута оплачен (пустой список модулей — организация без пакета, всё открыто). */
    fun hasFeature(path: String): Boolean = resolver.hasFeature(catalog.addonCode(path))

    fun canSee(page: CatalogPage): Boolean {
        if (resolver.pathOverride(page.path) == false) return false
        if (!hasFeature(page.path)) return false
        if (resolver.isAllAccess) return true
        return page.viewCapabilityId in resolver.session.capabilities
    }

    fun canSee(pageId: String): Boolean = catalog.page(pageId)?.let(::canSee) ?: false

    /** Группы с хотя бы одной доступной страницей. Пустые не показываем. */
    fun visibleGroups(): List<Pair<CatalogGroup, List<CatalogPage>>> =
        catalog.groups.mapNotNull { group ->
            val pages = group.pages.filter(::canSee)
            if (pages.isEmpty()) null else group to pages
        }

    /**
     * Кабинет владельца: кому открыты отчёты. Тот же порог, что у
     * `/api/admin/owner-analytics`: там не только выручка, но и расходы с прибылью.
     */
    val canSeeOwnerAnalytics: Boolean get() = resolver.can("reports.view")
}
