package kz.ordaops.android.feature.business.platform

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// Журнал действий (веб `/logs`, только суперадмин). Человеческие заголовки и строки
// деталей строит сервер из настоящего payload (`/api/admin/logs` его потом вырезает),
// поэтому здесь они берутся как есть; свои подписи — только для чипов и фильтров.

/** Одна запись журнала: аудит, уведомление или ИИ-запрос. */
data class LogItem(
    val id: String,
    val kind: String,
    val createdAt: String,
    val title: String,
    val subtitle: String?,
    val details: String?,
    val detailRows: List<String>,
    val entityType: String?,
    val action: String?,
    val actorEmail: String?,
    val channel: String?,
    val status: String?,
    val recipient: String?,
) {
    /** Ошибка — так же, как у сайта: сбой доставки, системная ошибка. */
    val isError: Boolean get() = status == "failed" || entityType == "system-error"

    /** Ошибка в широком смысле для счётчика сводки (`stats.errors` на сайте). */
    val countsAsError: Boolean
        get() {
            val a = action.orEmpty().lowercase()
            return status == "failed" || status == "error" || entityType.orEmpty().lowercase() == "system-error" ||
                a.contains("error") || a.contains("failed")
        }

    /** Запись целиком в JSON — «скопировать разработчику». */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id); put("kind", kind); put("createdAt", createdAt); put("title", title)
        put("subtitle", subtitle?.let(::JsonPrimitive) ?: JsonNull)
        put("details", details?.let(::JsonPrimitive) ?: JsonNull)
        put("detailRows", buildJsonArray { detailRows.forEach { add(JsonPrimitive(it)) } })
        put("entityType", entityType?.let(::JsonPrimitive) ?: JsonNull)
        put("action", action?.let(::JsonPrimitive) ?: JsonNull)
        put("actorEmail", actorEmail?.let(::JsonPrimitive) ?: JsonNull)
        put("channel", channel?.let(::JsonPrimitive) ?: JsonNull)
        put("status", status?.let(::JsonPrimitive) ?: JsonNull)
        put("recipient", recipient?.let(::JsonPrimitive) ?: JsonNull)
    }
}

data class LogPage(
    val total: Int,
    val page: Int,
    val limit: Int,
    val items: List<LogItem>,
    val actors: List<String>,
    val entityTypes: List<String>,
    val actions: List<String>,
) {
    val hasNext: Boolean get() = page * limit < total
    val hasPrev: Boolean get() = page > 1
}

private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
private fun JsonObject.n(key: String, default: Int): Int =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull?.toInt() } ?: default
private fun JsonObject.strs(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }.orEmpty()

fun parseLogItem(o: JsonObject): LogItem? {
    val id = o.s("id") ?: return null
    return LogItem(
        id = id,
        kind = o.s("kind") ?: "audit",
        createdAt = o.s("createdAt") ?: "",
        title = o.s("title").orEmpty(),
        subtitle = o.s("subtitle"),
        details = o.s("details"),
        detailRows = o.strs("detailRows"),
        entityType = o.s("entityType"),
        action = o.s("action"),
        actorEmail = o.s("actorEmail"),
        channel = o.s("channel"),
        status = o.s("status"),
        recipient = o.s("recipient"),
    )
}

/** `{ ok, total, page, limit, items, filters }`. */
fun parseLogPage(root: JsonElement): LogPage {
    val o = root.asObj() ?: return LogPage(0, 1, 80, emptyList(), emptyList(), emptyList(), emptyList())
    val filters = o["filters"].asObj()
    return LogPage(
        total = o.n("total", 0),
        page = o.n("page", 1),
        limit = o.n("limit", 80),
        items = (o["items"] as? JsonArray)?.mapNotNull { it.asObj()?.let(::parseLogItem) }.orEmpty(),
        actors = filters?.strs("actors").orEmpty(),
        entityTypes = filters?.strs("entityTypes").orEmpty(),
        actions = filters?.strs("actions").orEmpty(),
    )
}

