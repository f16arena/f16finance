package kz.ordaops.android.feature.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing

/**
 * Все сервисы плиткой по группам — вместо списка на восемьдесят строк. Порт
 * `OwnerServicesScreen.swift`. Раздел без нативного экрана помечен «сайт» и
 * открывается сайтом во вкладке Chrome.
 */
@Composable
fun OwnerServicesScreen(env: BusinessEnv) {
    var query by rememberSaveable { mutableStateOf("") }
    val pending by env.vm.pending.collectAsStateWithLifecycle()
    val all = remember(env.access) { Services.groups(env.access) }
    val groups = remember(all, query) { Services.filter(all, query) }
    OwnerScreenColumn {
        Text("Сервисы", color = Orda.colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = Spacing.md))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Найти сервис") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Очистить") }
            },
            singleLine = true,
            shape = RoundedCornerShape(Radius.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Orda.colors.surface,
                unfocusedContainerColor = Orda.colors.surface,
                focusedBorderColor = Orda.colors.brand,
                unfocusedBorderColor = Orda.colors.border,
            ),
        )
        if (groups.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = if (all.isEmpty()) "Разделов нет" else "Ничего не нашлось",
                message = if (all.isEmpty()) "Доступ пока не выдали. Потяните вниз на главной — список обновится, когда его выдадут." else "Попробуйте другое слово.",
            )
        }
        groups.forEach { group ->
            OwnerSection(group.label) {
                // По три в ряд: крупнее иконки и названия в две строки без переносов.
                TileGrid(group.items, columns = 3, rowSpacing = Spacing.xl) { item ->
                    ServiceTile(
                        item, OwnerTint.forGroup(group.id),
                        badge = if (item.pageId == "expenses-pending") pending.size else 0,
                        size = 64,
                    ) { env.open(item.pageId) }
                }
            }
        }
        if (groups.any { g -> g.items.any { it.isWeb } }) {
            OwnerFootnote("Разделы с пометкой «сайт» открываются в браузере — в приложении они появятся позже.")
        }
    }
}
