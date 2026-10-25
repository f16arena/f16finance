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
import android.Manifest
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kz.ordaops.android.platform.widget.WidgetSnapshot
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import kz.ordaops.android.feature.operator.shift.rememberOperatorStore
import kz.ordaops.android.platform.DeepLinks
import kz.ordaops.android.platform.ShiftNotification
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kz.ordaops.android.core.access.AccessResolver
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.operator.arena.ArenaTab
import kz.ordaops.android.feature.operator.audit.AuditTab
import kz.ordaops.android.feature.operator.chat.ChatTab
import kz.ordaops.android.feature.operator.chat.MessagesTab
import kz.ordaops.android.feature.operator.common.ActionOutbox
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.common.OperatorEnv
import kz.ordaops.android.feature.operator.knowledge.KnowledgeTab
import kz.ordaops.android.feature.operator.money.MoneyTab
import kz.ordaops.android.feature.operator.money.OperatorProfileContent
import kz.ordaops.android.feature.operator.money.ScheduleTab
import kz.ordaops.android.feature.operator.sale.SaleTab
import kz.ordaops.android.feature.operator.shift.ShiftTab
import kz.ordaops.android.feature.operator.work.ChecklistsTab
import kz.ordaops.android.feature.operator.work.TasksTab
import kz.ordaops.android.ui.components.pressScale
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
    val context = LocalContext.current
    val appScope = rememberCoroutineScope()
    val outbox = remember { ActionOutbox(context.applicationContext, api) }
    var points by remember { mutableStateOf<OperatorPoints?>(null) }
    LaunchedEffect(Unit) {
        // Хозяин очереди — до первой отправки: иначе накопленное сменщиком
        // уйдёт под именем того, кто вошёл сейчас.
        outbox.load()
        outbox.setOwner(resolver.session.operatorId)
        outbox.flush()
    }
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
    // Вкладки меняются, когда приходит обзор (магазин/клуб) — env должен видеть свежие.
    val currentTabs by rememberUpdatedState(tabs)
    var selected by rememberSaveable { mutableStateOf(OperatorSection.Shift) }
    var opened by rememberSaveable { mutableStateOf<OperatorSection?>(null) }
    val pending by outbox.pendingCount.collectAsState()
    val env = remember(resolver) {
        OperatorEnv(
            api = api,
            outbox = outbox,
            resolver = resolver,
            open = { name ->
                OperatorSection.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { target ->
                    if (target in currentTabs) {
                        selected = target
                        opened = null
                    } else {
                        opened = target
                    }
                }
            },
            signOut = {
                // Цифры смены не должны остаться на рабочем столе после выхода.
                appScope.launch { WidgetSnapshot.clear(context) }
                onSignOut()
            },
        )
    }

    // Из уведомления смены, ярлыка на иконке или плитки в шторке.
    LaunchedEffect(env) {
        DeepLinks.pendingSection.collect { name ->
            if (name != null) {
                env.open(name)
                DeepLinks.pendingSection.value = null
            }
        }
    }
    // Открытый из профиля раздел закрывается жестом «назад», а не выходом из приложения.
    // Предпросмотр жеста: раздел уменьшается и скругляется вслед за пальцем; отпустили —
    // закрылся, передумали — плавно вернулся.
    val backProgress = remember { Animatable(0f) }
    val backScope = rememberCoroutineScope()
    PredictiveBackHandler(enabled = opened != null) { events ->
        try {
            events.collect { backProgress.snapTo(it.progress) }
            opened = null
            backProgress.snapTo(0f)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            backScope.launch { backProgress.animateTo(0f, tween(220)) }
            throw e
        }
    }

    // Телефон — вкладки снизу; планшет и раскрытый складной — панель сбоку, и в
    // ней сразу все разделы: место есть, лишний заход в «Профиль» ни к чему.
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val navItems = if (wide) {
        tabs.filterNot { it == OperatorSection.Profile } + OperatorSection.moreSections(sellsGoods, hasArena) + OperatorSection.Profile
    } else {
        tabs
    }
    // Цвета считаются вне списка пунктов: он не composable-контекст.
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = Orda.colors.brand,
            selectedTextColor = Orda.colors.brand,
            unselectedIconColor = Orda.colors.textDim,
            unselectedTextColor = Orda.colors.textDim,
            indicatorColor = Orda.colors.brand.copy(alpha = 0.15f),
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = Orda.colors.brand,
            selectedTextColor = Orda.colors.brand,
            unselectedIconColor = Orda.colors.textDim,
            unselectedTextColor = Orda.colors.textDim,
            indicatorColor = Orda.colors.brand.copy(alpha = 0.15f),
        ),
    )
    val current = opened ?: selected
    NavigationSuiteScaffold(
        containerColor = Orda.colors.background,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = Orda.colors.elevated,
            navigationRailContainerColor = Orda.colors.elevated,
            navigationDrawerContainerColor = Orda.colors.elevated,
        ),
        navigationSuiteItems = {
            navItems.forEach { section ->
                val isSelected = current == section
                item(
                    selected = isSelected,
                    onClick = { env.open(section.name) },
                    icon = {
                        val bounce by animateFloatAsState(
                            if (isSelected) 1.18f else 1f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                            label = "navBounce",
                        )
                        Box(Modifier.graphicsLayer { scaleX = bounce; scaleY = bounce }) {
                            // Неотправленное без связи — видно на «Смене», чтобы не уйти домой с ним.
                            if (section == OperatorSection.Shift && pending > 0) {
                                BadgedBox(badge = { Badge { Text("$pending") } }) { Icon(section.icon, contentDescription = null) }
                            } else {
                                Icon(section.icon, contentDescription = null)
                            }
                        }
                    },
                    label = { Text(section.tabTitle, fontSize = 11.sp, maxLines = 1) },
                    colors = itemColors,
                )
            }
        },
    ) {
        val padding = WindowInsets.statusBars.asPaddingValues()
        Column(Modifier.fillMaxSize().padding(padding)) {
            CompositionLocalProvider(LocalOperatorEnv provides env) {
                ShiftNotificationEffect()
                // Смена раздела — короткий сдвиг с затуханием вместо резкой подмены экрана.
                AnimatedContent(
                    modifier = Modifier.graphicsLayer {
                        val p = backProgress.value
                        val scale = 1f - 0.1f * p
                        scaleX = scale
                        scaleY = scale
                        if (p > 0f) {
                            shape = RoundedCornerShape((28 * p).dp)
                            clip = true
                        }
                    },
                    targetState = opened ?: selected,
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        (fadeIn(tween(220)) + slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { if (forward) it / 6 else -it / 6 })
                            .togetherWith(fadeOut(tween(120)))
                    },
                    label = "section",
                ) { section ->
                when (section) {
                    OperatorSection.Shift -> ShiftTab()
                    OperatorSection.Sale -> SaleTab()
                    OperatorSection.Arena -> ArenaTab()
                    OperatorSection.Audit -> AuditTab()
                    OperatorSection.Tasks -> TasksTab()
                    OperatorSection.Checklists -> ChecklistsTab()
                    OperatorSection.Knowledge -> KnowledgeTab()
                    OperatorSection.Chat -> ChatTab()
                    OperatorSection.Messages -> MessagesTab()
                    OperatorSection.Money -> MoneyTab()
                    OperatorSection.Schedule -> ScheduleTab()
                    OperatorSection.Profile -> OperatorProfileContent(
                        more = OperatorSection.moreSections(sellsGoods, hasArena),
                        onOpen = { opened = it },
                    )
                }
                }
            }
        }
    }
}

