package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Движение денег: /api/admin/cashflow/summary, /outlook, /balance.
// Порт CashflowModels из FinanceModels.swift и CashflowExtras.swift.

/** Деньги по каналам: наличные, безнал, всего. */
@Serializable
data class CashflowMoney(val cash: Double = 0.0, val cashless: Double = 0.0, val total: Double? = null) {
    val sum: Double get() = total ?: (cash + cashless)
}

/** Пришло, ушло, осталось. */
@Serializable
data class CashflowFlow(
    @SerialName("in") val inflow: Double = 0.0,
    @SerialName("out") val outflow: Double = 0.0,
    val net: Double? = null,
) {
    val delta: Double get() = net ?: (inflow - outflow)
}

/** Поток по каналам. */
@Serializable
data class CashflowChannels(
    val cash: CashflowFlow = CashflowFlow(),
    val cashless: CashflowFlow = CashflowFlow(),
    val total: CashflowFlow = CashflowFlow(),
)

/** День периода. `balance` — накопленный итог с начала периода, а не остаток кассы. */
@Serializable
data class CashflowDay(
    val date: String = "",
    val income: Double = 0.0,
    val expense: Double = 0.0,
    val net: Double = 0.0,
    val balance: Double = 0.0,
    val cashIn: Double = 0.0,
    val cashOut: Double = 0.0,
    val cashlessIn: Double = 0.0,
    val cashlessOut: Double = 0.0,
    /** Остаток на конец дня — только если задана отметка остатка. */
    val onHand: CashflowMoney? = null,
) {
    val cashNet: Double get() = cashIn - cashOut
    val cashlessNet: Double get() = cashlessIn - cashlessOut
}

/** День, когда наличных ушло больше, чем пришло. */
@Serializable
data class CashflowDeficit(val date: String = "", val net: Double = 0.0)

/** Назначение расходов: текущие, вложения, партнёрам, налоги, прочие. */
@Serializable
data class CashflowActivity(
    val key: String = "",
    val label: String = "",
    val amount: Double = 0.0,
    val cash: Double = 0.0,
    val cashless: Double = 0.0,
    val previous: Double = 0.0,
)

/** Статья расходов периода. */
@Serializable
data class CashflowCategory(
    val name: String = "Без статьи",
    val group: String? = null,
    val activity: String? = null,
    val amount: Double = 0.0,
    val cash: Double = 0.0,
    val cashless: Double = 0.0,
    val previous: Double = 0.0,
)

/** Точка в движении денег. */
@Serializable
data class CashflowCompany(
    val id: String = "",
    val name: String = "—",
    val isExtra: Boolean = false,
    val inTotals: Boolean = true,
    val flows: CashflowChannels = CashflowChannels(),
    val previousNet: Double = 0.0,
)

/** Крупный расход периода. */
@Serializable
data class CashflowLargeExpense(
    val date: String = "",
    val company: String = "",
    val category: String = "",
    val payee: String = "",
    val amount: Double = 0.0,
    val cash: Double = 0.0,
    val cashless: Double = 0.0,
    val pending: Boolean = false,
)

/** Отметка остатка: сколько денег было на утро даты. */
@Serializable
data class CashflowAnchor(
    val id: String = "",
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("as_of_date") val asOfDate: String = "",
    @SerialName("cash_amount") val cash: Double = 0.0,
    @SerialName("cashless_amount") val cashless: Double = 0.0,
    val note: String? = null,
)

@Serializable
data class CashflowLowest(val date: String = "", val total: Double = 0.0, val cash: Double = 0.0)

/** Остаток по отметке: на начало и конец периода, самая низкая точка. */
@Serializable
data class CashflowBalance(
    val scope: String = "organization",
    val anchor: CashflowAnchor? = null,
    val start: CashflowMoney? = null,
    val end: CashflowMoney? = null,
    val lowest: CashflowLowest? = null,
)

@Serializable
data class CashflowTotals(
    val income: Double = 0.0,
    val expense: Double = 0.0,
    val net: Double = 0.0,
    val margin: Double = 0.0,
    val negativeDays: Int = 0,
    val endingBalance: Double = 0.0,
    val daysCount: Int = 0,
)

@Serializable
data class CashflowPending(val count: Int = 0, val total: Double = 0.0)

@Serializable
data class CashflowExtra(val names: List<String> = emptyList(), val included: Boolean = false)

