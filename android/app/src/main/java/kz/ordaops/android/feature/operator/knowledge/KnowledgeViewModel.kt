package kz.ordaops.android.feature.operator.knowledge

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.operator.common.OperatorEnv
import kz.ordaops.android.feature.operator.work.ExplainResponse
import kz.ordaops.android.feature.operator.work.KnowledgeArticle
import kz.ordaops.android.feature.operator.work.KnowledgeCenter
import kz.ordaops.android.feature.operator.work.OFFLINE_NOTICE
import kz.ordaops.android.feature.operator.work.operatorMessage

/** Статья с уже разобранной разметкой: HTML разбирается один раз, а не при каждой перерисовке. */
@Immutable
data class ArticleUi(
    val article: KnowledgeArticle,
    val needsConfirmation: Boolean,
    val blocks: List<RichText.Block>,
)

@Immutable
data class KnowledgeUi(
    val articles: List<ArticleUi>,
    val pendingCount: Int,
    val total: Int,
    val canConfirm: Boolean,
) {
    private val byId = articles.associateBy { it.article.id }
    fun find(id: String?): ArticleUi? = id?.let { byId[it] }

    companion object {
        /**
         * Сначала непрочитанные, потом важные, потом остальные — правила про кассу ищут в спешке.
         * Разметку разбираем тут же: вызывать вне главного потока.
         */
        fun of(center: KnowledgeCenter): KnowledgeUi {
            val pending = center.pendingConfirmations
            val ids = pending.map { it.id }.toSet()
            val rest = center.articles.filter { it.id !in ids }
            val ordered = pending + rest.filter { it.isCritical } + rest.filter { !it.isCritical }
            return KnowledgeUi(
                articles = ordered.map { a ->
                    val blocks = RichText.blocks(a.text).ifEmpty {
                        a.summary?.takeIf { it.isNotEmpty() }?.let { listOf(RichText.Block(RichText.Kind.Paragraph, it)) }.orEmpty()
                    }
                    ArticleUi(a, a.id in ids, blocks)
                },
                pendingCount = ids.size,
                total = center.articles.size,
                canConfirm = center.canConfirm,
            )
        }
    }
}

/** Подтверждение прочтения: чья статья, идёт ли отправка, чем кончилось. */
@Immutable
data class ConfirmUi(val articleId: String? = null, val busy: Boolean = false, val error: String? = null)

/** «Объясни проще»: ответ держим здесь, чтобы поворот не гонял запрос заново. */
@Immutable
data class ExplainUi(
    val articleId: String? = null,
    val answer: String? = null,
    val isAsking: Boolean = false,
    val error: String? = null,
    /** Растёт с каждым успешным ответом — по нему экран очищает поле вопроса. */
    val answers: Int = 0,
)

class KnowledgeViewModel(private val env: OperatorEnv) : ViewModel() {
    private val api get() = env.api
    private val request = ApiRequest("/api/operator/knowledge")

    private val _state = MutableStateFlow(Loadable<KnowledgeUi>())
    val state: StateFlow<Loadable<KnowledgeUi>> = _state.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _confirm = MutableStateFlow(ConfirmUi())
    val confirm: StateFlow<ConfirmUi> = _confirm.asStateFlow()

    private val _explain = MutableStateFlow(ExplainUi())
    val explain: StateFlow<ExplainUi> = _explain.asStateFlow()

    private var loadJob: Job? = null

    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch { loadKnowledge() }
    }

    private suspend fun loadKnowledge() {
        _state.load(
            cached = { api.cached<KnowledgeCenter>(request)?.let { withContext(Dispatchers.Default) { KnowledgeUi.of(it) } } },
            fresh = { val c = api.send<KnowledgeCenter>(request); withContext(Dispatchers.Default) { KnowledgeUi.of(c) } },
        )
    }

    /** Подтвердить прочтение. Версию передаём обязательно: правка текста не должна остаться незамеченной. */
    fun confirmArticle(article: KnowledgeArticle) {
        if (_confirm.value.busy) return
        _confirm.value = ConfirmUi(article.id, busy = true)
        viewModelScope.launch {
            try {
                val body = buildJsonObject {
                    put("article_id", article.id)
                    put("version", article.version)
                }
                val sent = env.outbox.perform(
                    "/api/operator/knowledge/confirm", HttpMethod.POST, body.toString(),
                    "Подтверждение прочтения", "knowledge:${article.id}:${article.version}",
                )
                _notice.value = if (sent) null else OFFLINE_NOTICE
                _confirm.value = ConfirmUi()
                loadKnowledge()
            } catch (e: Exception) {
                _confirm.value = ConfirmUi(article.id, error = e.operatorMessage())
            }
        }
    }

    /**
     * «Объясни проще»: сервер пересказывает статью простыми словами и отвечает
     * строго по тексту. Успех — текст ответа, неудача — сообщение.
     */
    fun explainArticle(id: String, question: String?) {
        if (_explain.value.isAsking) return
        // Другая статья — старый ответ к ней не относится.
        _explain.update {
            if (it.articleId == id) it.copy(isAsking = true, error = null)
            else ExplainUi(articleId = id, isAsking = true, answers = it.answers)
        }
        viewModelScope.launch {
            try {
                val body = buildJsonObject {
                    put("article_id", id)
                    if (!question.isNullOrEmpty()) put("question", question)
                }
                val text = api.send<ExplainResponse>(ApiRequest.json("/api/operator/knowledge/explain", body)).text
                _explain.update { it.copy(answer = text, isAsking = false, answers = it.answers + 1) }
            } catch (e: Exception) {
                _explain.update { it.copy(isAsking = false, error = e.operatorMessage()) }
            }
        }
    }
}
