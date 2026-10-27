package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Модели раздела «Финансы». Порт BusinessModels / LedgerEdit / IncomeDraft /
// ExpenseWizard / CatalogModels из OrdaKit. Декодер терпимый (`ApiJson`):
// недостающее поле — значение по умолчанию, а не падение экрана.

/** Строка дохода за смену — `/api/admin/incomes`. */
@Serializable
data class IncomeRow(
    val id: String = "",
    val date: String = "",
    val shift: String? = null,
    @SerialName("cash_amount") val cashAmount: Double = 0.0,
    @SerialName("kaspi_amount") val kaspiAmount: Double = 0.0,
    @SerialName("online_amount") val onlineAmount: Double = 0.0,
    @SerialName("card_amount") val cardAmount: Double = 0.0,
    val comment: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    /** Нужны при исправлении: сервер перезаписывает запись целиком. */
    @SerialName("operator_id") val operatorId: String? = null,
    @SerialName("kaspi_before_midnight") val kaspiBeforeMidnight: Double? = null,
) {
    val total: Double get() = cashAmount + kaspiAmount + onlineAmount + cardAmount
}

/** Строка расхода — `/api/admin/expenses`. */
@Serializable
data class ExpenseRow(
    val id: String = "",
    val date: String = "",
    val category: String? = null,
    @SerialName("cash_amount") val cashAmount: Double = 0.0,
    @SerialName("kaspi_amount") val kaspiAmount: Double = 0.0,
    val comment: String? = null,
    val status: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("operator_id") val operatorId: String? = null,
) {
    val total: Double get() = cashAmount + kaspiAmount
    val isPending: Boolean get() = status == "pending_approval"

    /**
     * Отклонённый расход в итоги не входит — как в web и ОПиУ. Сервер такие
     * обычно не отдаёт, но если отдал, считать их деньгами нельзя.
     */
    val isCounted: Boolean get() = status?.lowercase() !in REJECTED

    private companion object {
        val REJECTED = setOf("rejected", "declined", "cancelled", "canceled")
    }
}

/** Расход на одобрении — `/api/admin/expenses/pending`. */
@Serializable
data class PendingItem(
    val id: String = "",
    val date: String? = null,
    val category: String? = null,
    @SerialName("cash_amount") val cashAmount: Double = 0.0,
    @SerialName("kaspi_amount") val kaspiAmount: Double = 0.0,
    val comment: String? = null,
    @SerialName("one_off_payee") val payee: String? = null,
    @SerialName("one_off_reason") val reason: String? = null,
    @SerialName("company_id") val companyId: String? = null,
) {
    val total: Double get() = cashAmount + kaspiAmount
}

/** Статья расходов с бюджетом — `/api/admin/expense-categories`. */
@Serializable
data class ExpenseCategory(
    val id: String = "",
    val name: String = "",
    @SerialName("accounting_group") val accountingGroup: String? = null,
    @SerialName("monthly_budget") val monthlyBudget: Double = 0.0,
    @SerialName("spent_this_month") val spentThisMonth: Double = 0.0,
) {
    /** Себестоимость заводит только владелец: её место в приёмке, где есть накладная. */
    val isCogs: Boolean get() = accountingGroup?.trim()?.lowercase() == "cogs"

    /** Ноль — «бюджет не задан», а не «тратить нельзя». */
    val hasBudget: Boolean get() = monthlyBudget > 0

    val usage: Double? get() = if (hasBudget) spentThisMonth / monthlyBudget else null
    val isOverBudget: Boolean get() = hasBudget && spentThisMonth > monthlyBudget
    val isNearLimit: Boolean get() = usage?.let { it >= 0.8 && it <= 1.0 } ?: false
    val remaining: Double get() = maxOf(monthlyBudget - spentThisMonth, 0.0)

    /** Финансовая группа — та, что решает, куда статья попадёт в ОПиУ. */
    val groupLabel: String
        get() = when (accountingGroup) {
            "cogs" -> "Себестоимость"
            "operating" -> "Операционные"
            "payroll", "payroll_advance" -> "ФОТ"
            "payroll_tax" -> "Налоги с ФОТ"
            "income_tax" -> "Налог на прибыль"
            "pos_commission" -> "Комиссии эквайринга"
            "financial_expenses" -> "Финансовые"
            "non_operating" -> "Неоперационные"
            "depreciation" -> "Износ"
            "capex" -> "Вложения"
            "profit_distribution" -> "Распределение прибыли"
            else -> "Операционные"
        }
}

/** Оператор — для выбора в формах дохода/расхода. */
@Serializable
data class FinOperator(
    val id: String = "",
    val name: String = "",
    @SerialName("short_name") val shortName: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
) {
    val title: String get() = shortName?.takeIf { it.isNotBlank() } ?: name
}

/** Доверенный поставщик: платёж ему не требует документа. */
@Serializable
data class ExpenseVendor(
    val id: String = "",
    @SerialName("vendor_name") val name: String = "Без названия",
    @SerialName("default_category_id") val defaultCategoryId: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    val notes: String? = null,
)

