package kz.ordaops.android.feature.business.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.NativePage
import kz.ordaops.android.feature.business.OwnerFootnote
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.ops.OpsConfirm
import kz.ordaops.android.feature.business.ops.OpsPicker
import kz.ordaops.android.feature.business.ops.OpsSearchField
import kz.ordaops.android.feature.business.ops.OpsSection
import kz.ordaops.android.feature.business.ops.OpsSwitchRow
import kz.ordaops.android.feature.business.ops.dateTimeLabel
import kz.ordaops.android.feature.business.ops.longDay
import kz.ordaops.android.feature.business.ops.plural
import kz.ordaops.android.feature.business.ops.rememberOpsSubmit
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.feature.operator.money.SectionLabel
import kz.ordaops.android.feature.operator.money.SoftDivider
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.money.SubScreen
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

/** Действие, ждущее подтверждения: заголовок, пояснение, надпись кнопки и тело `PATCH`. */
private class PendingAction(val title: String, val message: String, val confirm: String, val body: JsonObject)

/**
 * Карточка организации: статус и подписка, точки и их лимит, пакет с модулями и
 * ручными функциями, оплата. Каждая запись — тот же `PATCH /api/admin/organizations`,
 * что и на сайте, и с подтверждением там, где последствия видны людям.
 */
@Composable
fun OrganizationScreen(model: PlatformViewModel, orgId: String, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val data = state.data
    val org = data?.org(orgId)
    val disabled by model.disabledActions.collectAsStateWithLifecycle()
    LaunchedEffect(orgId) { model.loadDisabledActions(orgId) }

    SubScreen(org?.name ?: "Организация", onBack = onBack) {
        if (org == null || data == null) {
            EmptyState(Icons.Filled.Business, "Организация не найдена", "Возможно, её удалили. Вернитесь к списку и обновите его.")
            return@SubScreen
        }
        OrganizationBody(model, data, org, disabledActions = disabled[orgId])
    }
}

