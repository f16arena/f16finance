package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod

// Запросы раздела «Команда» — те же маршруты и тела, что у `BusinessService`
// на айфоне. Новых эндпоинтов нет.

/** Запросы на чтение: путь и параметры, чтобы кэш `ApiClient` и загрузка совпадали. */
object TeamRequests {
    val operators = ApiRequest("/api/admin/operators")
    fun salary(weekStart: String) = ApiRequest("/api/admin/salary", query = mapOf("view" to "weekly", "weekStart" to weekStart))
    val staffSalary = ApiRequest("/api/admin/staff-salary", query = mapOf("view" to "summary", "include_archived" to "1"))
    val staff = ApiRequest("/api/admin/staff")
    fun schedule(weekStart: String) = ApiRequest("/api/admin/shifts", query = mapOf("weekStart" to weekStart, "includeSchedule" to "1"))
    fun shiftReports(limit: Int = 100) = ApiRequest("/api/admin/shifts/reports", query = mapOf("limit" to limit.toString()))
    val birthdays = ApiRequest("/api/admin/birthdays")
    fun operatorCard(id: String) = ApiRequest("/api/admin/operators/profile", query = mapOf("operator_id" to id))
    val myProfile = ApiRequest("/api/me/profile")
}

/** Логин и пароль, которые сервер вернул при заведении учётной записи. Пароль показывают один раз. */
data class OperatorAccount(val username: String, val password: String, val operatorId: String)

private fun JsonObjectBuilder.putOpt(key: String, value: String?) {
    if (!value.isNullOrBlank()) put(key, value)
}

/** Пустое поле сервер понимает как `null` — иначе нечем очистить значение. */
private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) put(key, JsonNull) else put(key, trimmed)
}

private suspend fun ApiClient.post(path: String, body: JsonObject, method: HttpMethod = HttpMethod.POST) {
    sendUnit(ApiRequest.json(path, body, method))
}

private suspend fun ApiClient.action(path: String, action: String, build: JsonObjectBuilder.() -> Unit) {
    post(path, buildJsonObject { put("action", action); build() })
}

/** Действие с `payload`: `{ "action": …, "payload": { … } }`. */
private suspend fun ApiClient.payloadAction(path: String, action: String, build: JsonObjectBuilder.() -> Unit) {
    post(path, buildJsonObject {
        put("action", action)
        put("payload", buildJsonObject(build))
    })
}

// ── Операторы ────────────────────────────────────────────────────────────────

suspend fun ApiClient.createOperator(name: String, fullName: String?, position: String?, phone: String?, email: String?) =
    payloadAction("/api/admin/operators", "createOperator") {
        put("name", name.trim())
        putOpt("full_name", fullName?.trim())
        putOpt("position", position?.trim())
        putOpt("phone", phone?.trim())
        putOpt("email", email?.trim())
    }

/** Завести вход. Пароль возвращается открытым ровно один раз. */
suspend fun ApiClient.createOperatorAccount(operatorId: String, username: String, name: String): OperatorAccount {
    val root = send<JsonElement>(
        ApiRequest.json(
            "/api/admin/create-operator-account",
            buildJsonObject {
                put("operatorId", operatorId)
                put("username", username)
                // Почта нужна серверу как признак «завести вход»; настоящий адрес он собирает из логина.
                put("email", "$username@operator.local")
                put("name", name)
            },
        ),
    )
    val o = root.asObject()
    return OperatorAccount(o?.str("username") ?: username, o?.str("password") ?: "", o?.str("operatorId") ?: operatorId)
}

suspend fun ApiClient.sendOperatorCredentials(operatorId: String, chatId: String, username: String, password: String, name: String) =
    post(
        "/api/admin/send-operator-credentials",
        buildJsonObject {
            put("operatorId", operatorId)
            put("chatId", chatId)
            put("username", username)
            put("password", password)
            put("name", name)
        },
    )

suspend fun ApiClient.setOperatorActive(operatorId: String, isActive: Boolean) =
    action("/api/admin/operators", "toggleOperatorActive") {
        put("operatorId", operatorId)
        put("is_active", isActive)
    }

suspend fun ApiClient.linkOperatorToStaff(operatorId: String) =
    action("/api/admin/operators", "linkStaff") { put("operatorId", operatorId) }

suspend fun ApiClient.resetOperatorPassword(userId: String, password: String) =
    post("/api/reset-password", buildJsonObject { put("userId", userId); put("password", password) })

suspend fun ApiClient.changeOperatorLogin(operatorId: String, username: String) =
    post("/api/admin/update-operator-login", buildJsonObject { put("operatorId", operatorId); put("username", username) })

