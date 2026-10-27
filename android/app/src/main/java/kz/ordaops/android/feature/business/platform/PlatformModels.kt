package kz.ordaops.android.feature.business.platform

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kz.ordaops.android.feature.business.ops.arr
import kz.ordaops.android.feature.business.ops.bool
import kz.ordaops.android.feature.business.ops.int
import kz.ordaops.android.feature.business.ops.items
import kz.ordaops.android.feature.business.ops.num
import kz.ordaops.android.feature.business.ops.obj
import kz.ordaops.android.feature.business.ops.parseDay
import kz.ordaops.android.feature.business.ops.str
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Платформа суперадмина: чистая логика без Compose. Источник правды — текущий веб
// (`app/api/admin/organizations`, `app/api/admin/platform/*`), а не устаревший
// OrdaKit/Platform: ключи читаем терпимо, лишнее игнорируем.

// ── Организации ──────────────────────────────────────────────────────────────

/** Подписка организации (`subscription` в ответе `GET /api/admin/organizations`). */
data class OrgSubscription(
    val status: String,
    val billingPeriod: String,
    val startsAt: String?,
    val endsAt: String?,
    val planName: String?,
) {
    val statusLabel: String
        get() = when (status) {
            "active" -> "Активна"
            "trialing" -> "Пробный период"
            "past_due" -> "Просрочена"
            "canceled" -> "Отменена"
            "" -> "Нет"
            else -> status
        }

    val isTrial: Boolean get() = status == "trialing"

    /** Сколько дней до конца подписки (триала); отрицательное — уже закончилась; null — срока нет. */
    fun daysLeft(today: LocalDate = LocalDate.now()): Int? =
        parseDay(endsAt)?.let { ChronoUnit.DAYS.between(today, it).toInt() }

    companion object {
        fun parse(o: JsonObject) = OrgSubscription(
            status = o.str("status") ?: "",
            billingPeriod = o.str("billingPeriod") ?: "monthly",
            startsAt = o.str("startsAt"),
            endsAt = o.str("endsAt"),
            planName = o.obj("plan")?.str("name"),
        )
    }
}

data class OrgCompany(val id: String, val name: String, val code: String?)

data class OrgInvoice(val id: String, val amount: Double, val status: String, val dueDate: String?, val paidAt: String?) {
    /** Выставлен и срок оплаты прошёл. */
    fun isOverdue(today: LocalDate = LocalDate.now()): Boolean =
        status == "issued" && (parseDay(dueDate)?.isBefore(today) ?: false)
}

/** Событие биллинга (история организации). */
data class BillingEvent(val type: String, val status: String?, val amount: Double?, val createdAt: String?) {
    val title: String
        get() = when (type) {
            "trial_started" -> "Старт пробного периода"
            "subscription_activated" -> "Активация"
            "payment_recorded" -> "Оплата"
            "subscription_past_due" -> "Просрочка"
            "subscription_cancel_scheduled" -> "Отмена в конце периода"
            "subscription_canceled" -> "Подписка отменена"
            "subscription_resumed" -> "Возобновление"
            "subscription_renewed" -> "Продление"
            "plan_changed" -> "Смена пакета"
            "invoice_issued" -> "Счёт выставлен"
            "organization_archived" -> "В архив"
            "organization_unarchived" -> "Из архива"
            else -> type
        }
}

/** Фича, действующая у организации, и откуда она взялась (`plan`, `addon`, `manual`, `legacy`). */
data class EffectiveFeature(val code: String, val sources: List<String>) {
    val isManual: Boolean get() = "manual" in sources
}

