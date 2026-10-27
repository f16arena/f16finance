package kz.ordaops.android.feature.business

import kz.ordaops.android.core.access.AccessResolver
import kz.ordaops.android.core.access.CapabilityCatalog
import kz.ordaops.android.core.access.PageAccess
import kz.ordaops.android.core.access.RolePermissionOverride
import kz.ordaops.android.core.access.SessionRole
import kz.ordaops.android.core.access.StaffRolePlaybook
import kz.ordaops.android.core.access.Workspace
import kz.ordaops.android.platform.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Фильтрация сервисов по правам и модулям, маршрутизация разделов и набор
 * должности. Каталог — настоящий `apple/Contracts/capabilities.json`.
 * Зеркало NativeSectionTests / StaffRolePlaybookTests из OrdaKit.
 */
class ServicesAccessTest {
    private val catalog: CapabilityCatalog by lazy {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream(CapabilityCatalog.ASSET)) { "нет capabilities.json в ресурсах теста" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        CapabilityCatalog.parse(text)
    }

    private fun access(
        capabilities: Set<String>,
        orgFeatures: List<String> = emptyList(),
        featuresAllAccess: Boolean = true,
        superAdmin: Boolean = false,
        staffRole: String? = "owner",
        overrides: List<RolePermissionOverride> = emptyList(),
    ) = PageAccess(
        AccessResolver(
            SessionRole(
                isSuperAdmin = superAdmin,
                isStaff = !superAdmin,
                capabilities = if (superAdmin) listOf("*") else capabilities.toList(),
                orgFeatures = orgFeatures,
                featuresAllAccess = featuresAllAccess,
                staffRole = staffRole,
                rolePermissionOverrides = overrides,
            ),
        ),
        catalog,
    )

    private fun ids(access: PageAccess) = Services.groups(access).flatMap { it.items }.map { it.pageId }

    @Test
    fun `каталог читается целиком`() {
        assertTrue(catalog.groups.size >= 9)
        assertEquals("/income", catalog.page("income")?.path)
        assertEquals("shop.catalog", catalog.addonCode("/store/warehouse"))
        assertNull(catalog.addonCode("/нет-такого"))
    }

    @Test
    fun `без прав сервисов нет, а не показывается недоступное`() {
        assertTrue(Services.groups(access(emptySet())).isEmpty())
    }

    @Test
    fun `видны только выданные страницы и пустые группы пропадают`() {
        val groups = Services.groups(access(setOf("salary.view", "income.view")))
        assertEquals(setOf("income", "salary"), groups.flatMap { it.items }.map { it.pageId }.toSet())
        assertTrue(groups.all { it.items.isNotEmpty() })
    }

    @Test
    fun `неоплаченный модуль прячет раздел`() {
        val denied = access(setOf("store-warehouse.view"), orgFeatures = listOf("addon.arena"), featuresAllAccess = false)
        assertFalse("store-warehouse" in ids(denied))
        val allowed = access(setOf("store-warehouse.view"), orgFeatures = listOf("shop.catalog"), featuresAllAccess = false)
        assertTrue("store-warehouse" in ids(allowed))
    }

    @Test
    fun `организация без пакета не ограничивается модулями`() {
        val a = access(setOf("store-warehouse.view"), orgFeatures = emptyList(), featuresAllAccess = false)
        assertTrue("store-warehouse" in ids(a))
    }

    @Test
    fun `ручное выключение маршрута прячет раздел`() {
        val a = access(setOf("income.view"), overrides = listOf(RolePermissionOverride("/income", false)))
        assertFalse("income" in ids(a))
    }

    @Test
    fun `журнал и доступы скрыты у владельца и видны суперадмину`() {
        val owner = access(setOf("logs.view", "access.view", "income.view"))
        assertFalse("logs" in ids(owner))
        assertFalse("access" in ids(owner))
        assertTrue("income" in ids(owner))
        val root = access(emptySet(), superAdmin = true)
        assertTrue("logs" in ids(root))
        assertTrue("access" in ids(root))
    }