// ── Доход: черновик и исправление ───────────────────────────────────────────

/** Заготовка дохода за смену — тело `POST /api/admin/incomes` (`createIncome`). */
data class IncomeDraft(
    val date: String,
    val companyId: String,
    val operatorId: String? = null,
    /** `day` или `night`. */
    val shift: String = "day",
    val cash: Double = 0.0,
    val kaspi: Double = 0.0,
    val card: Double = 0.0,
    val online: Double = 0.0,
    val comment: String? = null,
    /** Зона выручки (`incomes.zone`): `pc`, `ramen`, `other` — по профилю точки. */
    val zone: String? = null,
) {
    val total: Double get() = cash + kaspi + card + online

    /** Что мешает отправить; формулировки серверные. `null` — можно. */
    val validationMessage: String?
        get() = when {
            date.isEmpty() -> "Дата обязательна"
            companyId.isEmpty() -> "Точка обязательна"
            operatorId.isNullOrEmpty() -> "Оператор обязателен"
            shift != "day" && shift != "night" -> "Смена обязательна"
            cash < 0 || kaspi < 0 || card < 0 || online < 0 -> "Сумма не может быть отрицательной"
            total <= 0 -> "Сумма дохода обязательна"
            else -> null
        }

    val isValid: Boolean get() = validationMessage == null

    fun body(force: Boolean): JsonObject = buildJsonObject {
        put("action", "createIncome")
        put("force", force)
        put("payload", buildJsonObject {
            put("date", date)
            put("company_id", companyId)
            put("operator_id", operatorId)
            put("shift", shift)
            put("cash_amount", cash)
            put("kaspi_amount", kaspi)
            put("card_amount", card)
            put("online_amount", online)
            put("comment", comment?.trim()?.takeIf { it.isNotEmpty() })
            put("zone", zone)
            put("is_virtual", false)
        })
    }
}

/**
 * Приход точки с зонами (PS5, VR, бильярд…) — по записи на каждую зону с суммой,
 * `createIncomeBatch`; подпись зоны дописывается в комментарий, как на сайте.
 */
fun zoneIncomeBatchBody(
    date: String,
    companyId: String,
    operatorId: String,
    shift: String,
    comment: String,
    zones: List<Pair<RevenueZone, ZoneAmount>>,
): JsonObject = buildJsonObject {
    put("action", "createIncomeBatch")
    put("payload", kotlinx.serialization.json.JsonArray(
        zones.filter { it.second.total > 0 }.map { (z, a) ->
            buildJsonObject {
                put("date", date)
                put("company_id", companyId)
                put("operator_id", operatorId)
                put("shift", shift)
                put("zone", z.key)
                put("cash_amount", a.cash)
                put("kaspi_amount", a.cashless)
                put("online_amount", 0)
                put("card_amount", 0)
                put("comment", if (comment.trim().isNotEmpty()) "${comment.trim()} • ${z.label}" else z.label)
                put("is_virtual", true)
            }
        },
    ))
}

/** Исправление дохода. «Kaspi до полуночи» в форме нет — уходит как было. */
data class IncomeEdit(
    val id: String,
    val date: String,
    val cash: Double,
    val kaspi: Double,
    val online: Double,
    val card: Double,
    val comment: String,
    val operatorId: String?,
    val kaspiBeforeMidnight: Double?,
) {
    constructor(row: IncomeRow) : this(
        row.id, row.date, row.cashAmount, row.kaspiAmount, row.onlineAmount, row.cardAmount,
        row.comment.orEmpty(), row.operatorId, row.kaspiBeforeMidnight,
    )

    val total: Double get() = cash + kaspi + online + card

    val problem: String?
        get() = when {
            date.isBlank() -> "Укажите дату"
            listOf(cash, kaspi, online, card).any { it < 0 } -> "Сумма не может быть меньше нуля"
            total <= 0 -> "Укажите сумму"
            else -> null
        }

    fun body(): JsonObject = buildJsonObject {
        put("action", "updateIncome")
        put("incomeId", id)
        put("payload", buildJsonObject {
            put("date", date)
            put("operator_id", operatorId)
            put("cash_amount", cash)
            put("kaspi_amount", kaspi)
            put("kaspi_before_midnight", kaspiBeforeMidnight)
            put("online_amount", online)
            put("card_amount", card)
            put("comment", comment.trim().takeIf { it.isNotEmpty() })
        })
    }
}