/** Организация-клиент платформы. */
data class PlatformOrg(
    val id: String,
    val name: String,
    val slug: String,
    val status: String,
    val billingExempt: Boolean,
    val featuresEnforced: Boolean,
    val companyLimit: Int,
    val companyCount: Int,
    val memberCount: Int,
    val appUrl: String?,
    val createdAt: String?,
    val packageCode: String?,
    val addonCodes: List<String>,
    val companies: List<OrgCompany>,
    val invoices: List<OrgInvoice>,
    val subscription: OrgSubscription?,
    val effectiveFeatures: List<EffectiveFeature>,
    val billingEvents: List<BillingEvent>,
) {
    val isActive: Boolean get() = status == "active"
    val isSuspended: Boolean get() = status == "suspended"
    val isArchived: Boolean get() = status == "archived"

    val statusLabel: String
        get() = when (status) {
            "active" -> "Активна"
            "suspended" -> "Заморожена"
            "archived" -> "В архиве"
            else -> status
        }

    val overdueInvoices: Int get() = invoices.count { it.isOverdue() }

    /** Лимит точек исчерпан: новую точку организация создать не сможет. */
    val limitReached: Boolean get() = companyCount >= companyLimit

    companion object {
        fun parse(o: JsonObject) = PlatformOrg(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Организация",
            slug = o.str("slug") ?: "",
            status = o.str("status") ?: "active",
            billingExempt = o.bool("billingExempt") ?: false,
            featuresEnforced = o.bool("featuresEnforced") ?: false,
            companyLimit = o.int("companyLimit") ?: 1,
            companyCount = o.int("companyCount") ?: o.arr("companies").size,
            memberCount = o.int("memberCount") ?: 0,
            appUrl = o.str("appUrl"),
            createdAt = o.str("createdAt"),
            packageCode = o.str("packageCode"),
            addonCodes = o.strings("addonCodes"),
            companies = o.arr("companies").map { OrgCompany(it.str("id") ?: "", it.str("name") ?: "Точка", it.str("code")) },
            invoices = o.arr("invoices").map {
                OrgInvoice(it.str("id") ?: "", it.num("amount") ?: 0.0, it.str("status") ?: "issued", it.str("due_date"), it.str("paid_at"))
            },
            subscription = o.obj("subscription")?.let(OrgSubscription::parse),
            effectiveFeatures = o.arr("effectiveFeatures").map { EffectiveFeature(it.str("code") ?: "", it.strings("sources")) }.filter { it.code.isNotEmpty() },
            billingEvents = o.arr("billingEvents").map { BillingEvent(it.str("eventType") ?: "", it.str("status"), it.num("amount"), it.str("createdAt")) },
        )
    }
}

/** Сводка платформы — то, что суперадмин смотрит первым. */
data class PlatformOverview(
    val organizations: Int = 0,
    val activeOrganizations: Int = 0,
    val activeSubscriptions: Int = 0,
    val trialingSubscriptions: Int = 0,
    val pastDueSubscriptions: Int = 0,
    val companies: Int = 0,
    val members: Int = 0,
    /** Регулярная выручка с активных подписок. */
    val liveMrr: Double = 0.0,
    /** Потенциальная выручка с триалов — отдельно, это ещё не деньги. */
    val trialMrr: Double = 0.0,
    val overdueInvoices: Int = 0,
    val overdueSum: Double = 0.0,
    val paidThisMonth: Double = 0.0,
    val trialsEndingSoon: Int = 0,
) {
    companion object {
        fun parse(o: JsonObject) = PlatformOverview(
            organizations = o.int("organizationCount") ?: 0,
            activeOrganizations = o.int("activeOrganizationCount") ?: 0,
            activeSubscriptions = o.int("activeSubscriptions") ?: 0,
            trialingSubscriptions = o.int("trialingSubscriptions") ?: 0,
            pastDueSubscriptions = o.int("pastDueSubscriptions") ?: 0,
            companies = o.int("totalCompanies") ?: 0,
            members = o.int("totalMembers") ?: 0,
            liveMrr = o.num("liveMrr") ?: 0.0,
            trialMrr = o.num("trialMrr") ?: 0.0,
            overdueInvoices = o.int("overdueInvoices") ?: 0,
            overdueSum = o.num("overdueInvoicesSum") ?: 0.0,
            paidThisMonth = o.num("paidThisMonth") ?: 0.0,
            trialsEndingSoon = o.int("trialsEndingSoon") ?: 0,
        )
    }
}

/** Организация, требующая внимания, и почему. */
data class AttentionOrg(val id: String, val name: String, val reasons: List<String>)

/** Продаваемый пакет. */
data class PlatformPackage(val code: String, val name: String, val vertical: String?, val description: String?, val priceKzt: Double, val featureCount: Int)

/** Модуль (add-on) к пакету. */
data class PlatformAddon(val code: String, val name: String, val description: String?, val priceKzt: Double)

/** Функция каталога — «1 функция = 1 страница». */
data class FeatureItem(val code: String, val name: String, val category: String)

