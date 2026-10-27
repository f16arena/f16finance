package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant

// «Настройки», «Telegram» и «Диагностика» — экраны только для чтения: SystemModels.swift.

// ── Модели ───────────────────────────────────────────────────────────────────

/** Точка. Закрытая (`archived_at`) остаётся в истории, но вне лимита; «вне общих итогов» — настройка точки. */
data class SettingsCompany(
    val id: String, val name: String, val code: String?, val showInStructure: Boolean,
    val isClosed: Boolean = false, val excludedFromTotals: Boolean = false,
)

data class SettingsStaff(val id: String, val fullName: String, val email: String?, val role: String, val isActive: Boolean) {
    val roleLabel: String
        get() = when (role) {
            "owner" -> "Владелец"
            "manager" -> "Управляющий"
            "operator" -> "Оператор"
            "accountant" -> "Бухгалтер"
            "other" -> "Прочее"
            else -> role
        }

    /** Владельцы и управляющие идут первыми: доступ к деньгам и правам проверяют, открывая этот список. */
    val isAdministrative: Boolean get() = role == "owner" || role == "manager"
}

data class SettingsCategory(val id: String, val name: String, val monthlyBudget: Double?, val accountingGroup: String?)

/** Состояние организации: точки, команда, категории расходов, лимит точек — `SystemSettings`. */
data class SystemSettings(
    val companies: List<SettingsCompany>,
    val staff: List<SettingsStaff>,
    val categories: List<SettingsCategory>,
    val companyLimit: Int?,
) {
    /** Точки в лимите: закрытые не считаются (веб: «Закрыть точку» освобождает слот). */
    val openCompanies: List<SettingsCompany> get() = companies.filter { !it.isClosed }
    val freeCompanySlots: Int? get() = companyLimit?.let { maxOf(0, it - openCompanies.size) }
    val isCompanyLimitReached: Boolean get() = companyLimit?.let { openCompanies.size >= it } ?: false
    val monthlyBudgetTotal: Double get() = categories.sumOf { it.monthlyBudget ?: 0.0 }

    /** Команда: административные первыми, дальше по имени. */
    val sortedStaff: List<SettingsStaff>
        get() = staff.sortedWith(compareByDescending<SettingsStaff> { it.isAdministrative }.thenBy { it.fullName })

    companion object {
        fun parse(o: JsonObject) = SystemSettings(
            companies = o.arr("companies").map {
                SettingsCompany(
                    it.str("id") ?: "", it.str("name") ?: "Точка", it.str("code"), it.bool("show_in_structure") ?: true,
                    isClosed = !it.str("archived_at").isNullOrEmpty(), excludedFromTotals = it.bool("exclude_from_totals") ?: false,
                )
            },
            staff = o.arr("staff").map {
                SettingsStaff(it.str("id") ?: "", it.str("full_name") ?: "Без имени", it.str("email"), it.str("role") ?: "other", it.bool("is_active") ?: true)
            },
            categories = o.arr("categories").map {
                SettingsCategory(it.str("id") ?: "", it.str("name") ?: "Без названия", it.num("monthly_budget"), it.str("accounting_group"))
            },
            companyLimit = o.int("companyLimit"),
        )
    }
}

/** Состояние Telegram-канала: токен, общий чат, вебхук, бот. */
data class TelegramStatus(
    val hasToken: Boolean,
    val hasChatId: Boolean,
    val hasWebhookSecret: Boolean,
    val botName: String?,
    val webhookConfigured: Boolean?,
    val pendingUpdates: Int,
    val lastError: String?,
    val lastErrorAt: Instant?,
) {
    val isOperational: Boolean get() = hasToken && (webhookConfigured ?: false)

    companion object {
        fun parse(o: JsonObject): TelegramStatus {
            val bot = o.obj("botInfo")
            val hook = o.obj("webhookInfo")
            val username = bot?.str("username")
            return TelegramStatus(
                hasToken = o.bool("hasToken") ?: false,
                hasChatId = o.bool("hasChatId") ?: false,
                hasWebhookSecret = o.bool("hasWebhookSecret") ?: false,
                botName = bot?.let { if (!username.isNullOrEmpty()) "@$username" else it.str("first_name") ?: "бот" },
                webhookConfigured = hook?.let { it.bool("isConfigured") ?: false },
                pendingUpdates = hook?.int("pending_update_count") ?: 0,
                lastError = hook?.str("last_error_message"),
                // Telegram отдаёт время ошибки unix-секундами, а не строкой.
                lastErrorAt = hook?.num("last_error_date")?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it.toLong()) },
            )
        }
    }
}

