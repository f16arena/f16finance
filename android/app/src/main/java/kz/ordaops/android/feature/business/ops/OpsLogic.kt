package kz.ordaops.android.feature.business.ops

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate

// Чистая логика разделов «Операции / Касса / Общение / Система»: терпимое чтение
// JSON, черновики и правила проверки, сортировки. Без Compose и Android —
// покрыта юнит-тестами (зеркало TaskDraftTests / IncidentDraft в OrdaKit).

// ── Терпимое чтение JSON ─────────────────────────────────────────────────────
// Как `decodeFlexibleString` / `decodeFlexibleDouble` на айфоне: id и суммы
// приходят то строкой, то числом, то null — и это не должно ронять экран.

/** Строка по первому найденному ключу; число и логическое превращаем в текст, null — пусто. */
fun JsonObject.str(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        if (value is JsonNull) continue
        val text = value.contentOrNull ?: continue
        return text
    }
    return null
}

/** Число по первому найденному ключу; строку с числом тоже читаем. */
fun JsonObject.num(vararg keys: String): Double? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        if (value is JsonNull) continue
        value.doubleOrNull?.let { return it }
        value.contentOrNull?.replace(',', '.')?.trim()?.toDoubleOrNull()?.let { return it }
    }
    return null
}

fun JsonObject.int(vararg keys: String): Int? = num(*keys)?.toInt()

/** Логическое: `true`/`false`, а также `1`/`0` и строки. */
fun JsonObject.bool(vararg keys: String): Boolean? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        if (value is JsonNull) continue
        value.booleanOrNull?.let { return it }
        when (value.contentOrNull?.lowercase()) {
            "1", "true", "yes" -> return true
            "0", "false", "no" -> return false
        }
    }
    return null
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** Массив по ключу `data`, а если корень сам массив-обёртка — по ключу [key]. */
fun JsonObject.items(vararg keys: String): List<JsonObject> {
    for (key in keys) {
        val element: JsonElement? = this[key]
        if (element is JsonArray) return element.mapNotNull { it as? JsonObject }
    }
    return emptyList()
}

/** Дата без времени из `2026-10-25` или полного ISO. */
fun parseDay(value: String?): LocalDate? {
    if (value.isNullOrBlank() || value.length < 10) return null
    return runCatching { LocalDate.parse(value.substring(0, 10)) }.getOrNull()
}

// ── Задачи ───────────────────────────────────────────────────────────────────

/** Приоритет задачи; значения принимает сервер (`TaskPriority`). */
enum class TaskPriority(val raw: String, val title: String) {
    Low("low", "Низкий"),
    Medium("medium", "Обычный"),
    High("high", "Высокий"),
    Critical("critical", "Срочно");

    companion object {
        fun of(raw: String?): TaskPriority = entries.firstOrNull { it.raw == raw } ?: Medium
    }
}

/** Состояние задачи (`TaskState`). */
enum class TaskState(val raw: String, val title: String) {
    Backlog("backlog", "Копилка"),
    Todo("todo", "К выполнению"),
    InProgress("in_progress", "В работе"),
    Review("review", "На проверке"),
    Done("done", "Готово"),
    Archived("archived", "В архиве");

    companion object {
        /** Между этими переключают руками; архив — уборка, а не ход работы. */
        val selectable = listOf(Todo, InProgress, Review, Done)

        fun of(raw: String?): TaskState = entries.firstOrNull { it.raw == raw } ?: Todo
    }
}

/** Задача команды — `TeamTask`. */
data class OpsTask(
    val id: String,
    val number: Int?,
    val title: String,
    val details: String?,
    val status: String,
    val priority: String,
    /** `yyyy-MM-dd` или пусто. */
    val dueDate: String?,
    val companyId: String?,
    val operatorId: String?,
    val commentsCount: Int,
    val checklist: List<ChecklistItem>,
) {
    data class ChecklistItem(val id: String, val text: String, val isDone: Boolean)

    val isDone: Boolean get() = status == "done" || status == "completed"

    val statusLabel: String
        get() = when (status) {
            "todo", "new", "open" -> "К выполнению"
            "in_progress", "doing" -> "В работе"
            "review" -> "На проверке"
            "done", "completed" -> "Готово"
            "backlog" -> "Копилка"
            "cancelled", "canceled" -> "Отменена"
            else -> StatusText.humanize(status)
        }

    val priorityLabel: String
        get() = when (priority) {
            "high", "urgent" -> "Высокий"
            "critical" -> "Срочно"
            "low" -> "Низкий"
            else -> "Обычный"
        }

    val isUrgent: Boolean get() = priority == "high" || priority == "urgent" || priority == "critical"

    /** Срок прошёл, а задача не закрыта. Закрытые с прошедшим сроком просроченными не считаем. */
    fun isOverdue(today: LocalDate = LocalDate.now()): Boolean {
        val due = parseDay(dueDate) ?: return false
        return !isDone && due.isBefore(today)
    }

    val doneCount: Int get() = checklist.count { it.isDone }

    companion object {
        fun parse(o: JsonObject): OpsTask = OpsTask(
            id = o.str("id") ?: "",
            number = o.int("task_number")?.takeIf { it != 0 },
            title = o.str("title") ?: "Задача",
            details = o.str("description"),
            status = o.str("status") ?: "todo",
            priority = o.str("priority") ?: "normal",
            dueDate = o.str("due_date"),
            companyId = o.str("company_id"),
            operatorId = o.str("operator_id"),
            commentsCount = o.int("comments_count") ?: 0,
            checklist = o.arr("checklist").mapIndexed { index, item ->
                ChecklistItem(
                    id = item.str("id") ?: "i$index",
                    text = item.str("text", "title") ?: "",
                    isDone = item.bool("done", "completed") ?: false,
                )
            },
        )
    }
}

