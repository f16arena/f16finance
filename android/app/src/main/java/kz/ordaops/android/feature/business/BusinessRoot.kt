package kz.ordaops.android.feature.business

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kz.ordaops.android.core.access.AccessResolver
import kz.ordaops.android.core.access.CapabilityCatalog
import kz.ordaops.android.core.access.PageAccess
import kz.ordaops.android.core.access.Workspace
import kz.ordaops.android.core.access.playbook
import kz.ordaops.android.core.net.ApiClient
import kz.ordaops.android.feature.business.platform.OrganizationContextBanner
import kz.ordaops.android.feature.business.platform.PlatformRoot
import kz.ordaops.android.feature.operator.FloatingTabBar
import kz.ordaops.android.feature.operator.TabBarItem
import kz.ordaops.android.feature.operator.chat.ChatTab
import kz.ordaops.android.feature.operator.common.ActionOutbox
import kz.ordaops.android.feature.operator.common.LocalOperatorEnv
import kz.ordaops.android.feature.operator.common.OperatorEnv
import kz.ordaops.android.feature.operator.money.SubScreen
import kz.ordaops.android.platform.DeepLinks
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.theme.Orda

/** Вкладка кабинета: ключ для панели и сохранения состояния. */
private fun workKey(pageId: String) = "work:$pageId"

/**
 * Кабинет владельца, сотрудника и суперадмина. Владельцу и тем, кому открыты
 * деньги, — кабинет по смыслу (главная, аналитика, сервисы, общение, профиль);
 * остальным должностям — свой (главная по должности, до двух рабочих вкладок,
 * сервисы, профиль). Порт `BusinessRootView` + `OwnerTabs` + `StaffTabs`.
 *
 * Суперадмину первой вкладкой идёт «Платформа» (`feature/business/platform`):
 * организации, сервер, ошибки; опасное остаётся на сайте.
 */