/** Вся картина платформы одним запросом. */
data class PlatformData(
    val overview: PlatformOverview,
    val organizations: List<PlatformOrg>,
    val attention: List<AttentionOrg>,
    val packages: List<PlatformPackage>,
    val addons: List<PlatformAddon>,
    val features: List<FeatureItem>,
) {
    fun org(id: String?): PlatformOrg? = id?.let { key -> organizations.firstOrNull { it.id == key } }

    /** Подмена одной организации ответом `PATCH` (в нём она в том же формате, что и в списке). */
    fun replacing(updated: PlatformOrg): PlatformData =
        copy(organizations = organizations.map { if (it.id == updated.id) updated else it })

    companion object {
        fun parse(root: JsonObject) = PlatformData(
            overview = root.obj("overview")?.let(PlatformOverview::parse) ?: PlatformOverview(),
            organizations = root.items("organizations").map(PlatformOrg::parse).filter { it.id.isNotEmpty() },
            attention = root.items("attention").map { AttentionOrg(it.str("id") ?: "", it.str("name") ?: "Организация", it.strings("reasons")) }.filter { it.id.isNotEmpty() },
            packages = root.items("packages").map {
                PlatformPackage(it.str("code") ?: "", it.str("name") ?: (it.str("code") ?: ""), it.str("vertical"), it.str("description"), it.num("price_kzt") ?: 0.0, it.strings("feature_codes").size)
            }.filter { it.code.isNotEmpty() },
            addons = root.items("addons").map { PlatformAddon(it.str("code") ?: "", it.str("name") ?: (it.str("code") ?: ""), it.str("description"), it.num("price_kzt") ?: 0.0) }
                .filter { it.code.isNotEmpty() },
            features = root.items("features").map { FeatureItem(it.str("code") ?: "", it.str("name") ?: (it.str("code") ?: ""), it.str("category") ?: "") }
                .filter { it.code.isNotEmpty() },
        )
    }
}

/** Массив строк по ключу; не массив — пусто. */
fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content } ?: emptyList()

// ── Список: фильтр и поиск ───────────────────────────────────────────────────

enum class OrgFilter(val title: String) {
    All("Все"), Active("Активные"), Trial("Триал"), PastDue("Просрочены"), Suspended("Заморожены"), Archived("Архив");

    fun matches(org: PlatformOrg): Boolean = when (this) {
        All -> !org.isArchived
        Active -> org.isActive && org.subscription?.status != "trialing" && org.subscription?.status != "past_due"
        Trial -> !org.isArchived && org.subscription?.isTrial == true
        PastDue -> !org.isArchived && (org.subscription?.status == "past_due" || org.overdueInvoices > 0)
        Suspended -> org.isSuspended
        Archived -> org.isArchived
    }
}

/** Поиск по названию, поддомену и названиям точек, затем фильтр по состоянию. */
fun filterOrganizations(all: List<PlatformOrg>, query: String, filter: OrgFilter): List<PlatformOrg> {
    val needle = query.trim().lowercase()
    return all.filter { filter.matches(it) }.filter { org ->
        needle.isEmpty() || org.name.lowercase().contains(needle) || org.slug.lowercase().contains(needle) ||
            org.companies.any { it.name.lowercase().contains(needle) }
    }
}

/** Подпись срока подписки для строки списка: «триал: 5 дн.», «просрочен на 3 дн.». */
fun subscriptionHint(sub: OrgSubscription?, today: LocalDate = LocalDate.now()): String? {
    if (sub == null) return null
    val left = sub.daysLeft(today)
    return when {
        sub.isTrial && left == null -> "триал"
        sub.isTrial && left!! < 0 -> "триал закончился"
        sub.isTrial -> "триал: ${left!!} дн."
        sub.status == "active" && left != null && left in 0..7 -> "платёж через $left дн."
        sub.status == "active" && left != null && left < 0 -> "срок вышел ${-left} дн. назад"
        sub.status == "past_due" -> "просрочена"
        sub.status == "canceled" -> "отменена"
        else -> null
    }
}

// ── Запись: тела PATCH /api/admin/organizations ──────────────────────────────
// Веб шлёт одно и то же: `organizationId` + нужные поля. Конфиг повторяет
// `runSub` из app/platform/organizations/[id]/page.tsx.

object OrgPatch {
    /** Приостановить доступ: организация замораживается, подписка уходит в просрочку (кнопка веба). */
    fun suspend(id: String): JsonObject = buildJsonObject {
        put("organizationId", id); put("organizationStatus", "suspended"); put("subscriptionAction", "markPastDue")
    }

