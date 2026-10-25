package kz.ordaops.android.feature.operator.sale

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kz.ordaops.android.feature.operator.shift.FormSection
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.PaymentMethod
import kz.ordaops.android.feature.operator.shift.PillSegment
import kz.ordaops.android.feature.operator.shift.SaleLine
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

enum class PrintStatus { Idle, Printing, Done, Failed }

/** Состояние последней печати — для кнопки «Печать чека» на экране подтверждения. */
class PrintState {
    var status by mutableStateOf(PrintStatus.Idle)
    var error by mutableStateOf<String?>(null)
}

/** Настройки печати чека: принтер, ширина ленты, автопечать. Хранятся на устройстве. */
@Composable
internal fun PrintSettingsSheet(settings: SaleSettings, printer: ReceiptPrinter, companyName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Разрешение выдают в системном диалоге; список принтеров обновляем после ответа.
    var refresh by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    var testError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val sunmi = remember { printer.isSunmiAvailable() }
    val kinds = buildList {
        add(PrinterKind.None to "Нет")
        // Встроенного принтера нет — пункт не показываем, чтобы не вводить в заблуждение.
        if (sunmi) add(PrinterKind.Sunmi to "Терминал")
        add(PrinterKind.Bluetooth to "Bluetooth")
    }

    OrdaSheet("Печать чека", onDismiss) {
        FormSection("Принтер") {
            PillSegment(kinds, settings.kind) { settings.selectKind(it) }
        }

        if (settings.kind == PrinterKind.Bluetooth) {
            FormSection("Bluetooth-принтер") {
                if (!printer.hasBluetoothPermission()) {
                    Text("Нужен доступ к Bluetooth, чтобы увидеть спаренные принтеры.", color = Orda.colors.textMuted, fontSize = 14.sp)
                    PrimaryButton("Разрешить Bluetooth", Modifier.fillMaxWidth()) {
                        if (Build.VERSION.SDK_INT >= 31) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                } else {
                    val bonded = remember(refresh) { printer.bondedPrinters() }
                    if (bonded.isEmpty()) {
                        Text(
                            "Нет спаренных устройств. Спарьте принтер в настройках Bluetooth телефона и вернитесь сюда.",
                            color = Orda.colors.textMuted,
                            fontSize = 14.sp,
                        )
                    }
                    bonded.forEach { device ->
                        val on = settings.btAddress == device.address
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (on) Orda.colors.brand.copy(alpha = 0.14f) else Orda.colors.surface, RoundedCornerShape(Radius.md))
                                .clickable { settings.selectBluetooth(device.address, device.name) }
                                .padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(device.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (on) Text("выбран", color = Orda.colors.brand, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (settings.kind != PrinterKind.None) {
            FormSection("Лента") {
                PillSegment(listOf(32 to "58 мм", 48 to "80 мм"), settings.width) { settings.selectWidth(it) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Печатать чек сразу после продажи", color = Orda.colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = settings.autoPrint, onCheckedChange = { settings.selectAutoPrint(it) })
            }
            testError?.let { ErrorBanner(it) }
            PrimaryButton("Тестовая печать", Modifier.fillMaxWidth(), enabled = settings.canPrint, loading = testing) {
                testing = true
                testError = null
                scope.launch {
                    val demo = ReceiptData(
                        companyName = companyName,
                        soldAtMillis = System.currentTimeMillis(),
                        lines = listOf(SaleLine("test", "Тестовый товар", 2.0, 500.0)),
                        total = 1000.0,
                        method = PaymentMethod.Cash,
                        cashAmount = 1000.0,
                        kaspiAmount = 0.0,
                        cashReceived = 2000.0,
                        change = 1000.0,
                    )
                    testError = printer.print(demo, settings)
                    testing = false
                }
            }
        }
    }
}