/** Комментарий к задаче; имён сервер не отдаёт — показываем роль. */
data class OpsTaskComment(val id: String, val content: String, val createdAt: String?, val fromOperator: Boolean) {
    val authorLabel: String get() = if (fromOperator) "Оператор" else "Сотрудник"

    companion object {
        fun parse(o: JsonObject) = OpsTaskComment(
            id = o.str("id") ?: "",
            content = o.str("content") ?: "",
            createdAt = o.str("created_at"),
            fromOperator = o.str("operator_id") != null,
        )
    }
}

/**
 * Порядок на доске: просроченные наверх, дальше срочные, дальше по сроку, без срока — в конец.
 */
fun sortTasks(tasks: List<OpsTask>, today: LocalDate = LocalDate.now()): List<OpsTask> =
    tasks.sortedWith(
        compareByDescending<OpsTask> { it.isOverdue(today) }
            .thenByDescending { it.isUrgent }
            .thenBy { parseDay(it.dueDate) == null }
            .thenBy { parseDay(it.dueDate) ?: LocalDate.MAX }
            .thenBy { it.title },
    )

/** Что заполняют при постановке задачи — `TaskDraft`. */
data class TaskDraft(
    val title: String = "",
    val details: String = "",
    val priority: TaskPriority = TaskPriority.Medium,
    val status: TaskState = TaskState.Todo,
    val companyId: String = "",
    /** Исполнитель — оператор; пусто — задача в общем списке точки. */
    val operatorId: String? = null,
    /** `yyyy-MM-dd` или пусто. */
    val dueDate: String? = null,
) {
    /** Что мешает отправить; формулировки серверные. */
    val validationMessage: String?
        get() = when {
            title.isBlank() -> "Название задачи обязательно"
            companyId.isEmpty() -> "Для задачи нужно выбрать точку"
            else -> null
        }

    val isValid: Boolean get() = validationMessage == null

    /**
     * Тело `createTask`. Пустые поля — явный `null`: сервер кладёт их в запись как есть,
     * а пропущенный ключ читался бы как «не трогать».
     */
    fun body(): JsonObject = buildJsonObject {
        put("action", "createTask")
        put(
            "payload",
            buildJsonObject {
                put("title", title.trim())
                put("description", details.trim().ifEmpty { null })
                put("priority", priority.raw)
                put("status", status.raw)
                put("operator_id", operatorId?.ifEmpty { null })
                put("company_id", companyId)
                put("due_date", dueDate?.ifEmpty { null })
            },
        )
    }
}

/**
 * Частичная правка: переданы только изменённые поля. Пустая строка в `operator_id`
 * и `due_date` — «снять»; пропущенный ключ — «не трогать».
 */
data class TaskPatch(
    val title: String? = null,
    val description: String? = null,
    val priority: String? = null,
    val status: String? = null,
    val operatorId: String? = null,
    val companyId: String? = null,
    val dueDate: String? = null,
) {
    fun body(taskId: String): JsonObject = buildJsonObject {
        put("action", "updateTask")
        put("taskId", taskId)
        put(
            "payload",
            buildJsonObject {
                title?.let { put("title", it) }
                description?.let { put("description", it) }
                priority?.let { put("priority", it) }
                status?.let { put("status", it) }
                operatorId?.let { put("operator_id", it) }
                companyId?.let { put("company_id", it) }
                dueDate?.let { put("due_date", it) }
            },
        )
    }
}

fun taskStatusBody(taskId: String, status: TaskState): JsonObject = buildJsonObject {
    put("action", "changeStatus"); put("taskId", taskId); put("status", status.raw)
}