@Composable
private fun OrganizationBody(model: PlatformViewModel, data: PlatformData, org: PlatformOrg, disabledActions: Int?) {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var trialSheet by remember { mutableStateOf(false) }
    var featuresSheet by remember { mutableStateOf(false) }
    var limitDraft by remember(org.companyLimit) { mutableStateOf(org.companyLimit) }

    fun apply(body: JsonObject) = submit.run(
        { model.patch(body)?.let { error(it) } },
        { haptics.success() },
    )

    OwnerScreenColumn {
        submit.error?.let { ErrorBanner(it) }
        HeroCard {
            Text(org.statusLabel, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
            Text(org.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(org.slug.takeIf { it.isNotEmpty() }, "${org.memberCount} ${plural(org.memberCount, "человек", "человека", "человек")}").joinToString(" · "),
                color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
            )
        }
        if (!org.isArchived) {
            PrimaryButton("Войти как владелец", Modifier.fillMaxWidth()) { env.selectOrganization(org.id); env.goTab(kz.ordaops.android.feature.business.BusinessTab.Home) }
            OwnerFootnote("Приложение покажет данные этой организации, как их видит её владелец. Вернуться — «Вся платформа» вверху.")
        }

        // ── Подписка ──
        OpsSection("Подписка") {
            val sub = org.subscription
            StatRow("Статус", sub?.statusLabel ?: "Подписки нет")
            sub?.planName?.let { StatRow("Тариф", it) }
            longDay(sub?.endsAt)?.let { StatRow(if (sub?.isTrial == true) "Триал до" else "Оплачено до", it) }
            subscriptionHint(sub)?.let { StatRow("Срок", it) }
            when {
                org.isArchived -> Text("Организация в архиве — восстановить или удалить её можно только на сайте.", color = Orda.colors.textDim, fontSize = 13.sp)
                else -> {
                    SoftDivider()
                    OutlinedButton(onClick = { trialSheet = true }, enabled = !submit.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Выдать пробный период", color = Orda.colors.text)
                    }
                    if (org.isSuspended) {
                        PrimaryButton("Возобновить доступ", Modifier.fillMaxWidth(), enabled = !submit.busy) {
                            pending = PendingAction(
                                "Возобновить доступ?", "Организация «${org.name}» снова станет активной, подписка возобновится, сотрудники смогут входить.",
                                "Возобновить", OrgPatch.resume(org.id),
                            )
                        }
                    } else {
                        TextButton(onClick = {
                            pending = PendingAction(
                                "Приостановить доступ?",
                                "Всем сотрудникам «${org.name}» закроется вход, подписка уйдёт в просрочку. Это видно сразу. Вернуть можно кнопкой «Возобновить».",
                                "Приостановить", OrgPatch.suspend(org.id),
                            )
                        }, enabled = !submit.busy, modifier = Modifier.fillMaxWidth()) { Text("Приостановить доступ", color = Orda.colors.negative) }
                    }
                }
            }
        }

        // ── Точки ──
        OpsSection("Точки", count = "${org.companyCount} из ${org.companyLimit}") {
            if (org.companies.isEmpty()) Text("Точек пока нет", color = Orda.colors.textDim, fontSize = 14.sp)
            org.companies.forEach { c ->
                StatRow(c.name, c.code.orEmpty())
            }
            SoftDivider()
            SectionLabel("Сколько точек можно создать")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedButton(onClick = { limitDraft = (limitDraft - 1).coerceAtLeast(org.companyCount.coerceAtLeast(1)) }, enabled = !submit.busy) { Text("−") }
                Text("$limitDraft", color = Orda.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { limitDraft += 1 }, enabled = !submit.busy) { Text("+") }
                if (limitDraft != org.companyLimit) {
                    PrimaryButton("Сохранить", Modifier.weight(1f), loading = submit.busy) { apply(OrgPatch.companyLimit(org.id, limitDraft)) }
                }
            }
            if (org.limitReached) Text("Лимит исчерпан: новую точку организация создать не сможет.", color = Orda.colors.warning, fontSize = 13.sp)
        }

        // ── Пакет и функции ──
        OpsSection("Пакет и функции") {
            OpsPicker(
                "Пакет", listOf("" to "Без пакета") + data.packages.map { it.code to "${it.name} · ${tenge(it.priceKzt)}" },
                org.packageCode.orEmpty(),
                onSelect = { code ->
                    if (code != org.packageCode.orEmpty()) {
                        val name = data.packages.firstOrNull { it.code == code }?.name
                        pending = PendingAction(
                            if (code.isEmpty()) "Снять пакет?" else "Назначить пакет «$name»?",
                            if (code.isEmpty()) "Функции пакета у организации отключатся." else "Функции пакета включатся у всех точек организации.",
                            if (code.isEmpty()) "Снять" else "Назначить", OrgPatch.assignPackage(org.id, code),
                        )
                    }
                },
            )
            if (data.addons.isNotEmpty()) {
                SectionLabel("Модули")
                data.addons.forEach { addon ->
                    OpsSwitchRow(
                        addon.name, addon.code in org.addonCodes, subtitle = addon.description?.takeIf { it.isNotBlank() } ?: tenge(addon.priceKzt), enabled = !submit.busy,
                        onChange = { on -> apply(OrgPatch.setAddon(org.id, addon.code, on)) },
                    )
                }
            }
            SoftDivider()
            OpsSwitchRow(
                "Жёсткая блокировка по пакету", org.featuresEnforced,
                subtitle = "Страницы вне пакета закрыты. Выключено — открыты все.", enabled = !submit.busy,
                onChange = { on ->
                    pending = PendingAction(
                        if (on) "Включить блокировку?" else "Выключить блокировку?",
                        if (on) "У «${org.name}» закроются все страницы, которых нет в пакете и модулях." else "У «${org.name}» откроются все страницы, даже не входящие в пакет.",
                        if (on) "Включить" else "Выключить", OrgPatch.featuresEnforced(org.id, on),
                    )
                },
            )
            val manual = org.effectiveFeatures.count { it.isManual }
            OutlinedButton(onClick = { featuresSheet = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Функции вручную" + if (manual > 0) " · выдано $manual" else "", color = Orda.colors.text)
            }
            Text(
                "Действует функций: ${org.effectiveFeatures.size}" + (disabledActions?.takeIf { it > 0 }?.let { " · выключено действий рубильником: $it" } ?: ""),
                color = Orda.colors.textDim, fontSize = 13.sp,
            )
        }

        // ── Оплата ──
        OpsSection("Оплата") {
            OpsSwitchRow(
                "Вне подписки", org.billingExempt, subtitle = "Внутренний клиент: счета не выставляются, доступ без блокировок.", enabled = !submit.busy,
                onChange = { on ->
                    pending = PendingAction(
                        if (on) "Вывести из подписки?" else "Вернуть в подписку?",
                        if (on) "«${org.name}» перестанет блокироваться за неоплату и получать счета." else "«${org.name}» снова подчиняется сроку подписки и может быть заблокирована.",
                        if (on) "Вывести" else "Вернуть", OrgPatch.billingExempt(org.id, on),
                    )
                },
            )
            if (org.overdueInvoices > 0) {
                Text("Просроченных счетов: ${org.overdueInvoices}", color = Orda.colors.warning, fontSize = 14.sp)
            }
            org.invoices.take(5).forEach { inv ->
                StatRow(
                    "Счёт · ${invoiceStatus(inv)}", tenge(inv.amount),
                    valueColor = if (inv.isOverdue()) Orda.colors.warning else Orda.colors.text,
                )
            }
            org.billingEvents.take(5).forEach { e ->
                StatRow(e.title, dateTimeLabel(e.createdAt).orEmpty(), labelColor = Orda.colors.textDim)
            }
            Text("Выставить счёт и отметить оплату — на сайте.", color = Orda.colors.textDim, fontSize = 13.sp)
        }

        // ── Только на сайте ──
        OpsSection("Только на сайте") {
            Text(
                "Архив и удаление организации, владелец и участники, счета, логотип, конструктор пакетов, рубильник отдельных действий.",
                color = Orda.colors.textDim, fontSize = 13.sp,
            )
            OutlinedButton(onClick = { NativePage.openWeb(context, "/platform/organizations/${org.id}") }, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть карточку на сайте", color = Orda.colors.text)
            }
        }
    }

    pending?.let { action ->
        OpsConfirm(
            action.title, action.message, action.confirm,
            onConfirm = { pending = null; apply(action.body) },
            onDismiss = { pending = null },
        )
    }
    if (trialSheet) TrialSheet(
        org, onDismiss = { trialSheet = false },
        onPick = { days ->
            trialSheet = false
            pending = PendingAction(
                "Выдать пробный период?",
                "Подписка «${org.name}» станет пробной на $days ${plural(days, "день", "дня", "дней")} с сегодняшнего дня. Если организация заморожена, она снова станет активной.",
                "Выдать", OrgPatch.startTrial(org.id, days),
            )
        },
    )
    if (featuresSheet) FeaturesSheet(data, org, busy = submit.busy, onDismiss = { featuresSheet = false }, onToggle = { code, on -> apply(OrgPatch.setFeatureGrant(org.id, code, on)) })
}

private fun invoiceStatus(inv: OrgInvoice): String = when {
    inv.isOverdue() -> "просрочен"
    inv.status == "paid" -> "оплачен"
    inv.status == "void" -> "аннулирован"
    inv.status == "issued" -> "выставлен"
    else -> inv.status
}

@Composable
private fun TrialSheet(org: PlatformOrg, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var days by remember { mutableStateOf(14) }
    OrdaSheet("Пробный период", onDismiss) {
        Text("«${org.name}» получит пробный период с сегодняшнего дня. Прежний срок подписки заменится.", color = Orda.colors.textMuted, fontSize = 14.sp)
        OpsPicker("Срок", TRIAL_DAY_OPTIONS.map { "$it" to "$it ${plural(it, "день", "дня", "дней")}" }, "$days", onSelect = { days = it.toInt() })
        PrimaryButton("Далее", Modifier.fillMaxWidth()) { onPick(days) }
    }
}

/** Функции каталога: переключатель выдаёт или снимает ручной грант; чужие источники (пакет, модуль) показаны подписью. */
@Composable
private fun FeaturesSheet(data: PlatformData, org: PlatformOrg, busy: Boolean, onDismiss: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    var query by remember { mutableStateOf("") }
    val sources = remember(org) { org.effectiveFeatures.associate { it.code to it.sources } }
    val rows = remember(data, query) {
        val needle = query.trim().lowercase()
        data.features.filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.code.lowercase().contains(needle) }
    }
    OrdaSheet("Функции организации", onDismiss) {
        Text("Переключатель — выдача вручную. Функции из пакета и модулей он не отключает.", color = Orda.colors.textDim, fontSize = 13.sp)
        OpsSearchField(query, { query = it }, "Найти функцию")
        if (data.features.isEmpty()) Text("Каталог функций не загрузился.", color = Orda.colors.textDim)
        rows.take(60).forEach { f ->
            val src = sources[f.code].orEmpty()
            val others = src.filter { it != "manual" }
            OpsSwitchRow(
                f.name, "manual" in src, enabled = !busy,
                subtitle = if (others.isEmpty()) f.category.takeIf { it.isNotEmpty() } else "уже есть: " + others.joinToString { sourceTitle(it) },
                onChange = { on -> onToggle(f.code, on) },
            )
        }
        if (rows.size > 60) Text("Показаны первые 60 из ${rows.size} — уточните поиск.", color = Orda.colors.textDim, fontSize = 13.sp)
    }
}

private fun sourceTitle(source: String): String = when (source) {
    "plan" -> "по пакету"
    "addon" -> "по модулю"
    "legacy" -> "исторически"
    "manual" -> "вручную"
    else -> source
}