@Composable
fun BusinessRoot(resolver: AccessResolver, api: ApiClient, onSignOut: () -> Unit, onSelectOrganization: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val catalog by produceState<CapabilityCatalog?>(null) {
        value = withContext(Dispatchers.IO) { CapabilityCatalog.load(context) }
    }
    val loaded = catalog
    if (loaded == null) {
        LoadingView()
        return
    }
    val access = remember(resolver, loaded) { PageAccess(resolver, loaded) }
    // Ключ включает организацию: у суперадмина фильтр «Арена за месяц» не должен переехать в чужую компанию.
    val orgKey = api.organizationId ?: "own"
    val vm: BusinessViewModel = viewModel(
        key = "business:$orgKey:${resolver.session.displayName}",
        factory = viewModelFactory {
            initializer {
                BusinessViewModel(
                    api = api,
                    prefs = context.applicationContext.getSharedPreferences("orda_business", android.content.Context.MODE_PRIVATE),
                    organizationKey = orgKey,
                    canSeePending = access.resolver.can("expenses-pending.view") || access.canSeeOwnerAnalytics,
                )
            }
        },
    )
    LaunchedEffect(vm) { vm.bootstrap() }

    val ownerLayout = access.canSeeOwnerAnalytics
    val hasPlatform = resolver.workspace == Workspace.Platform
    // Общение: чат команды, если он выдан, иначе задачи. У суперадмина места нет — вместо него платформа.
    val talk: String? = when {
        !ownerLayout || hasPlatform -> null
        resolver.can("team-chat.view") -> "team-chat"
        resolver.can("tasks.view") -> "tasks"
        else -> null
    }
    val workPages = remember(access) {
        if (ownerLayout) emptyList() else playbookWorkPages(access)
    }

    var tab by rememberSaveable { mutableStateOf(BusinessTab.Home.name) }
    // Нативные экраны разделов поверх вкладки; пока их нет — стек пуст, всё уходит на сайт.
    var stack by rememberSaveable { mutableStateOf(listOf<String>()) }

    val openPage: (String) -> Unit = { pageId ->
        when {
            NativePage.isNative(pageId) -> stack = stack + pageId
            else -> access.catalog.page(pageId)?.takeIf { access.canSee(it) }?.let { NativePage.openWeb(context, it.path) }
        }
    }
    val goTab: (BusinessTab) -> Unit = { target ->
        tab = target.name
        stack = emptyList()
    }

    val env = remember(access, vm) {
        BusinessEnv(api = api, access = access, vm = vm, open = openPage, goTab = goTab, signOut = onSignOut, selectOrganization = onSelectOrganization)
    }

    // Нажали на уведомление или ссылку — открываем тот раздел, о котором она. Если права нет — ничего не делаем.
    LaunchedEffect(env) {
        DeepLinks.pendingPage.collect { pageId ->
            if (pageId != null) {
                route(pageId, ownerLayout, hasPlatform, talk, workPages, access, env, setTab = { tab = it }, clearStack = { stack = emptyList() })
                DeepLinks.pendingPage.value = null
            }
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack = stack.dropLast(1) }

    // Вкладки
    val items = buildList {
        if (hasPlatform) add(TabBarItem(BusinessTab.Platform.name, "Платформа", Icons.Filled.AccountBalance))
        add(TabBarItem(BusinessTab.Home.name, "Главная", Icons.Filled.Home))
        if (ownerLayout) add(TabBarItem(BusinessTab.Analytics.name, "Аналитика", Icons.Filled.PieChart))
        workPages.forEach { id ->
            add(TabBarItem(workKey(id), Services.quickTitle(id, access.catalog.page(id)?.label).let { workTabTitle(id, it) }, pageIcon(id)))
        }
        add(TabBarItem(BusinessTab.Services.name, "Сервисы", Icons.Filled.GridView))
        if (talk != null) add(TabBarItem(BusinessTab.Talk.name, if (talk == "team-chat") "Чат" else "Задачи", if (talk == "team-chat") Icons.Filled.Forum else Icons.Filled.Checklist))
        add(TabBarItem(BusinessTab.Profile.name, "Профиль", Icons.Filled.AccountCircle))
    }
    val pending by vm.pending.collectAsState()
    val badges = buildMap {
        if (pending.isNotEmpty()) {
            put(BusinessTab.Services.name, pending.size)
            if (workKey("expenses-pending") in items.map { it.key }) put(workKey("expenses-pending"), pending.size)
        }
    }
    val selected = if (items.any { it.key == tab }) tab else BusinessTab.Home.name

    // Состояние каждой вкладки (прокрутка, поиск) живёт, пока человек переключается между ними.
    val saveable = rememberSaveableStateHolder()
    val outbox = remember { ActionOutbox(context.applicationContext, api) }
    val chatEnv = remember(resolver) {
        OperatorEnv(api = api, outbox = outbox, resolver = resolver, open = {}, signOut = onSignOut)
    }
    val content: @Composable () -> Unit = {
        CompositionLocalProvider(LocalBusiness provides env) {
          Column(Modifier.fillMaxSize()) {
            // Суперадмин смотрит организацию глазами владельца — об этом напоминает полоса сверху.
            if (hasPlatform) OrganizationContextBanner()
            Box(Modifier.weight(1f).fillMaxWidth()) {
            val top = stack.lastOrNull()
            if (top != null) {
                SubScreen(access.catalog.page(top)?.label ?: top, onBack = { stack = stack.dropLast(1) }) { NativePage.Screen(top) }
            } else {
                saveable.SaveableStateProvider(selected) {
                    when {
                        selected == BusinessTab.Platform.name -> PlatformRoot()
                        selected == BusinessTab.Home.name -> if (ownerLayout) OwnerHomeScreen(env) else StaffHomeScreen(env)
                        selected == BusinessTab.Analytics.name -> OwnerAnalyticsScreen(env)
                        selected == BusinessTab.Services.name -> OwnerServicesScreen(env)
                        selected == BusinessTab.Profile.name -> BusinessProfileScreen(env)
                        selected == BusinessTab.Talk.name && talk == "team-chat" ->
                            CompositionLocalProvider(LocalOperatorEnv provides chatEnv) { ChatTab() }
                        selected == BusinessTab.Talk.name && talk != null -> PageTab(env, talk)
                        selected == workKey("team-chat") ->
                            CompositionLocalProvider(LocalOperatorEnv provides chatEnv) { ChatTab() }
                        selected.startsWith("work:") -> PageTab(env, selected.removePrefix("work:"))
                    }
                }
            }
            }
          }
        }
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 600
    if (wide) {
        val itemColors = NavigationSuiteDefaults.itemColors(
            navigationBarItemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = Orda.colors.brand, selectedTextColor = Orda.colors.brand,
                unselectedIconColor = Orda.colors.textDim, unselectedTextColor = Orda.colors.textDim,
                indicatorColor = Orda.colors.brand.copy(alpha = 0.15f),
            ),
            navigationRailItemColors = NavigationRailItemDefaults.colors(
                selectedIconColor = Orda.colors.brand, selectedTextColor = Orda.colors.brand,
                unselectedIconColor = Orda.colors.textDim, unselectedTextColor = Orda.colors.textDim,
                indicatorColor = Orda.colors.brand.copy(alpha = 0.15f),
            ),
        )
        // Планшет и складной: панель сбоку.
        NavigationSuiteScaffold(
            layoutType = NavigationSuiteType.NavigationRail,
            containerColor = Orda.colors.background,
            navigationSuiteColors = NavigationSuiteDefaults.colors(navigationRailContainerColor = Orda.colors.elevated),
            navigationSuiteItems = {
                items.forEach { item ->
                    item(
                        selected = selected == item.key,
                        onClick = { goTabKey(item.key, { tab = it }, { stack = emptyList() }) },
                        icon = {
                            val count = badges[item.key] ?: 0
                            if (count > 0) {
                                BadgedBox(badge = { Badge { Text("$count") } }) { Icon(item.icon, contentDescription = null) }
                            } else {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(item.title, fontSize = 11.sp, maxLines = 1) },
                        colors = itemColors,
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Orda.colors.background)) {
            Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) { content() }
            FloatingTabBar(
                items = items,
                selectedKey = selected,
                badges = badges,
                onSelect = { key -> goTabKey(key, { tab = it }, { stack = emptyList() }) },
            )
        }
    }
}

private fun goTabKey(key: String, setTab: (String) -> Unit, clearStack: () -> Unit) {
    setTab(key)
    clearStack()
}

/** Подпись рабочей вкладки — одно слово: длинное панель обрежет многоточием. */
internal fun workTabTitle(pageId: String, short: String): String = when (pageId) {
    "team-chat" -> "Чат"
    "tasks" -> "Задачи"
    else -> short
}

/** Рабочие вкладки должности: первые две выданные из её набора, без дублей. */
internal fun playbookWorkPages(access: PageAccess): List<String> {
    val reachable = Services.groups(access).flatMap { it.items }.map { it.pageId }.toSet()
    return access.resolver.playbook.tabPageIds.filter { it in reachable }.distinct().take(2)
}

/**
 * Переход по идентификатору раздела (уведомление, ссылка). У главной, аналитики,
 * платформы и общения — своя вкладка, остальное — раздел: нативный экран либо сайт.
 */
private fun route(
    pageId: String,
    ownerLayout: Boolean,
    hasPlatform: Boolean,
    talk: String?,
    workPages: List<String>,
    access: PageAccess,
    env: BusinessEnv,
    setTab: (String) -> Unit,
    clearStack: () -> Unit,
) {
    when (val target = BusinessRoute.resolve(pageId, hasPlatform, talk)) {
        BusinessRoute.Home -> { setTab(BusinessTab.Home.name); clearStack() }
        BusinessRoute.Analytics -> if (ownerLayout) { setTab(BusinessTab.Analytics.name); clearStack() }
        BusinessRoute.Platform -> { setTab(BusinessTab.Platform.name); clearStack() }
        BusinessRoute.Talk -> { setTab(BusinessTab.Talk.name); clearStack() }
        is BusinessRoute.Page -> when {
            target.pageId in workPages -> { setTab(workKey(target.pageId)); clearStack() }
            // Не свой раздел — не открываем пустой экран, остаёмся где были.
            access.canSee(target.pageId) -> env.open(target.pageId)
        }
    }
}

/** Вкладка, у которой нет нативного экрана: нативный, если есть, иначе приглашение открыть сайт. */
@Composable
private fun PageTab(env: BusinessEnv, pageId: String) {
    if (NativePage.isNative(pageId)) {
        NativePage.Screen(pageId)
        return
    }
    val page = env.access.catalog.page(pageId)
    WebPlaceholder(page?.label ?: pageId, "Этот раздел пока открывается на сайте.", page?.path ?: "/")
}

@Composable
private fun WebPlaceholder(title: String, message: String, path: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        EmptyState(
            icon = Icons.Filled.GridView,
            title = title,
            message = message,
        )
        Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            PrimaryButton("Открыть на сайте") { NativePage.openWeb(context, path) }
        }
    }
}
