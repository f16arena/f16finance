package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBusiness
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.NativePage
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// Действия «Настроек»: закрыть и открыть точку, зоны выручки точки, праздники и
// поздравления команды. Запросы сайта: `POST /api/admin/settings`
// (`entity: company`, `close | reopen | zones | saveZones`) и
// `GET/PATCH /api/admin/org-settings`. Мастер новой точки многошаговый — он на сайте.

private const val SETTINGS = "/api/admin/settings"
private const val ORG_SETTINGS = "/api/admin/org-settings"

// ── Закрыть и открыть точку ──────────────────────────────────────────────────

/** Закрыть или открыть точку может только владелец; сервер проверяет это сам, кнопку прячем заранее. */
fun canCloseOpenPoints(staffRole: String?, isSuperAdmin: Boolean): Boolean = isSuperAdmin || staffRole == "owner"

/** Закрытие: история остаётся в отчётах, точка уходит из выбора, касса перестаёт работать, слот лимита освобождается. */
fun closePointBody(companyId: String, reason: String?): JsonObject = buildJsonObject {
    put("entity", "company"); put("action", "close"); put("id", companyId)
    if (!reason.isNullOrBlank()) put("reason", reason.trim().take(300))
}

fun reopenPointBody(companyId: String): JsonObject = buildJsonObject {
    put("entity", "company"); put("action", "reopen"); put("id", companyId)
}

// ── Зоны выручки ─────────────────────────────────────────────────────────────

const val MaxRevenueZones = 12

/** Зона выручки точки. Ключ пишется в доходы и не меняется; меняется только подпись и признак «включена». */
data class RevenueZone(val key: String, val label: String, val active: Boolean)

/** Выручка зоны за 30 дней и дата последней записи — по ним видно «мёртвую» зону. */
data class ZoneStat(val total30: Double, val lastDate: String?)

data class ZonesData(val zones: List<RevenueZone>, val stats: Map<String, ZoneStat>) {
    companion object {
        fun parse(root: JsonObject): ZonesData {
            val zones = root.arr("zones").map { RevenueZone(it.str("key") ?: "", it.str("label", "key") ?: "Зона", it.bool("active") ?: true) }.filter { it.key.isNotEmpty() }
            val stats = root.obj("stats")?.let { s ->
                s.keys.associateWith { k -> s.obj(k).let { o -> ZoneStat(o?.num("total30") ?: 0.0, o?.str("lastDate")) } }
            }.orEmpty()
            return ZonesData(zones, stats)
        }
    }
}

fun zonesRequestBody(companyId: String): JsonObject = buildJsonObject {
    put("entity", "company"); put("action", "zones"); put("id", companyId)
}

/** Новая зона уходит с пустым ключом: сервер сам выдаст его из названия. */
fun saveZonesBody(companyId: String, zones: List<RevenueZone>): JsonObject = buildJsonObject {
    put("entity", "company"); put("action", "saveZones"); put("id", companyId)
    put("zones", buildJsonArray {
        zones.forEach { z -> add(buildJsonObject { put("key", z.key); put("label", z.label.trim()); put("active", z.active) }) }
    })
}

/** Проверка до отправки — те же правила, что `sanitizeZones` на сервере. */
fun zonesProblem(zones: List<RevenueZone>): String? {
    if (zones.size > MaxRevenueZones) return "Не больше $MaxRevenueZones зон на точку"
    val seen = HashSet<String>()
    for (z in zones) {
        val label = z.label.trim().replace(Regex("\\s+"), " ")
        if (label.isEmpty()) return "У зоны должно быть название"
        if (!seen.add(label.lowercase())) return "Зона «$label» указана дважды"
    }
    return null
}

// ── Праздники и поздравления ─────────────────────────────────────────────────

/** Праздник из казахстанского списка (`lib/core/holidays.ts`); `{name}` в тексте заменяется именем сотрудника. */
data class Holiday(val key: String, val month: Int, val day: Int, val name: String, val greeting: String, val note: String? = null)