data class TelegramAllowedUser(val id: String, val telegramUserId: String, val label: String?, val canFinance: Boolean) {
    val displayName: String get() = label?.takeIf { it.isNotEmpty() } ?: telegramUserId
}

data class TelegramStaffLink(val id: String, val fullName: String, val role: String?, val chatId: String?, val isActive: Boolean) {
    val isLinked: Boolean get() = !chatId.isNullOrEmpty()
}

data class TelegramData(
    val status: TelegramStatus,
    val allowed: List<TelegramAllowedUser>,
    val allowedTableExists: Boolean,
    val staff: List<TelegramStaffLink>,
) {
    /** Работающие сотрудники без привязанного Telegram. */
    val unlinkedStaff: List<TelegramStaffLink> get() = staff.filter { it.isActive && !it.isLinked }
}

/** Одна проверка диагностики. */
data class DiagnosticCheck(val id: String, val title: String, val detail: String?, val state: State) {
    enum class State { Ok, Warning, Failure, Unknown }
}

/** Диагностика сервера: миграции базы и регулярные задачи — `SystemDiagnostics`. */
data class SystemDiagnostics(
    val crons: List<Cron>,
    val migrationsPresent: Boolean,
    val appliedCount: Int,
    val fileCount: Int,
    val pending: List<String>,
    val extra: List<String>,
    val migrationsError: String?,
) {
    data class Cron(val path: String, val schedule: String, val lastRunAt: Instant?) {
        /** Не запускалась больше двух суток. */
        fun looksStale(now: Instant = Instant.now()): Boolean = lastRunAt != null && now.epochSecond - lastRunAt.epochSecond > 48 * 3600
        val neverLogged: Boolean get() = lastRunAt == null
    }

    /** Список проверок, как собирает `SystemDiagnostics.checks`. */
    fun checks(now: Instant = Instant.now()): List<DiagnosticCheck> = buildList {
        if (migrationsPresent) {
            when {
                !migrationsError.isNullOrEmpty() -> add(DiagnosticCheck("migrations", "Миграции базы", migrationsError, DiagnosticCheck.State.Unknown))
                pending.isNotEmpty() -> add(DiagnosticCheck("migrations", "Не применено миграций: ${pending.size}", pending.take(5).joinToString("\n"), DiagnosticCheck.State.Failure))
                else -> add(DiagnosticCheck("migrations", "Миграции базы применены", "в репозитории $fileCount, в базе $appliedCount", DiagnosticCheck.State.Ok))
            }
            if (extra.isNotEmpty()) {
                add(DiagnosticCheck("migrations-extra", "В базе есть миграции без файлов: ${extra.size}", extra.take(5).joinToString("\n"), DiagnosticCheck.State.Warning))
            }
        } else {
            add(DiagnosticCheck("migrations", "Миграции базы", "сервер не вернул состояние", DiagnosticCheck.State.Unknown))
        }
        if (crons.isEmpty()) {
            add(DiagnosticCheck("crons", "Регулярные задачи не настроены", "автоматические отчёты и рассылки не запускаются", DiagnosticCheck.State.Warning))
        } else {
            crons.forEach { cron ->
                val state = when {
                    cron.neverLogged -> DiagnosticCheck.State.Unknown
                    cron.looksStale(now) -> DiagnosticCheck.State.Warning
                    else -> DiagnosticCheck.State.Ok
                }
                val whenText = cron.lastRunAt?.let { "последний запуск ${dateTimeLabel(it.toString())}" } ?: "запусков в журнале нет"
                add(DiagnosticCheck("cron:${cron.path}", cron.path, listOf(cron.schedule, whenText).filter { it.isNotEmpty() }.joinToString(" · "), state))
            }
        }
    }

    companion object {
        fun parse(o: JsonObject): SystemDiagnostics {
            val m = o.obj("migrations")
            return SystemDiagnostics(
                crons = o.arr("crons").map { Cron(it.str("path") ?: "—", it.str("schedule") ?: "", parseMoment(it.str("last_run_at"))) },
                migrationsPresent = m != null,
                appliedCount = m?.int("applied_count") ?: 0,
                fileCount = m?.int("file_count") ?: 0,
                pending = m?.strings("pending").orEmpty(),
                extra = m?.strings("extra").orEmpty(),
                migrationsError = m?.str("error"),
            )
        }
    }
}

