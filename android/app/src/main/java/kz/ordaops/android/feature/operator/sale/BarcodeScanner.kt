package kz.ordaops.android.feature.operator.sale

import androidx.compose.runtime.saveable.rememberSaveable
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.util.concurrent.Executors

/**
 * Непрерывный сканер штрихкодов — BarcodeScanner.swift. Камера остаётся
 * открытой, товары падают в чек один за другим: схема «навёл → снял → закрыл»
 * превращает ревизию на 800 позиций в многочасовую пытку.
 *
 * CameraX + ML Kit. Разрешения на камеру может не быть — тогда вызывающий
 * (`ScannerPane`) показывает запасной ручной ввод.
 */
@Composable
internal fun BarcodeScanner(paused: Boolean, onScan: (String) -> Unit, modifier: Modifier = Modifier, torch: Boolean = false) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPaused by rememberUpdatedState(paused)
    val currentOnScan by rememberUpdatedState(onScan)
    // Камера нужна для фонарика; привязка к жизненному циклу делается один раз на lifecycleOwner.
    var camera by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93, Barcode.FORMAT_ITF,
                    Barcode.FORMAT_QR_CODE,
                )
                .build(),
        )
    }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        // Последний код и время: камера видит один и тот же штрихкод десятки раз в
        // секунду, и без этого одна бутылка попала бы в чек сорок раз.
        var lastCode: String? = null
        var lastAt = 0L
        var wasPaused = false
        // Один кадр в обработке: пока не закончили с текущим, следующие отбрасываем.
        val busy = java.util.concurrent.atomic.AtomicBoolean(false)

        @androidx.annotation.OptIn(ExperimentalGetImage::class)
        fun analyze(proxy: ImageProxy) {
            val media = proxy.image
            if (media == null || currentPaused || !busy.compareAndSet(false, true)) {
                // Проснулись — забываем последний код: ту же бутылку могут поднести снова намеренно.
                if (currentPaused) wasPaused = true
                proxy.close()
                return
            }
            if (wasPaused) {
                lastCode = null
                lastAt = 0L
                wasPaused = false
            }
            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    for (barcode in codes) {
                        val value = barcode.rawValue?.trim().orEmpty()
                        if (value.isEmpty()) continue
                        // Тот же код повторно — не чаще раза в полторы секунды: хватает, чтобы
                        // намеренно отсканировать две одинаковые бутылки, и отсекает дребезг.
                        val now = System.currentTimeMillis()
                        if (value == lastCode && now - lastAt < 1500) continue
                        lastCode = value
                        lastAt = now
                        currentOnScan(value)
                    }
                }
                .addOnCompleteListener { busy.set(false); proxy.close() }
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            val p = providerFuture.get()
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            runCatching {
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // Отвязываем от жизненного цикла: камера не должна оставаться занятой за закрытым листом.
            camera = null
            provider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scanner.close()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * Сканер с запасным ручным вводом: он же нужен, когда штрихкод затёрт или
 * камеры нет / нет разрешения.
 */
@Composable
internal fun ScannerPane(
    paused: Boolean = false,
    onCode: (String) -> Unit,
) {
    val context = LocalContext.current
    var granted by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    var manual by rememberSaveable { mutableStateOf("") }
    fun submitManual() {
        val code = manual.trim()
        if (code.isEmpty()) return
        onCode(code)
        manual = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (granted) {
            BarcodeScanner(
                paused = paused,
                onScan = onCode,
                torch = torch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(Radius.lg))
                    .border(1.dp, Orda.colors.brand.copy(alpha = 0.4f), RoundedCornerShape(Radius.lg)),
            )
            Text(
                if (torch) "Выключить фонарик" else "Включить фонарик",
                color = Orda.colors.brand,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { torch = !torch }.padding(Spacing.sm),
            )
        } else {
            Text(
                if (denied) "Нет доступа к камере — разрешите его в настройках телефона или введите штрихкод вручную."
                else "Сканер камеры недоступен — введите штрихкод или найдите товар в списке.",
                color = Orda.colors.textMuted,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Orda.colors.surfaceRaised, RoundedCornerShape(Radius.md))
                    .padding(Spacing.md),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Штрихкод вручную") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitManual() }),
                shape = RoundedCornerShape(Radius.md),
                colors = ordaFieldColors(),
            )
            Text(
                "Найти",
                color = if (manual.isEmpty()) Orda.colors.textDim else Orda.colors.brand,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = manual.isNotEmpty(), onClick = ::submitManual)
                    .padding(Spacing.sm),
            )
        }
    }
}
