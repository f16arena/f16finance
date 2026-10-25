package kz.ordaops.android.feature.operator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kz.ordaops.android.core.access.AccessResolver
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Разделы оператора — `OperatorSection` из OperatorRootView.swift. */
enum class OperatorSection(val title: String, val tabTitle: String, val icon: ImageVector) {
    Shift("Смена", "Смена", Icons.Filled.GridView),
    Sale("Продажа", "Продажа", Icons.Filled.ShoppingCart),
    Arena("Зал", "Зал", Icons.Filled.Computer),
    Audit("Ревизия", "Ревизия", Icons.Filled.Inventory),
    Tasks("Задачи", "Задачи", Icons.Filled.Checklist),
    Checklists("Чек-листы", "Чек-листы", Icons.Filled.Verified),
    Knowledge("Знания", "Знания", Icons.AutoMirrored.Filled.MenuBook),
    Chat("Командный чат", "Чат", Icons.Filled.Forum),
    Messages("Сообщения", "Сообщения", Icons.Filled.Email),
    Money("Мои деньги", "Деньги", Icons.Filled.AccountBalanceWallet),
    Schedule("График", "График", Icons.Filled.CalendarMonth),
    Profile("Профиль", "Профиль", Icons.Filled.AccountCircle);

    companion object {
        /** У магазина — касса; у клуба место «Продажи» занимает зал. */
        fun phoneTabs(sellsGoods: Boolean, hasArena: Boolean): List<OperatorSection> = when {
            sellsGoods -> listOf(Shift, Sale, Chat, Tasks, Profile)
            hasArena -> listOf(Shift, Arena, Chat, Tasks, Profile)
            else -> listOf(Shift, Chat, Knowledge, Tasks, Profile)
        }

        /** Остальное — списком в профиле, как группы бокового меню на айпаде. */
        fun moreSections(sellsGoods: Boolean, hasArena: Boolean): List<OperatorSection> {
            val tabs = phoneTabs(sellsGoods, hasArena)
            val all = buildList {
                if (sellsGoods) add(Audit)
                if (hasArena && sellsGoods) add(Arena)
                addAll(listOf(Checklists, Knowledge, Messages, Money, Schedule))
            }
            return all.filterNot { it in tabs }
        }
    }
}

@Serializable
data class OperatorPoints(val industries: List<String> = emptyList(), val sellsGoods: Boolean = true)

/** Только то, что нужно каркасу; полный обзор — у экранов кабинета. */
@Serializable
data class OverviewHead(val points: OperatorPoints? = null)

@Composable
fun OperatorRoot(resolver: AccessResolver, api: ApiClient, onSignOut: () -> Unit) {
    var points by remember { mutableStateOf<OperatorPoints?>(null) }
    LaunchedEffect(Unit) {
        val request = ApiRequest("/api/operator/overview")
        api.cached<OverviewHead>(request)?.let { points = it.points }
        runCatching { api.send<OverviewHead>(request) }.onSuccess { points = it.points }
    }
    // Старый сервер поля не шлёт — считаем, что торговля есть: прятать кассу хуже.
    val sellsGoods = points?.sellsGoods ?: true
    val industries = points?.industries.orEmpty()
    val hasArena = "club" in industries || "ps_club" in industries

    val tabs = OperatorSection.phoneTabs(sellsGoods, hasArena)
    var selected by rememberSaveable { mutableStateOf(OperatorSection.Shift) }
    var opened by rememberSaveable { mutableStateOf<OperatorSection?>(null) }

    Scaffold(
        containerColor = Orda.colors.background,
        bottomBar = {
            NavigationBar(containerColor = Orda.colors.elevated) {
                tabs.forEach { section ->
                    NavigationBarItem(
                        selected = selected == section && opened == null,
                        onClick = {
                            selected = section
                            opened = null
                        },
                        icon = { Icon(section.icon, contentDescription = null) },
                        label = { Text(section.tabTitle, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Orda.colors.brand,
                            selectedTextColor = Orda.colors.brand,
                            unselectedIconColor = Orda.colors.textDim,
                            unselectedTextColor = Orda.colors.textDim,
                            indicatorColor = Orda.colors.brand.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val section = opened ?: selected
            when (section) {
                OperatorSection.Profile -> OperatorProfile(
                    name = resolver.session.displayName ?: "Оператор",
                    roleLabel = resolver.session.roleLabel,
                    more = OperatorSection.moreSections(sellsGoods, hasArena),
                    onOpen = { opened = it },
                    onSignOut = onSignOut,
                )
                else -> SectionPlaceholder(section)
            }
        }
    }
}

/** Раздел ещё переносится с айфона. */
@Composable
private fun SectionPlaceholder(section: OperatorSection) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        EmptyState(
            icon = Icons.Filled.Construction,
            title = section.title,
            message = "Раздел переносится с iPhone-версии и появится в следующем обновлении.",
        )
    }
}

@Composable
private fun OperatorProfile(
    name: String,
    roleLabel: String?,
    more: List<OperatorSection>,
    onOpen: (OperatorSection) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(name, color = Orda.colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        roleLabel?.let { Text(it, color = Orda.colors.textDim) }
        if (more.isNotEmpty()) {
            OrdaCard {
                more.forEach { section ->
                    MenuRow(section.icon, section.title) { onOpen(section) }
                }
            }
        }
        OrdaCard {
            MenuRow(Icons.AutoMirrored.Filled.ExitToApp, "Выйти из аккаунта", danger = true, onClick = onSignOut)
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, title: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val tint = if (danger) Orda.colors.negative else Orda.colors.brandBright
        Icon(icon, contentDescription = null, tint = tint)
        Text(title, color = if (danger) Orda.colors.negative else Orda.colors.text, fontSize = 16.sp)
    }
}