/** Фильтры экрана; [params] — те же query-параметры, что у сайта. */
data class LogFilters(
    val search: String = "",
    val domain: String = "",
    val kind: String = "",
    val entityType: String = "",
    val action: String = "",
    val actor: String = "",
    val onlyErrors: Boolean = false,
    /** По умолчанию шум (просмотры страниц, ИИ-вызовы) скрыт. */
    val includeNoise: Boolean = false,
) {
    fun params(page: Int, limit: Int = 80): Map<String, String> = buildMap {
        put("page", page.toString())
        put("limit", limit.toString())
        if (search.isNotBlank()) put("q", search.trim())
        if (domain.isNotEmpty()) put("domain", domain)
        if (kind.isNotEmpty()) put("kind", kind)
        if (entityType.isNotEmpty()) put("entityType", entityType)
        if (action.isNotEmpty()) put("action", action)
        if (actor.isNotEmpty()) put("actor", actor)
        if (onlyErrors) put("onlyErrors", "true")
        if (includeNoise) put("includeNoise", "true")
    }
}

/** Быстрые наборы фильтров — те же, что чипы на сайте (`applyPreset`). */
enum class LogPreset(val key: String, val title: String) {
    All("all", "Важное"),
    Pages("pages", "Страницы"),
    SiteErrors("site-errors", "Ошибки сайта"),
    Telegram("telegram", "Telegram"),
    Ai("ai", "ИИ"),
    Receipts("receipts", "Приёмка"),
    Debts("debts", "Долги"),
    Finance("finance", "Финансы"),
    Auth("auth", "Входы"),
    Staff("staff", "Кадры"),
    Operations("operations", "Операции"),
    Structure("structure", "Структура"),
    Errors("errors", "Ошибки"),
}

/** Применить набор: сбрасывает остальные фильтры, шум не трогает. */
fun LogFilters.withPreset(p: LogPreset): LogFilters {
    val base = copy(search = "", domain = "", kind = "", entityType = "", action = "", actor = "", onlyErrors = false)
    return when (p) {
        LogPreset.All -> base
        LogPreset.Pages -> base.copy(domain = "pages")
        LogPreset.SiteErrors -> base.copy(domain = "site-errors", onlyErrors = true)
        LogPreset.Telegram -> base.copy(domain = "telegram")
        LogPreset.Ai -> base.copy(domain = "ai")
        LogPreset.Receipts -> base.copy(domain = "receipts")
        LogPreset.Debts -> base.copy(domain = "debts")
        LogPreset.Finance -> base.copy(domain = "finance")
        LogPreset.Auth -> base.copy(domain = "auth", kind = "audit")
        LogPreset.Staff -> base.copy(domain = "staff", kind = "audit")
        LogPreset.Operations -> base.copy(domain = "operations")
        LogPreset.Structure -> base.copy(domain = "structure", kind = "audit")
        LogPreset.Errors -> base.copy(entityType = "system-error", onlyErrors = true)
    }
}

/** Какой набор сейчас подсвечен (как условие `active` у чипа на сайте). */
fun LogFilters.isPresetActive(p: LogPreset): Boolean = when (p) {
    LogPreset.All -> domain.isEmpty() && !onlyErrors
    LogPreset.Errors -> onlyErrors && domain.isEmpty()
    else -> domain == p.key
}

/** Сводка по показанной странице. */
data class LogStats(
    val shown: Int, val errors: Int, val sales: Int, val finance: Int, val debts: Int,
    val shifts: Int, val staff: Int, val inventory: Int, val tasks: Int, val notifications: Int,
)

fun logStats(items: List<LogItem>): LogStats {
    fun ent(i: LogItem) = i.entityType.orEmpty().lowercase()
    fun count(p: (LogItem) -> Boolean) = items.count(p)
    return LogStats(
        shown = items.size,
        errors = count { it.countsAsError },
        sales = count { ent(it) in setOf("point-sale", "point-return") },
        finance = count { ent(it) in setOf("income", "expense", "income-export", "expense-export") },
        debts = count { ent(it) in setOf("point-debt", "point-debt-item", "supplier-debt", "debts", "debt") },
        shifts = count { ent(it) in setOf("shift", "point-shift", "point-shift-report", "shift-week", "shift-change-request", "shift-week-response", "shift-publication") },
        staff = count { ent(it) in setOf("staff", "operator", "staff-payment", "operator-staff-link", "operator-salary-adjustment", "operator-salary-week-payment") },
        inventory = count { ent(it).startsWith("inventory") },
        tasks = count { ent(it) in setOf("task", "task-comment", "point-incident") },
        notifications = count { it.kind == "notification" },
    )
}