suspend fun ApiClient.promoteOperator(operatorId: String, role: String, monthlySalary: Double?) =
    payloadAction("/api/admin/operator-career", "promoteOperator") {
        put("operatorId", operatorId)
        put("role", role)
        if (monthlySalary != null) put("monthly_salary", monthlySalary)
    }

/**
 * Сохранить карточку оператора: два запроса, как на айфоне. Профиль (`PATCH`)
 * заодно перезаписывает Telegram, поэтому текущий уходит обратно; имя — отдельным
 * `updateOperator`, который перезаписывает и ФИО, должность, телефон, почту.
 */
suspend fun ApiClient.saveOperatorCard(card: OperatorCard) {
    post(
        "/api/admin/operators/profile",
        buildJsonObject {
            put("operator_id", card.operatorId)
            putNullable("telegram_chat_id", card.telegramChatId)
            put("profile", buildJsonObject {
                putNullable("full_name", card.fullName)
                putNullable("phone", card.phone)
                putNullable("email", card.email)
                putNullable("position", card.position)
                putNullable("hire_date", card.hireDate)
                putNullable("birth_date", card.birthDate)
                putNullable("address", card.address)
                putNullable("photo_url", card.photoUrl)
                putNullable("emergency_contact_name", card.emergencyName)
                putNullable("emergency_contact_phone", card.emergencyPhone)
                putNullable("notes", card.notes)
            })
        },
        HttpMethod.PATCH,
    )
    action("/api/admin/operators", "updateOperator") {
        put("operatorId", card.operatorId)
        put("payload", buildJsonObject {
            put("name", card.name.trim())
            putNullable("short_name", card.shortName)
            putNullable("full_name", card.fullName)
            putNullable("position", card.position)
            putNullable("phone", card.phone)
            putNullable("email", card.email)
        })
    }
}

/** Уволить: `kind` — `operator` или `staff`. Причина обязательна. */
suspend fun ApiClient.dismissPerson(kind: String, id: String, reason: String, dismissalType: String, cascadePaired: Boolean) =
    post(
        "/api/admin/hr/dismiss",
        buildJsonObject {
            put("kind", kind)
            put("id", id)
            put("reason", reason)
            put("dismissal_type", dismissalType)
            put("cascade_paired", cascadePaired)
        },
    )

suspend fun ApiClient.restorePerson(kind: String, id: String) =
    post("/api/admin/hr/restore", buildJsonObject { put("kind", kind); put("id", id) })

// ── Сотрудники ───────────────────────────────────────────────────────────────

suspend fun ApiClient.createStaff(fullName: String, role: String, monthlySalary: Double, phone: String?, email: String?) =
    payloadAction("/api/admin/staff", "createStaff") {
        put("full_name", fullName)
        put("role", role)
        put("monthly_salary", monthlySalary)
        putOpt("phone", phone)
        putOpt("email", email)
    }

/** Письмо сотруднику: приглашение или смена пароля. Возвращает текст сервера. */
suspend fun ApiClient.sendStaffAccessEmail(staffId: String, invite: Boolean): String {
    val root = send<JsonElement>(
        ApiRequest.json(
            "/api/admin/staff-accounts",
            buildJsonObject {
                put("action", if (invite) "inviteStaffAccount" else "sendPasswordReset")
                put("staffId", staffId)
            },
        ),
    )
    return root.asObject()?.str("message") ?: "Письмо отправлено."
}

// ── Зарплата операторов (неделя) ─────────────────────────────────────────────

suspend fun ApiClient.paySalaryWeek(
    operatorId: String, weekStart: String, paymentDate: String,
    cash: Double, kaspi: Double, comment: String?, withAdvance: Boolean,
) = payloadAction("/api/admin/salary", if (withAdvance) "createPaymentWithAdvance" else "createWeeklyPayment") {
    put("operator_id", operatorId)
    put("week_start", weekStart)
    put("payment_date", paymentDate)
    put("cash_amount", cash)
    put("kaspi_amount", kaspi)
    putOpt("comment", comment?.trim())
}

suspend fun ApiClient.createSalaryAdvance(
    operatorId: String, companyId: String, weekStart: String, paymentDate: String,
    cash: Double, kaspi: Double, comment: String?,
) = payloadAction("/api/admin/salary", "createAdvance") {
    put("operator_id", operatorId)
    put("company_id", companyId)
    put("week_start", weekStart)
    put("payment_date", paymentDate)
    put("cash_amount", cash)
    put("kaspi_amount", kaspi)
    putOpt("comment", comment?.trim())
}

