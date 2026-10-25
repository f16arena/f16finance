package kz.ordaops.android

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kz.ordaops.android.core.auth.AuthStore
import kz.ordaops.android.core.auth.SessionClient
import kz.ordaops.android.core.auth.SessionStorage
import kz.ordaops.android.core.net.ApiClient

/** Общие объекты приложения: один клиент API и одно состояние входа. */
class OrdaApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var auth: AuthStore
        private set
    lateinit var api: ApiClient
        private set

    override fun onCreate() {
        super.onCreate()
        val baseUrl = BuildConfig.API_BASE_URL
        auth = AuthStore(SessionClient(baseUrl), SessionStorage(this), appScope)
        api = ApiClient(this, baseUrl, auth)
        auth.api = api
        auth.restore()
    }
}