/** Заголовок записи: серверный, если он осмысленный (не «entity • action» и не «·»). */
fun logTitle(item: LogItem): String {
    val t = item.title.trim()
    val meaningful = t.isNotEmpty() && t != "·" && !t.contains(" • ") && !Regex("[:·]\\s*·?\\s*$").containsMatchIn(t)
    if (meaningful) return t
    val who = item.actorEmail?.substringBefore('@') ?: "Система"
    val entity = item.entityType?.let { LogLabels.entity[it] ?: it } ?: "событие"
    val act = item.action?.let { " (${LogLabels.actionWord[it] ?: it})" }.orEmpty()
    return "$who — $entity$act"
}

object LogLabels {
    val entity = mapOf(
        "income" to "Доход", "expense" to "Расход", "company" to "Компания", "staff" to "Сотрудник",
        "operator" to "Оператор", "expense_category" to "Категория расходов", "profitability-input" to "ОПиУ ввод",
        "kaspi_terminal" to "Безналичный терминал", "operator-salary-adjustment" to "Корректировка зарплаты",
        "staff-payment" to "Выплата зарплаты", "auth-attempt" to "Вход в систему", "auth-session" to "Сессия",
        "system-error" to "Ошибка системы", "ai-usage" to "ИИ-запрос", "task" to "Задача", "shift" to "Смена",
        "operator-company-assignment" to "Назначение в компанию", "operator-career" to "Карьера оператора",
        "salary_payment" to "Выплата зарплаты", "visit" to "Посещение", "page-view" to "Просмотр страницы",
        "inventory-receipt" to "Приёмка", "supplier-debt" to "Долг поставщика", "point-debt" to "Долг точки",
        "point-debt-item" to "Товар в долг точки",
    )
    val actionWord = mapOf(
        "create" to "добавил", "create-batch" to "добавил пачкой", "update" to "изменил", "update-online" to "обновил Online сумму",
        "delete" to "удалил", "upsert" to "сохранил", "login" to "вошёл в систему", "logout" to "вышел из системы",
        "failed" to "неудачная попытка входа", "complete" to "выполнен", "error" to "ошибка",
        "page-view" to "просмотрел страницу", "visit" to "посетил сайт",
    )

    /** Подписи действий в списке фильтра и на чипе. */
    val actionBadge = mapOf(
        "create" to "Создание", "create-batch" to "Пачка", "update" to "Изменение", "update-online" to "Online обновление",
        "delete" to "Удаление", "upsert" to "Сохранение", "login" to "Вход", "logout" to "Выход", "failed" to "Ошибка",
        "error" to "Ошибка", "complete" to "Выполнено", "page-view" to "Просмотр", "visit" to "Посещение",
        "point-debt-notify" to "Уведомление о долге", "point-debt-item" to "Товар в долг",
    )
    val channel = mapOf("telegram" to "Telegram", "email" to "Email", "ai" to "ИИ")
    private val values = mapOf(
        "success" to "успешно", "sent" to "отправлено", "delivered" to "доставлено", "failed" to "ошибка", "error" to "ошибка",
        "openai" to "OpenAI", "gemini" to "Gemini", "ai" to "ИИ", "client-navigation" to "переход по сайту",
        "react-error-boundary" to "ошибка React-интерфейса", "unhandledrejection" to "необработанная ошибка браузера",
        "point-debt-notify" to "уведомление о долге точки", "point-debt-item" to "товар в долг точки",
    )

    /** Значение строки деталей по-русски: «sent» → «отправлено». */
    fun value(raw: String): String = values[raw.trim().lowercase()] ?: raw.trim()
}

/** «Метка: значение» → пара; без двоеточия вся строка — метка без значения. */
fun logDetailRow(row: String): Pair<String, String> {
    val i = row.indexOf(": ")
    if (i < 0) return row to ""
    return row.substring(0, i) to LogLabels.value(row.substring(i + 2))
}

/** Тело запроса объяснения ошибки: те же поля, что шлёт сайт. */
fun explainErrorBody(item: LogItem): JsonObject {
    val areaRow = item.detailRows.firstOrNull { it.startsWith("Где упало:") || it.startsWith("Область:") }
    val area = areaRow?.substringAfter(':')?.trim() ?: item.entityType
    val message = item.detailRows.joinToString(" · ").ifEmpty { item.details ?: item.title }
    return buildJsonObject {
        put("title", item.title)
        put("area", area?.let(::JsonPrimitive) ?: JsonNull)
        put("message", message)
        put("action", item.action?.let(::JsonPrimitive) ?: JsonNull)
        put("entityType", item.entityType?.let(::JsonPrimitive) ?: JsonNull)
    }
}
