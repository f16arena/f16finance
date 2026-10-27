package kz.ordaops.android.feature.business

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Модели ответов кабинета владельца. Разбор терпимый (ApiJson: неизвестные ключи
// игнорируются, null вместо числа → значение по умолчанию), как декодеры
// OrdaKit: новый ключ на сервере не должен ронять экран.

/** Обёртка `{ "data": … }`. */
@Serializable
data class BizData<T>(val data: T)

/** Обёртка `{ "data": [ … ] }`. */
@Serializable
data class BizList<T>(val data: List<T> = emptyList())

// ── Дашборд: GET /api/admin/dashboard ────────────────────────────────────────

@Serializable
data class DayRevenue(
    val total: Double = 0.0,
    val count: Int = 0,
    val cash: Double = 0.0,
    val kaspi: Double = 0.0,
    val card: Double = 0.0,
    val online: Double = 0.0,
)

/** Товар с остатком ниже порога. */
@Serializable
data class LowStockItem(
    val id: String = "",
    val name: String = "Товар",
    val threshold: Double = 0.0,
    val balance: Double = 0.0,
)

@Serializable
data class DashboardYesterday(val total: Double = 0.0)

/** Тело `data` ответа `/api/admin/dashboard`. */
@Serializable
data class BusinessDashboard(
    val today: DayRevenue = DayRevenue(),
    val yesterday: DashboardYesterday = DashboardYesterday(),
    @SerialName("change_percent") val changePercent: Double? = null,
    @SerialName("month_total") val monthTotal: Double = 0.0,
    @SerialName("low_stock") val lowStock: List<LowStockItem> = emptyList(),
)

// ── Расходы на одобрении: GET /api/admin/expenses/pending ────────────────────

@Serializable
data class PendingExpense(
    val id: String = "",
    @SerialName("cash_amount") val cashAmount: Double = 0.0,
    @SerialName("kaspi_amount") val kaspiAmount: Double = 0.0,
) {
    val total: Double get() = cashAmount + kaspiAmount
}

/** Точка (компания) организации: GET /api/admin/companies. */
@Serializable
data class Company(val id: String = "", val name: String = "Точка", val code: String? = null)

// ── Аналитика владельца: GET /api/admin/owner-analytics ──────────────────────

@Serializable
data class OwnerAnalytics(
    val period: Period = Period(),
    val companies: List<CompanyOption> = emptyList(),
    val kpi: KpiPair = KpiPair(),
    val series: List<SeriesPoint> = emptyList(),
    val byCompany: List<CompanyRow> = emptyList(),
    val weekdays: List<Weekday> = emptyList(),
    /** Нет права на отчёты — разбивки по статьям нет вовсе. */
    val expenseCategories: List<ExpenseCategory>? = null,
    /** Нет права на аналитику операторов — блока нет. */
    val operators: Operators? = null,
    /** Точки без кассы (клуб на отчётах смен) — блока нет. */
    val pos: Pos? = null,
) {
    @Serializable
    data class Period(
        val from: String = "",
        val to: String = "",
        /** Последний день с данными: «месяц» посреди месяца кончается сегодня. */
        val through: String = "",
        val prevFrom: String = "",
        val prevTo: String = "",
        val compare: String = "prev",
        /** Шаг графика, выбирает сервер по длине периода: day, week, month. */
        val group: String = "day",
        /** Период ещё идёт — цифры будут расти. */
        val partial: Boolean = false,
    )

    @Serializable
    data class CompanyOption(
        val id: String = "",
        val name: String = "Точка",
        /** Отдельная касса, которую итоги по умолчанию не складывают. */
        val isExtra: Boolean = false,
    )

    @Serializable
    data class KpiPair(val current: Kpi = Kpi(), val previous: Kpi = Kpi())

    @Serializable
    data class Kpi(
        val revenue: Double = 0.0,
        val expense: Double = 0.0,
        /** Доход минус все расходы, включая покупку оборудования. */
        val profit: Double = 0.0,
        val pnlProfit: Double = 0.0,
        val cash: Double = 0.0,
        val kaspi: Double = 0.0,
        val card: Double = 0.0,
        val online: Double = 0.0,
        /** Число отчётов смен. */
        val shifts: Int = 0,
    ) {
        /** Маржа в процентах; null — выручки нет, делить не на что. */
        val marginPercent: Double? get() = if (revenue > 0) profit / revenue * 100 else null
    }

    @Serializable
    data class SeriesPoint(
        val date: String = "",
        val prevDate: String = "",
        val revenue: Double = 0.0,
        val expense: Double = 0.0,
        val profit: Double = 0.0,
        val prevRevenue: Double = 0.0,
        val prevExpense: Double = 0.0,
        val prevProfit: Double = 0.0,
    )

    @Serializable
    data class CompanyRow(
        val id: String = "",
        val name: String = "Точка",
        val revenue: Double = 0.0,
        val expense: Double = 0.0,
        val profit: Double = 0.0,
        val prevRevenue: Double = 0.0,
        val prevProfit: Double = 0.0,
    )

    @Serializable
    data class Weekday(
        /** Понедельник = 0 … воскресенье = 6. */
        val weekday: Int = 0,
        val total: Double = 0.0,
        val days: Int = 0,
        val average: Double = 0.0,
    )

    @Serializable
    data class ExpenseCategory(val name: String = "", val amount: Double = 0.0, val prevAmount: Double = 0.0)

    @Serializable
    data class Operators(val rows: List<OperatorRow> = emptyList(), val unattributed: Double = 0.0)

    @Serializable
    data class OperatorRow(
        val id: String = "",
        val name: String = "",
        val revenue: Double = 0.0,
        val shifts: Int = 0,
        val perShift: Double = 0.0,
        val prevRevenue: Double = 0.0,
        val prevPerShift: Double = 0.0,
    )

    @Serializable
    data class Pos(
        val amount: Double = 0.0,
        val receipts: Int = 0,
        val avgCheck: Double = 0.0,
        /** [день недели 0…6][час 0…23] — сумма чеков. */
        val heatmap: List<List<Double>> = emptyList(),
        val byHour: List<Hour> = emptyList(),
        val topItems: List<Item> = emptyList(),
        val previous: Previous = Previous(),
    ) {
        @Serializable
        data class Hour(val hour: Int = 0, val amount: Double = 0.0, val count: Int = 0)

        @Serializable
        data class Item(val name: String = "", val qty: Double = 0.0, val revenue: Double = 0.0, val profit: Double? = null)

        @Serializable
        data class Previous(
            val amount: Double = 0.0,
            val receipts: Int = 0,
            val avgCheck: Double = 0.0,
            /** База обрезана тем же часом, что и сегодня. */
            val sameTime: Boolean = false,
        )
    }
}

/** Что именно спросить у сервера. */
data class OwnerAnalyticsQuery(
    val from: String,
    val to: String,
    val companyIds: List<String> = emptyList(),
    val compare: AnalyticsCompare = AnalyticsCompare.Previous,
    val includeExtra: Boolean = false,
    /**
     * Лёгкий ответ для главной: без товаров, статей и операторов. Главной нужны
     * итоги и точки, а разбор чеков по товарам за месяц — десятки тысяч строк.
     */
    val lite: Boolean = false,
) {
    val queryParams: Map<String, String>
        get() = buildMap {
            put("from", from)
            put("to", to)
            put("compare", compare.wire)
            if (includeExtra) put("include_extra", "1")
            if (lite) put("lite", "1")
            if (companyIds.isNotEmpty()) put("company_ids", companyIds.sorted().joinToString(","))
        }
}
