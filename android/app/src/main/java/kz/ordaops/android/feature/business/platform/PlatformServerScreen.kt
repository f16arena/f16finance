package kz.ordaops.android.feature.business.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.NativePage
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.business.ops.HeroFact
import kz.ordaops.android.feature.business.ops.OpsSection
import kz.ordaops.android.feature.business.ops.dateTimeLabel
import kz.ordaops.android.feature.business.ops.plural
import kz.ordaops.android.feature.operator.money.HeroCard
import kz.ordaops.android.feature.operator.money.StatRow
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

@Composable
private fun HealthLevel.tint(): Color = when (this) {
    HealthLevel.Ok -> Orda.colors.positive
    HealthLevel.Running -> Orda.colors.info
    HealthLevel.Warn -> Orda.colors.warning
    HealthLevel.Bad -> Orda.colors.negative
}

private fun epochLabel(t: Long): String =
    dateTimeLabel(java.time.Instant.ofEpochSecond(t).toString()).orEmpty()

/**
 * Сервер (только чтение): состояние хоста, сайты, контейнеры, деплои, бэкап, кроны.
 * Пересобрать, откатить версию и вернуть автодеплой — только на сайте: на телефоне
 * такая кнопка нажимается слишком легко.
 */
@Composable
internal fun ServerTab() {
    val env = LocalBusiness.current
    val context = LocalContext.current
    val model: ServerStatusViewModel = viewModel(key = "platform-server", factory = viewModelFactory { initializer { ServerStatusViewModel(env.api) } })
    val state by model.state.collectAsStateWithLifecycle()
    var logName by rememberSaveable { mutableStateOf<String?>(null) }

    // Пока вкладка открыта — свежие цифры каждые полминуты, как сайт каждые пять секунд, но экономнее для батареи.
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(30_000)
        }
    }

    StateContainer(
        state = state, onRetry = model::refresh, onRefresh = model::refresh,
        emptyIcon = Icons.Filled.Dns,
    ) { status ->
        val verdict = remember(status) { healthVerdict(status) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "verdict") {
                HeroCard {
                    Text("Состояние", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp)
                    Text(verdict.level.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    verdict.issues.forEach { (_, why) -> Text("• $why", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp) }
                    status.server?.let { s ->
                        Row(Modifier.padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                            HeroFact("Процессор", s.cpuNow?.let { "%.0f%%".format(it) } ?: "—")
                            HeroFact("Память", "%.0f%%".format(s.memPercent))
                            HeroFact("Диск", "%.0f%%".format(s.diskPercent))
                        }
                    }
                }
            }
            status.server?.let { s ->
                item(key = "server") {
                    OpsSection("Сервер ${s.host}") {
                        StatRow("Работает", uptimeText(s.uptimeSeconds))
                        StatRow("Ядер", "${s.cores}  ·  нагрузка ${"%.2f".format(java.util.Locale("ru"), s.load1)}")
                        StatRow("Память", "${bytesText(s.memUsed)} из ${bytesText(s.memTotal)}")
                        StatRow("Диск", "${bytesText(s.diskUsed)} из ${bytesText(s.diskTotal)}")
                        if (s.swapTotal > 0) StatRow("Подкачка", "${bytesText(s.swapUsed)} из ${bytesText(s.swapTotal)}")
                    }
                }
            }
            if (status.checks.isNotEmpty()) {
                item(key = "checks") {
                    OpsSection("Сайты") {
                        status.checks.forEach { c ->
                            StatRow(c.host, if (c.isUp) "работает" + (c.ms?.let { " · $it мс" } ?: "") else "не отвечает", valueColor = if (c.isUp) Orda.colors.positive else Orda.colors.negative)
                        }
                    }
                }
            }
            if (status.containers.isNotEmpty()) {
                item(key = "containers") {
                    OpsSection("Контейнеры", count = "${status.containers.count { it.isHealthy }} из ${status.containers.size}") {
                        status.containers.forEach { c ->
                            StatRow(
                                c.name, c.health?.takeIf { c.state == "running" } ?: c.state,
                                valueColor = if (c.isHealthy) Orda.colors.positive else Orda.colors.negative,
                            )
                        }
                    }
                }
            }
            item(key = "deploys") {
                OpsSection("Последние деплои") {
                    if (status.deploys.isEmpty()) Text("Журнал деплоев пуст", color = Orda.colors.textDim, fontSize = 14.sp)
                    status.deploys.take(8).forEach { d ->
                        val tint = when (d.status) {
                            "ok" -> Orda.colors.positive
                            "running" -> Orda.colors.info
                            "unknown" -> Orda.colors.warning
                            else -> Orda.colors.negative
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            StatRow(
                                d.subject.ifEmpty { d.sha.ifEmpty { "Деплой" } }, d.statusTitle, valueColor = tint,
                            )
                            Text(
                                listOfNotNull(d.sha.takeIf { it.isNotEmpty() }, agoText(d.start, status.now), durationText(d.duration).takeIf { it.isNotEmpty() }).joinToString(" · "),
                                color = Orda.colors.textDim, fontSize = 12.sp,
                            )
                            d.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = Orda.colors.negative, fontSize = 12.sp, maxLines = 3) }
                        }
                    }
                    Text("Пересобрать или откатить версию — на сайте.", color = Orda.colors.textDim, fontSize = 13.sp)
                }
            }
            status.backup?.let { b ->
                item(key = "backup") {
                    OpsSection("Бэкап базы") {
                        StatRow("Последний", agoText(b.time, status.now), valueColor = if (status.now - b.time > 26 * 3600) Orda.colors.warning else Orda.colors.text)
                        StatRow("Размер", bytesText(b.sizeBytes.toDouble()))
                        StatRow("Копий хранится", "${b.count}")
                    }
                }
            }
            if (status.crons.isNotEmpty()) {
                item(key = "crons") {
                    OpsSection("Кроны за сутки", count = "${status.crons.size}") {
                        status.crons.forEach { c ->
                            StatRow(
                                c.name, if (c.failed24h > 0) "сбоев ${c.failed24h} из ${c.runs24h}" else "${c.runs24h} ${plural(c.runs24h, "запуск", "запуска", "запусков")}",
                                valueColor = if (c.isFailing) Orda.colors.negative else if (c.failed24h > 0) Orda.colors.warning else Orda.colors.text,
                            )
                        }
                    }
                }
            }
            item(key = "logs") {
                OpsSection("Журналы") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        HOST_LOGS.forEach { (key, title) ->
                            OutlinedButton(onClick = { logName = key }, modifier = Modifier.weight(1f)) { Text(title, color = Orda.colors.text, maxLines = 1) }
                        }
                    }
                }
            }
            item(key = "web") {
                OutlinedButton(onClick = { NativePage.openWeb(context, "/platform/status") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть сервер на сайте (деплой, база, Studio)", color = Orda.colors.text)
                }
            }
        }
    }
    logName?.let { name -> LogSheet(model, name, onDismiss = { logName = null }) }
}

