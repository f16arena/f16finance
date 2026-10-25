package kz.ordaops.android.feature.operator.sale

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import woyou.aidlservice.jiuiv5.ICallback
import woyou.aidlservice.jiuiv5.IWoyouService
import java.util.UUID
import kotlin.coroutines.resume

/** Каким принтером печатать чек. */
enum class PrinterKind { None, Sunmi, Bluetooth }

/** Выбранный принтер — на устройстве, а не на сервере: у каждого телефона/терминала он свой. */
class SaleSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("orda_sale_settings", Context.MODE_PRIVATE)

    var kind by mutableStateOf(runCatching { PrinterKind.valueOf(prefs.getString("printer_kind", null) ?: "None") }.getOrDefault(PrinterKind.None))
        private set
    var btAddress by mutableStateOf(prefs.getString("bt_address", null))
        private set
    var btName by mutableStateOf(prefs.getString("bt_name", null))
        private set
    /** Ширина ленты в знаках: 32 — 58 мм, 48 — 80 мм. */
    var width by mutableStateOf(prefs.getInt("paper_width", 32))
        private set
    var autoPrint by mutableStateOf(prefs.getBoolean("auto_print", false))
        private set

    /** Печать вообще возможна: выбран принтер (для Bluetooth — ещё и конкретный). */
    val canPrint: Boolean get() = kind == PrinterKind.Sunmi || (kind == PrinterKind.Bluetooth && btAddress != null)

    fun selectKind(value: PrinterKind) { kind = value; prefs.edit().putString("printer_kind", value.name).apply() }
    fun selectBluetooth(address: String, name: String?) {
        btAddress = address; btName = name
        prefs.edit().putString("bt_address", address).putString("bt_name", name).apply()
    }
    fun selectWidth(value: Int) { width = value; prefs.edit().putInt("paper_width", value).apply() }
    fun selectAutoPrint(value: Boolean) { autoPrint = value; prefs.edit().putBoolean("auto_print", value).apply() }
}

@Composable
fun rememberSaleSettings(): SaleSettings {
    val context = LocalContext.current
    return remember { SaleSettings(context.applicationContext) }
}

/** Пара «имя — адрес» спаренного Bluetooth-принтера. */
data class BondedPrinter(val name: String, val address: String)

/**
 * Печать чека: встроенный принтер Sunmi (AIDL-служба) или Bluetooth ESC/POS.
 * Все методы возвращают текст ошибки или null — печать не должна ронять продажу.
 */
class ReceiptPrinter(context: Context) {
    private val app = context.applicationContext

    // ── Определение ──────────────────────────────────────────────────────────

    /** Есть ли на устройстве служба печати Sunmi; без неё пункт в настройках прячем. */
    fun isSunmiAvailable(): Boolean = runCatching {
        app.packageManager.getPackageInfo(SUNMI_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Спаренные устройства; без разрешения — пусто. Фильтровать по «принтерам» нечем: класс устройства часто не задан. */
    @SuppressLint("MissingPermission")
    fun bondedPrinters(): List<BondedPrinter> {
        if (!hasBluetoothPermission()) return emptyList()
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty().map { BondedPrinter(it.name ?: it.address, it.address) }.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    // ── Печать ───────────────────────────────────────────────────────────────

    suspend fun print(data: ReceiptData, settings: SaleSettings): String? {
        val rows = ReceiptFormatter.rows(data, settings.width)
        return withContext(Dispatchers.IO) {
            when (settings.kind) {
                PrinterKind.Sunmi -> printSunmi(rows)
                PrinterKind.Bluetooth -> printBluetooth(rows, settings.btAddress)
                PrinterKind.None -> "Принтер не выбран."
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun printBluetooth(rows: List<ReceiptRow>, address: String?): String? {
        if (address == null) return "Выберите Bluetooth-принтер в настройках печати."
        if (!hasBluetoothPermission()) return "Нет разрешения на Bluetooth."
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "На устройстве нет Bluetooth."
        if (!adapter.isEnabled) return "Включите Bluetooth."
        return try {
            val device: BluetoothDevice = adapter.getRemoteDevice(address)
            // Поиск устройств замедляет и обрывает соединение.
            adapter.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                val out = socket.outputStream
                out.write(ReceiptFormatter.escPos(rows))
                out.flush()
                // Закрытие сокета сразу после записи обрывает хвост у части принтеров.
                delay(600)
            } finally {
                runCatching { socket.close() }
            }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Не удалось напечатать: принтер не отвечает (${e.message ?: "ошибка связи"})."
        }
    }

    private suspend fun printSunmi(rows: List<ReceiptRow>): String? {
        var connection: ServiceConnection? = null
        return try {
            val service = withTimeoutOrNull(4_000) {
                suspendCancellableCoroutine<IWoyouService?> { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (cont.isActive) cont.resume(IWoyouService.Stub.asInterface(binder))
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {}
                    }
                    connection = conn
                    val intent = Intent("woyou.aidlservice.jiuiv5.IWoyouService").setPackage(SUNMI_PACKAGE)
                    val bound = runCatching { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                    if (!bound && cont.isActive) cont.resume(null)
                }
            } ?: return "Принтер терминала не отвечает."

            // Колбэк не нужен: служба ставит задания в очередь сама.
            service.printerInit(null)
            for (row in rows) {
                service.setAlignment(if (row.align == RowAlign.Center) 1 else 0, null)
                service.setFontSize(if (row.big) 30f else 24f, null)
                service.printText(row.text + "\n", null)
            }
            service.lineWrap(3, null)
            // Нож — обычной командой ESC/POS: отдельный метод отреза есть не во всех версиях службы.
            runCatching { service.sendRAWData(byteArrayOf(0x1D, 0x56, 66, 0), null) }
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "Не удалось напечатать на принтере терминала."
        } finally {
            // Задания уже в очереди службы; отвязываемся чуть позже, чтобы не оборвать печать.
            connection?.let { conn ->
                withContext(NonCancellable) {
                    delay(1_500)
                    runCatching { app.unbindService(conn) }
                }
            }
        }
    }

    companion object {
        const val SUNMI_PACKAGE = "woyou.aidlservice.jiuiv5"
        /** Стандартный профиль последовательного порта (SPP). */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