/** Ответ `GET /api/admin/cashflow/summary` (в обёртке `data`). */
@Serializable
data class CashflowReport(
    val from: String = "",
    val to: String = "",
    val days: List<CashflowDay> = emptyList(),
    val totals: CashflowTotals = CashflowTotals(),
    val prevFrom: String? = null,
    val prevTo: String? = null,
    val flows: CashflowChannels = CashflowChannels(),
    val previous: CashflowChannels = CashflowChannels(),
    val activities: List<CashflowActivity> = emptyList(),
    val categories: List<CashflowCategory> = emptyList(),
    val companies: List<CashflowCompany> = emptyList(),
    val largestExpenses: List<CashflowLargeExpense> = emptyList(),
    val pending: CashflowPending = CashflowPending(),
    val cashDeficitDays: List<CashflowDeficit> = emptyList(),
    /** `null` — отметки нет, и «остаток» показывать нельзя: накопленный поток — не деньги в кассе. */
    val balance: CashflowBalance? = null,
    val extra: CashflowExtra = CashflowExtra(),
) {
    val bestDay: CashflowDay? get() = days.maxByOrNull { it.net }

    /** Только настоящий минус: «худший из прибыльных» дней ни о чём не говорит. */
    val worstDay: CashflowDay? get() = days.minByOrNull { it.net }?.takeIf { it.net < 0 }

    /** Поток периода; если сервер не дал каналы — берём итоги по дням. */
    val headlineFlow: CashflowFlow
        get() = if (flows.total.inflow == 0.0 && flows.total.outflow == 0.0) {
            CashflowFlow(totals.income, totals.expense, totals.net)
        } else flows.total
}

// ── Платежи вперёд ──────────────────────────────────────────────────────────

/** Регулярный платёж из шаблонов расходов на ближайший месяц. */
@Serializable
data class CashflowUpcomingPayment(
    val date: String = "",
    @SerialName("templateId") val templateId: String = "",
    val name: String = "",
    val category: String = "",
    val amount: Double = 0.0,
    val cashless: Boolean = false,
    @SerialName("companyId") val companyId: String = "",
    val company: String = "—",
)

@Serializable
data class CashflowProjectionDay(
    val date: String = "",
    val income: Double = 0.0,
    val payments: Double = 0.0,
    val other: Double = 0.0,
    val balance: Double? = null,
)

@Serializable
data class CashflowProjectionLowest(val date: String? = null, val balance: Double? = null)

/** Деньги до конца месяца: ожидаемая выручка, платежи и остаток по дням. */
@Serializable
data class CashflowProjection(
    val monthEnd: String = "",
    /** `model` — по модели прогноза, `average` — по среднему дню. */
    val source: String = "average",
    val incomeLeft: Double = 0.0,
    val paymentsLeft: Double = 0.0,
    val otherSpendLeft: Double = 0.0,
    val netLeft: Double = 0.0,
    val balanceStart: Double? = null,
    val balanceEnd: Double? = null,
    val lowest: CashflowProjectionLowest? = null,
    val days: List<CashflowProjectionDay> = emptyList(),
)

/** Ответ `GET /api/admin/cashflow/outlook`. */
@Serializable
data class CashflowOutlook(
    val today: String = "",
    val payments: List<CashflowUpcomingPayment> = emptyList(),
    val projection: CashflowProjection? = null,
    val balanceToday: CashflowMoney? = null,
    val anchor: CashflowAnchor? = null,
)

/** Ответ `GET /api/admin/cashflow/balance`. */
@Serializable
data class CashflowAnchorList(
    val anchors: List<CashflowAnchor> = emptyList(),
    /** Указывать и удалять отметки может владелец или управляющий. */
    val canEdit: Boolean = false,
    /** Без миграции таблицы сервер отвечает `false` и подсказкой. */
    val available: Boolean = true,
    val hint: String? = null,
)

/** Новая отметка остатка: сколько было денег на утро даты — до движений дня. */
data class CashflowAnchorDraft(
    /** `null` — вся организация. */
    val companyId: String?,
    val date: String,
    val cash: Double,
    val cashless: Double,
    val note: String,
) {
    /** Ключи ровно как у сайта. */
    fun body(): JsonObject = buildJsonObject {
        put("company_id", companyId)
        put("as_of_date", date)
        put("cash_amount", cash)
        put("cashless_amount", cashless)
        put("note", note.trim())
    }
}

/** Чистая логика экрана — для теста. */
object CashflowLogic {
    /** Знак перед суммой: «+», «−» или без знака для нуля. */
    fun signedText(value: Double, format: (Double) -> String): String {
        val rounded = Math.round(value)
        val sign = if (rounded > 0) "+" else if (rounded < 0) "−" else ""
        return sign + format(Math.abs(rounded).toDouble())
    }

    /** Изменение чистого потока к прошлому периоду; при нулевом «пришло» в прошлом сравнивать не с чем. */
    fun netChange(report: CashflowReport): Double? {
        val prev = report.previous.total
        if (prev.inflow <= 0.0) return null
        val base = prev.delta
        if (base == 0.0) return null
        return (report.headlineFlow.delta - base) / Math.abs(base) * 100
    }
}
