package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.ui.components.money
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

// Модели «Правил зарплаты», «Кадров» и «Структуры команды».

// ── Правила зарплаты: GET /api/admin/salary-rules ────────────────────────────

/** Строка условий правила: «Оборот от 100 000 ₸ — +2 000 ₸». */
data class RuleTerm(val id: String, val text: String, val amount: String, val isBonus: Boolean)

data class SalaryRule(
    val id: String,
    val companyCode: String,
    val shiftType: String,
    val basePerShift: Double,
    val seniorOperatorBonus: Double?,
    val seniorCashierBonus: Double?,
    val threshold1Turnover: Double?,
    val threshold1Bonus: Double?,
    val threshold2Turnover: Double?,
    val threshold2Bonus: Double?,
    val lowTurnoverThreshold: Double?,
    val lowTurnoverBase: Double?,
    val isActive: Boolean,
    val effectiveFrom: String?,
) {
    val isNight: Boolean get() = shiftType == "night"

    /** Максимум, который точка может отдать человеку за смену. */
    val ceilingPerShift: Double
        get() = basePerShift + (threshold1Bonus ?: 0.0) + (threshold2Bonus ?: 0.0) +
            maxOf(seniorOperatorBonus ?: 0.0, seniorCashierBonus ?: 0.0)

    val terms: List<RuleTerm>
        get() = buildList {
            add(RuleTerm("base", "Ставка за смену", money(basePerShift), false))
            if (lowTurnoverThreshold != null && lowTurnoverBase != null) {
                add(RuleTerm("low", "Оборот меньше ${money(lowTurnoverThreshold)} — ставка падает до", money(lowTurnoverBase), false))
            }
            if (threshold1Turnover != null && threshold1Bonus != null && threshold1Bonus > 0) {
                add(RuleTerm("t1", "Оборот от ${money(threshold1Turnover)}", signedMoney(threshold1Bonus), true))
            }
            if (threshold2Turnover != null && threshold2Bonus != null && threshold2Bonus > 0) {
                add(RuleTerm("t2", "Оборот от ${money(threshold2Turnover)}", signedMoney(threshold2Bonus), true))
            }
            if (seniorOperatorBonus != null && seniorOperatorBonus > 0) {
                add(RuleTerm("so", "Старший оператор на смене", signedMoney(seniorOperatorBonus), true))
            }
            if (seniorCashierBonus != null && seniorCashierBonus > 0) {
                add(RuleTerm("sc", "Старший кассир на смене", signedMoney(seniorCashierBonus), true))
            }
        }

    companion object {
        fun from(o: JsonObject) = SalaryRule(
            id = o.str("id") ?: "",
            companyCode = o.str("company_code") ?: "",
            shiftType = o.str("shift_type") ?: "day",
            basePerShift = o.dbl("base_per_shift"),
            seniorOperatorBonus = o.dblOrNull("senior_operator_bonus"),
            seniorCashierBonus = o.dblOrNull("senior_cashier_bonus"),
            threshold1Turnover = o.dblOrNull("threshold1_turnover"),
            threshold1Bonus = o.dblOrNull("threshold1_bonus"),
            threshold2Turnover = o.dblOrNull("threshold2_turnover"),
            threshold2Bonus = o.dblOrNull("threshold2_bonus"),
            lowTurnoverThreshold = o.dblOrNull("low_turnover_threshold"),
            lowTurnoverBase = o.dblOrNull("low_turnover_base"),
            isActive = o.bool("is_active", true),
            effectiveFrom = o.str("effective_from"),
        )
    }
}

data class SalaryRuleVersion(val ruleId: String, val effectiveFrom: String?, val basePerShift: Double?)

data class SeniorityTier(val id: String, val minMonths: Int, val bonusPercent: Double, val isActive: Boolean) {
    /** «2 года 3 месяца», «6 месяцев». */
    val tenureLabel: String
        get() {
            val years = minMonths / 12
            val months = minMonths % 12
            val y = "$years ${plural(years, "год", "года", "лет")}"
            val m = "$months ${plural(months, "месяц", "месяца", "месяцев")}"
            return when {
                years == 0 -> m
                months == 0 -> y
                else -> "$y $m"
            }
        }
}

data class SalaryRuleChange(
    val id: String,
    val action: String,
    val createdAt: String?,
    val actorEmail: String?,
    val companyCode: String?,
    val shiftType: String?,
    val previousBase: Double?,
    val nextBase: Double?,
) {
    val actionLabel: String
        get() = when (action) {
            "create" -> "создано"
            "update" -> "изменено"
            "delete" -> "удалено"
            else -> action
        }

    /** Изменение ставки; null, если нечего сравнивать или не поменялась. */
    val baseDelta: Double?
        get() {
            val p = previousBase ?: return null
            val n = nextBase ?: return null
            return (n - p).takeIf { it != 0.0 }
        }
}

