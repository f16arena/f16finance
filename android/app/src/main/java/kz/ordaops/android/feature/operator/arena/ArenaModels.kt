package kz.ordaops.android.feature.operator.arena

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Дата из ответа сайта: ISO с поясом или голая дата (тогда полдень UTC, как `DateParsing` на айфоне). */
internal fun parseDate(raw: String?): Instant? {
    if (raw.isNullOrEmpty()) return null
    return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { LocalDate.parse(raw.take(10)).atTime(12, 0).toInstant(ZoneOffset.UTC) }.getOrNull()
}

/** Ответ `GET /api/operator/arena` приходит в обёртке `data`. */
@Serializable
internal data class ArenaEnvelope(val data: ArenaHall = ArenaHall())

@Serializable
data class ArenaZone(
    val id: String = "",
    val name: String = "Зона",
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class ArenaStation(
    val id: String = "",
    @SerialName("zone_id") val zoneId: String? = null,
    val name: String = "Станция",
    @SerialName("order_index") val orderIndex: Double = 0.0,
    @SerialName("is_active") val isActive: Boolean = true,
)

@Serializable
data class ArenaTariff(
    val id: String = "",
    @SerialName("zone_id") val zoneId: String? = null,
    val name: String = "Тариф",
    @SerialName("duration_minutes") val durationMinutes: Double = 60.0,
    val price: Double = 0.0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("tariff_type") val tariffType: String = "fixed",
    @SerialName("window_start_time") val windowStart: String? = null,
    @SerialName("window_end_time") val windowEnd: String? = null,
) {
    /** Тариф действует только в окно времени (ночной, дневной). */
    val windowLabel: String?
        get() = if (tariffType == "time_window" && windowStart != null && windowEnd != null) {
            "${windowStart.take(5)}–${windowEnd.take(5)}"
        } else {
            null
        }

    val durationLabel: String
        get() {
            val minutes = durationMinutes.toInt()
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h > 0 && m > 0 -> "$h ч $m мин"
                h > 0 -> "$h ч"
                else -> "$m мин"
            }
        }
}

@Serializable
data class ArenaSession(
    val id: String = "",
    @SerialName("station_id") val stationId: String = "",
    @SerialName("tariff_id") val tariffId: String? = null,
    @SerialName("started_at") val startedAtRaw: String? = null,
    @SerialName("ends_at") val endsAtRaw: String? = null,
    val amount: Double = 0.0,
    @SerialName("cash_amount") val cashAmount: Double = 0.0,
    @SerialName("kaspi_amount") val kaspiAmount: Double = 0.0,
    val status: String = "active",
) {
    val startedAt: Instant? get() = parseDate(startedAtRaw)
    val endsAt: Instant? get() = parseDate(endsAtRaw)
    val isActive: Boolean get() = status == "active"

    /**
     * Сколько осталось в секундах. Отрицательное — время вышло, а гость ещё
     * сидит: это обычное дело, оператору надо видеть, насколько просрочено.
     */
    fun remaining(nowMillis: Long): Long {
        val end = endsAt ?: return 0
        return (end.toEpochMilli() - nowMillis) / 1000
    }

    fun isExpired(nowMillis: Long) = remaining(nowMillis) <= 0

    /** Меньше десяти минут — пора подойти и спросить, продлевает ли гость. */
    fun isEndingSoon(nowMillis: Long): Boolean {
        val left = remaining(nowMillis)
        return left > 0 && left <= 10 * 60
    }
}

@Serializable
data class ArenaIncomeRow(
    val comment: String = "Арена",
    @SerialName("cash_amount") val cash: Double = 0.0,
    @SerialName("kaspi_amount") val kaspi: Double = 0.0,
    @SerialName("created_at") val atRaw: String? = null,
) {
    val total: Double get() = cash + kaspi
    val at: Instant? get() = parseDate(atRaw)
}

@Serializable
data class ArenaTechLog(
    val id: String = "",
    @SerialName("station_name") val stationName: String? = null,
    val reason: String = "Поломка",
    val amount: Double = 0.0,
    @SerialName("created_at") val atRaw: String? = null,
) {
    val at: Instant? get() = parseDate(atRaw)
}

@Serializable
data class ArenaTodayIncome(
    val cash: Double = 0.0,
    val kaspi: Double = 0.0,
    val rows: List<ArenaIncomeRow> = emptyList(),
)

/** Зал клуба: станции, тарифы и запущенные сессии — `ArenaHall` из OrdaKit. */
@Serializable
data class ArenaHall(
    val zones: List<ArenaZone> = emptyList(),
    val stations: List<ArenaStation> = emptyList(),
    val tariffs: List<ArenaTariff> = emptyList(),
    val sessions: List<ArenaSession> = emptyList(),
    @SerialName("today_income") val todayIncome: ArenaTodayIncome? = null,
    @SerialName("today_tech_logs") val techLogs: List<ArenaTechLog> = emptyList(),
) {
    val todayCash: Double get() = todayIncome?.cash ?: 0.0
    val todayKaspi: Double get() = todayIncome?.kaspi ?: 0.0
    val todayRows: List<ArenaIncomeRow> get() = todayIncome?.rows.orEmpty()
    val todayTotal: Double get() = todayCash + todayKaspi

    fun session(stationId: String): ArenaSession? = sessions.firstOrNull { it.stationId == stationId && it.isActive }

    val busyCount: Int get() = stations.count { session(it.id) != null }

    /** Станции зоны в том порядке, в каком они стоят в зале. */
    fun stations(zoneId: String?): List<ArenaStation> =
        stations.filter { it.zoneId == zoneId }
            .sortedWith(compareBy<ArenaStation> { it.orderIndex }.thenComparator { a, b -> naturalCompare(a.name, b.name) })

    /**
     * Тарифы, которые можно предложить на этой станции: тариф зоны — только для
     * станций своей зоны, общий (без зоны) годится везде. Ночной пакет днём
     * сервер уже отсеял.
     */
    fun tariffs(station: ArenaStation): List<ArenaTariff> =
        tariffs.filter { it.isActive && (it.zoneId == null || it.zoneId == station.zoneId) }.sortedBy { it.price }
}

/** «Станция 2» раньше «Станция 10» — как `localizedStandardCompare`. */
private fun naturalCompare(a: String, b: String): Int {
    val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.toBigInteger()
            val ny = y.toBigInteger()
            nx.compareTo(ny)
        } else {
            x.compareTo(y, ignoreCase = true)
        }
        if (c != 0) return c
    }
    return ra.size - rb.size
}

/** Как гость платит. Смешанная оплата — обычное дело: часть налом, остаток переводом. */
enum class ArenaPayment(val wire: String, val title: String) {
    Cash("cash", "Наличные"),
    Kaspi("kaspi", "Kaspi"),
    Mixed("mixed", "Смешанно"),
}

/** Для проверки «моя ли смена» нужно лишь это из `/api/operator/shift/current`. */
@Serializable
internal data class ShiftHead(
    val shift: ShiftStatus? = null,
    @SerialName("is_mine") val isMine: Boolean = true,
)

@Serializable
internal data class ShiftStatus(val status: String = "open")