    @Test
    fun `служебные страницы не попадают в плитки`() {
        val root = access(emptySet(), superAdmin = true)
        val all = ids(root)
        assertFalse("dashboard" in all)
        assertFalse("welcome" in all)
        assertFalse("workspace" in all)
        assertTrue("income" in all)
    }

    @Test
    fun `кабинет владельца — тем, у кого есть отчёты`() {
        assertTrue(access(setOf("reports.view")).canSeeOwnerAnalytics)
        assertFalse(access(setOf("income.view")).canSeeOwnerAnalytics)
        assertTrue(access(emptySet(), superAdmin = true).canSeeOwnerAnalytics)
    }

    @Test
    fun `любимые сервисы — в порядке владельца, добранные остальными`() {
        val a = access(setOf("salary.view", "income.view", "tasks.view", "pos-receipts.view"))
        val favorites = Services.favorites(a, count = 8).map { it.pageId }
        // Порядок владельца: доходы раньше зарплаты, зарплата раньше задач; невыданное не попадает.
        assertEquals(listOf("income", "salary", "tasks", "pos-receipts"), favorites)
    }

    @Test
    fun `у сотрудника любимые сервисы — по набору его должности`() {
        val a = access(setOf("salary.view", "income.view", "tax.view"), staffRole = "accountant")
        // Бухгалтер: income, ..., salary, tax — порядок набора, а не владельца (где salary раньше tax не гарантирован).
        val favorites = Services.favorites(a).map { it.pageId }
        assertEquals(listOf("income", "salary", "tax"), favorites)
    }

    @Test
    fun `короткие названия плиток`() {
        assertEquals("ОПиУ", Services.shortTitle("Рентабельность (ОПиУ)"))
        assertEquals("Смены", Services.shortTitle("Смены (расписание)"))
        assertEquals("Доходы", Services.shortTitle("Доходы"))
        assertEquals("Решения", Services.quickTitle("expenses-pending", "Ожидающие расходы"))
        assertEquals("Склад", Services.quickTitle("store-warehouse", "Склад (остатки)"))
    }

    @Test
    fun `поиск по плиткам оставляет совпавшие группы`() {
        val groups = Services.groups(access(setOf("income.view", "expenses.view", "salary.view")))
        val found = Services.filter(groups, "  ДОХОД ")
        assertEquals(listOf("income"), found.flatMap { it.items }.map { it.pageId })
        assertTrue(Services.filter(groups, "нет такого").isEmpty())
        assertEquals(groups, Services.filter(groups, ""))
    }

    @Test
    fun `раздел без нативного экрана идёт на сайт по пути из каталога`() {
        val a = access(setOf("income.view"))
        val item = Services.groups(a).flatMap { it.items }.single()
        // «Доходы» стали нативными (раздел «Финансы»): признак веба следует за реестром.
        assertEquals(!NativePage.isNative("income"), item.isWeb)
        assertEquals("/income", item.path)
        assertEquals("https://www.ordaops.kz/income", NativePage.webUrl(item.path, "https://www.ordaops.kz"))
        assertEquals("https://www.ordaops.kz/income", NativePage.webUrl("income", "https://www.ordaops.kz/"))
    }

    // ── Маршрутизация ────────────────────────────────────────────────────────

    @Test
    fun `идентификатор раздела выбирает вкладку`() {
        assertEquals(BusinessRoute.Home, BusinessRoute.resolve("home.dashboard", false, null))
        assertEquals(BusinessRoute.Home, BusinessRoute.resolve("business.dashboard", false, null))
        assertEquals(BusinessRoute.Analytics, BusinessRoute.resolve("owner.analytics", false, null))
        assertEquals(BusinessRoute.Platform, BusinessRoute.resolve("platform.overview", true, null))
        // Платформа без суперадмина — обычная страница, не вкладка.
        assertEquals(BusinessRoute.Page("platform.overview"), BusinessRoute.resolve("platform.overview", false, null))
        assertEquals(BusinessRoute.Talk, BusinessRoute.resolve("team-chat", false, "team-chat"))
        // Чат не выдан, вкладка общения — задачи: чат остаётся обычной страницей.
        assertEquals(BusinessRoute.Page("team-chat"), BusinessRoute.resolve("team-chat", false, "tasks"))
        assertEquals(BusinessRoute.Page("income"), BusinessRoute.resolve("income", false, "tasks"))
    }