data class SalaryRuleBook(
    val rules: List<SalaryRule>,
    val companies: List<ScheduleCompany>,
    val companyCodes: Map<String, String>,
    val versions: List<SalaryRuleVersion>,
    val seniorityTiers: List<SeniorityTier>,
    val history: List<SalaryRuleChange>,
) {
    val activeRules: List<SalaryRule> get() = rules.filter { it.isActive }
    val activeTiers: List<SeniorityTier> get() = seniorityTiers.filter { it.isActive }.sortedBy { it.minMonths }
    val maxSeniorityPercent: Double get() = activeTiers.maxOfOrNull { it.bonusPercent } ?: 0.0

    fun companyName(code: String): String =
        companies.firstOrNull { companyCodes[it.id].orEmpty().equals(code, ignoreCase = true) }?.name ?: code.uppercase()

    /**
     * Прошлые смены считаются по своей версии ставки: без этого списка непонятно,
     * почему старая неделя не сошлась с текущей ставкой.
     */
    fun versionHistory(ruleId: String): List<SalaryRuleVersion> =
        versions.filter { it.ruleId == ruleId }.sortedByDescending { it.effectiveFrom.orEmpty() }

    companion object {
        fun from(root: JsonElement): SalaryRuleBook {
            val d = root.unwrapData().asObject()
            val companyObjs = d?.get("companies").asObjects()
            return SalaryRuleBook(
                rules = d?.get("rules").asObjects().map(SalaryRule::from),
                companies = companyObjs.map { ScheduleCompany(it.str("id") ?: "", it.str("name") ?: "Точка") },
                companyCodes = companyObjs.associate { (it.str("id") ?: "") to (it.str("code") ?: "") },
                versions = d?.get("ruleVersions").asObjects().map {
                    SalaryRuleVersion(it.str("rule_id") ?: "", it.str("effective_from"), it.dblOrNull("base_per_shift"))
                },
                seniorityTiers = d?.get("seniorityTiers").asObjects().map {
                    SeniorityTier(it.str("id") ?: "", it.int("min_months"), it.dbl("bonus_percent"), it.bool("is_active", true))
                },
                history = d?.get("history").asObjects().map {
                    val payload = it["payload"].asObject()
                    val prev = payload?.get("previous").asObject()?.dblOrNull("base_per_shift")
                    val next = payload?.get("next").asObject()?.dblOrNull("base_per_shift") ?: payload?.dblOrNull("base_per_shift")
                    SalaryRuleChange(
                        id = it.str("id") ?: "",
                        action = it.str("action") ?: "update",
                        createdAt = it.str("created_at"),
                        actorEmail = it.str("actor_email"),
                        companyCode = payload?.str("company_code"),
                        shiftType = payload?.str("shift_type"),
                        previousBase = prev,
                        nextBase = next,
                    )
                },
            )
        }
    }
}

// ── Кадры: GET /api/admin/hr ─────────────────────────────────────────────────

data class HrPerson(
    val id: String,
    /** `operator` или `staff`. */
    val kind: String,
    val fullName: String,
    val position: String?,
    val role: String?,
    val phone: String?,
    val email: String?,
    val hireDate: String?,
    val isActive: Boolean,
    val isHybrid: Boolean,
    val hasLogin: Boolean,
    val lastLogin: String?,
    val dismissalDate: String?,
    val dismissedAt: String?,
    val dismissalType: String?,
    val dismissalReason: String?,
    val dismissedByName: String?,
    val monthlySalary: Double?,
    /** Старшая роль на точке: повышенный оператор показывается старшим, а не просто оператором. */
    val pointRole: String? = null,
) {
    val isOperator: Boolean get() = kind == "operator"
    val isDismissed: Boolean get() = !isActive

    /** Последний день: дата увольнения, иначе момент оформления. */
    val leftOn: String? get() = dismissalDate ?: dismissedAt

    val roleLabel: String
        get() = when (val r = pointRole ?: role.orEmpty()) {
            "" -> if (isOperator) "Оператор" else "Сотрудник"
            // Должности заводит владелец сам — незнакомое значение показываем как есть.
            else -> if (r in knownRoles) roleTitle(r) else r
        }

    val dismissalTypeLabel: String?
        get() = when (dismissalType) {
            null, "" -> null
            "voluntary" -> "По собственному желанию"
            "mutual_agreement" -> "По соглашению сторон"
            "cause" -> "По статье"
            "contract_end" -> "Истёк срок договора"
            "other" -> "Другое"
            else -> dismissalType
        }

    /** Полных месяцев в компании: до увольнения, а у работающего — до сегодня. */
    fun tenureMonths(today: LocalDate = LocalDate.now()): Int? {
        val hired = WeekMath.parse(hireDate) ?: return null
        val end = WeekMath.parse(leftOn) ?: today
        if (!end.isAfter(hired)) return 0
        return ChronoUnit.MONTHS.between(hired, end).toInt().coerceAtLeast(0)
    }

    /** Ушёл не раньше, чем `days` дней назад. */
    fun leftWithin(days: Long, today: LocalDate = LocalDate.now()): Boolean {
        if (!isDismissed) return false
        val left = WeekMath.parse(leftOn) ?: return false
        return !left.isBefore(today.minusDays(days))
    }

    companion object {
        private val knownRoles = setOf("owner", "manager", "marketer", "accountant", "operator", "senior_operator", "senior_cashier", "other")

        fun from(o: JsonObject): HrPerson {
            val full = o.str("full_name")
            val short = o.str("short_name")
            return HrPerson(
                id = o.str("id") ?: "",
                kind = o.str("kind") ?: "staff",
                fullName = listOfNotNull(full, short).firstOrNull { it.isNotEmpty() } ?: "Без имени",
                position = o.str("position"),
                role = o.str("role"),
                phone = o.str("phone"),
                email = o.str("email"),
                hireDate = o.str("hire_date"),
                isActive = o.bool("is_active", true),
                isHybrid = o.bool("is_hybrid", false),
                hasLogin = o.bool("has_login", false),
                lastLogin = o.str("last_login"),
                dismissalDate = o.str("dismissal_date"),
                dismissedAt = o.str("dismissed_at"),
                dismissalType = o.str("dismissal_type"),
                dismissalReason = o.str("dismissal_reason"),
                dismissedByName = o.str("dismissed_by_name"),
                monthlySalary = o.dblOrNull("monthly_salary"),
                pointRole = o.str("point_role"),
            )
        }

        fun list(root: JsonElement): List<HrPerson> = root.unwrapData().asObjects().map(::from).filter { it.id.isNotEmpty() }
    }
}