val KzHolidays = listOf(
    Holiday("new_year", 1, 1, "Новый год", "С Новым годом, {name}! 🎄\n\nПусть этот год принесёт здоровье, успех и много добрых моментов. Спасибо что ты с нами!"),
    Holiday("new_year_2", 1, 2, "Новый год (2-й день)", "Продолжаем праздник! 🎉 С новогодними каникулами, {name}! Отдыхай как следует."),
    Holiday("women_day", 3, 8, "Международный женский день", "С 8 Марта, {name}! 💐\n\nСпасибо за тепло, заботу и труд. Пусть весна принесёт радость. Ты прекрасна!"),
    Holiday("nauryz", 3, 21, "Наурыз", "Наурыз құтты болсын, {name}! 🌷\n\nЖаңа жыл жаңа сәттіліктер әкелсін! Здоровья тебе и твоим близким."),
    Holiday("nauryz_2", 3, 22, "Наурыз (2-й день)", "Наурыз мейрамын құттықтаймыз, {name}! 🌷 Берекелі күндер тілейміз."),
    Holiday("nauryz_3", 3, 23, "Наурыз (3-й день)", "Наурыз көрісу күні құтты болсын, {name}! 🌷"),
    Holiday("unity_day", 5, 1, "Праздник единства народа Казахстана", "С праздником единства народа Казахстана, {name}! 🤝\n\nМы — одна команда, одна семья. Мира и согласия!"),
    Holiday("defender_day", 5, 7, "День защитника Отечества", "С Днём защитника Отечества, {name}! 🛡 Спасибо за службу всем кто защищал и защищает."),
    Holiday("victory_day", 5, 9, "День Победы", "С Днём Победы, {name}! 🌹\n\nПомним подвиг тех, кто отстоял мир. Низкий поклон ветеранам."),
    Holiday("capital_day", 7, 6, "День Столицы", "С Днём Столицы, {name}! 🇰🇿\n\nАстана — наша гордость. Желаю тебе процветания и здоровья!"),
    Holiday("constitution_day", 8, 30, "День Конституции", "С Днём Конституции, {name}! 🇰🇿\n\nС праздником, желаю мира и стабильности."),
    Holiday("republic_day", 10, 25, "Республика күні", "Республика күні құтты болсын, {name}! 🇰🇿\n\nБейбітшілік пен берекелі тірлік тілеймін."),
    Holiday("first_president_day", 12, 1, "День Первого Президента", "С Днём Первого Президента, {name}! 🌟", "с 2022 года не государственный праздник"),
    Holiday("independence_day", 12, 16, "День Независимости", "С Днём Независимости, {name}! 🇰🇿\n\nПусть мир, процветание и независимость всегда будут с нами. Гордимся нашей страной!"),
    Holiday("independence_day_2", 12, 17, "День Независимости (2-й день)", "С праздником Независимости, {name}! 🇰🇿", "с 2022 года не выходной"),
)

private val MonthsGenitive = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

fun holidayDate(month: Int, day: Int): String = "$day ${MonthsGenitive.getOrElse(month - 1) { "" }}"

data class CustomHoliday(val id: String, val month: Int, val day: Int, val name: String, val greeting: String)