    /** Возобновить: организация активна, подписка возобновлена. */
    fun resume(id: String): JsonObject = buildJsonObject {
        put("organizationId", id); put("organizationStatus", "active"); put("subscriptionAction", "resume")
    }

    /** Выдать пробный период на [days] дней (1…90, как на сервере). */
    fun startTrial(id: String, days: Int): JsonObject = buildJsonObject {
        put("organizationId", id); put("subscriptionAction", "startTrial"); put("trialDays", days.coerceIn(1, 90))
    }

    fun companyLimit(id: String, limit: Int): JsonObject = buildJsonObject {
        put("organizationId", id); put("companyLimit", limit.coerceAtLeast(1))
    }

    fun featuresEnforced(id: String, on: Boolean): JsonObject = buildJsonObject { put("organizationId", id); put("featuresEnforced", on) }

    fun billingExempt(id: String, on: Boolean): JsonObject = buildJsonObject { put("organizationId", id); put("billingExempt", on) }

    /** Назначить пакет; пустой код — снять пакет. */
    fun assignPackage(id: String, code: String): JsonObject = buildJsonObject { put("organizationId", id); put("assignPackage", code) }

    fun setAddon(id: String, addon: String, enabled: Boolean): JsonObject = buildJsonObject {
        put("organizationId", id)
        putJsonObject("setAddon") { put("addon", addon); put("enabled", enabled) }
    }

    /** Ручная выдача или снятие функции (источник `manual`). */
    fun setFeatureGrant(id: String, code: String, enabled: Boolean): JsonObject = buildJsonObject {
        put("organizationId", id)
        putJsonObject("setFeatureGrant") { put("code", code); put("enabled", enabled) }
    }
}

/** Допустимые сроки пробного периода в выборе (дней). */
val TRIAL_DAY_OPTIONS = listOf(7, 14, 30, 60, 90)

// ── Сервер и деплои ──────────────────────────────────────────────────────────

enum class HealthLevel(val title: String) { Ok("Работает"), Running("Деплой"), Warn("Внимание"), Bad("Сбой") }

data class ServerInfo(
    val host: String,
    val cores: Int,
    val uptimeSeconds: Long,
    val load1: Double,
    val memTotal: Double,
    val memUsed: Double,
    val swapTotal: Double,
    val swapUsed: Double,
    val diskTotal: Double,
    val diskUsed: Double,
    val rebootRequired: Boolean,
    /** Последняя загрузка процессора, %; null, если истории ещё нет. */
    val cpuNow: Double?,
) {
    val memPercent: Double get() = percentOf(memUsed, memTotal)
    val diskPercent: Double get() = percentOf(diskUsed, diskTotal)
    val swapPercent: Double get() = percentOf(swapUsed, swapTotal)
}

data class DeployItem(val start: Long, val duration: Long?, val sha: String, val status: String, val subject: String, val error: String?, val action: String) {
    val statusTitle: String
        get() = when (status) {
            "ok" -> "Готово"
            "failed" -> "Ошибка"
            "rollback" -> "Откат"
            "running" -> "Сборка"
            else -> "Оборвался"
        }
}

data class SiteCheck(val url: String, val code: Int, val ms: Int?) {
    val isUp: Boolean get() = code in 1..499
    val host: String get() = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
}

data class ContainerItem(val name: String, val state: String, val health: String?, val restarts: Int) {
    val isHealthy: Boolean get() = state == "running" && health != "unhealthy"
}

data class BackupInfo(val name: String, val time: Long, val sizeBytes: Long, val count: Int)

data class CronItem(val name: String, val schedule: String?, val lastAt: Long?, val lastCode: Int?, val runs24h: Int, val failed24h: Int) {
    val isFailing: Boolean get() = lastCode != null && lastCode != 200
}

data class ErrorSummary(val total: Int, val hourly: List<Int>) {
    val lastHour: Int get() = hourly.lastOrNull() ?: 0
}

