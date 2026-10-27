package kz.ordaops.android.feature.business.ops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing
import java.time.Instant

// «Точки и устройства»: операторские программы, киоски и кассы с отметкой «на связи».

/** Программа точки (оператор, киоск, касса) — `PointProject`. */
data class PointProject(
    val id: String,
    val name: String,
    val mode: String,
    val isActive: Boolean,
    val lastSeen: Instant?,
    val companyNames: List<String>,
) {
    val modeLabel: String
        get() = when (mode) {
            "operator" -> "Операторская"
            "kiosk" -> "Киоск"
            "pos" -> "Касса"
            else -> mode
        }

    /** На связи: выходила за последние сутки. */
    fun isOnline(now: Instant = Instant.now()): Boolean =
        lastSeen != null && now.epochSecond - lastSeen.epochSecond < 86_400

    val neverSeen: Boolean get() = lastSeen == null

    /** Статус текстом: выключена / не подключалась / на связи / молчит. */
    fun statusText(now: Instant = Instant.now()): String = when {
        !isActive -> "выключена"
        neverSeen -> "не подключалась"
        isOnline(now) -> "на связи"
        else -> "молчит"
    }

    companion object {
        fun parse(o: JsonObject) = PointProject(
            id = o.str("id") ?: "",
            name = o.str("name") ?: "Без названия",
            mode = o.str("point_mode") ?: "operator",
            isActive = o.bool("is_active") ?: true,
            lastSeen = parseMoment(o.str("last_seen_at")),
            companyNames = o.arr("companies").map { it.str("name") ?: "Точка" },
        )
    }
}

/** Активные программы, не выходившие на связь больше суток (никогда не подключавшиеся тоже). */
fun offlineProjects(projects: List<PointProject>, now: Instant = Instant.now()): List<PointProject> =
    projects.filter { it.isActive && !it.isOnline(now) }

private fun parseProjects(root: JsonObject): List<PointProject> =
    (root.obj("data") ?: root).arr("projects").map(PointProject::parse).filter { it.id.isNotEmpty() }

/** Тело переключателя: токен сохраняется, включить обратно можно в любой момент. */
fun toggleProjectBody(projectId: String, isActive: Boolean): JsonObject = buildJsonObject {
    put("action", "toggleProjectActive"); put("projectId", projectId); put("is_active", isActive)
}

private fun PointProject.icon(): ImageVector = when (mode) {
    "operator" -> Icons.Filled.DesktopWindows
    "kiosk" -> Icons.Filled.Tv
    "pos" -> Icons.Filled.CreditCard
    else -> Icons.Filled.Dashboard
}

@Composable
fun PointDevicesScreen() {
    val env = LocalBusiness.current
    val canToggle = env.can("point-devices.toggle_active")
    val loader = rememberOpsLoader("point-devices", ApiRequest("/api/admin/point-devices"), ::parseProjects)
    val state by loader.state.collectAsStateWithLifecycle()
    val submit = rememberOpsSubmit()
    val haptics = rememberHaptics()
    var toggling by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        StateContainer(
            state = state,
            onRetry = loader::refresh,
            isEmpty = { it.isEmpty() },
            emptyIcon = Icons.Filled.DesktopWindows,
            emptyTitle = "Программ нет",
            emptyMessage = "Здесь появятся операторские программы и киоски точек.",
        ) { projects ->
            val offline = offlineProjects(projects)
            val online = projects.count { it.isOnline() }
            LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                item(key = "hero") {
                    // Одна цифра вместо трёх плиток: сколько на связи; цвет сразу говорит, есть ли молчащие.
                    HeroCard {
                        Text(if (offline.isEmpty()) "Все программы на связи" else "Есть молчащие программы", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                        Text("$online из ${projects.size}", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("на связи сейчас", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                            HeroFact("Всего программ", "${projects.size}")
                            HeroFact("Молчат сутки", "${offline.size}")
                        }
                    }
                }
                submit.error?.let { item(key = "err") { ErrorBanner(it) } }
                if (offline.isNotEmpty()) {
                    item(key = "offline") {
                        OpsSection("Не выходят на связь", count = "точка может не продавать") {
                            offline.forEach { p -> ProjectRow(p, null) }
                        }
                    }
                }
                item(key = "all-head") {
                    OpsSection("Все программы", count = "${projects.size}") {}
                }
                items(projects, key = { it.id }) { p ->
                    ProjectRow(p, if (canToggle) ({ toggling = p.id }) else null)
                }
            }
        }
    }

    // Планшет забыли на точке, компьютер увезли в ремонт — доступ закрывают в ту же минуту.
    state.data?.firstOrNull { it.id == toggling }?.let { project ->
        OpsConfirm(
            if (project.isActive) "Отключить устройство?" else "Включить устройство?",
            if (project.isActive) {
                "${project.name} перестанет работать: касса не откроется и чеки не пробьются. Токен сохранится — включить обратно можно здесь же."
            } else {
                "${project.name} снова заработает с прежним токеном, настраивать заново не нужно."
            },
            if (project.isActive) "Отключить" else "Включить",
            onConfirm = {
                toggling = null
                submit.run(
                    { env.api.sendUnit(ApiRequest.json("/api/admin/point-devices", toggleProjectBody(project.id, !project.isActive))) },
                    { haptics.success(); loader.refresh() },
                )
            },
            onDismiss = { toggling = null },
        )
    }
}

@Composable
private fun ProjectRow(project: PointProject, onClick: (() -> Unit)?) {
    val color = when {
        !project.isActive -> Orda.colors.textDim
        project.neverSeen -> Orda.colors.warning
        project.isOnline() -> Orda.colors.positive
        else -> Orda.colors.negative
    }
    val subtitle = if (project.companyNames.isEmpty()) project.modeLabel else "${project.modeLabel} · ${project.companyNames.joinToString(", ")}"
    OpsRow(
        project.icon(), color, project.name, subtitle = subtitle,
        trailing = project.statusText(), trailingColor = color,
        trailingSub = project.lastSeen?.let { dateTimeLabel(it.toString()) }, onClick = onClick,
    )
}
