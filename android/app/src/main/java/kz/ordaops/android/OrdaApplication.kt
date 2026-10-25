package kz.ordaops.android

import android.app.Application
import android.os.StrictMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kz.ordaops.android.core.auth.AuthStore
import kz.ordaops.android.core.auth.SessionClient
import kz.ordaops.android.core.auth.SessionStorage
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.platform.QuickEntryVault

/** Общие объекты приложения: один клиент API и одно состояние входа. */
class OrdaApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var auth: AuthStore
        private set
    lateinit var api: ApiClient
        private set
    lateinit var quickVault: QuickEntryVault
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // В отладке громко ловим диск и сеть в главном потоке — источник рывков и ANR.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectActivityLeaks().penaltyLog().build(),
            )
        }
        val baseUrl = BuildConfig.API_BASE_URL
        quickVault = QuickEntryVault(this)
        auth = AuthStore(SessionClient(baseUrl), SessionStorage(this), quickVault, appScope)
        api = ApiClient(this, baseUrl, auth)
        auth.api = api
        auth.restore()
    }
}
