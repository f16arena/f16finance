package kz.ordaops.android.feature.operator.sale

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.PointCustomer
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * Выбор клиента для чека — CustomerPickerSheet.swift. Ищут по карте, телефону
 * или имени — тем, что называют у стойки. Без запроса показывает частых.
 * `GET /api/operator/customers?search=`.
 */
@Composable
internal fun CustomerPickerSheet(onPick: (PointCustomer) -> Unit, onDismiss: () -> Unit) {
    val api = LocalOperatorEnv.current.api
    var customers by remember { mutableStateOf<List<PointCustomer>>(emptyList()) }
    var search by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // Пауза перед запросом: иначе он уходит на каждую цифру карты. LaunchedEffect
    // отменяет предыдущий запуск при новом вводе.
    LaunchedEffect(search) {
        if (search.isNotEmpty()) delay(300)
        loading = true
        try {
            val request = ApiRequest("/api/operator/customers", query = if (search.isEmpty()) emptyMap() else mapOf("search" to search))
            customers = PointCustomer.listFrom(api.send<JsonElement>(request))
            loadError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.userMessage()
        } finally {
            loading = false
        }
    }

    OrdaSheet("Клиент", onDismiss) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Карта, телефон или имя") },
            singleLine = true,
            shape = RoundedCornerShape(Radius.md),
            colors = ordaFieldColors(),
        )
        when {
            loadError != null && customers.isEmpty() -> ErrorBanner(loadError.orEmpty())
            loading && customers.isEmpty() -> LoadingView()
            customers.isEmpty() -> EmptyState(
                icon = Icons.Filled.PersonSearch,
                title = if (search.isEmpty()) "Клиентов нет" else "Никого не нашлось",
                message = if (search.isEmpty()) "Клиентов заводят на сайте — в разделе «Клиенты»." else "Проверьте номер карты или телефон.",
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                customers.forEach { customer ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(customer)
                                onDismiss()
                            }
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(customer.name, color = Orda.colors.text, fontSize = 16.sp)
                            if (customer.subtitle.isNotEmpty()) {
                                Text(customer.subtitle, color = Orda.colors.textDim, fontSize = 13.sp)
                            }
                        }
                        // Бонусы — то, ради чего карту и достают.
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${customer.loyaltyPoints.toInt()}",
                                color = if (customer.loyaltyPoints > 0) Orda.colors.positive else Orda.colors.textDim,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("бонусов", color = Orda.colors.textDim, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
