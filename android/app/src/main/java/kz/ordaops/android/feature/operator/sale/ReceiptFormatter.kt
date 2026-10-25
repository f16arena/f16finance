package kz.ordaops.android.feature.operator.sale

import kz.ordaops.android.feature.operator.shift.PaymentMethod
import kz.ordaops.android.feature.operator.shift.SaleLine
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Данные чека для печати — то же, что на чеке десктопной кассы (receipt-html.ts), без реквизитов ККМ. */
data class ReceiptData(
    val companyName: String,
    val soldAtMillis: Long,
    val lines: List<SaleLine>,
    val total: Double,
    val method: PaymentMethod,
    val cashAmount: Double,
    val kaspiAmount: Double,
    /** Сколько дал покупатель; null — без сдачи. */
    val cashReceived: Double?,
    val change: Double,
)

internal enum class RowAlign { Left, Center }

/** Строка чека, уже разбитая по ширине бумаги. */
internal data class ReceiptRow(
    val text: String,
    val align: RowAlign = RowAlign.Left,
    val bold: Boolean = false,
    val big: Boolean = false,
)

/**
 * Раскладка чека в строки под ширину бумаги (32 знака — 58 мм, 48 — 80 мм) и в байты ESC/POS.
 * Один источник текста для обоих принтеров: встроенного Sunmi и Bluetooth.
 */
internal object ReceiptFormatter {

    /** Кириллица печатается в CP866: символ тенге и прочие «умные» знаки там не существуют. */
    private fun plain(text: String) = text
        .replace("₸", "тг")
        .replace("×", "x")
        .replace("·", "-")
        .replace("—", "-")
        .replace("–", "-")
        .replace("«", "\"")
        .replace("»", "\"")
        .replace(' ', ' ')
        .replace(' ', ' ')

    private fun amount(value: Double): String {
        val whole = Math.round(value)
        val digits = Math.abs(whole).toString()
        val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
        return (if (whole < 0) "-" else "") + grouped + " тг"
    }

    private fun qty(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale("ru"), "%.3f", value).trimEnd('0').trimEnd(',')

    /** «Слева ........ справа» ровно в [width] знаков; не влезло — справа переносится на свою строку. */
    private fun twoColumns(left: String, right: String, width: Int): List<String> {
        val gap = width - left.length - right.length
        return if (gap >= 1) listOf(left + " ".repeat(gap) + right) else listOf(left, right.padStart(width))
    }

    private fun wrap(text: String, width: Int): List<String> {
        val out = mutableListOf<String>()
        var current = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            var w = word
            while (w.length > width) {
                if (current.isNotEmpty()) { out += current; current = "" }
                out += w.take(width)
                w = w.drop(width)
            }
            current = when {
                current.isEmpty() -> w
                current.length + 1 + w.length <= width -> "$current $w"
                else -> { out += current; w }
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    fun rows(data: ReceiptData, width: Int): List<ReceiptRow> {
        val rows = mutableListOf<ReceiptRow>()
        val line = "-".repeat(width)
        wrap(plain(data.companyName), width).forEach { rows += ReceiptRow(it, RowAlign.Center, bold = true) }
        rows += ReceiptRow(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(data.soldAtMillis)), RowAlign.Center)
        rows += ReceiptRow(line)
        for (l in data.lines) {
            wrap(plain(l.name), width).forEach { rows += ReceiptRow(it) }
            twoColumns("${qty(l.quantity)} x ${amount(l.unitPrice)}", amount(l.total), width).forEach { rows += ReceiptRow(it) }
        }
        rows += ReceiptRow(line)
        twoColumns("ИТОГО", amount(data.total), width).forEach { rows += ReceiptRow(it, bold = true, big = true) }
        val label = when (data.method) {
            PaymentMethod.Cash -> "Наличные"
            PaymentMethod.Kaspi -> "Безналичный"
            PaymentMethod.Mixed -> "Смешанная"
        }
        rows += ReceiptRow("Оплата: $label")
        if (data.method == PaymentMethod.Mixed) {
            if (data.cashAmount > 0) twoColumns("  Наличные", amount(data.cashAmount), width).forEach { rows += ReceiptRow(it) }
            if (data.kaspiAmount > 0) twoColumns("  Безналичный", amount(data.kaspiAmount), width).forEach { rows += ReceiptRow(it) }
        }
        data.cashReceived?.let { received ->
            twoColumns("Получено", amount(received), width).forEach { rows += ReceiptRow(it) }
            twoColumns("СДАЧА", amount(data.change), width).forEach { rows += ReceiptRow(it, bold = true) }
        }
        rows += ReceiptRow(line)
        rows += ReceiptRow("Спасибо!", RowAlign.Center, bold = true)
        return rows
    }

    private val cp866: Charset = runCatching { Charset.forName("IBM866") }.getOrDefault(Charsets.US_ASCII)
    private val gb18030: Charset = runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.UTF_8)

    /**
     * Чек в ESC/POS: выравнивание, жирный, двойной размер, подача и обрезка.
     * Bluetooth-принтеры — CP866 (`ESC t 17`). Принтер Sunmi по умолчанию
     * в GB18030, а в нём кириллица есть, — кодовую страницу не трогаем.
     */
    fun escPos(rows: List<ReceiptRow>, sunmi: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun bytes(vararg b: Int) = out.write(ByteArray(b.size) { b[it].toByte() })
        val charset = if (sunmi) gb18030 else cp866
        bytes(0x1B, 0x40)        // ESC @ — сброс
        if (!sunmi) bytes(0x1B, 0x74, 17)    // ESC t 17 — кодовая страница CP866
        for (row in rows) {
            bytes(0x1B, 0x61, if (row.align == RowAlign.Center) 1 else 0)
            bytes(0x1B, 0x45, if (row.bold) 1 else 0)
            bytes(0x1D, 0x21, if (row.big) 0x01 else 0x00) // удвоенная высота: ширина строки не меняется
            out.write(plain(row.text).toByteArray(charset))
            out.write(0x0A)
        }
        bytes(0x1B, 0x45, 0)
        bytes(0x1D, 0x21, 0)
        bytes(0x1B, 0x64, 4)     // ESC d 4 — подача бумаги к ножу
        bytes(0x1D, 0x56, 66, 0) // GS V 66 0 — отрезать (принтеры без ножа игнорируют)
        return out.toByteArray()
    }
}