/** Ответ `GET /api/admin/platform/status`. */
data class ServerStatus(
    val now: Long,
    val panelError: String?,
    val server: ServerInfo?,
    val deploys: List<DeployItem>,
    val checks: List<SiteCheck>,
    val containers: List<ContainerItem>,
    val backup: BackupInfo?,
    val crons: List<CronItem>,
    val errors: ErrorSummary,
) {
    companion object {
        fun parse(root: JsonObject): ServerStatus {
            val project = root.obj("project")
            val server = root.obj("server")?.let { s ->
                ServerInfo(
                    host = s.str("host") ?: "cloud-001",
                    cores = s.int("ncpu") ?: 0,
                    uptimeSeconds = (s.num("uptime") ?: 0.0).toLong(),
                    load1 = (s["load"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: 0.0,
                    memTotal = s.num("mem_total") ?: 0.0, memUsed = s.num("mem_used") ?: 0.0,
                    swapTotal = s.num("swap_total") ?: 0.0, swapUsed = s.num("swap_used") ?: 0.0,
                    diskTotal = s.num("disk_total") ?: 0.0, diskUsed = s.num("disk_used") ?: 0.0,
                    rebootRequired = s.bool("reboot_required") ?: false,
                    cpuNow = s.arr("history").lastOrNull()?.num("cpu"),
                )
            }
            return ServerStatus(
                now = (root.num("now") ?: 0.0).toLong(),
                panelError = root.str("panel_error"),
                server = server,
                deploys = project?.arr("deploys").orEmpty().map {
                    DeployItem(
                        start = (it.num("start") ?: 0.0).toLong(), duration = it.num("duration")?.toLong(), sha = it.str("sha") ?: "",
                        status = it.str("status") ?: "unknown", subject = it.str("subject") ?: "", error = it.str("error"), action = it.str("action") ?: "push",
                    )
                },
                checks = project?.arr("checks").orEmpty().map { SiteCheck(it.str("url") ?: "", it.int("code") ?: 0, it.int("ms")) },
                containers = project?.arr("containers").orEmpty().map { ContainerItem(it.str("name") ?: "", it.str("state") ?: "", it.str("health"), it.int("restarts") ?: 0) },
                backup = project?.obj("backup")?.let { BackupInfo(it.str("name") ?: "", (it.num("time") ?: 0.0).toLong(), (it.num("size") ?: 0.0).toLong(), it.int("count") ?: 0) },
                crons = root.arr("crons").map {
                    CronItem(it.str("name") ?: "", it.str("schedule"), it.num("last_at")?.toLong(), it.int("last_code"), it.int("runs_24h") ?: 0, it.int("failed_24h") ?: 0)
                },
                errors = root.obj("errors")?.let { e ->
                    ErrorSummary(e.int("total") ?: 0, (e["hourly"] as? JsonArray).orEmpty().map { ((it as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0).toInt() })
                } ?: ErrorSummary(0, emptyList()),
            )
        }
    }
}

private fun percentOf(part: Double, whole: Double): Double = if (whole <= 0) 0.0 else part * 100 / whole

/** Итог здоровья и причины — порт `overall()` страницы /platform/status. */
data class HealthVerdict(val level: HealthLevel, val issues: List<Pair<HealthLevel, String>>)

fun healthVerdict(status: ServerStatus): HealthVerdict {
    val issues = mutableListOf<Pair<HealthLevel, String>>()
    fun bump(level: HealthLevel, why: String) { issues += level to why }

    status.panelError?.let { bump(HealthLevel.Warn, "нет данных с панели сервера: $it") }
    status.checks.filter { !it.isUp }.forEach { bump(HealthLevel.Bad, "не отвечает ${it.host}") }
    status.containers.forEach { c ->
        when {
            c.state != "running" -> bump(HealthLevel.Bad, "${c.name} — ${c.state}")
            c.health == "unhealthy" -> bump(HealthLevel.Bad, "${c.name} — unhealthy")
        }
    }
    val last = status.deploys.firstOrNull()
    if (last?.status == "running") bump(HealthLevel.Running, "собирается новая версия")
    else if (last != null && last.status != "ok") bump(HealthLevel.Warn, "последний деплой не прошёл — работает прежняя версия")
    status.backup?.let { if (status.now - it.time > 26 * 3600) bump(HealthLevel.Warn, "бэкап базы старше суток") }
    val failing = status.crons.count { it.isFailing }
    if (failing > 0) bump(HealthLevel.Warn, "кронов падает: $failing")
    if (status.errors.lastHour >= 10) bump(HealthLevel.Warn, "${status.errors.lastHour} ошибок за последний час")
    status.server?.let { s ->
        if (s.diskPercent >= 90) bump(HealthLevel.Bad, "диск заполнен на 90%+")
        else if (s.diskPercent >= 80) bump(HealthLevel.Warn, "диск заполнен на 80%+")
        if (s.rebootRequired) bump(HealthLevel.Warn, "серверу нужна перезагрузка (обновления ядра)")
    }
    val worst = issues.maxByOrNull { rank(it.first) }?.first ?: HealthLevel.Ok
    return HealthVerdict(worst, issues)
}

private fun rank(level: HealthLevel) = when (level) {
    HealthLevel.Ok -> 0
    HealthLevel.Running -> 1
    HealthLevel.Warn -> 2
    HealthLevel.Bad -> 3
}

/** Размер в байтах: «3,2 ГБ», «540 МБ». */
fun bytesText(bytes: Double): String {
    val gb = 1024.0 * 1024 * 1024
    val mb = 1024.0 * 1024
    return when {
        bytes >= 10 * gb -> "%.0f ГБ".format(java.util.Locale("ru"), bytes / gb)
        bytes >= gb -> "%.1f ГБ".format(java.util.Locale("ru"), bytes / gb)
        else -> "%.0f МБ".format(java.util.Locale("ru"), bytes / mb)
    }
}

/** Время работы: «12 дн 3 ч», «5 ч 20 мин». */
fun uptimeText(seconds: Long): String =
    if (seconds >= 86400) "${seconds / 86400} дн ${seconds % 86400 / 3600} ч" else "${seconds / 3600} ч ${seconds % 3600 / 60} мин"

/** Давность: «только что», «5 мин назад», «3 ч назад», «2 дн назад». */
fun agoText(t: Long, now: Long): String {
    val s = (now - t).coerceAtLeast(0)
    return when {
        s < 45 -> "только что"
        s < 3600 -> "${(s + 30) / 60} мин назад"
        s < 86400 -> "${s / 3600} ч назад"
        else -> "${s / 86400} дн назад"
    }
}

/** Длительность сборки: «45 с», «3 мин 05 с». */
fun durationText(seconds: Long?): String = when {
    seconds == null -> ""
    seconds < 60 -> "$seconds с"
    else -> "${seconds / 60} мин %02d с".format(seconds % 60)
}

// ── Ошибки ───────────────────────────────────────────────────────────────────

/** Периоды журнала ошибок — ключи, которые знает `GET /api/admin/platform/errors`. */
val ERROR_PERIODS = listOf("1h" to "Час", "24h" to "Сутки", "7d" to "Неделя", "30d" to "Месяц")

data class ErrorSample(val at: Long, val actor: String?, val pathname: String?, val message: String, val stack: String?)

data class ErrorGroup(val area: String, val message: String, val scope: String, val count: Int, val firstAt: Long, val lastAt: Long, val users: Int, val samples: List<ErrorSample>) {
    val isClient: Boolean get() = scope == "client"
}

data class ErrorsReport(val period: String, val total: Int, val truncated: Boolean, val now: Long, val groups: List<ErrorGroup>) {
    companion object {
        fun parse(root: JsonObject) = ErrorsReport(
            period = root.str("period") ?: "24h",
            total = root.int("total") ?: 0,
            truncated = root.bool("truncated") ?: false,
            now = (root.num("now") ?: 0.0).toLong(),
            groups = root.arr("groups").map { g ->
                ErrorGroup(
                    area = g.str("area") ?: "—", message = g.str("message") ?: "", scope = g.str("scope") ?: "server",
                    count = g.int("count") ?: 1, firstAt = (g.num("first_at") ?: 0.0).toLong(), lastAt = (g.num("last_at") ?: 0.0).toLong(),
                    users = g.int("users") ?: 0,
                    samples = g.arr("samples").map { s ->
                        ErrorSample((s.num("at") ?: 0.0).toLong(), s.str("actor"), s.str("pathname"), s.str("full_message") ?: "", s.str("stack"))
                    },
                )
            },
        )
    }
}

/** Допустимые журналы хоста для `GET /api/admin/platform/status/logs?log=`. */
val HOST_LOGS = listOf("deploy" to "Сборка", "cron" to "Кроны", "backup" to "Бэкап")

/** Хвост журнала: оставляем последние [lines] строк, пустые с краёв срезаем. */
fun tailLines(text: String, lines: Int): String =
    text.trimEnd().lines().takeLast(lines).joinToString("\n")

/** Сумма в тенге без дробной части: «1 250 000 ₸». */
fun tenge(value: Double): String = kz.ordaops.android.ui.components.money(value)
