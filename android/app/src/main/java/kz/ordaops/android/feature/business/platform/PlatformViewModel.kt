package kz.ordaops.android.feature.business.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.HttpMethod
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.core.ui.Loadable
import kz.ordaops.android.core.ui.load
import kz.ordaops.android.feature.business.ops.obj

private const val ORGS_PATH = "/api/admin/organizations"

/** Платформенные запросы — не тенантные: заголовок организации им только мешает (как на айфоне). */
private fun platformGet(path: String, query: Map<String, String> = emptyMap()) =
    ApiRequest(path, query = query, skipsOrganizationHeader = true)

/**
 * Состояние платформы: организации, пакеты, каталог функций. Один запрос
 * `GET /api/admin/organizations` отдаёт всё; после записи подменяем одну
 * организацию тем, что вернул `PATCH`, а не перечитываем платформу целиком.
 */
class PlatformViewModel(private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<PlatformData>())
    val state: StateFlow<Loadable<PlatformData>> = _state.asStateFlow()
    private var job: Job? = null

    /** Сколько действий выключено рубильником у организации (только чтение, ключ — id организации). */
    private val _disabledActions = MutableStateFlow<Map<String, Int>>(emptyMap())
    val disabledActions: StateFlow<Map<String, Int>> = _disabledActions.asStateFlow()

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            val request = platformGet(ORGS_PATH)
            _state.load(
                cached = { api.cached<JsonObject>(request)?.let(PlatformData::parse) },
                fresh = { PlatformData.parse(api.send<JsonObject>(request)) },
            )
        }
    }

    /** Название организации по id — для баннера «смотрите как владелец»; пока список не пришёл, null. */
    fun nameOf(id: String?): String? = _state.value.data?.org(id)?.name

    /**
     * Записать изменение организации. Успех — `null`, отказ — человеческий текст.
     * Подменяет организацию из ответа; если ответ без неё — перечитывает платформу.
     */
    suspend fun patch(body: JsonObject): String? = try {
        val response = api.send<JsonObject>(
            ApiRequest.json(ORGS_PATH, body, HttpMethod.PATCH).copy(skipsOrganizationHeader = true),
        )
        val updated = response.obj("organization")?.let(PlatformOrg::parse)?.takeIf { it.id.isNotEmpty() }
        val current = _state.value.data
        if (updated != null && current != null) {
            _state.value = Loadable(data = current.replacing(updated))
        } else {
            refresh()
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.userMessage()
    }

    /** Рубильник действий организации: сколько их выключено. Ошибки глотаем — это справка, а не данные экрана. */
    fun loadDisabledActions(organizationId: String) {
        viewModelScope.launch {
            runCatching {
                val root = api.send<JsonObject>(platformGet("$ORGS_PATH/capabilities", mapOf("organization_id" to organizationId)))
                (root["disabled"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
            }.onSuccess { count -> _disabledActions.value = _disabledActions.value + (organizationId to count) }
        }
    }
}

/** Сервер и деплои: `GET /api/admin/platform/status`, обновляется по запросу и раз в несколько секунд, пока экран открыт. */
class ServerStatusViewModel(private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<ServerStatus>())
    val state: StateFlow<Loadable<ServerStatus>> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.load(fresh = { ServerStatus.parse(api.send<JsonObject>(platformGet("/api/admin/platform/status"))) })
        }
    }

    /** Журнал хоста текстом (`log` = deploy/cron/backup). */
    suspend fun log(name: String, tail: Int = 200): String =
        api.download(platformGet("/api/admin/platform/status/logs", mapOf("log" to name, "tail" to "$tail"))).decodeToString()
}

/** Журнал ошибок за выбранный период: `GET /api/admin/platform/errors?period=`. */
class ErrorsViewModel(private val api: ApiClient) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<ErrorsReport>())
    val state: StateFlow<Loadable<ErrorsReport>> = _state.asStateFlow()
    private var job: Job? = null

    fun load(period: String) {
        job?.cancel()
        // Цифры другого периода читались бы как ответ на этот — забываем показанное.
        _state.value = Loadable(isLoading = true)
        job = viewModelScope.launch {
            _state.load(fresh = { ErrorsReport.parse(api.send<JsonObject>(platformGet("/api/admin/platform/errors", mapOf("period" to period)))) })
        }
    }
}