/** Массив строк по ключу. */
fun JsonObject.strings(key: String): List<String> =
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: emptyList()

// ── Экраны ───────────────────────────────────────────────────────────────────

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String, color: Color) {
    OpsRow(icon, color, title, trailing = value, trailingColor = color)
}

/** Настройки: точки с лимитом, категории расходов с бюджетами, команда. Только чтение. */
@Composable
fun SettingsScreen() {
    val loader = rememberOpsLoader("settings", ApiRequest("/api/admin/settings"), SystemSettings::parse)
    val state by loader.state.collectAsStateWithLifecycle()
    var actionFor by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    StateContainer(state = state, onRetry = loader::refresh, emptyIcon = Icons.Filled.Settings) { data ->
        data.companies.firstOrNull { it.id == actionFor }?.let { PointActionsSheet(it, onDismiss = { actionFor = null }, onChanged = loader::refresh) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            item {
                // Главное — сколько точек из лимита; упёрлись в лимит — ниже предупреждение.
                HeroCard {
                    Text("Точки", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    Text(data.companyLimit?.let { "${data.openCompanies.size} из $it" } ?: "${data.openCompanies.size}", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    data.freeCompanySlots?.let { Text("свободно слотов: $it", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp) }
                    Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        HeroFact("Команда", "${data.staff.size}")
                        HeroFact("Категории", "${data.categories.size}")
                        HeroFact("Бюджет в месяц", money(data.monthlyBudgetTotal))
                    }
                }
            }
            if (data.isCompanyLimitReached) {
                item {
                    OpsRow(Icons.Filled.Warning, Orda.colors.warning, "Лимит точек исчерпан", subtitle = "Новую точку можно завести только после расширения тарифа.")
                }
            }
            item {
                OpsSection("Точки", count = data.freeCompanySlots?.let { "свободно: $it" } ?: "${data.companies.size}") {
                    if (data.companies.isEmpty()) Text("Точек пока нет", color = Orda.colors.textDim)
                    data.companies.forEach { c ->
                        OpsRow(
                            Icons.Filled.Apartment, if (c.isClosed) Orda.colors.textDim else Orda.colors.brand, c.name,
                            subtitle = listOfNotNull(c.code?.takeIf { it.isNotEmpty() }, "не в общих итогах".takeIf { c.excludedFromTotals }).joinToString(" · ").ifEmpty { null },
                            trailing = if (c.isClosed) "закрыта" else if (!c.showInStructure) "вне структуры" else null,
                            trailingColor = Orda.colors.textDim,
                            onClick = { actionFor = c.id },
                        )
                    }
                }
            }
            item { SettingsExtras(canAddPoint = !data.isCompanyLimitReached) }
            item {
                OpsSection("Категории расходов", count = if (data.monthlyBudgetTotal > 0) "бюджет ${money(data.monthlyBudgetTotal)}" else null) {
                    if (data.categories.isEmpty()) Text("Категорий пока нет", color = Orda.colors.textDim)
                    data.categories.forEach { cat ->
                        val budget = cat.monthlyBudget ?: 0.0
                        OpsRow(
                            Icons.Filled.Settings, Orda.colors.accent, cat.name,
                            subtitle = cat.accountingGroup?.takeIf { it.isNotEmpty() } ?: if (budget > 0) "бюджет в месяц" else "без бюджета",
                            trailing = if (budget > 0) money(budget) else "—",
                        )
                    }
                }
            }
            item {
                OpsSection("Команда", count = "активных: ${data.staff.count { it.isActive }}") {
                    if (data.staff.isEmpty()) Text("Сотрудников пока нет", color = Orda.colors.textDim)
                    data.sortedStaff.forEach { m ->
                        OpsRow(
                            Icons.Filled.Person, if (m.isActive) Orda.colors.brand else Orda.colors.textDim, m.fullName, subtitle = m.email,
                            trailing = m.roleLabel, trailingColor = if (m.isAdministrative) Orda.colors.info else Orda.colors.textDim,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun loadTelegram(api: ApiClient): TelegramData = coroutineScope {
    // Три независимых запроса: последовательная загрузка утроила бы ожидание.
    val status = async { TelegramStatus.parse(api.send<JsonObject>(ApiRequest("/api/telegram/status"))) }
    val allowed = async { api.send<JsonObject>(ApiRequest("/api/telegram/allowed-users")) }
    val staff = async { api.send<JsonObject>(ApiRequest("/api/telegram/staff-ids")) }
    val allowedRoot = allowed.await()
    TelegramData(
        status = status.await(),
        allowed = allowedRoot.items("data").map {
            TelegramAllowedUser(it.str("id") ?: "", it.str("telegram_user_id") ?: "—", it.str("label"), it.bool("can_finance") ?: true)
        },
        allowedTableExists = allowedRoot.bool("tableExists") ?: true,
        staff = staff.await().items("data").map {
            TelegramStaffLink(it.str("id") ?: "", it.str("full_name") ?: "Без имени", it.str("role"), it.str("telegram_chat_id"), it.bool("is_active") ?: true)
        },
    )
}

/** Telegram: работает ли канал, кто получает сообщения бота, кто ещё не подключён. Только чтение. */
@Composable
fun TelegramScreen() {
    val loader = rememberOpsFetcher("telegram", ::loadTelegram)
    val state by loader.state.collectAsStateWithLifecycle()
    StateContainer(state = state, onRetry = loader::refresh, emptyIcon = Icons.Filled.Forum) { data ->
        val status = data.status
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            item {
                HeroCard {
                    Text(if (status.isOperational) "Канал работает" else "Канал настроен не полностью", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    Text(status.botName ?: "Бот не подключён", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(if (status.isOperational) "на связи" else "требует настройки", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        HeroFact("Токен", if (status.hasToken) "задан" else "не задан")
                        HeroFact("Вебхук", if (status.webhookConfigured == true) "есть" else "нет")
                    }
                }
            }
            item {
                val c = Orda.colors
                OpsSection("Настройка канала") {
                    StatusRow(Icons.Filled.Key, "Токен бота", if (status.hasToken) "задан" else "не задан", if (status.hasToken) c.positive else c.negative)
                    StatusRow(Icons.Filled.Forum, "Общий чат", if (status.hasChatId) "задан" else "не задан", if (status.hasChatId) c.positive else c.warning)
                    val hook = status.webhookConfigured == true
                    StatusRow(Icons.Filled.AltRoute, "Вебхук", if (hook) "зарегистрирован" else "не зарегистрирован", if (hook) c.positive else c.negative)
                    StatusRow(Icons.Filled.Lock, "Секрет вебхука", if (status.hasWebhookSecret) "задан" else "не задан", if (status.hasWebhookSecret) c.positive else c.warning)
                    if (status.pendingUpdates > 0) StatusRow(Icons.Filled.Warning, "Необработанных сообщений", "${status.pendingUpdates}", c.warning)
                    status.lastError?.takeIf { it.isNotEmpty() }?.let { message ->
                        OpsRow(Icons.Filled.Close, c.negative, "Последняя ошибка", subtitle = listOfNotNull(message, status.lastErrorAt?.let { dateTimeLabel(it.toString()) }).joinToString("\n"))
                    }
                }
            }
            item {
                OpsSection("Получатели бота", count = if (data.allowed.isNotEmpty()) "${data.allowed.size}" else null) {
                    when {
                        !data.allowedTableExists -> Text("Список получателей ещё не развёрнут в базе", color = Orda.colors.warning)
                        data.allowed.isEmpty() -> Text("Получателей нет", color = Orda.colors.textDim)
                        else -> data.allowed.forEach { u ->
                            OpsRow(
                                Icons.Filled.Person, Orda.colors.brand, u.displayName, subtitle = u.telegramUserId,
                                trailing = if (u.canFinance) "финансы" else "без финансов", trailingColor = if (u.canFinance) Orda.colors.info else Orda.colors.textDim,
                            )
                        }
                    }
                }
            }
            item {
                OpsSection("Без Telegram", count = "подключено ${data.staff.count { it.isLinked }} из ${data.staff.size}") {
                    if (data.unlinkedStaff.isEmpty()) Text("Все работающие сотрудники подключены", color = Orda.colors.positive)
                    data.unlinkedStaff.forEach { m -> OpsRow(Icons.Filled.Person, Orda.colors.brand, m.fullName, trailing = m.role, trailingColor = Orda.colors.textDim) }
                }
            }
        }
    }
}

/** Диагностика: миграции базы и регулярные задачи; главная цифра — сколько проверок прошло. */
@Composable
fun DiagnosticsScreen() {
    val loader = rememberOpsLoader("diagnostics", ApiRequest("/api/admin/debug/system")) { root -> SystemDiagnostics.parse(root.obj("data") ?: root) }
    val state by loader.state.collectAsStateWithLifecycle()
    StateContainer(state = state, onRetry = loader::refresh, emptyIcon = Icons.Filled.MonitorHeart) { data ->
        val checks = data.checks()
        val failures = checks.count { it.state == DiagnosticCheck.State.Failure }
        val warnings = checks.count { it.state == DiagnosticCheck.State.Warning }
        val ok = checks.count { it.state == DiagnosticCheck.State.Ok }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            item {
                HeroCard {
                    Text(if (failures > 0) "Есть сбои" else if (warnings > 0) "Есть предупреждения" else "Всё в порядке", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    Text("$ok из ${checks.size}", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text("проверок пройдено", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        HeroFact("Предупреждений", "$warnings")
                        HeroFact("Сбоев", "$failures")
                    }
                }
            }
            item {
                OpsSection("Проверки", count = "всего ${checks.size}") {
                    if (checks.isEmpty()) Text("Сервер не вернул ни одной проверки", color = Orda.colors.textDim)
                    checks.forEach { check ->
                        val c = Orda.colors
                        val (color, icon, label) = when (check.state) {
                            DiagnosticCheck.State.Ok -> Triple(c.positive, Icons.Filled.Check, "ок")
                            DiagnosticCheck.State.Warning -> Triple(c.warning, Icons.Filled.Warning, "внимание")
                            DiagnosticCheck.State.Failure -> Triple(c.negative, Icons.Filled.Close, "сбой")
                            DiagnosticCheck.State.Unknown -> Triple(c.textDim, Icons.Filled.HelpOutline, "неизвестно")
                        }
                        OpsRow(icon, color, check.title, subtitle = check.detail, trailing = label, trailingColor = color)
                    }
                }
            }
        }
    }
}
