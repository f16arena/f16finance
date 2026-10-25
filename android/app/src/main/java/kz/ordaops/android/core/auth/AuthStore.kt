package kz.ordaops.android.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kz.ordaops.android.core.access.AccessResolver
import kz.ordaops.android.core.access.SessionRole
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.TokenProvider
import kz.ordaops.android.core.net.userMessage

/**
 * Состояние входа — как `AuthStore` на айфоне: восстановление сессии,
 * вход, продление токена, загрузка прав, выход.
 */
class AuthStore(
    private val auth: SessionClient,
    private val storage: SessionStorage,
    private val scope: CoroutineScope,
) : TokenProvider {

    enum class Phase { Restoring, SignedOut, LoadingRole, SignedIn }

    data class State(
        val phase: Phase = Phase.Restoring,
        val role: SessionRole? = null,
        val signInError: String? = null,
        val roleError: String? = null,
        val isSigningIn: Boolean = false,
        /** Почему выкинуло из аккаунта — показываем на экране входа. */
        val signOutReason: String? = null,
        /** Есть сохранённая сессия для входа по отпечатку. */
        val hasQuickEntry: Boolean = false,
        val quickEntryError: String? = null,
    ) {
        val resolver: AccessResolver? get() = role?.let(::AccessResolver)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var session: SessionClient.Session? = null

    lateinit var api: ApiClient

    private val refreshMutex = Mutex()
    private var inFlight: CompletableDeferred<RefreshOutcome>? = null
    private var lastRefreshRejected = false

    private sealed interface RefreshOutcome {
        data class Token(val value: String) : RefreshOutcome
        data object Rejected : RefreshOutcome
        data object Temporary : RefreshOutcome
    }

    // ── TokenProvider ────────────────────────────────────────────────────────

    override fun currentAccessToken(): String? = session?.accessToken

    override suspend fun refreshAccessToken(): String? =
        (refreshSession() as? RefreshOutcome.Token)?.value

    // ── Жизненный цикл ───────────────────────────────────────────────────────

    fun restore() {
        _state.update { it.copy(hasQuickEntry = storage.hasQuick()) }
        scope.launch {
            val stored = storage.load()
            if (stored == null) {
                _state.update { it.copy(phase = Phase.SignedOut) }
                return@launch
            }
            session = stored
            if (stored.isExpiringSoon && refreshSession() is RefreshOutcome.Rejected) {
                signOutAutomatically("При запуске сервер отклонил продление сессии — вход нужен заново.")
                return@launch
            }
            _state.update { it.copy(phase = Phase.LoadingRole) }
            loadRole()
        }
    }

    fun signIn(login: String, password: String) {
        if (_state.value.isSigningIn) return
        _state.update { it.copy(isSigningIn = true, signInError = null) }
        scope.launch {
            try {
                val newSession = auth.signIn(login, password)
                persist(newSession)
                _state.update { it.copy(phase = Phase.LoadingRole, signOutReason = null) }
                loadRole()
            } catch (e: Exception) {
                _state.update { it.copy(signInError = e.message ?: "Ошибка входа", phase = Phase.SignedOut) }
            } finally {
                _state.update { it.copy(isSigningIn = false) }
            }
        }
    }

    /**
     * Вход после подтверждения отпечатком: продлеваем сохранённую копию.
     * Сервер её не принял — копия бесполезна, забываем и просим пароль.
     */
    fun signInWithQuickEntry() {
        val stored = storage.loadQuick() ?: run {
            _state.update { it.copy(hasQuickEntry = false) }
            return
        }
        if (_state.value.isSigningIn) return
        _state.update { it.copy(isSigningIn = true, quickEntryError = null, signInError = null) }
        scope.launch {
            try {
                val session = auth.refresh(stored.refreshToken)
                persist(session)
                _state.update { it.copy(phase = Phase.LoadingRole, signOutReason = null) }
                loadRole()
            } catch (e: SessionClient.AuthError.InvalidCredentials) {
                storage.clearQuick()
                _state.update { it.copy(hasQuickEntry = false, quickEntryError = "Быстрый вход устарел — войдите логином и паролем.") }
            } catch (e: Exception) {
                _state.update { it.copy(quickEntryError = "Сервер не ответил. Попробуйте ещё раз или войдите логином и паролем.") }
            } finally {
                _state.update { it.copy(isSigningIn = false) }
            }
        }
    }

    fun signOut() {
        storage.clear()
        api.clearCache()
        api.organizationId = null
        session = null
        _state.update { State(phase = Phase.SignedOut, signOutReason = it.signOutReason, hasQuickEntry = storage.hasQuick()) }
    }

    private fun signOutAutomatically(reason: String) {
        _state.update { it.copy(signOutReason = reason) }
        signOut()
    }

    fun reloadRole() {
        scope.launch { loadRole() }
    }

    fun dismissRoleError() {
        _state.update { it.copy(roleError = null) }
    }

    /** Переключение организации: не открылась — возвращаемся к прежней. */
    fun setOrganization(id: String?) {
        scope.launch {
            val previous = api.organizationId
            api.organizationId = id
            if (loadRole()) return@launch
            api.organizationId = previous
            loadRole()
            _state.update { it.copy(roleError = "Не удалось открыть организацию. Вернулись к прежней.") }
        }
    }

    private suspend fun loadRole(): Boolean = try {
        val role: SessionRole = api.send(ApiRequest("/api/auth/session-role"))
        _state.update { it.copy(role = role, roleError = null, phase = Phase.SignedIn) }
        true
    } catch (e: Exception) {
        if (e is ApiError.Unauthorized && lastRefreshRejected) {
            signOutAutomatically("Сервер не принял сессию при загрузке прав — вход нужен заново.")
        } else {
            // Роль не загрузилась (обычно сеть) — даём повторить, не выкидывая из аккаунта.
            _state.update { it.copy(roleError = e.userMessage(), phase = Phase.SignedIn) }
        }
        false
    }

    private fun persist(newSession: SessionClient.Session) {
        session = newSession
        lastRefreshRejected = false
        // Свежий токен — и в копию быстрого входа: старый refresh после продления уже не примут.
        storage.saveQuick(newSession)
        if (!storage.save(newSession)) {
            _state.update { it.copy(signOutReason = "Сессию не удалось сохранить на устройстве — вход не переживёт закрытие приложения.") }
        }
    }

    /** Одно продление на всех: параллельные 401 ждут один и тот же запрос. */
    private suspend fun refreshSession(): RefreshOutcome {
        val (deferred, owner) = refreshMutex.withLock {
            inFlight?.let { it to false } ?: CompletableDeferred<RefreshOutcome>().also { inFlight = it }.let { it to true }
        }
        if (!owner) return deferred.await()

        val token = session?.refreshToken
        val result = if (token == null) {
            RefreshOutcome.Rejected
        } else {
            try {
                val refreshed = auth.refresh(token)
                persist(refreshed)
                RefreshOutcome.Token(refreshed.accessToken)
            } catch (e: SessionClient.AuthError.InvalidCredentials) {
                RefreshOutcome.Rejected
            } catch (e: Exception) {
                RefreshOutcome.Temporary
            }
        }
        lastRefreshRejected = result is RefreshOutcome.Rejected
        refreshMutex.withLock { inFlight = null }
        deferred.complete(result)
        return result
    }
}