/** Премия или штраф: `kind` — `bonus` или `fine`, сумма положительная — знак задаёт вид. */
suspend fun ApiClient.createSalaryAdjustment(
    operatorId: String, companyId: String?, date: String, amount: Double, kind: String, comment: String?,
) = payloadAction("/api/admin/salary", "createAdjustment") {
    put("operator_id", operatorId)
    put("date", date)
    put("amount", amount)
    put("kind", kind)
    putOpt("company_id", companyId)
    putOpt("comment", comment?.trim())
}

suspend fun ApiClient.voidSalaryPayment(paymentId: String, operatorId: String, weekStart: String) =
    action("/api/admin/salary", "voidPayment") {
        put("paymentId", paymentId); put("operatorId", operatorId); put("weekStart", weekStart)
    }

suspend fun ApiClient.voidSalaryAdjustment(adjustmentId: String, operatorId: String, weekStart: String) =
    action("/api/admin/salary", "voidAdjustment") {
        put("adjustmentId", adjustmentId); put("operatorId", operatorId); put("weekStart", weekStart)
    }

suspend fun ApiClient.markOperatorDebtsPaid(operatorId: String, weekStart: String) =
    action("/api/admin/salary", "markDebtsPaid") { put("operatorId", operatorId); put("weekStart", weekStart) }

suspend fun ApiClient.sendSalaryToTelegram(operatorId: String, weekStart: String, weekEnd: String) =
    post(
        "/api/telegram/salary-snapshot",
        buildJsonObject {
            put("operatorId", operatorId)
            put("dateFrom", weekStart)
            put("dateTo", weekEnd)
            put("weekStart", weekStart)
        },
    )

// ── Зарплата админ-состава (половина месяца) ─────────────────────────────────

suspend fun ApiClient.payStaffSalary(
    staffId: String, companyId: String, payDate: String, slot: String,
    cash: Double, kaspi: Double, expected: Double, comment: String?,
) = post(
    "/api/admin/staff-salary",
    buildJsonObject {
        put("action", "createPayment")
        put("staff_id", staffId)
        put("company_id", companyId)
        put("pay_date", payDate)
        put("slot", slot)
        put("cash_amount", cash)
        put("kaspi_amount", kaspi)
        // Сумму сервер считает заново; это число уходит только в аудит.
        put("expected_amount", expected)
        putOpt("comment", comment?.trim())
    },
)

suspend fun ApiClient.addStaffExtraDay(staffId: String, date: String, amount: Double?) =
    post(
        "/api/admin/staff-salary",
        buildJsonObject {
            put("action", "addExtraDay")
            put("staff_id", staffId)
            put("date", date)
            if (amount != null && amount > 0) put("custom_amount", amount)
        },
    )

/** Премия, штраф или аванс сотруднику. Точка обязательна только для аванса. */
suspend fun ApiClient.createStaffAdjustment(
    staffId: String, companyId: String?, kind: String, amount: Double, date: String, comment: String?,
) = post(
    "/api/admin/staff-salary",
    buildJsonObject {
        put("action", "addAdjustment")
        put("staff_id", staffId)
        put("kind", kind)
        put("amount", amount)
        put("date", date)
        putOpt("company_id", companyId)
        putOpt("comment", comment?.trim())
    },
)

// ── Смены ────────────────────────────────────────────────────────────────────

/** Пустое имя освобождает смену — отдельного «удалить» у расписания нет. */
suspend fun ApiClient.saveShift(companyId: String, date: String, shiftType: String, operatorName: String) =
    payloadAction("/api/admin/shifts", "saveShift") {
        put("companyId", companyId)
        put("date", date)
        put("shiftType", shiftType)
        put("operatorName", operatorName)
    }

suspend fun ApiClient.resolveShiftIssue(
    requestId: String, status: String, action: String, replacement: String?, note: String?,
) = payloadAction("/api/admin/shifts", "resolveIssue") {
    put("requestId", requestId)
    put("status", status)
    put("resolutionAction", action)
    putOpt("replacementOperatorName", replacement)
    putOpt("resolutionNote", note?.trim())
}

suspend fun ApiClient.reopenShift(id: String, reason: String) =
    post("/api/admin/shifts/reports", buildJsonObject { put("shiftId", id); put("reason", reason) })

// ── Мои данные ───────────────────────────────────────────────────────────────

/** Свои контакты. `telegram` шлём только операторам. */
suspend fun ApiClient.saveMyProfile(phone: String?, email: String?, telegram: String?) =
    post(
        "/api/me/profile",
        buildJsonObject {
            if (phone != null) put("phone", phone)
            if (email != null) put("email", email)
            if (telegram != null) put("telegram_chat_id", telegram)
        },
        HttpMethod.PATCH,
    )
