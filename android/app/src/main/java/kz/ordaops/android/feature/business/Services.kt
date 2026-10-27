package kz.ordaops.android.feature.business

import kz.ordaops.android.core.access.PageAccess
import kz.ordaops.android.core.access.playbook

/** Раздел в плитке сервисов. */
data class ServiceItem(
    val pageId: String,
    val title: String,
    val groupId: String,
    /** Маршрут сайта (`/income`) — по нему открывается раздел без нативного экрана. */
    val path: String,
) {
    /** Нативного экрана нет — раздел откроется сайтом. */
    val isWeb: Boolean get() = !NativePage.isNative(pageId)
}

data class ServiceGroup(val id: String, val label: String, val items: List<ServiceItem>)

/**
 * Какие сервисы показать владельцу и сотруднику: всё, что открыто по правам,
 * модулям и ручным выключениям. Порт `OwnerServices` и `nativeGroups()` с одним
 * отличием: на айфоне пункт без нативного экрана скрыт, здесь он открывается
 * сайтом (`NativePage`) — доступ обещан, и он есть.
 */
object Services {
    /** Что владелец открывает чаще всего — на главную, в этом порядке. */
    val favoriteIds = listOf(
        "income", "expenses", "cashflow", "profitability",
        "store-warehouse", "salary", "shifts", "tasks",
        "reports", "operators", "point-debts", "store-catalog",
    )

    /** Служебные страницы каталога: вкладка «Главная» уже есть, остальные — не для телефона. */
    private val hiddenPages = setOf("dashboard", "welcome", "workspace", "operator-lead")

    /**
     * Право в каталоге есть, но роут проверяет суперадмина раньше права: владелец
     * с этим правом получил бы отказ. Пункт, который гарантированно не откроется,
     * не показываем (`NativeSection.requiresSuperAdmin`).
     */
    private val superAdminOnly = setOf("logs")

    /** «Должности и доступ»: все маршруты страницы требуют владельца (или суперадмина), а не просто право. */
    private val ownerOnly = setOf("access")

    fun groups(access: PageAccess): List<ServiceGroup> {
        val isSuper = access.resolver.session.isSuperAdmin
        val isOwner = isSuper || access.resolver.session.staffRole == "owner"
        return access.visibleGroups().mapNotNull { (group, pages) ->
            val items = pages
                .filter { it.id !in hiddenPages && (isSuper || it.id !in superAdminOnly) && (isOwner || it.id !in ownerOnly) }
                .map { ServiceItem(it.id, shortTitle(it.label), group.id, it.path) }
            if (items.isEmpty()) null else ServiceGroup(group.id, group.label, items)
        }
    }

    /** Любимые сервисы в порядке должности, добранные остальными открытыми. */
    fun favorites(access: PageAccess, count: Int = 8): List<ServiceItem> {
        val every = groups(access).flatMap { it.items }
        val byId = every.associateBy { it.pageId }
        val ids = if (access.resolver.workspaceIsStaff()) access.resolver.playbook.favoritePageIds else favoriteIds
        val result = ids.mapNotNull { byId[it] }.toMutableList()
        if (result.size < count) {
            result += every.filter { it !in result }.take(count - result.size)
        }
        return result.take(count)
    }

    /** Поиск по названию, группы без совпадений пропадают. */
    fun filter(groups: List<ServiceGroup>, query: String): List<ServiceGroup> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val found = group.items.filter { it.title.lowercase().contains(needle) }
            if (found.isEmpty()) null else group.copy(items = found)
        }
    }

    private val replacements = mapOf(
        "Рентабельность (ОПиУ)" to "ОПиУ",
        "Денежные потоки" to "Движение денег",
        "Ожидающие расходы" to "На согласовании",
        "Доверенные поставщики" to "Поставщики",
        "Еженедельный отчёт" to "Отчёт недели",
        "Эффективность продавцов" to "Продавцы",
        "Аналитика склада + ABC" to "Склад ABC",
        "Достижения операторов" to "Достижения",
        "Реклама на витрине" to "Реклама",
        "Командный чат" to "Чат команды",
        "Личные сообщения" to "Сообщения",
    )

    /** Названия из каталога прав бывают длинными — в плитку влезает два слова. */
    fun shortTitle(label: String): String {
        replacements[label]?.let { return it }
        // «Смены (расписание)» — уточнение в скобках нужно меню сайта, в плитке оно ломает подпись.
        val bracket = label.indexOf('(')
        if (bracket > 0) return label.substring(0, bracket).trim()
        return label
    }

    /** Подпись под круглой кнопкой на главной сотрудника — одним словом. */
    fun quickTitle(pageId: String, label: String?): String = when (pageId) {
        "expenses-pending" -> "Решения"
        "shifts" -> "Смены"
        "shifts-reports" -> "Отчёты"
        "store-requests" -> "Заявки"
        "store-revisions" -> "Ревизия"
        "store-advertising" -> "Реклама"
        "customers" -> "Клиенты"
        "news" -> "Новости"
        "incidents" -> "Инциденты"
        "salary" -> "Зарплата"
        "pos-receipts" -> "Чеки"
        "pos-returns" -> "Возвраты"
        "point-debts" -> "Долги"
        "knowledge-admin" -> "База"
        else -> shortTitle(label ?: pageId)
    }
}

private fun kz.ordaops.android.core.access.AccessResolver.workspaceIsStaff(): Boolean =
    workspace == kz.ordaops.android.core.access.Workspace.Staff