fun taskDeleteBody(taskId: String): JsonObject = buildJsonObject {
    put("action", "deleteTask"); put("taskId", taskId)
}

fun taskCommentBody(taskId: String, content: String): JsonObject = buildJsonObject {
    put("action", "addComment"); put("taskId", taskId); put("content", content)
}

/** Ответ исполнителя по своей задаче: сервер сам выводит статус и пишет комментарий. */
fun taskRespondBody(taskId: String, response: String, note: String?): JsonObject = buildJsonObject {
    put("action", "respondTask"); put("taskId", taskId); put("response", response)
    if (!note.isNullOrEmpty()) put("note", note)
}

// ── Инциденты ────────────────────────────────────────────────────────────────

enum class IncidentKind(val raw: String, val title: String) {
    Violation("violation", "Нарушение"),
    Bonus("bonus", "Поощрение"),
    Note("note", "Заметка"),
}

enum class IncidentSeverity(val raw: String, val title: String) {
    Info("info", "Мелочь"),
    Normal("normal", "Обычный"),
    Warning("warning", "Серьёзный"),
    Critical("critical", "Критичный"),
}

/** Решение по инциденту (`IncidentStatus.decisions`). */
enum class IncidentDecision(val raw: String, val title: String) {
    Confirmed("confirmed", "Подтвердить"),
    Disputed("disputed", "Оспорить"),
    Voided("voided", "Отменить"),
}

/** Штраф, бонус или заметка по сотруднику — `Incident`. */
data class OpsIncident(
    val id: String,
    val kind: String,
    val title: String,
    val details: String?,
    val fineAmount: Double,
    val bonusAmount: Double,
    val status: String,
    val occurredAt: String?,
    val companyName: String?,
    val subjectName: String?,
    val photoCount: Int,
) {
    /** Бонус в плюс, штраф в минус. */
    val netAmount: Double get() = bonusAmount - fineAmount
    val isFine: Boolean get() = fineAmount > 0
    val isBonus: Boolean get() = bonusAmount > 0

    val kindLabel: String
        get() = when (kind) {
            "fine", "penalty" -> "Штраф"
            "bonus", "reward" -> "Бонус"
            "note", "remark" -> "Заметка"
            "violation" -> "Нарушение"
            else -> "Запись"
        }

    val statusLabel: String
        get() = when (status) {
            "pending", "new", "open" -> "На рассмотрении"
            "draft" -> "Черновик"
            "approved", "confirmed" -> "Подтверждён"
            "rejected", "declined" -> "Отклонён"
            "disputed" -> "Оспорен"
            "voided" -> "Отменён"
            "closed" -> "Закрыт"
            else -> StatusText.humanize(status)
        }

    /** Ждёт решения: новые и черновики. */
    val isPending: Boolean get() = status == "pending" || status == "new" || status == "open" || status == "draft"

    companion object {
        fun parse(o: JsonObject): OpsIncident {
            fun name(key: String): String? = o.obj(key)?.str("full_name", "name", "short_name")
            return OpsIncident(
                id = o.str("id") ?: "",
                kind = o.str("kind") ?: "note",
                title = o.str("title") ?: "Без названия",
                details = o.str("description"),
                fineAmount = o.num("fine_amount") ?: 0.0,
                bonusAmount = o.num("bonus_amount") ?: 0.0,
                status = o.str("status") ?: "pending",
                occurredAt = o.str("occurred_at"),
                companyName = name("company"),
                subjectName = name("subject"),
                photoCount = (o["photo_urls"] as? JsonArray)?.size ?: 0,
            )
        }
    }
}

/** Итоги над списком: штрафы, бонусы, число ждущих решения. */
data class IncidentTotals(val fines: Double, val bonuses: Double, val pending: Int) {
    val net: Double get() = bonuses - fines
}

fun incidentTotals(items: List<OpsIncident>) = IncidentTotals(
    fines = items.sumOf { it.fineAmount },
    bonuses = items.sumOf { it.bonusAmount },
    pending = items.count { it.isPending },
)

