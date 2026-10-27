package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Модели раздела «Команда». Разбор вручную по JsonObject, терпимый, как
// `decodeFlexible*` в OrdaKit: число пришло строкой, `operator_profiles` — то
// объектом, то массивом, ключа нет вовсе — экран не падает.

// ── Терпимое чтение JSON ─────────────────────────────────────────────────────

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/** Элементы массива, которые являются объектами; не массив — пусто. */
internal fun JsonElement?.asObjects(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

/** Строка из примитива (число и булево тоже); пусто и null — null. */
internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.takeIf { it.isNotEmpty() }
}

internal fun JsonObject.dbl(key: String): Double =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull() ?: 0.0

internal fun JsonObject.dblOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toDoubleOrNull()

internal fun JsonObject.int(key: String): Int = dbl(key).toInt()

internal fun JsonObject.bool(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.let {
        when (it.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    } ?: default

/** Тело `data` из конверта `{ "data": … }`; без конверта — сам корень. */
internal fun JsonElement.unwrapData(): JsonElement = (this as? JsonObject)?.get("data") ?: this

// ── Недели ───────────────────────────────────────────────────────────────────

/**
 * Недельный цикл зарплаты и графика. Сервер отвергает произвольные даты, поэтому
 * неделя всегда выровнена на понедельник — как `DateRange.weekStart` в OrdaKit.
 */
object WeekMath {
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE

    fun monday(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    fun currentWeekStart(today: LocalDate = LocalDate.now()): String = monday(today).format(iso)

    /** Сдвиг понедельника на `weeks` недель; мусорная дата — текущая неделя. */
    fun shifted(weekStart: String, weeks: Int, today: LocalDate = LocalDate.now()): String {
        val base = parse(weekStart) ?: monday(today)
        return monday(base).plusWeeks(weeks.toLong()).format(iso)
    }

    fun parse(text: String?): LocalDate? =
        text?.take(10)?.let { runCatching { LocalDate.parse(it, iso) }.getOrNull() }

    fun format(date: LocalDate): String = date.format(iso)

    /** Дни недели от понедельника. */
    fun days(weekStart: String, today: LocalDate = LocalDate.now()): List<LocalDate> {
        val start = monday(parse(weekStart) ?: today)
        return (0L..6L).map { start.plusDays(it) }
    }

    fun isCurrentOrFuture(weekStart: String, today: LocalDate = LocalDate.now()): Boolean =
        !(parse(weekStart) ?: monday(today)).isBefore(monday(today))

    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    fun weekdayShort(date: LocalDate): String = weekdays[date.dayOfWeek.value - 1]

    /** «5 мая». */
    fun dayMonth(date: LocalDate): String = "${date.dayOfMonth} ${months[date.monthValue - 1]}"

    fun dayMonth(iso: String?): String = parse(iso)?.let(::dayMonth) ?: iso.orEmpty()

    /** «пн, 5 мая». */
    fun dayTitle(iso: String?): String =
        parse(iso)?.let { "${weekdayShort(it)}, ${dayMonth(it)}" } ?: iso.orEmpty()

    /** «5 мая — 11 мая». */
    fun rangeTitle(weekStart: String): String {
        val days = days(weekStart)
        return "${dayMonth(days.first())} — ${dayMonth(days.last())}"
    }

    /** «5 мая 2026, 14:30» из ISO-времени сервера; не разобралось — как есть. */
    fun dateTime(text: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (text.isNullOrEmpty()) return ""
        val instant = runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(text) }.getOrNull()
            ?: return dayMonth(text)
        val local = instant.atZone(zone)
        return "${dayMonth(local.toLocalDate())}, %02d:%02d".format(local.hour, local.minute)
    }

    /** Сколько часов прошло с момента ISO-времени; null, если не разобрать. */
    fun hoursSince(text: String?, now: java.time.Instant = java.time.Instant.now()): Long? {
        if (text.isNullOrEmpty()) return null
        val instant = runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(text) }.getOrNull()
            ?: return null
        return ChronoUnit.HOURS.between(instant, now)
    }

    fun sameMonth(isoDate: String?, now: YearMonth = YearMonth.now()): Boolean =
        parse(isoDate)?.let { YearMonth.from(it) == now } ?: false
}

/** Склонение: 1 смена, 2 смены, 5 смен. */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

/** «+1 200 ₸» / «−300 ₸». */
internal fun signedMoney(value: Double): String {
    val text = kz.ordaops.android.ui.components.money(abs(value))
    return when {
        value > 0.5 -> "+$text"
        value < -0.5 -> "−$text"
        else -> text
    }
}

// ── Операторы: GET /api/admin/operators ──────────────────────────────────────

data class OperatorStats(
    val totalShifts: Int = 0,
    val totalTurnover: Double = 0.0,
    val avgPerShift: Double = 0.0,
    val totalDebts: Double = 0.0,
    val totalBonuses: Double = 0.0,
)

data class TeamOperator(
    val id: String,
    val name: String,
    val shortName: String?,
    val isActive: Boolean,
    val role: String,
    val fullName: String?,
    val phone: String?,
    val position: String?,
    val photoUrl: String?,
    val hireDate: String?,
    val username: String?,
    val lastLogin: String?,
    /** Учётная запись входа: сброс пароля работает с пользователем, а не с карточкой. */
    val authUserId: String?,
    val telegramChatId: String?,
    /** Без карточки сотрудника система теряет человека: смены без хозяина, регламенты не пишутся. */
    val hasStaffLink: Boolean,
    /**
     * Операторская учётка администрации (Олжас, Магомедов…): нужна только для долгов
     * кассы. Её не увольняем и не закрываем — иначе теряются долги.
     */
    val isAdminStaff: Boolean = false,
    val stats: OperatorStats,
) {
    val hasTelegram: Boolean get() = !telegramChatId.isNullOrEmpty()

    /** Полное имя из профиля, если есть: в `name` часто служебное «Алия К.». */
    val displayName: String get() = fullName?.trim()?.takeIf { it.isNotEmpty() } ?: name

    companion object {
        fun from(o: JsonObject): TeamOperator {
            // `operator_profiles` приходит объектом или массивом — Supabase решает по схеме.
            val profile = when (val p = o["operator_profiles"]) {
                is JsonObject -> p
                is JsonArray -> p.firstOrNull() as? JsonObject
                else -> null
            }
            val auth = o["auth"].asObject()
            val stats = o["stats"].asObject()
            val chat = o.str("telegram_chat_id")
            return TeamOperator(
                id = o.str("id") ?: "",
                name = o.str("name") ?: "Оператор",
                shortName = o.str("short_name"),
                isActive = o.bool("is_active", true),
                role = o.str("role") ?: "operator",
                fullName = profile?.str("full_name"),
                phone = profile?.str("phone"),
                position = profile?.str("position"),
                photoUrl = profile?.str("photo_url"),
                hireDate = profile?.str("hire_date"),
                username = auth?.str("username"),
                lastLogin = auth?.str("last_login"),
                authUserId = auth?.str("user_id"),
                telegramChatId = chat,
                // Старые сборки сервера флага не присылают: считаем, что связка есть.
                hasStaffLink = o.bool("has_staff_link", true),
                isAdminStaff = o.bool("is_admin_staff", false),
                stats = OperatorStats(
                    totalShifts = stats?.int("totalShifts") ?: 0,
                    totalTurnover = stats?.dbl("totalTurnover") ?: 0.0,
                    avgPerShift = stats?.dbl("avgPerShift") ?: 0.0,
                    totalDebts = stats?.dbl("totalDebts") ?: 0.0,
                    totalBonuses = stats?.dbl("totalBonuses") ?: 0.0,
                ),
            )
        }

        fun list(root: JsonElement): List<TeamOperator> =
            root.unwrapData().asObjects().map(::from).filter { it.id.isNotEmpty() }
    }
}

/**
 * Порядок списка операторов: действующие выше уволенных, дальше по обороту —
 * владелец заходит понять, кто вытягивает выручку.
 */
fun List<TeamOperator>.sortedForRoster(): List<TeamOperator> =
    sortedWith(compareByDescending<TeamOperator> { it.isActive }.thenByDescending { it.stats.totalTurnover })

/** Карточка оператора для правки: `GET /api/admin/operators/profile`. */
data class OperatorCard(
    val operatorId: String,
    val name: String,
    val shortName: String,
    val telegramChatId: String,
    val fullName: String,
    val phone: String,
    val email: String,
    val position: String,
    val hireDate: String,
    val birthDate: String,
    val address: String,
    val photoUrl: String,
    val emergencyName: String,
    val emergencyPhone: String,
    val notes: String,
) {
    companion object {
        fun from(root: JsonElement): OperatorCard {
            val data = root.unwrapData().asObject()
            val op = data?.get("operator").asObject()
            val p = data?.get("profile").asObject()
            return OperatorCard(
                operatorId = op?.str("id") ?: "",
                name = op?.str("name") ?: "",
                shortName = op?.str("short_name") ?: "",
                telegramChatId = op?.str("telegram_chat_id") ?: "",
                fullName = p?.str("full_name") ?: "",
                phone = p?.str("phone") ?: "",
                email = p?.str("email") ?: "",
                position = p?.str("position") ?: "",
                hireDate = p?.str("hire_date") ?: "",
                birthDate = p?.str("birth_date") ?: "",
                address = p?.str("address") ?: "",
                photoUrl = p?.str("photo_url") ?: "",
                emergencyName = p?.str("emergency_contact_name") ?: "",
                emergencyPhone = p?.str("emergency_contact_phone") ?: "",
                notes = p?.str("notes") ?: "",
            )
        }
    }
}

// ── Оформление оператора ─────────────────────────────────────────────────────

/** Логин из имени и проверка логина — порт `OperatorUsername`. */
object OperatorUsername {
    private val map = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** «Алия Каримова» → `aliya.karimova`. */
    fun suggestion(name: String): String {
        val sb = StringBuilder()
        for (ch in name.lowercase()) {
            val replacement = map[ch]
            when {
                replacement != null -> sb.append(replacement)
                ch.isLetterOrDigit() -> sb.append(ch)
                ch == ' ' || ch == '-' -> sb.append('.')
            }
        }
        var result = sb.toString()
        while (result.endsWith(".")) result = result.dropLast(1)
        while (result.contains("..")) result = result.replace("..", ".")
        return result
    }

    fun validationMessage(username: String): String? = when {
        username.length < 3 -> "Логин короче трёх символов"
        username.contains(' ') -> "В логине не бывает пробелов"
        username.any { !(it.code < 128 && (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-')) } ->
            "Только латиница, цифры, точка и дефис"
        else -> null
    }
}

/** Проверки форм: почта и телефон — как `MyProfileChange` в OrdaKit. */
object FormChecks {
    fun isEmailValid(email: String): Boolean {
        val parts = email.split('@')
        if (parts.size != 2 || parts[0].isEmpty()) return false
        val domain = parts[1]
        return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
    }

    fun isPhoneValid(phone: String): Boolean = phone.count { it.isDigit() } >= 10

    /** Новый оператор: что мешает оформить, или null. */
    fun operatorProblem(name: String, phone: String, email: String): String? = when {
        name.isBlank() -> "Имя обязательно"
        email.isNotEmpty() && !isEmailValid(email) -> "Проверьте почту"
        phone.isNotEmpty() && !isPhoneValid(phone) -> "Проверьте телефон"
        else -> null
    }

    /** Разбор суммы из поля: запятая как точка, пробелы не мешают, мусор — ноль. */
    fun amount(text: String): Double =
        text.replace(',', '.').replace(" ", "").replace(" ", "").trim().toDoubleOrNull() ?: 0.0
}

// ── Зарплата операторов: GET /api/admin/salary?view=weekly ───────────────────

data class SalaryPayment(
    val id: String,
    val date: String,
    val total: Double,
    val cash: Double,
    val kaspi: Double,
    val comment: String?,
    val status: String,
) {
    /** Отменённые выплаты сервер продолжает отдавать — в истории видны, откатывать нечего. */
    val isActive: Boolean get() = status != "voided"
}

data class SalaryAdjustment(
    val id: String,
    val date: String,
    val amount: Double,
    val kind: String,
    val comment: String?,
    val companyId: String?,
    val status: String,
) {
    val isActive: Boolean get() = status != "voided"

    val kindLabel: String
        get() = when (kind) {
            "bonus" -> "Премия"
            "fine" -> "Штраф"
            "debt" -> "Долг"
            "advance" -> "Аванс"
            else -> "Корректировка"
        }

    /** Премия прибавляет к зарплате, остальное вычитается. */
    val isAddition: Boolean get() = kind == "bonus"
}

data class SalaryWeek(
    val gross: Double,
    val bonus: Double,
    val fine: Double,
    val debt: Double,
    val advance: Double,
    val net: Double,
    val paid: Double,
    val remaining: Double,
    val status: String,
    val shifts: Int,
    val payments: List<SalaryPayment>,
    val adjustments: List<SalaryAdjustment>,
) {
    val statusLabel: String
        get() = when (status) {
            "paid" -> "Выплачено"
            "partial" -> "Частично"
            else -> "Не выплачено"
        }
}

data class SalaryRow(
    val operatorId: String,
    val operatorName: String,
    val isActive: Boolean,
    val week: SalaryWeek,
    val hasActivity: Boolean,
) {
    /** Подпись статуса под суммой: «осталось 5 000 ₸», «выплачено», «не выплачено». */
    val statusText: String
        get() = if (week.status == "partial" && week.remaining > 0.01) {
            "осталось ${kz.ordaops.android.ui.components.money(week.remaining)}"
        } else {
            week.statusLabel.lowercase()
        }

    companion object {
        fun from(o: JsonObject): SalaryRow {
            val op = o["operator"].asObject()
            val w = o["week"].asObject() ?: JsonObject(emptyMap())
            val full = op?.str("full_name")?.trim()
            return SalaryRow(
                operatorId = op?.str("id") ?: "",
                operatorName = full?.takeIf { it.isNotEmpty() } ?: op?.str("name") ?: "Оператор",
                isActive = op?.bool("is_active", true) ?: true,
                hasActivity = o.bool("hasActivity", false),
                week = SalaryWeek(
                    gross = w.dbl("grossAmount"),
                    bonus = w.dbl("bonusAmount"),
                    fine = w.dbl("fineAmount"),
                    debt = w.dbl("debtAmount"),
                    advance = w.dbl("advanceAmount"),
                    net = w.dbl("netAmount"),
                    paid = w.dbl("paidAmount"),
                    remaining = w.dbl("remainingAmount"),
                    status = w.str("status") ?: "draft",
                    shifts = w.int("shiftsCount"),
                    payments = w["payments"].asObjects().map {
                        SalaryPayment(
                            id = it.str("id") ?: "",
                            date = it.str("payment_date") ?: "",
                            total = it.dbl("total_amount"),
                            cash = it.dbl("cash_amount"),
                            kaspi = it.dbl("kaspi_amount"),
                            comment = it.str("comment"),
                            status = it.str("status") ?: "active",
                        )
                    },
                    adjustments = w["adjustments"].asObjects().map {
                        SalaryAdjustment(
                            id = it.str("id") ?: "",
                            date = it.str("date") ?: "",
                            amount = it.dbl("amount"),
                            kind = it.str("kind") ?: "",
                            comment = it.str("comment"),
                            companyId = it.str("companyId"),
                            status = it.str("status") ?: "active",
                        )
                    },
                ),
            )
        }
    }
}

data class SalaryTotals(
    val gross: Double = 0.0,
    val bonus: Double = 0.0,
    val fine: Double = 0.0,
    val debt: Double = 0.0,
    val net: Double = 0.0,
    val paid: Double = 0.0,
    val remaining: Double = 0.0,
    val paidOperators: Int = 0,
    val activeOperators: Int = 0,
) {
    /** Доля выплаченного, 0…1. */
    val paidRatio: Double get() = if (net > 0) (paid / net).coerceIn(0.0, 1.0) else 0.0
}

data class SalaryWeekReport(
    val weekStart: String,
    val weekEnd: String,
    val operators: List<SalaryRow>,
    val totals: SalaryTotals,
) {
    companion object {
        fun from(root: JsonElement): SalaryWeekReport {
            val d = root.unwrapData().asObject()
            val t = d?.get("totals").asObject()
            return SalaryWeekReport(
                weekStart = d?.str("weekStart") ?: "",
                weekEnd = d?.str("weekEnd") ?: "",
                operators = d?.get("operators").asObjects().map(SalaryRow::from),
                totals = SalaryTotals(
                    gross = t?.dbl("grossAmount") ?: 0.0,
                    bonus = t?.dbl("bonusAmount") ?: 0.0,
                    fine = t?.dbl("fineAmount") ?: 0.0,
                    debt = t?.dbl("debtAmount") ?: 0.0,
                    net = t?.dbl("netAmount") ?: 0.0,
                    paid = t?.dbl("paidAmount") ?: 0.0,
                    remaining = t?.dbl("remainingAmount") ?: 0.0,
                    paidOperators = t?.int("paidOperators") ?: 0,
                    activeOperators = t?.int("activeOperators") ?: 0,
                ),
            )
        }
    }
}

// ── Зарплата админ-состава: GET /api/admin/staff-salary?view=summary ─────────

data class ExtraDay(val date: String, val amount: Double)

data class StaffSalaryRow(
    val id: String,
    val name: String,
    val role: String?,
    val monthlySalary: Double,
    val half: Double,
    val bonuses: Double,
    val debts: Double,
    val fines: Double,
    val advances: Double,
    val toPay: Double,
    val paidThisMonth: Double,
    val monthClosed: Boolean,
    val paidSlots: List<String>,
    val extraDays: List<ExtraDay>,
    val isActive: Boolean,
    val dismissalDate: String?,
    val isMe: Boolean,
    /** `operator` — строка собрана из админ-оператора, оклада и записи в staff нет. */
    val sourceType: String,
) {
    val isFromOperator: Boolean get() = sourceType == "operator"

    /** Половина месяца, за которую ещё не платили; null — обе закрыты. */
    val openSlot: String?
        get() = when {
            // График выплат задаёт организация: в месяце может быть и одна выплата.
            monthClosed -> null
            "first" !in paidSlots -> "first"
            "second" !in paidSlots -> "second"
            else -> null
        }

    companion object {
        fun from(o: JsonObject): StaffSalaryRow {
            val full = o.str("name")?.trim()
            val short = o.str("short_name")?.trim()
            return StaffSalaryRow(
                id = o.str("id") ?: "",
                name = listOfNotNull(full, short).firstOrNull { it.isNotEmpty() } ?: "Сотрудник",
                role = o.str("role"),
                monthlySalary = o.dbl("monthly_salary"),
                half = o.dbl("half"),
                bonuses = o.dbl("bonuses"),
                debts = o.dbl("debts"),
                fines = o.dbl("fines"),
                advances = o.dbl("advances"),
                toPay = o.dbl("toPay"),
                paidThisMonth = o.dbl("paid_this_month"),
                monthClosed = o.bool("month_closed", false),
                paidSlots = (o["paid_slots"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
                extraDays = o["extra_days"].asObjects().map { ExtraDay(it.str("date") ?: "", it.dbl("amount")) },
                isActive = o.bool("is_active", true),
                dismissalDate = o.str("dismissal_date"),
                isMe = o.bool("is_me", false),
                sourceType = o.str("source_type") ?: "staff",
            )
        }
    }
}

data class StaffSalarySummary(
    val rows: List<StaffSalaryRow>,
    val toPayTotal: Double,
    val paidThisMonthTotal: Double,
    /** `first` — выплата до 15-го, `second` — после. */
    val slot: String,
    /** Границы текущего периода выплаты (`ГГГГ-ММ-ДД`): график выплат — настройка организации. */
    val periodFrom: String? = null,
    val periodTo: String? = null,
    /** Ответ урезан до своей строки: права смотреть чужие зарплаты нет. */
    val selfOnly: Boolean,
    /** null — сервер старой версии и про связь аккаунта не знает. */
    val meLinked: Boolean?,
) {
    val isFirstHalf: Boolean get() = slot == "first"

    /** «1–15 октября» по границам периода сервера; без них — по номеру половины. */
    val periodLabel: String
        get() {
            val from = WeekMath.parse(periodFrom)
            val to = WeekMath.parse(periodTo)
            if (from == null || to == null) return if (isFirstHalf) "первая половина месяца" else "вторая половина месяца"
            val month = WeekMath.dayMonth(to).substringAfter(' ')
            return "${from.dayOfMonth}–${to.dayOfMonth} $month"
        }

    /** Своя строка: помеченная `is_me` либо единственная при `self_only`. */
    val myRow: StaffSalaryRow? get() = rows.firstOrNull { it.isMe } ?: if (selfOnly) rows.firstOrNull() else null

    companion object {
        fun from(root: JsonElement): StaffSalarySummary {
            val d = root.unwrapData().asObject()
            val totals = d?.get("totals").asObject()
            return StaffSalarySummary(
                rows = d?.get("rows").asObjects().map(StaffSalaryRow::from),
                toPayTotal = totals?.dbl("toPay") ?: 0.0,
                paidThisMonthTotal = totals?.dbl("paidThisMonth") ?: 0.0,
                slot = d?.str("slot") ?: "first",
                periodFrom = d?.get("period").asObject()?.str("from"),
                periodTo = d?.get("period").asObject()?.str("to"),
                selfOnly = d?.bool("self_only", false) ?: false,
                meLinked = (d?.get("me_linked") as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let {
                    it.content.lowercase() == "true"
                },
            )
        }
    }
}

/** Строка расчёта: имя, сумма, знак. */
data class SalaryLine(val label: String, val value: Double, val sign: Sign) {
    enum class Sign { Plain, Plus, Minus, Total }
}

/** Расчёт недели оператора построчно: ноль штрафов — не новость, пустое не показываем. */
fun SalaryWeek.breakdown(): List<SalaryLine> = buildList {
    add(SalaryLine("Начислено за смены", gross, SalaryLine.Sign.Plain))
    if (bonus > 0) add(SalaryLine("Премии", bonus, SalaryLine.Sign.Plus))
    if (fine > 0) add(SalaryLine("Штрафы", fine, SalaryLine.Sign.Minus))
    if (debt > 0) add(SalaryLine("Долги", debt, SalaryLine.Sign.Minus))
    if (advance > 0) add(SalaryLine("Авансы", advance, SalaryLine.Sign.Minus))
    add(SalaryLine("К выплате", net, SalaryLine.Sign.Total))
    if (paid > 0) add(SalaryLine("Выплачено", paid, SalaryLine.Sign.Minus))
}

/** Расчёт половины месяца админ-сотрудника построчно. */
fun StaffSalaryRow.breakdown(): List<SalaryLine> = buildList {
    add(SalaryLine("Половина оклада", half, SalaryLine.Sign.Plain))
    if (bonuses > 0) add(SalaryLine("Премии", bonuses, SalaryLine.Sign.Plus))
    if (fines > 0) add(SalaryLine("Штрафы", fines, SalaryLine.Sign.Minus))
    if (debts > 0) add(SalaryLine("Долги", debts, SalaryLine.Sign.Minus))
    if (advances > 0) add(SalaryLine("Авансы", advances, SalaryLine.Sign.Minus))
    add(SalaryLine("К выплате", toPay, SalaryLine.Sign.Total))
    if (paidThisMonth > 0) add(SalaryLine("Выплачено за месяц", paidThisMonth, SalaryLine.Sign.Plain))
}

/** Название должности для подписи. */
fun roleTitle(role: String): String = when (role) {
    "owner" -> "Владелец"
    "manager" -> "Управляющий"
    "marketer" -> "Маркетолог"
    "accountant" -> "Бухгалтер"
    "senior_operator" -> "Старший оператор"
    "senior_cashier" -> "Старший кассир"
    "operator" -> "Оператор"
    "other" -> "Сотрудник"
    else -> role.replace('_', ' ')
}

// ── Сотрудники: GET /api/admin/staff ─────────────────────────────────────────

data class StaffMember(
    val id: String,
    val fullName: String,
    val role: String,
    val monthlySalary: Double,
    val isActive: Boolean,
    val phone: String?,
    val email: String?,
    /** Охват по точкам (`staff.company_ids`); `null` — все точки организации. */
    val companyIds: List<String>? = null,
) {
    val roleLabel: String
        get() = when (role) {
            "owner" -> "Владелец"
            "manager" -> "Управляющий"
            "accountant" -> "Бухгалтер"
            "other" -> "Сотрудник"
            else -> roleTitle(role)
        }
}

data class StaffPayment(val id: String, val staffId: String, val payDate: String?, val amount: Double, val comment: String?)

data class StaffList(val staff: List<StaffMember>, val payments: List<StaffPayment>) {
    /** Выплачено сотруднику за календарный месяц. */
    fun paidThisMonth(staffId: String, now: YearMonth = YearMonth.now()): Double =
        payments.filter { it.staffId == staffId && WeekMath.sameMonth(it.payDate, now) }.sumOf { it.amount }

    companion object {
        /** Ответ без конверта `data` — роут отдаёт поля в корне. */
        fun from(root: JsonElement): StaffList {
            val d = root.asObject()
            return StaffList(
                staff = d?.get("staff").asObjects().map {
                    StaffMember(
                        id = it.str("id") ?: "",
                        fullName = it.str("full_name") ?: "Сотрудник",
                        role = it.str("role") ?: "other",
                        monthlySalary = it.dbl("monthly_salary"),
                        isActive = it.bool("is_active", true),
                        phone = it.str("phone"),
                        email = it.str("email"),
                        companyIds = (it["company_ids"] as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content }?.takeIf { ids -> ids.isNotEmpty() },
                    )
                },
                payments = d?.get("payments").asObjects().map {
                    StaffPayment(
                        id = it.str("id") ?: "",
                        staffId = it.str("staff_id") ?: "",
                        payDate = it.str("pay_date"),
                        amount = it.dbl("amount"),
                        comment = it.str("comment"),
                    )
                },
            )
        }
    }
}

/** Действующие выше уволенных, дальше по окладу. */
fun List<StaffMember>.sortedForStaff(): List<StaffMember> =
    sortedWith(compareByDescending<StaffMember> { it.isActive }.thenByDescending { it.monthlySalary })

// ── График смен: GET /api/admin/shifts?includeSchedule=1 ─────────────────────

data class ScheduleCompany(val id: String, val name: String)

data class RosterShift(
    val id: String,
    val date: String,
    val operatorName: String,
    val shiftType: String,
    val companyId: String?,
) {
    val isNight: Boolean get() = shiftType.contains("night") || shiftType == "ночь"
}

data class ShiftIssue(
    val id: String,
    val companyId: String,
    val operatorName: String,
    val shiftDate: String,
    val shiftType: String,
    val status: String,
    val reason: String?,
    val leadAction: String?,
    val leadStatus: String?,
    val leadNote: String?,
    val leadOperatorName: String?,
    val replacementName: String?,
) {
    val isOpen: Boolean get() = status == "open" || status == "awaiting_reason"
    val isNight: Boolean get() = shiftType == "night"

    val proposalLabel: String?
        get() = when (leadAction) {
            "keep" -> "Старший: оставить смену"
            "remove" -> "Старший: снять со смены"
            "replace" -> replacementName?.let { "Старший: заменить на $it" } ?: "Старший: заменить"
            else -> null
        }
}

data class ShiftSchedule(
    val companies: List<ScheduleCompany>,
    val shifts: List<RosterShift>,
    val requests: List<ShiftIssue>,
) {
    val openRequests: List<ShiftIssue> get() = requests.filter { it.isOpen }

    fun shiftsOn(day: String, companyId: String?): List<RosterShift> =
        shifts.filter { it.date == day && (companyId == null || it.companyId == companyId) }

    /** Кого можно предложить на замену: люди из графика этой точки, кроме самого заявителя. */
    fun replacementCandidates(issue: ShiftIssue): List<String> {
        val names = mutableListOf<String>()
        for (s in shifts) {
            if (s.companyId != issue.companyId) continue
            val n = s.operatorName
            if (n.isEmpty() || n == issue.operatorName || n in names) continue
            names += n
        }
        issue.replacementName?.let { if (it !in names) names.add(0, it) }
        return names
    }

    companion object {
        fun from(root: JsonElement): ShiftSchedule {
            val r = root.asObject()
            val sch = r?.get("schedule").asObject()
            return ShiftSchedule(
                companies = sch?.get("companies").asObjects().map { ScheduleCompany(it.str("id") ?: "", it.str("name") ?: "Точка") },
                shifts = sch?.get("shifts").asObjects().map {
                    RosterShift(
                        id = it.str("id") ?: "",
                        date = it.str("date")?.take(10) ?: "",
                        operatorName = it.str("operator_name") ?: "",
                        shiftType = it.str("shift_type") ?: "day",
                        companyId = it.str("company_id"),
                    )
                },
                // Заявки лежат в корне ответа рядом со `schedule`, а не внутри.
                requests = r?.get("requests").asObjects().map {
                    ShiftIssue(
                        id = it.str("id") ?: "",
                        companyId = it.str("company_id") ?: "",
                        operatorName = it.str("operator_name") ?: "Оператор",
                        shiftDate = it.str("shift_date")?.take(10) ?: "",
                        shiftType = it.str("shift_type") ?: "day",
                        status = it.str("status") ?: "open",
                        reason = it.str("reason"),
                        leadAction = it.str("lead_action"),
                        leadStatus = it.str("lead_status"),
                        leadNote = it.str("lead_note"),
                        leadOperatorName = it.str("lead_operator_name"),
                        replacementName = it.str("lead_replacement_operator_name"),
                    )
                },
            )
        }
    }
}

// ── Отчёты смен: GET /api/admin/shifts/reports ───────────────────────────────

data class ShiftTotals(val sales: Double = 0.0, val cash: Double = 0.0, val kaspi: Double = 0.0, val count: Int = 0)

data class ShiftReport(
    val id: String,
    val status: String,
    val shiftType: String?,
    val openedAt: String?,
    val closedAt: String?,
    val openingCash: Double,
    val closingCash: Double,
    val companyName: String?,
    val hasZReport: Boolean,
    val totals: ShiftTotals,
    /**
     * Итог кассы, который посчитала касса при закрытии: «+» излишек, «−» недостача.
     * Новое поле сервера — старые ответы его не присылают.
     */
    val cashReportDiff: Double?,
) {
    val isOpen: Boolean get() = status == "open"

    val statusLabel: String get() = if (isOpen) "Открыта" else if (status == "closed") "Закрыта" else status

    val typeLabel: String
        get() = when (shiftType) {
            "day" -> "День"
            "night" -> "Ночь"
            null -> "Смена"
            else -> shiftType
        }

    /** Расхождение по наличным: факт минус начало и наличные продажи. У открытой смены нет. */
    val computedCashDifference: Double?
        get() = if (isOpen) null else closingCash - (openingCash + totals.cash)

    /**
     * Расхождение для показа. Итог из отчёта кассы точнее (учитывает монеты, Kaspi,
     * техкомпенсацию), поэтому приоритет у него; иначе считаем по наличным.
     */
    val cashDifference: Double?
        get() = if (isOpen) null else cashReportDiff ?: computedCashDifference

    companion object {
        fun from(o: JsonObject): ShiftReport {
            // Итоги приходят двумя наборами имён: закрытая смена — `sales_total`, открытая — `sales`.
            val t = o["totals_json"].asObject() ?: o["live_totals"].asObject()
            return ShiftReport(
                id = o.str("id") ?: "",
                status = o.str("status") ?: "closed",
                shiftType = o.str("shift_type"),
                openedAt = o.str("opened_at"),
                closedAt = o.str("closed_at"),
                openingCash = o.dbl("opening_cash"),
                closingCash = o.dbl("closing_cash"),
                companyName = o["company"].asObject()?.str("name"),
                hasZReport = o.str("z_report_url") != null,
                totals = ShiftTotals(
                    sales = t?.dblOrNull("sales_total") ?: t?.dblOrNull("sales") ?: t?.dblOrNull("total") ?: 0.0,
                    cash = t?.dblOrNull("sales_cash") ?: t?.dblOrNull("cash") ?: 0.0,
                    kaspi = t?.dblOrNull("sales_kaspi") ?: t?.dblOrNull("kaspi") ?: 0.0,
                    count = (t?.dblOrNull("sales_count") ?: t?.dblOrNull("count") ?: 0.0).toInt(),
                ),
                cashReportDiff = o.dblOrNull("cash_report_diff"),
            )
        }

        fun list(root: JsonElement): List<ShiftReport> =
            root.unwrapData().asObject()?.get("shifts").asObjects().map(::from)
    }
}

/** Расхождение меньше тенге — округление, не недостача. */
fun isMaterialDiscrepancy(diff: Double?): Boolean = diff != null && abs(diff) > 1.0

// ── Дни рождения: GET /api/admin/birthdays ───────────────────────────────────

data class Birthday(
    val id: String,
    val name: String,
    val position: String?,
    val photoUrl: String?,
    val companyName: String?,
    val daysUntil: Int,
    val age: Int?,
) {
    val isToday: Boolean get() = daysUntil == 0
    val isThisWeek: Boolean get() = daysUntil in 0..7

    val whenLabel: String
        get() = when (daysUntil) {
            0 -> "сегодня"
            1 -> "завтра"
            else -> "через $daysUntil ${plural(daysUntil, "день", "дня", "дней")}"
        }
}

data class BirthdayList(val items: List<Birthday>, val withoutBirthDate: Int) {
    val today: List<Birthday> get() = items.filter { it.isToday }
    val thisWeek: List<Birthday> get() = items.filter { it.isThisWeek && !it.isToday }
    val later: List<Birthday> get() = items.filter { !it.isThisWeek }

    companion object {
        fun from(root: JsonElement): BirthdayList {
            val d = root.unwrapData().asObject()
            return BirthdayList(
                items = d?.get("items").asObjects().map {
                    Birthday(
                        id = it.str("id") ?: it.str("name") ?: "",
                        name = it.str("name") ?: "Сотрудник",
                        position = it.str("position"),
                        photoUrl = it.str("photo_url"),
                        companyName = it.str("company_name"),
                        daysUntil = it.int("daysUntil"),
                        age = it.dblOrNull("age")?.toInt(),
                    )
                },
                withoutBirthDate = d?.get("stats").asObject()?.int("withoutBirthDate") ?: 0,
            )
        }
    }
}

// ── Мои данные и моя зарплата ────────────────────────────────────────────────

/** `GET /api/me/profile`. */
data class MyProfile(
    val kind: String,
    val fullName: String?,
    val position: String?,
    val phone: String?,
    val email: String?,
    val telegramChatId: String?,
) {
    /** Telegram привязывают только у операторов. */
    val supportsTelegram: Boolean get() = kind == "operator"

    companion object {
        fun from(root: JsonElement): MyProfile {
            val d = root.unwrapData().asObject()
            return MyProfile(
                kind = d?.str("kind") ?: "",
                fullName = d?.str("fullName"),
                position = d?.str("position"),
                phone = d?.str("phone"),
                email = d?.str("email"),
                telegramChatId = d?.str("telegramChatId"),
            )
        }
    }
}
