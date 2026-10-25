package kz.ordaops.android.feature.operator.audit

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Непрерывный сканер штрихкодов (CameraX + ML Kit) — `BarcodeScanner.swift`.
 *
 * Камера остаётся открытой, а коды летят один за другим: ревизия на 800 позиций
 * не должна быть схемой «навёл → снял → закрыл». Пока сверху вводят количество
 * ([paused]), коды не ищутся — иначе соседняя бутылка перебивает набранное.
 */
@Composable
internal fun AuditScannerPane(paused: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (granted) {
            CameraPreview(paused, onCode)
        } else {
            Box(
                Modifier.fillMaxWidth().background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md)).padding(Spacing.md),
            ) {
                Text(
                    if (denied) {
                        "Нет доступа к камере — разрешите его в настройках или введите штрихкод вручную."
                    } else {
                        "Ждём разрешения на камеру. Штрихкод можно ввести вручную."
                    },
                    color = Orda.colors.textMuted, fontSize = 12.sp,
                )
            }
        }
        ManualCodeField(onCode)
    }
}

@Composable
private fun CameraPreview(paused: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pausedFlag = remember { AtomicBoolean(paused) }
    val onCodeState = rememberUpdatedState(onCode)
    // Последний код и время: камера видит один и тот же штрихкод десятки раз в
    // секунду. Повтор принимаем не чаще раза в полторы секунды — этого хватает,
    // чтобы намеренно отсканировать две одинаковые бутылки.
    val lastCode = remember { arrayOf<String?>(null) }
    val lastAt = remember { longArrayOf(0L) }

    // Проснулись — забываем последний код: ту же бутылку могут поднести снова намеренно.
    androidx.compose.runtime.LaunchedEffect(paused) {
        pausedFlag.set(paused)
        if (!paused) {
            lastCode[0] = null
            lastAt[0] = 0L
        }
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_E, Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                    Barcode.FORMAT_ITF, Barcode.FORMAT_QR_CODE,
                )
                .build(),
        )
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null || pausedFlag.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(image)
                    .addOnSuccessListener { codes ->
                        val value = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                        if (value != null && !pausedFlag.get()) {
                            val now = System.currentTimeMillis()
                            if (!(value == lastCode[0] && now - lastAt[0] < 1500)) {
                                lastCode[0] = value
                                lastAt[0] = now
                                onCodeState.value(value)
                            }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxWidth().height(240.dp)
            .clip(RoundedCornerShape(Radius.lg))
            .border(1.dp, Orda.colors.brand.copy(alpha = 0.4f), RoundedCornerShape(Radius.lg)),
    )
}

/** Запасной ручный ввод: он же нужен, когда штрихкод затёрт. */
@Composable
private fun ManualCodeField(onCode: (String) -> Unit) {
    var manual by remember { mutableStateOf("") }
    fun submit() {
        val code = manual.trim()
        if (code.isEmpty()) return
        onCode(code)
        manual = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Штрихкод вручную") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            shape = RoundedCornerShape(Radius.md),
        )
        TextButton(onClick = ::submit, enabled = manual.isNotEmpty()) {
            Text("Найти", fontWeight = FontWeight.SemiBold, color = if (manual.isEmpty()) Orda.colors.textDim else Orda.colors.brand)
        }
    }
}
