package kz.ordaops.android.feature.operator.sale

import android.os.IBinder
import android.os.Parcel

/**
 * Встроенный принтер Sunmi — два вызова службы `woyou.aidlservice.jiuiv5` без
 * генератора AIDL: он падает на кириллице в пути проекта (C:\Users\Арыстан).
 *
 * Номер вызова = позиция метода в IWoyouService.aidl. Берём только те, в
 * которых все версии службы сходятся: printerInit (3) и sendRAWData (10).
 * Остальное — выравнивание, размер, текст — уходит обычными байтами ESC/POS.
 */
internal class SunmiPrinterService(private val binder: IBinder) {

    fun printerInit() = call(IBinder.FIRST_CALL_TRANSACTION + 3) { }

    fun sendRawData(bytes: ByteArray) = call(IBinder.FIRST_CALL_TRANSACTION + 10) { it.writeByteArray(bytes) }

    private inline fun call(code: Int, args: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            args(data)
            // Колбэк (ICallback) не нужен — служба сама ставит задания в очередь.
            data.writeStrongBinder(null)
            binder.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val DESCRIPTOR = "woyou.aidlservice.jiuiv5.IWoyouService"
    }
}
