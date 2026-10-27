package kz.ordaops.android.feature.business.platform

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.business.ops.HeroFact
import kz.ordaops.android.feature.business.ops.OpsRow
import kz.ordaops.android.feature.business.ops.OpsSearchField
import kz.ordaops.android.feature.business.ops.OpsSection
import kz.ordaops.android.feature.business.ops.plural
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

private enum class PlatformTab(val title: String) { Orgs("Организации"), Server("Сервер"), Errors("Ошибки") }

/**
 * Вкладка «Платформа» суперадмина: организации-клиенты, здоровье сервера и журнал
 * ошибок. Всё, что необратимо или опасно (удаление организации, редактор БД, откат
 * деплоя, Studio), остаётся на сайте — здесь на них только ссылки.
 */
@Composable
fun PlatformRoot() {
    val model = rememberPlatformModel()
    var tab by rememberSaveable { mutableStateOf(PlatformTab.Orgs.name) }
    var orgId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(model) { model.refresh() }
    BackHandler(enabled = orgId != null) { orgId = null }

    val openedOrg = orgId
    if (openedOrg != null) {
        OrganizationScreen(model, openedOrg, onBack = { orgId = null })
        return
    }
    Column(Modifier.fillMaxSize()) {
        PillSegment(
            PlatformTab.entries.map { it.name to it.title }, tab,
            Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) { tab = it }
        when (PlatformTab.valueOf(tab)) {
            PlatformTab.Orgs -> OrganizationsTab(model, onOpen = { orgId = it })
            PlatformTab.Server -> ServerTab()
            PlatformTab.Errors -> ErrorsTab()
        }
    }
}

/** Одна на приложение модель платформы: ей пользуются и вкладка, и баннер выбранной организации. */
@Composable
fun rememberPlatformModel(): PlatformViewModel {
    val env = LocalBusiness.current
    return viewModel(key = "platform", factory = viewModelFactory { initializer { PlatformViewModel(env.api) } })
}

@Composable
private fun OrganizationsTab(model: PlatformViewModel, onOpen: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(OrgFilter.All.name) }
    val current = OrgFilter.valueOf(filter)

    StateContainer(
        state = state, onRetry = model::refresh, onRefresh = model::refresh,
        isEmpty = { it.organizations.isEmpty() }, emptyIcon = Icons.Filled.Business,
        emptyTitle = "Организаций пока нет", emptyMessage = "Новые организации создаются на сайте — в разделе платформы.",
    ) { data ->
        val shown = remember(data, query, filter) { filterOrganizations(data.organizations, query, current) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "hero") { OverviewCard(data.overview) }
            if (data.attention.isNotEmpty()) {
                item(key = "attention") {
                    OpsSection("Требуют внимания", count = "${data.attention.size}") {
                        data.attention.forEach { a ->
                            OpsRow(
                                Icons.Filled.Business, Orda.colors.warning, a.name, subtitle = a.reasons.joinToString(" · "),
                                onClick = { onOpen(a.id) },
                            )
                        }
                    }
                }
            }
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OpsSearchField(query, { query = it }, "Организация, поддомен или точка")
                    PillSegment(OrgFilter.entries.map { it.name to it.title }, filter) { filter = it }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text("Ничего не найдено", color = Orda.colors.textDim, modifier = Modifier.padding(vertical = Spacing.lg))
                }
            }
            items(shown, key = { it.id }, contentType = { "org" }) { org ->
                Column(
                    Modifier.fillMaxWidth().background(Orda.colors.surface, RoundedCornerShape(24.dp)).padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                ) {
                    OpsRow(
                        Icons.Filled.Business, statusTint(org), org.name,
                        subtitle = listOfNotNull(
                            org.slug.takeIf { it.isNotEmpty() },
                            "${org.companyCount} из ${org.companyLimit} ${plural(org.companyLimit, "точки", "точек", "точек")}",
                            subscriptionHint(org.subscription),
                        ).joinToString(" · "),
                        trailing = org.statusLabel, trailingColor = statusTint(org),
                        onClick = { onOpen(org.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun statusTint(org: PlatformOrg): Color = when {
    org.isArchived -> Orda.colors.textDim
    org.isSuspended -> Orda.colors.negative
    org.subscription?.status == "past_due" || org.overdueInvoices > 0 -> Orda.colors.warning
    org.subscription?.isTrial == true -> Orda.colors.info
    else -> Orda.colors.positive
}

@Composable
private fun OverviewCard(o: PlatformOverview) {
    HeroCard {
        Text("Выручка в месяц", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
        Text(tenge(o.liveMrr), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        if (o.trialMrr > 0) Text("ещё ${tenge(o.trialMrr)} на триалах — это пока не деньги", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            HeroFact("Организации", "${o.activeOrganizations} из ${o.organizations}")
            HeroFact("Точки", "${o.companies}")
            HeroFact("Люди", "${o.members}")
        }
        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            HeroFact("Триалы", "${o.trialingSubscriptions}")
            HeroFact("Просрочены", "${o.pastDueSubscriptions}")
            HeroFact("Оплачено за месяц", tenge(o.paidThisMonth))
        }
        if (o.overdueInvoices > 0) {
            Text(
                "Просроченных счетов: ${o.overdueInvoices} на ${tenge(o.overdueSum)}",
                color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

/**
 * Полоса «смотрите как владелец» — постоянно на виду: перепутать чужую организацию
 * со своей, правя деньги и права, дорого. Нет выбранной организации — не рисуется.
 */
@Composable
fun OrganizationContextBanner() {
    val env = LocalBusiness.current
    val organizationId = env.api.organizationId ?: return
    val model = rememberPlatformModel()
    val state by model.state.collectAsStateWithLifecycle()
    val name = state.data?.org(organizationId)?.name ?: "организация"
    Row(
        Modifier.fillMaxWidth().background(kz.ordaops.android.ui.theme.Navy).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(Icons.Filled.Visibility, null, tint = Color.White, modifier = Modifier.padding(end = Spacing.sm))
        Text("Смотрите как владелец: $name", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
        TextButton(onClick = { env.selectOrganization(null) }) { Text("Вся платформа", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}
