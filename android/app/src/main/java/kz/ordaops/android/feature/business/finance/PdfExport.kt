package kz.ordaops.android.feature.business.finance

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import java.io.File

/** Состояние выгрузки PDF для экрана: идёт ли, чем кончилась. Живёт в `remember`. */
class PdfRunner(private val context: Context, private val api: ApiClient) {
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /** Скачать и открыть. Ошибка остаётся в [error] — экран покажет её плашкой. */
    suspend fun run(request: ApiRequest, name: String) {
        if (running) return
        running = true
        error = null
        try {
            PdfExport.open(context, PdfExport.download(context, api, request, name))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.userMessage()
        } finally {
            running = false
        }
    }
}

@Composable
fun rememberPdfRunner(): PdfRunner {
    val context = LocalContext.current
    val api = LocalBusiness.current.api
    return remember(context, api) { PdfRunner(context, api) }
}

/** Провайдер файлов выгрузки: свой класс, чтобы не пересечься с чужим `FileProvider` в манифесте. */
class FinanceFileProvider : FileProvider()

/**
 * PDF с сервера — те же файлы, что скачивают на сайте. Роуты сами собирают
 * данные из соседних API по токену, поэтому работают и из приложения.
 * Скачали → записали в кэш → открыли просмотрщиком (или «Поделиться»).
 */
object PdfExport {
    /** Скачать и записать во временный файл с понятным именем: его увидят в почте и мессенджере. */
    suspend fun download(context: Context, api: ApiClient, request: ApiRequest, name: String): File {
        val bytes = api.download(request)
        return withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "exports").apply { mkdirs() }
            File(folder, fileName(name)).apply { writeBytes(bytes) }
        }
    }

    /** Открыть PDF; нет просмотрщика — предложить «Поделиться». */
    fun open(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.finance.files", file)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(view)
        } catch (_: ActivityNotFoundException) {
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Отправить PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Имя файла без символов, которые файловая система не любит. */
    fun fileName(title: String, ext: String = "pdf"): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "report" } + ".$ext"
}