/** Исправление расхода. Точка и оператор стартуют с исходных значений. */
data class ExpenseEdit(
    val id: String,
    val date: String,
    val category: String,
    val cash: Double,
    val kaspi: Double,
    val comment: String,
    val companyId: String,
    val operatorId: String?,
) {
    val total: Double get() = cash + kaspi

    val problem: String?
        get() = when {
            date.isBlank() -> "Укажите дату"
            category.isBlank() -> "Выберите категорию"
            cash < 0 || kaspi < 0 -> "Сумма не может быть меньше нуля"
            total <= 0 -> "Укажите сумму"
            else -> null
        }

    fun body(): JsonObject = buildJsonObject {
        put("action", "updateExpense")
        put("expenseId", id)
        put("payload", buildJsonObject {
            put("date", date)
            put("company_id", companyId)
            put("operator_id", operatorId)
            put("category", category.trim())
            put("cash_amount", cash)
            put("kaspi_amount", kaspi)
            put("comment", comment.trim().takeIf { it.isNotEmpty() })
        })
    }

    companion object {
        /** `null`, если у записи нет точки: такую сервер не примет — правку не предлагаем. */
        fun of(row: ExpenseRow): ExpenseEdit? {
            val company = row.companyId?.takeIf { it.isNotEmpty() } ?: return null
            return ExpenseEdit(row.id, row.date, row.category.orEmpty(), row.cashAmount, row.kaspiAmount, row.comment.orEmpty(), company, row.operatorId)
        }
    }
}

// ── Расход: мастер ──────────────────────────────────────────────────────────

/** Чем подтверждён расход. */
enum class ExpenseDocumentKind(val wire: String, val title: String, val requiresFile: Boolean) {
    Receipt("receipt", "Чек", true),
    Invoice("invoice", "Накладная", true),
    Bill("bill", "Счёт", true),
    Whitelist("whitelist", "Доверенный поставщик", false),
    OneOff("one_off", "Разовый платёж", false),
}

/** Что заполняет владелец; уходит в сессию мастера целиком. */
data class ExpenseDraft(
    val date: String,
    val companyId: String = "",
    val operatorId: String? = null,
    val categoryId: String = "",
    val categoryName: String = "",
    val amountCash: Double = 0.0,
    val amountKaspi: Double = 0.0,
    val itemName: String = "",
    val comment: String = "",
    val documentKind: ExpenseDocumentKind = ExpenseDocumentKind.Receipt,
    val documentUrls: List<String> = emptyList(),
    val whitelistVendorId: String? = null,
    val oneOffPayee: String = "",
    val oneOffReason: String = "",
    /** Подтверждение «расход задним числом»: сервер требует для дат старше недели. */
    val backdatedConfirmed: Boolean = false,
) {
    val total: Double get() = amountCash + amountKaspi

    /**
     * Мастер отвергает расход уже после загрузки файла, поэтому проверки
     * повторены на клиенте — формулировки серверные.
     */
    val validationMessage: String?
        get() = when {
            date.isEmpty() -> "Дата обязательна"
            companyId.isEmpty() -> "Точка обязательна"
            categoryId.isEmpty() || categoryName.isEmpty() -> "Категория обязательна"
            itemName.trim().length < 5 -> "Краткое название обязательно (≥ 5 символов)"
            comment.trim().length < 20 -> "Комментарий обязателен (≥ 20 символов)"
            amountCash < 0 || amountKaspi < 0 -> "Сумма не может быть отрицательной"
            total <= 0 -> "Сумма расхода обязательна"
            documentKind.requiresFile && documentUrls.isEmpty() -> "Прикрепите чек/накладную"
            documentKind == ExpenseDocumentKind.Whitelist && whitelistVendorId.isNullOrEmpty() -> "Выберите доверенного поставщика"
            documentKind == ExpenseDocumentKind.OneOff && oneOffPayee.trim().length < 3 -> "Укажите получателя (≥ 3 символов)"
            documentKind == ExpenseDocumentKind.OneOff && oneOffReason.trim().length < 30 ->
                "Опишите причину отсутствия документа (≥ 30 символов)"
            else -> null
        }

    val isValid: Boolean get() = validationMessage == null

    /**
     * Шаг мастера — слияние `{...сохранённое, ...присланное}`, поэтому пустые
     * поля пишем явным `null`: иначе получатель разового платежа пережил бы
     * смену типа документа.
     */
    fun payload(): JsonObject = buildJsonObject {
        put("date", date)
        put("company_id", companyId)
        put("operator_id", operatorId?.takeIf { it.isNotEmpty() })
        put("category_id", categoryId)
        put("category_name", categoryName)
        put("amount_cash", amountCash)
        put("amount_kaspi", amountKaspi)
        put("item_name", itemName.trim())
        put("comment", comment.trim())
        put("backdated_confirmed", backdatedConfirmed)
        put("document_kind", documentKind.wire)
        put("document_urls", kotlinx.serialization.json.JsonArray(documentUrls.map { JsonPrimitive(it) }))
        put("document_url", documentUrls.firstOrNull())
        put("whitelist_vendor_id", if (documentKind == ExpenseDocumentKind.Whitelist) whitelistVendorId else null)
        put("one_off_payee", if (documentKind == ExpenseDocumentKind.OneOff) oneOffPayee.trim() else null)
        put("one_off_reason", if (documentKind == ExpenseDocumentKind.OneOff) oneOffReason.trim() else null)
    }
}