/** Что заполняют при регистрации инцидента — `IncidentDraft`. */
data class IncidentDraft(
    val companyId: String = "",
    val kind: IncidentKind = IncidentKind.Violation,
    val title: String = "",
    val details: String = "",
    val severity: IncidentSeverity = IncidentSeverity.Normal,
    val fineAmount: Double = 0.0,
    val bonusAmount: Double = 0.0,
    /** Кого касается; бывает инцидент про точку, а не про человека. */
    val subjectStaffId: String? = null,
) {
    val validationMessage: String?
        get() = when {
            companyId.isEmpty() -> "Точка обязательна"
            title.isBlank() -> "Название обязательно"
            fineAmount < 0 || bonusAmount < 0 -> "Сумма не может быть отрицательной"
            // Штраф у поощрения и премия у нарушения — почти всегда промах в поле, а не замысел.
            kind == IncidentKind.Bonus && fineAmount > 0 -> "У поощрения не бывает штрафа"
            kind == IncidentKind.Violation && bonusAmount > 0 -> "У нарушения не бывает премии"
            else -> null
        }

    val isValid: Boolean get() = validationMessage == null

    /** Тело POST: пустые необязательные поля не отправляем вовсе. */
    fun body(): JsonObject = buildJsonObject {
        put("company_id", companyId)
        put("kind", kind.raw)
        put("title", title.trim())
        details.trim().takeIf { it.isNotEmpty() }?.let { put("description", it) }
        put("severity", severity.raw)
        if (fineAmount > 0) put("fine_amount", fineAmount)
        if (bonusAmount > 0) put("bonus_amount", bonusAmount)
        subjectStaffId?.takeIf { it.isNotEmpty() }?.let { put("subject_staff_id", it) }
        put("source", "manual")
        put("status", "confirmed")
    }
}

fun incidentStatusBody(decision: IncidentDecision): JsonObject = buildJsonObject { put("status", decision.raw) }

// ── Клиенты ──────────────────────────────────────────────────────────────────

/** Клиент точки: лояльность, траты, визиты — `Customer`. */
data class OpsCustomer(
    val id: String,
    val name: String,
    val phone: String?,
    val cardNumber: String?,
    val loyaltyPoints: Double,
    val totalSpent: Double,
    val visitsCount: Int,
    val companyName: String?,
) {
    /** Без визитов делить не на что — ноль, а не деление на ноль. */
    val averageCheck: Double get() = if (visitsCount > 0) totalSpent / visitsCount else 0.0

    val initials: String
        get() = name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().toString() }.uppercase()

    /** Подходит ли под поиск по имени, телефону или карте. */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) || (phone?.contains(q) == true) || (cardNumber?.contains(q) == true)
    }

    companion object {
        fun parse(o: JsonObject) = OpsCustomer(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Без имени",
            phone = o.str("phone"),
            cardNumber = o.str("card_number"),
            loyaltyPoints = o.num("loyalty_points") ?: 0.0,
            totalSpent = o.num("total_spent") ?: 0.0,
            visitsCount = o.int("visits_count") ?: 0,
            companyName = o.obj("company")?.str("name"),
        )
    }
}

/** Новый клиент: только имя обязательно. */
fun createCustomerBody(name: String, phone: String?, cardNumber: String?, companyId: String?): JsonObject = buildJsonObject {
    put("action", "createCustomer")
    put(
        "payload",
        buildJsonObject {
            put("name", name.trim())
            phone?.trim()?.takeIf { it.isNotEmpty() }?.let { put("phone", it) }
            cardNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { put("card_number", it) }
            companyId?.takeIf { it.isNotEmpty() }?.let { put("company_id", it) }
        },
    )
}

/** Корректировка баллов: [delta] со знаком; ноль сервер отвергает. */
fun adjustPointsBody(customerId: String, delta: Int, reason: String?): JsonObject = buildJsonObject {
    put("action", "adjustPoints"); put("customerId", customerId); put("delta", delta)
    if (!reason.isNullOrBlank()) put("reason", reason.trim())
}

/** Что станет с баллами; не ниже нуля показываем, но списать больше, чем есть, нельзя. */
fun resultingPoints(current: Double, amount: Int, adding: Boolean): Double = current + if (adding) amount else -amount

/** Целое из поля «баллы»: только цифры. */
fun parsePointsInput(text: String): Int = text.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0

// ── Статусы ──────────────────────────────────────────────────────────────────

/** Тексты статусов сервера по-русски — `StatusText`. */
object StatusText {
    /** Известные слова — переводим, остальное делаем читаемым: `in_review` → `In review`. */
    fun humanize(raw: String): String {
        val known = mapOf(
            "pending" to "Ожидает", "approved" to "Одобрено", "rejected" to "Отклонено",
            "active" to "Активно", "inactive" to "Выключено", "open" to "Открыто", "closed" to "Закрыто",
            "paid" to "Оплачено", "unpaid" to "Не оплачено", "cancelled" to "Отменено", "canceled" to "Отменено",
            "completed" to "Завершено", "failed" to "Ошибка", "sent" to "Отправлено", "draft" to "Черновик",
        )
        known[raw.lowercase()]?.let { return it }
        val spaced = raw.replace('_', ' ').replace('-', ' ').trim()
        return spaced.replaceFirstChar { it.uppercase() }
    }
}