@Composable
private fun LogSheet(model: ServerStatusViewModel, name: String, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(name) {
        scope.launch {
            try {
                text = tailLines(model.log(name, 200), 200)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            }
        }
    }
    OrdaSheet("Журнал: ${HOST_LOGS.firstOrNull { it.first == name }?.second ?: name}", onDismiss) {
        when {
            error != null -> Text(error!!, color = Orda.colors.negative)
            text == null -> Text("Загружаем…", color = Orda.colors.textDim)
            text!!.isBlank() -> Text("Журнал пуст", color = Orda.colors.textDim)
            else -> Text(text!!, color = Orda.colors.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
        }
    }
}

/** Журнал ошибок сервера и браузеров, сгруппированный: где, что, сколько раз. */
@Composable
internal fun ErrorsTab() {
    val env = LocalBusiness.current
    val model: ErrorsViewModel = viewModel(key = "platform-errors", factory = viewModelFactory { initializer { ErrorsViewModel(env.api) } })
    val state by model.state.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf("24h") }
    var opened by remember { mutableStateOf<ErrorGroup?>(null) }
    LaunchedEffect(period) { model.load(period) }

    Column(Modifier.fillMaxSize()) {
        PillSegment(ERROR_PERIODS, period, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)) { period = it }
        StateContainer(
            state = state, onRetry = { model.load(period) }, onRefresh = { model.load(period) },
            isEmpty = { it.groups.isEmpty() }, emptyIcon = Icons.Filled.BugReport,
            emptyTitle = "Ошибок нет", emptyMessage = "За выбранный период сервер и браузеры ошибок не прислали.",
            modifier = Modifier.weight(1f),
        ) { report ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 96.dp, top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item(key = "total") {
                    Text(
                        "Всего ${report.total} ${plural(report.total, "случай", "случая", "случаев")}, групп ${report.groups.size}" +
                            if (report.truncated) " · показаны последние 1000" else "",
                        color = Orda.colors.textDim, fontSize = 13.sp,
                    )
                }
                items(report.groups, key = { it.area + "|" + it.message }, contentType = { "group" }) { g ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(Orda.colors.surface, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                            .clickable { opened = g }
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(g.area, color = Orda.colors.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("×${g.count}", color = if (g.count >= 10) Orda.colors.negative else Orda.colors.warning, fontWeight = FontWeight.Bold)
                        }
                        Text(g.message.ifEmpty { "Без текста" }, color = Orda.colors.text, fontSize = 15.sp, maxLines = 3)
                        Text(
                            listOfNotNull(if (g.isClient) "браузер" else "сервер", agoText(g.lastAt, report.now), g.users.takeIf { it > 0 }?.let { "$it ${plural(it, "человек", "человека", "человек")}" }).joinToString(" · "),
                            color = Orda.colors.textDim, fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
    opened?.let { g -> ErrorSheet(g, onDismiss = { opened = null }) }
}

@Composable
private fun ErrorSheet(g: ErrorGroup, onDismiss: () -> Unit) {
    OrdaSheet(g.area, onDismiss) {
        Text(g.message, color = Orda.colors.text, fontSize = 15.sp)
        StatRow("Случаев", "${g.count}")
        StatRow("Первый", epochLabel(g.firstAt))
        StatRow("Последний", epochLabel(g.lastAt))
        g.samples.firstOrNull()?.let { s ->
            s.pathname?.let { StatRow("Страница", it) }
            s.actor?.let { StatRow("Кто", it) }
            s.stack?.takeIf { it.isNotBlank() }?.let {
                Text(it.take(1500), color = Orda.colors.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
            }
        }
    }
}