/**
 * Смена в шторке: своя открытая смена → постоянное уведомление с таймером и
 * выручкой; закрыли, ушли из аккаунта — уведомление убираем.
 */
@Composable
private fun ShiftNotificationEffect() {
    val context = LocalContext.current
    val store = rememberOperatorStore()
    val state = store.shiftState
    val pending by LocalOperatorEnv.current.outbox.pendingCount.collectAsState()
    // Разрешение дали — перезапустить эффект ниже, чтобы уведомление появилось сразу.
    var granted by remember { mutableStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted++
    }
    val shift = state?.shift
    val mine = shift != null && shift.isOpen && state.isMine
    LaunchedEffect(mine, shift?.id, state?.totals, store.companyName, pending, granted) {
        if (!mine) {
            ShiftNotification.hide(context)
            return@LaunchedEffect
        }
        if (!ShiftNotification.canPost(context) && Build.VERSION.SDK_INT >= 33) {
            // Спрашиваем в момент, когда понятно зачем: смена открыта.
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return@LaunchedEffect
        }
        ShiftNotification.show(
            context,
            ShiftNotification.Snapshot(
                companyName = store.companyName,
                openedAtMillis = shift?.openedAtMillis,
                revenue = state?.totals?.netTotal ?: 0.0,
                salesCount = state?.totals?.salesCount ?: 0,
                isNight = shift?.isNight == true,
                pendingSales = pending,
            ),
        )
    }
    // Виджет на рабочем столе: те же цифры, что в шторке, плюс заработок за неделю.
    val weekEarned = store.overview?.week?.netAmount
    LaunchedEffect(shift?.id, shift?.status, mine, state?.totals, store.companyName, weekEarned) {
        if (state == null) return@LaunchedEffect
        val kind = when {
            shift == null || !shift.isOpen -> WidgetSnapshot.State.Closed
            mine -> WidgetSnapshot.State.Mine
            else -> WidgetSnapshot.State.Other
        }
        // Снимок виджета — файл настроек: читаем и пишем вне главного потока.
        withContext(Dispatchers.IO) {
            val previous = WidgetSnapshot.load(context)
            WidgetSnapshot.save(
                context,
                WidgetSnapshot(
                    state = kind,
                    company = store.companyName,
                    who = shift?.operatorName.orEmpty(),
                    revenue = if (kind == WidgetSnapshot.State.Mine) state.totals.netTotal else 0.0,
                    salesCount = if (kind == WidgetSnapshot.State.Mine) state.totals.salesCount else 0,
                    weekEarned = weekEarned ?: previous.weekEarned,
                ),
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { ShiftNotification.hide(context) }
    }
}

/** Раздел ещё переносится с айфона. */
@Composable
fun SectionPlaceholder(section: OperatorSection) {
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
        Modifier.fillMaxWidth().pressScale().clickable(onClick = onClick).padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        val tint = if (danger) Orda.colors.negative else Orda.colors.brandBright
        Icon(icon, contentDescription = null, tint = tint)
        Text(title, color = if (danger) Orda.colors.negative else Orda.colors.text, fontSize = 16.sp)
    }
}
