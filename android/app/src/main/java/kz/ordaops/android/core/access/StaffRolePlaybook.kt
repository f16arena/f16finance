package kz.ordaops.android.core.access

/**
 * Чем живёт должность: что у неё на главной, какие быстрые кнопки, вкладки и
 * сервисы — в каком порядке. Только порядок и подача, права не расширяет:
 * показывается лишь то, что выдано на `/access`. Порт StaffRolePlaybook.swift.
 */
data class StaffRolePlaybook(
    val title: String,
    /** Одна фраза о смысле работы — подпись на главной карточке. */
    val focus: String,
    val actions: List<Action>,
    /** Разделы, которые выносятся во вкладки, по важности. */
    val tabPageIds: List<String>,
    /** Сервисы на главной, по важности. */
    val favoritePageIds: List<String>,
) {
    sealed interface Action {
        data object AddExpense : Action
        data object AddIncome : Action
        data object AddTask : Action
        data class Page(val id: String) : Action
    }

    companion object {
        /** Незнакомая должность (свою заводит владелец) — общий набор с её названием. */
        fun forRole(staffRole: String?): StaffRolePlaybook = when (val role = staffRole.orEmpty()) {
            "owner" -> Owner
            "manager" -> Manager
            "accountant" -> Accountant
            "marketer" -> Marketer
            "senior_operator" -> SeniorOperator
            "senior_cashier" -> SeniorCashier
            "operator" -> employee("Оператор")
            "", "other" -> employee("Сотрудник")
            else -> employee(role)
        }

        val Owner = StaffRolePlaybook(
            title = "Владелец",
            focus = "Весь бизнес на одном экране.",
            actions = listOf(Action.AddExpense, Action.AddIncome, Action.AddTask),
            tabPageIds = listOf("team-chat", "tasks"),
            favoritePageIds = listOf(
                "income", "expenses", "cashflow", "profitability",
                "store-warehouse", "salary", "shifts", "tasks",
                "reports", "operators", "point-debts", "store-catalog",
            ),
        )

        val Manager = StaffRolePlaybook(
            title = "Управляющий",
            focus = "Точки работают, команда на месте.",
            actions = listOf(Action.AddTask, Action.AddExpense, Action.Page("shifts"), Action.Page("store-requests")),
            tabPageIds = listOf("tasks", "shifts", "team-chat"),
            favoritePageIds = listOf(
                "shifts", "tasks", "operators", "store-warehouse",
                "store-requests", "expenses-pending", "shifts-reports", "incidents",
                "staff", "salary", "store-revisions", "point-debts",
            ),
        )

        val Accountant = StaffRolePlaybook(
            title = "Бухгалтер",
            focus = "Деньги сходятся до тенге.",
            actions = listOf(Action.AddExpense, Action.AddIncome, Action.Page("expenses-pending"), Action.Page("salary")),
            tabPageIds = listOf("expenses-pending", "team-chat", "tasks"),
            favoritePageIds = listOf(
                "income", "expenses", "expenses-pending", "cashflow",
                "salary", "tax", "point-debts", "profitability",
                "reports", "expense-whitelist", "categories", "store-suppliers",
            ),
        )

        val Marketer = StaffRolePlaybook(
            title = "Маркетолог",
            focus = "Гости приходят и возвращаются.",
            actions = listOf(Action.AddTask, Action.Page("customers"), Action.Page("store-advertising"), Action.Page("news")),
            tabPageIds = listOf("customers", "team-chat", "tasks"),
            favoritePageIds = listOf(
                "customers", "store-advertising", "news", "messages",
                "sales-kpi", "analytics", "store-analytics", "birthdays",
                "ratings", "moderation", "telegram", "goals",
            ),
        )

        val SeniorOperator = StaffRolePlaybook(
            title = "Старший оператор",
            focus = "Смены идут по плану.",
            actions = listOf(Action.AddTask, Action.Page("shifts"), Action.Page("store-revisions"), Action.Page("incidents")),
            tabPageIds = listOf("shifts", "tasks", "team-chat"),
            favoritePageIds = listOf(
                "shifts", "shifts-reports", "operators", "store-revisions",
                "store-requests", "store-warehouse", "incidents", "operator-exams",
                "knowledge-admin", "operator-analytics", "store-writeoffs", "tasks",
            ),
        )

        val SeniorCashier = StaffRolePlaybook(
            title = "Старший кассир",
            focus = "Касса сходится каждую смену.",
            actions = listOf(Action.Page("pos-receipts"), Action.Page("pos-returns"), Action.Page("shifts-reports"), Action.Page("point-debts")),
            tabPageIds = listOf("shifts-reports", "tasks", "team-chat"),
            favoritePageIds = listOf(
                "pos-receipts", "pos-returns", "shifts-reports", "point-debts",
                "income", "customers", "store-showcase", "shifts",
                "store-receipt-settings", "incidents", "tasks", "knowledge-admin",
            ),
        )

        fun employee(title: String) = StaffRolePlaybook(
            title = title,
            focus = "Задачи, график и всё нужное под рукой.",
            actions = listOf(Action.AddTask, Action.Page("shifts"), Action.Page("knowledge-admin"), Action.Page("news")),
            tabPageIds = listOf("tasks", "team-chat", "shifts"),
            favoritePageIds = listOf(
                "tasks", "shifts", "knowledge-admin", "news",
                "team-chat", "birthdays", "operator-achievements", "ratings",
            ),
        )
    }
}

/** Набор должности вошедшего. */
val AccessResolver.playbook: StaffRolePlaybook get() = StaffRolePlaybook.forRole(session.staffRole)
