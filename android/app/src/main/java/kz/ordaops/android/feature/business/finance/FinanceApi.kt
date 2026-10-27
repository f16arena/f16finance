package kz.ordaops.android.feature.business.finance

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.feature.business.BizList
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Запросы раздела «Финансы» — то, что на айфоне живёт в `BusinessService` и
 * `ExpenseWizardService`. Только пути, которые уже зовёт айфон.
 */
class FinanceApi(val api: ApiClient) {

    /** Потолок страницы у роутов доходов/расходов: признака «есть ещё» они не отдают. */
    private val pageSize = "5000"

    suspend fun incomes(from: String, to: String): List<IncomeRow> =
        api.send<BizList<IncomeRow>>(incomesRequest(from, to)).data

    suspend fun cachedIncomes(from: String, to: String): List<IncomeRow>? =
        api.cached<BizList<IncomeRow>>(incomesRequest(from, to))?.data

    suspend fun expenses(from: String, to: String): List<ExpenseRow> =
        api.send<BizList<ExpenseRow>>(expensesRequest(from, to)).data

    suspend fun cachedExpenses(from: String, to: String): List<ExpenseRow>? =
        api.cached<BizList<ExpenseRow>>(expensesRequest(from, to))?.data

    private fun incomesRequest(from: String, to: String) =
        ApiRequest("/api/admin/incomes", query = mapOf("from" to from, "to" to to, "page_size" to pageSize))

    private fun expensesRequest(from: String, to: String) =
        ApiRequest("/api/admin/expenses", query = mapOf("from" to from, "to" to to, "page_size" to pageSize))

    /** Дубль (409 `duplicate`) приходит как `ApiError.Conflict`; `force` — «сохранить всё равно». */
    suspend fun createIncome(draft: IncomeDraft, force: Boolean) =
        api.sendUnit(ApiRequest.json("/api/admin/incomes", draft.body(force)))

    suspend fun updateIncome(edit: IncomeEdit) = api.sendUnit(ApiRequest.json("/api/admin/incomes", edit.body()))

    suspend fun updateExpense(edit: ExpenseEdit) = api.sendUnit(ApiRequest.json("/api/admin/expenses", edit.body()))

    suspend fun deleteIncome(id: String) = api.sendUnit(
        ApiRequest.json("/api/admin/incomes", buildJsonObject { put("action", "deleteIncome"); put("incomeId", id) }),
    )

    suspend fun deleteExpense(id: String) = api.sendUnit(
        ApiRequest.json("/api/admin/expenses", buildJsonObject { put("action", "deleteExpense"); put("expenseId", id) }),
    )

    // ── Одобрение расходов ──────────────────────────────────────────────────

    suspend fun pending(): List<PendingItem> = api.send<BizList<PendingItem>>(ApiRequest("/api/admin/expenses/pending")).data

    suspend fun approve(id: String) = api.sendUnit(ApiRequest("/api/admin/expenses/$id/approve", HttpMethod.POST))

    suspend fun decline(id: String, reason: String?) {
        val body = if (reason.isNullOrBlank()) null else buildJsonObject { put("reason", reason) }
        api.sendUnit(
            if (body == null) ApiRequest("/api/admin/expenses/$id/decline", HttpMethod.POST)
            else ApiRequest.json("/api/admin/expenses/$id/decline", body),
        )
    }

    // ── Справочники ─────────────────────────────────────────────────────────

    suspend fun operators(): List<FinOperator> = api.send<BizList<FinOperator>>(ApiRequest("/api/admin/operators")).data

    suspend fun categories(): List<ExpenseCategory> =
        api.send<BizList<ExpenseCategory>>(ApiRequest("/api/admin/expense-categories")).data

    suspend fun vendors(): List<ExpenseVendor> =
        api.send<BizList<ExpenseVendor>>(ApiRequest("/api/admin/expenses/whitelist")).data

    // ── Мастер расхода ──────────────────────────────────────────────────────
    //
    // Расход одним запросом не создать (сервер отвечает 410 на createExpense):
    // открыть сессию → приложить документ → сохранить поля → отправить.

    @Serializable
    private data class SessionData(val id: String = "")

    @Serializable
    private data class SessionEnvelope(val data: SessionData = SessionData())

    @Serializable
    private data class UploadResult(@SerialName("document_urls") val documentUrls: List<String> = emptyList())

    suspend fun startExpenseSession(): String =
        api.send<SessionEnvelope>(ApiRequest.json("/api/admin/expenses/wizard", JsonObject(emptyMap()))).data.id

    /** Приложить документ. Возвращает все ссылки сессии, включая прежние. */
    suspend fun uploadExpenseDocument(sessionId: String, bytes: ByteArray, fileName: String, mimeType: String): List<String> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("session_id", sessionId)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        return api.send<UploadResult>(ApiRequest("/api/admin/expenses/wizard/upload", HttpMethod.POST, body = body)).documentUrls
    }

    /** Шаг и отправка идут подряд: форма на телефоне одна. */
    suspend fun submitExpense(sessionId: String, draft: ExpenseDraft) {
        api.sendUnit(
            ApiRequest.json(
                "/api/admin/expenses/wizard",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("step", 3)
                    put("payload", draft.payload())
                },
                HttpMethod.PATCH,
            ),
        )
        api.sendUnit(ApiRequest.json("/api/admin/expenses/wizard/submit", buildJsonObject { put("session_id", sessionId) }))
    }
}