/** Порядок в «Кадрах»: работающие по алфавиту, уволенные ниже — свежие увольнения выше старых. */
fun List<HrPerson>.sortedForHr(): List<HrPerson> {
    val (gone, working) = partition { it.isDismissed }
    return working.sortedBy { it.fullName.lowercase(Locale.ROOT) } + gone.sortedByDescending { it.leftOn.orEmpty() }
}

// ── Структура команды: GET /api/admin/structure ──────────────────────────────

data class StructureAssignment(val operatorId: String, val companyId: String, val roleInCompany: String, val isPrimary: Boolean) {
    val roleLabel: String
        get() = when (roleInCompany) {
            "senior_operator" -> "Старший оператор"
            "senior_cashier" -> "Старший кассир"
            else -> "Оператор"
        }
    val isSenior: Boolean get() = roleInCompany != "operator"
}

data class CareerLink(val operatorId: String, val staffId: String, val operatorName: String?)

data class StructureCompany(val id: String, val name: String, val code: String?)

data class TeamStructure(
    val leads: List<StaffMember>,
    val companies: List<StructureCompany>,
    val operators: List<TeamOperator>,
    val assignments: List<StructureAssignment>,
    val careerLinks: List<CareerLink>,
) {
    fun staffedBy(companyId: String): List<TeamOperator> {
        val ids = assignments.filter { it.companyId == companyId }.map { it.operatorId }.toSet()
        return operators.filter { it.id in ids }
    }

    fun assignment(operatorId: String, companyId: String): StructureAssignment? =
        assignments.firstOrNull { it.operatorId == operatorId && it.companyId == companyId }

    /** Без точки человек выпадает из графика и расчёта смены. */
    val unassignedOperators: List<TeamOperator>
        get() {
            val assigned = assignments.map { it.operatorId }.toSet()
            return operators.filter { it.id !in assigned }
        }

    fun subordinates(leadId: String): List<CareerLink> = careerLinks.filter { it.staffId == leadId }

    val operatorsWithoutLead: List<TeamOperator>
        get() {
            val linked = careerLinks.map { it.operatorId }.toSet()
            return operators.filter { it.id !in linked }
        }

    companion object {
        fun from(root: JsonElement): TeamStructure {
            val d = root.unwrapData().asObject()
            return TeamStructure(
                leads = d?.get("staff").asObjects().map {
                    StaffMember(
                        id = it.str("id") ?: "", fullName = it.str("full_name") ?: "Сотрудник", role = it.str("role") ?: "other",
                        monthlySalary = it.dbl("monthly_salary"), isActive = it.bool("is_active", true),
                        phone = it.str("phone"), email = it.str("email"),
                    )
                },
                companies = d?.get("companies").asObjects().map { StructureCompany(it.str("id") ?: "", it.str("name") ?: "Точка", it.str("code")) },
                operators = d?.get("operators").asObjects().map(TeamOperator::from),
                assignments = d?.get("assignments").asObjects().map {
                    StructureAssignment(
                        it.str("operator_id") ?: "", it.str("company_id") ?: "",
                        it.str("role_in_company") ?: "operator", it.bool("is_primary", false),
                    )
                },
                careerLinks = d?.get("careerLinks").asObjects().map {
                    val op = it["operator"].asObject()
                    // Связь профиля приходит объектом или массивом — зависит от схемы, а не от данных.
                    val profileName = when (val p = op?.get("operator_profiles")) {
                        is JsonObject -> p.str("full_name")
                        is JsonArray -> (p.firstOrNull() as? JsonObject)?.str("full_name")
                        else -> null
                    }
                    CareerLink(it.str("operator_id") ?: "", it.str("staff_id") ?: "", profileName ?: op?.str("name"))
                },
            )
        }
    }
}