/** Настройки поздравлений организации (`organizations.settings.holidays`). По умолчанию — прежний список и тексты. */
data class HolidaySettings(
    val enabled: Boolean = true,
    val disabled: Set<String> = emptySet(),
    val greetings: Map<String, String> = emptyMap(),
    val custom: List<CustomHoliday> = emptyList(),
) {
    fun isOn(key: String): Boolean = key !in disabled
    fun toggled(key: String): HolidaySettings = copy(disabled = if (key in disabled) disabled - key else disabled + key)
    fun withGreeting(key: String, text: String?): HolidaySettings =
        copy(greetings = if (text.isNullOrBlank()) greetings - key else greetings + (key to text.trim().take(600)))

    /** Своя дата: `yyyy-MM-dd` из выбора, год отбрасывается. Пустое имя или дата — не добавляем. */
    fun withCustom(isoDate: String, name: String, id: String): HolidaySettings {
        val day = parseDay(isoDate) ?: return this
        if (name.isBlank()) return this
        return copy(custom = custom + CustomHoliday(id, day.monthValue, day.dayOfMonth, name.trim().take(80), ""))
    }

    fun withoutCustom(id: String): HolidaySettings = copy(custom = custom.filter { it.id != id })

    /** Тело сохранения; пустой текст своего праздника сервер заменит стандартным. */
    fun body(): JsonObject = buildJsonObject {
        put("holidays", buildJsonObject {
            put("enabled", enabled)
            put("disabled", buildJsonArray { disabled.sorted().forEach { add(JsonPrimitive(it)) } })
            put("greetings", buildJsonObject { greetings.forEach { (k, v) -> put(k, v) } })
            put("custom", buildJsonArray {
                custom.forEach { c ->
                    add(buildJsonObject { put("id", c.id); put("month", c.month); put("day", c.day); put("name", c.name); put("greeting", c.greeting) })
                }
            })
        })
    }

    companion object {
        /** Ответ `GET /api/admin/org-settings`: `{ settings: { holidays: {…} } }`. Нет блока — значения по умолчанию. */
        fun parse(root: JsonObject): HolidaySettings {
            val h = root.obj("settings")?.obj("holidays") ?: return HolidaySettings()
            val known = KzHolidays.map { it.key }.toSet()
            return HolidaySettings(
                enabled = h.bool("enabled") ?: true,
                disabled = (h["disabled"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.filter { it in known }?.toSet().orEmpty(),
                greetings = h.obj("greetings")?.let { g -> g.keys.mapNotNull { k -> g.str(k)?.takeIf { k in known }?.let { k to it } }.toMap() }.orEmpty(),
                custom = h.arr("custom").mapNotNull { c ->
                    val month = c.int("month") ?: return@mapNotNull null
                    val day = c.int("day") ?: return@mapNotNull null
                    CustomHoliday(c.str("id") ?: "c$month-$day", month, day, c.str("name") ?: return@mapNotNull null, c.str("greeting") ?: "")
                },
            )
        }
    }
}

// ── Окна ─────────────────────────────────────────────────────────────────────

/** Что можно сделать с точкой: зоны выручки, закрыть или открыть. */
@Composable
fun PointActionsSheet(company: SettingsCompany, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val session = env.resolver.session
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var zones by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val canManage = env.can("settings.manage_companies")
    val canCloseOpen = canCloseOpenPoints(session.staffRole, session.isSuperAdmin)
    val c = Orda.colors
    OrdaSheet(company.name, onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        Text(
            if (company.isClosed) "Точка закрыта: история в отчётах, касса не работает, слот лимита свободен." else "Точка работает.",
            color = c.textMuted, fontSize = 14.sp,
        )
        if (canManage) OpsRow(Icons.Filled.Tune, c.brand, "Зоны выручки", subtitle = "PS5, VR, бильярд — по чему видеть выручку отдельно", onClick = { zones = true })
        if (canCloseOpen && !company.isClosed) {
            if (!closing) OpsRow(Icons.Filled.Lock, c.negative, "Закрыть точку", subtitle = "Обратимо: историю не теряем", onClick = { closing = true })
            else {
                OpsField("Причина", reason, { reason = it }, placeholder = "Необязательно", minLines = 2)
                PrimaryButton("Закрыть точку", Modifier.fillMaxWidth(), loading = submit.busy) {
                    submit.run(
                        { env.api.sendUnit(ApiRequest.json(SETTINGS, closePointBody(company.id, reason))) },
                        { haptics.success(); onChanged(); onDismiss() },
                    )
                }
            }
        }
        if (canCloseOpen && company.isClosed) {
            OpsRow(
                Icons.Filled.LockOpen, c.positive, "Открыть точку", subtitle = "Займёт место в лимите точек",
                onClick = {
                    submit.run(
                        { env.api.sendUnit(ApiRequest.json(SETTINGS, reopenPointBody(company.id))) },
                        { haptics.success(); onChanged(); onDismiss() },
                    )
                },
            )
        }
        if (!canManage && !canCloseOpen) Text("Менять точки может владелец.", color = c.textDim, fontSize = 13.sp)
    }
    if (zones) ZonesSheet(company, onDismiss = { zones = false })
}

@Composable
private fun ZonesSheet(company: SettingsCompany, onDismiss: () -> Unit) {
    val env = LocalBusiness.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    val draft: SnapshotStateList<RevenueZone> = remember { mutableStateListOf() }
    var stats by remember { mutableStateOf<Map<String, ZoneStat>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(company.id) {
        runCatching { ZonesData.parse(env.api.send<JsonObject>(ApiRequest.json(SETTINGS, zonesRequestBody(company.id)))) }
            .onSuccess { draft.clear(); draft.addAll(it.zones); stats = it.stats; loaded = true }
            .onFailure { loadError = it.message ?: "Не удалось загрузить зоны" }
    }
    val c = Orda.colors
    val problem = zonesProblem(draft.toList())
    OrdaSheet("Зоны выручки: ${company.name}", onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        loadError?.let { ErrorBanner(it) }
        if (!loaded && loadError == null) Text("Загружаем…", color = c.textDim)
        if (loaded) {
            Text(
                "Зону не удаляют, а выключают: выручка по ней остаётся в отчётах. Выключенная зона скрыта из формы прихода.",
                color = c.textDim, fontSize = 13.sp,
            )
            draft.forEachIndexed { i, z ->
                val s = stats[z.key]
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpsField("Название", z.label, { draft[i] = z.copy(label = it) }, singleLine = true)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (s == null) "записей за год нет" else "за 30 дней ${money(s.total30)}" + (s.lastDate?.let { " · последняя запись ${shortDay(it)}" } ?: ""),
                            color = c.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                    }
                    OpsSwitchRow("Включена", z.active) { draft[i] = z.copy(active = it) }
                }
            }
            if (draft.size < MaxRevenueZones) {
                OpsRow(Icons.Filled.AddBusiness, c.brand, "Добавить зону", onClick = { draft.add(RevenueZone("", "", true)) })
            }
            problem?.let { if (draft.any { z -> z.label.isNotBlank() } || draft.size > MaxRevenueZones) Text(it, color = c.warning, fontSize = 13.sp) }
            PrimaryButton("Сохранить", Modifier.fillMaxWidth(), enabled = problem == null, loading = submit.busy) {
                submit.run(
                    { env.api.sendUnit(ApiRequest.json(SETTINGS, saveZonesBody(company.id, draft.toList()))) },
                    { haptics.success(); onDismiss() },
                )
            }
        }
    }
}

/** Карточка в «Настройках»: праздники команды и кнопка мастера новой точки (он на сайте). */
@Composable
fun SettingsExtras(canAddPoint: Boolean) {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val session = env.resolver.session
    val isOwner = canCloseOpenPoints(session.staffRole, session.isSuperAdmin)
    var holidays by remember { mutableStateOf(false) }
    OpsSection("Ещё") {
        OpsRow(Icons.Filled.Celebration, Orda.colors.accent, "Праздники и поздравления", subtitle = "Бот поздравляет команду в Telegram в 9:00", onClick = { holidays = true })
        if (canAddPoint) {
            OpsRow(
                Icons.Filled.AddBusiness, Orda.colors.brand, "Новая точка",
                subtitle = "Мастер из нескольких шагов — откроется на сайте", onClick = { NativePage.openWeb(context, "/settings/new-point") },
            )
        }
    }
    if (holidays) HolidaysSheet(canEdit = isOwner, onDismiss = { holidays = false })
}

@Composable
private fun HolidaysSheet(canEdit: Boolean, onDismiss: () -> Unit) {
    val env = LocalBusiness.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var settings by remember { mutableStateOf(HolidaySettings()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var newDate by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching { HolidaySettings.parse(env.api.send<JsonObject>(ApiRequest(ORG_SETTINGS))) }
            .onSuccess { settings = it; loaded = true }
            .onFailure { loadError = it.message ?: "Не удалось загрузить настройки" }
    }
    /** Сохраняем сразу после каждой правки, как на сайте; в окне остаётся то, что принял сервер. */
    fun save(next: HolidaySettings) {
        submit.run(
            { env.api.sendUnit(ApiRequest.json(ORG_SETTINGS, next.body(), HttpMethod.PATCH)) },
            { settings = next; haptics.success() },
        )
    }
    val c = Orda.colors
    OrdaSheet("Праздники и поздравления", onDismiss) {
        submit.error?.let { ErrorBanner(it) }
        loadError?.let { ErrorBanner(it) }
        if (!loaded && loadError == null) Text("Загружаем…", color = c.textDim)
        if (loaded) {
            Text("В тексте {name} заменяется именем сотрудника.", color = c.textDim, fontSize = 13.sp)
            OpsSwitchRow("Поздравлять команду", settings.enabled, enabled = canEdit && !submit.busy) { save(settings.copy(enabled = it)) }
            KzHolidays.forEach { h ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OpsSwitchRow(
                        "${h.name} · ${holidayDate(h.month, h.day)}", settings.isOn(h.key),
                        subtitle = h.note ?: settings.greetings[h.key]?.let { "свой текст" },
                        enabled = canEdit && settings.enabled && !submit.busy,
                    ) { save(settings.toggled(h.key)) }
                    if (canEdit) {
                        if (editing == h.key) {
                            OpsField("Текст поздравления", text, { text = it }, minLines = 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                PrimaryButton("Сохранить текст", enabled = text.isNotBlank()) { editing = null; save(settings.withGreeting(h.key, text)) }
                                if (settings.greetings[h.key] != null) {
                                    PrimaryButton("Стандартный") { editing = null; save(settings.withGreeting(h.key, null)) }
                                }
                            }
                        } else {
                            Text(
                                if (settings.greetings[h.key] != null) "изменить свой текст" else "изменить текст", color = c.info, fontSize = 13.sp,
                                modifier = Modifier.padding(start = Spacing.xs).clickable { editing = h.key; text = settings.greetings[h.key] ?: h.greeting },
                            )
                        }
                    }
                }
            }
            if (settings.custom.isNotEmpty()) Text("Свои даты", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            settings.custom.forEach { h ->
                OpsRow(
                    Icons.Filled.Celebration, c.accent, h.name, subtitle = holidayDate(h.month, h.day),
                    trailing = if (canEdit) "Убрать" else null, trailingColor = c.negative,
                    onClick = if (canEdit) ({ save(settings.withoutCustom(h.id)) }) else null,
                )
            }
            if (canEdit) {
                Text("Добавить свой праздник", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                OpsDateField("Дата", newDate, { newDate = it })
                OpsField("Название", newName, { newName = it }, placeholder = "День рождения клуба", singleLine = true)
                PrimaryButton("Добавить", Modifier.fillMaxWidth(), enabled = newDate.isNotEmpty() && newName.isNotBlank()) {
                    save(settings.withCustom(newDate, newName, "c" + System.currentTimeMillis().toString(36)))
                    newDate = ""; newName = ""
                }
            } else {
                Text("Менять праздники может владелец.", color = c.textDim, fontSize = 12.sp)
            }
        }
    }
}