    @Test
    fun `ссылка orda page разбирается`() {
        assertEquals("income", DeepLinks.pageFromUri("orda", "page", "/income"))
        assertEquals("finance.income", DeepLinks.pageFromUri("orda", "page", "finance.income/"))
        assertNull(DeepLinks.pageFromUri("https", "page", "/income"))
        assertNull(DeepLinks.pageFromUri("orda", "other", "/income"))
        assertNull(DeepLinks.pageFromUri("orda", "page", "/"))
        assertNull(DeepLinks.pageFromUri(null, null, null))
    }

    // ── Должности ────────────────────────────────────────────────────────────

    @Test
    fun `каждый раздел в наборах есть в каталоге — опечатка молча спрятала бы кнопку`() {
        val sets = listOf(
            StaffRolePlaybook.Owner, StaffRolePlaybook.Manager, StaffRolePlaybook.Accountant, StaffRolePlaybook.Marketer,
            StaffRolePlaybook.SeniorOperator, StaffRolePlaybook.SeniorCashier, StaffRolePlaybook.employee("Сотрудник"),
        )
        for (set in sets) {
            val pages = set.tabPageIds + set.favoritePageIds + set.actions.filterIsInstance<StaffRolePlaybook.Action.Page>().map { it.id }
            for (id in pages) assertNotNull("${set.title}: $id", catalog.page(id))
        }
    }

    @Test
    fun `должность выбирает свой набор, незнакомая — общий с её названием`() {
        assertEquals(StaffRolePlaybook.Accountant, StaffRolePlaybook.forRole("accountant"))
        assertEquals(StaffRolePlaybook.Marketer, StaffRolePlaybook.forRole("marketer"))
        assertEquals("Сотрудник", StaffRolePlaybook.forRole(null).title)
        assertEquals("Бариста", StaffRolePlaybook.forRole("Бариста").title)
    }

    @Test
    fun `рабочие вкладки должности — только выданные, не больше двух`() {
        val a = access(setOf("expenses-pending.view", "team-chat.view", "tasks.view"), staffRole = "accountant")
        assertEquals(listOf("expenses-pending", "team-chat"), playbookWorkPages(a))
        val none = access(setOf("income.view"), staffRole = "accountant")
        assertTrue(playbookWorkPages(none).isEmpty())
    }

    @Test
    fun `рабочее пространство по роли`() {
        assertEquals(Workspace.Owner, a(isStaff = true, staffRole = "owner").workspace)
        assertEquals(Workspace.Staff, a(isStaff = true, staffRole = "manager").workspace)
        assertEquals(Workspace.Platform, a(isSuperAdmin = true).workspace)
    }

    private fun a(isStaff: Boolean = false, isSuperAdmin: Boolean = false, staffRole: String? = null) =
        AccessResolver(SessionRole(isSuperAdmin = isSuperAdmin, isStaff = isStaff, staffRole = staffRole))

    // ── Смена пароля ─────────────────────────────────────────────────────────

    @Test
    fun `правила смены пароля`() {
        assertEquals("Введите текущий пароль", passwordProblem("", "12345678", "12345678"))
        assertEquals("Новый пароль — не короче 8 символов", passwordProblem("old", "short", "short"))
        assertEquals("Новый пароль должен отличаться от текущего", passwordProblem("12345678", "12345678", "12345678"))
        assertEquals("Пароли не совпадают", passwordProblem("old", "12345678", "1234567"))
        assertNull(passwordProblem("old", "12345678", "12345678"))
    }
}
