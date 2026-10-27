package kz.ordaops.android.feature.business.platform

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.ordaops.android.core.net.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private fun json(text: String): JsonObject = ApiJson.parseToJsonElement(text).jsonObject

/** Платформа суперадмина: разбор ответов веба, фильтры, тела записи, вердикт здоровья сервера. */
class PlatformModelsTest {
    private val today = LocalDate.of(2026, 10, 27)

    private val platform = PlatformData.parse(
        json(
            """{
          "overview":{"organizationCount":3,"activeOrganizationCount":2,"liveMrr":28000,"trialMrr":15000,"overdueInvoices":1,"overdueInvoicesSum":9000},
          "organizations":[
            {"id":"o1","name":"F16 Arena","slug":"f16","status":"active","companyLimit":3,"companyCount":2,"memberCount":9,"packageCode":"club",
             "addonCodes":["ai"],"companies":[{"id":"c1","name":"Арена"},{"id":"c2","name":"Рамен"}],
             "subscription":{"status":"active","endsAt":"2026-11-02T00:00:00Z","plan":{"name":"Про"}},
             "effectiveFeatures":[{"code":"income","sources":["plan","manual"]},{"code":"hr","sources":["plan"]}]},
            {"id":"o2","name":"Магазин","slug":"shop","status":"active","subscription":{"status":"trialing","endsAt":"2026-10-30"},
             "invoices":[{"id":"i1","amount":"9000","status":"issued","due_date":"2026-10-01"}]},
            {"id":"o3","name":"Старая","slug":"old","status":"archived"},
            {"id":"o4","name":"Заморожена","slug":"frz","status":"suspended"}
          ],
          "attention":[{"id":"o2","name":"Магазин","reasons":["триал истекает","просроченный счёт"]}],
          "packages":[{"code":"club","name":"Клуб","price_kzt":"28000","feature_codes":["a","b"]}],
          "addons":[{"code":"ai","name":"ИИ","price_kzt":5000}],
          "features":[{"code":"income","name":"Доходы","category":"Финансы"}]
        }""",
        ),
    )

    @Test
    fun parsesOverviewAndOrganizationsLeniently() {
        assertEquals(28000.0, platform.overview.liveMrr, 0.0)
        assertEquals(4, platform.organizations.size)
        val f16 = platform.org("o1")!!
        assertEquals(3, f16.companyLimit)
        assertEquals(listOf("Арена", "Рамен"), f16.companies.map { it.name })
        assertEquals("Про", f16.subscription?.planName)
        assertTrue(f16.effectiveFeatures.first { it.code == "income" }.isManual)
        assertFalse(f16.effectiveFeatures.first { it.code == "hr" }.isManual)
        // Сумма пришла строкой — не роняет разбор.
        assertEquals(9000.0, platform.org("o2")!!.invoices.single().amount, 0.0)
        // Лимит по умолчанию — одна точка, как на сервере.
        assertEquals(1, platform.org("o3")!!.companyLimit)
        assertEquals(28000.0, platform.packages.single().priceKzt, 0.0)
        assertEquals(2, platform.packages.single().featureCount)
        assertEquals("ИИ", platform.addons.single().name)
        assertEquals("o2", platform.attention.single().id)
    }

    @Test
    fun emptyOrBrokenAnswerDoesNotCrash() {
        val empty = PlatformData.parse(json("{}"))
        assertTrue(empty.organizations.isEmpty())
        assertEquals(0, empty.overview.organizations)
        val noId = PlatformData.parse(json("""{"organizations":[{"name":"без id"}]}"""))
        assertTrue(noId.organizations.isEmpty())
    }

    @Test
    fun filtersSplitArchiveAndStates() {
        val all = platform.organizations
        assertEquals(listOf("o1", "o2", "o4"), all.filter(OrgFilter.All::matches).map { it.id })
        assertEquals(listOf("o1"), all.filter(OrgFilter.Active::matches).map { it.id })
        assertEquals(listOf("o2"), all.filter(OrgFilter.Trial::matches).map { it.id })
        // Просрочен — по просроченному счёту, даже если подписка триальная.
        assertEquals(listOf("o2"), all.filter(OrgFilter.PastDue::matches).map { it.id })
        assertEquals(listOf("o4"), all.filter(OrgFilter.Suspended::matches).map { it.id })
        assertEquals(listOf("o3"), all.filter(OrgFilter.Archived::matches).map { it.id })
    }

    @Test
    fun searchByNameSlugAndCompany() {
        val all = platform.organizations
        assertEquals(listOf("o1"), filterOrganizations(all, "  ARENA ", OrgFilter.All).map { it.id })
        assertEquals(listOf("o2"), filterOrganizations(all, "shop", OrgFilter.All).map { it.id })
        assertEquals(listOf("o1"), filterOrganizations(all, "рамен", OrgFilter.All).map { it.id })
        assertTrue(filterOrganizations(all, "нет такого", OrgFilter.All).isEmpty())
        assertEquals(3, filterOrganizations(all, "", OrgFilter.All).size)
    }

    @Test
    fun subscriptionHintsCountDays() {
        fun hint(status: String, ends: String?) = subscriptionHint(OrgSubscription(status, "monthly", null, ends, null), today)
        assertEquals("триал: 3 дн.", hint("trialing", "2026-10-30"))
        assertEquals("триал закончился", hint("trialing", "2026-10-20"))
        assertEquals("платёж через 6 дн.", hint("active", "2026-11-02"))
        assertEquals("срок вышел 7 дн. назад", hint("active", "2026-10-20"))
        assertNull(hint("active", "2026-12-31"))
        assertEquals("просрочена", hint("past_due", null))
        assertNull(subscriptionHint(null, today))
    }

    @Test
    fun overdueInvoiceNeedsIssuedStatusAndPastDueDate() {
        assertTrue(OrgInvoice("1", 1.0, "issued", "2026-10-26", null).isOverdue(today))
        assertFalse(OrgInvoice("1", 1.0, "issued", "2026-10-27", null).isOverdue(today))
        assertFalse(OrgInvoice("1", 1.0, "paid", "2026-10-01", "2026-10-02").isOverdue(today))
        assertFalse(OrgInvoice("1", 1.0, "issued", null, null).isOverdue(today))
    }

    @Test
    fun limitReachedAtEqualCount() {
        assertTrue(platform.org("o1")!!.copy(companyCount = 3).limitReached)
        assertFalse(platform.org("o1")!!.limitReached)
    }

    @Test
    fun replacingSwapsOnlyThatOrganization() {
        val renamed = platform.org("o1")!!.copy(name = "Новая")
        val next = platform.replacing(renamed)
        assertEquals("Новая", next.org("o1")!!.name)
        assertEquals("Магазин", next.org("o2")!!.name)
        assertEquals(platform.organizations.size, next.organizations.size)
    }

    // ── тела записи: ровно те поля, что шлёт сайт ──

    @Test
    fun suspendAndResumeMatchWebButtons() {
        val s = OrgPatch.suspend("o1")
        assertEquals("o1", s["organizationId"]!!.jsonPrimitive.content)
        assertEquals("suspended", s["organizationStatus"]!!.jsonPrimitive.content)
        assertEquals("markPastDue", s["subscriptionAction"]!!.jsonPrimitive.content)
        val r = OrgPatch.resume("o1")
        assertEquals("active", r["organizationStatus"]!!.jsonPrimitive.content)
        assertEquals("resume", r["subscriptionAction"]!!.jsonPrimitive.content)
    }

    @Test
    fun trialDaysClampedToServerRange() {
        assertEquals("90", OrgPatch.startTrial("o", 500)["trialDays"]!!.jsonPrimitive.content)
        assertEquals("1", OrgPatch.startTrial("o", 0)["trialDays"]!!.jsonPrimitive.content)
        assertEquals("startTrial", OrgPatch.startTrial("o", 14)["subscriptionAction"]!!.jsonPrimitive.content)
        assertTrue(TRIAL_DAY_OPTIONS.all { it in 1..90 })
    }

    @Test
    fun limitNeverBelowOne() {
        assertEquals("1", OrgPatch.companyLimit("o", -4)["companyLimit"]!!.jsonPrimitive.content)
        assertEquals("5", OrgPatch.companyLimit("o", 5)["companyLimit"]!!.jsonPrimitive.content)
    }

    @Test
    fun nestedBodiesForAddonAndFeatureGrant() {
        val addon = OrgPatch.setAddon("o", "ai", true)["setAddon"]!!.jsonObject
        assertEquals("ai", addon["addon"]!!.jsonPrimitive.content)
        assertEquals("true", addon["enabled"]!!.jsonPrimitive.content)
        val grant = OrgPatch.setFeatureGrant("o", "income", false)["setFeatureGrant"]!!.jsonObject
        assertEquals("income", grant["code"]!!.jsonPrimitive.content)
        assertEquals("false", grant["enabled"]!!.jsonPrimitive.content)
        assertEquals("", OrgPatch.assignPackage("o", "")["assignPackage"]!!.jsonPrimitive.content)
    }

    // ── сервер ──

    private fun status(extra: String = "", deploys: String = """[{"start":1000,"status":"ok","sha":"abc1234","subject":"fix"}]""") = ServerStatus.parse(
        json(
            """{"now":2000,"panel_error":null,
          "server":{"host":"cloud-001","ncpu":4,"uptime":90000,"load":[0.5,0.4,0.3],"mem_total":1000,"mem_used":500,"swap_total":0,"swap_used":0,
                    "disk_total":1000,"disk_used":500,"reboot_required":false,"history":[{"cpu":12.5}]},
          "project":{"deploys":$deploys,"checks":[{"url":"https://www.ordaops.kz/","code":200,"ms":120}],
                     "containers":[{"name":"web","state":"running","health":"healthy"}],
                     "backup":{"name":"b","time":1900,"size":1048576,"count":7}},
          "crons":[{"name":"a","last_code":200,"runs_24h":3,"failed_24h":0}],
          "errors":{"total":2,"hourly":[0,1,1]}$extra}""",
        ),
    )

    @Test
    fun healthyServerIsOk() {
        val s = status()
        assertEquals(HealthLevel.Ok, healthVerdict(s).level)
        assertEquals(12.5, s.server!!.cpuNow!!, 0.0)
        assertEquals(50.0, s.server!!.memPercent, 0.0)
        assertEquals("cloud-001", s.server!!.host)
        assertEquals(7, s.backup!!.count)
    }

    @Test
    fun verdictRaisesToWorstIssue() {
        val failedDeploy = status(deploys = """[{"start":1000,"status":"failed","sha":"x"}]""")
        assertEquals(HealthLevel.Warn, healthVerdict(failedDeploy).level)
        val running = status(deploys = """[{"start":1000,"status":"running"}]""")
        assertEquals(HealthLevel.Running, healthVerdict(running).level)
        val down = status().copy(checks = listOf(SiteCheck("https://www.ordaops.kz/", 0, null)))
        val v = healthVerdict(down)
        assertEquals(HealthLevel.Bad, v.level)
        assertTrue(v.issues.any { it.second.contains("www.ordaops.kz") })
        val sick = status().copy(containers = listOf(ContainerItem("db", "running", "unhealthy", 0)))
        assertEquals(HealthLevel.Bad, healthVerdict(sick).level)
    }

    @Test
    fun staleBackupAndDiskWarn() {
        val stale = status().copy(now = 1900 + 27 * 3600L)
        assertTrue(healthVerdict(stale).issues.any { it.second.contains("бэкап") })
        val full = status().let { it.copy(server = it.server!!.copy(diskUsed = 920.0)) }
        assertEquals(HealthLevel.Bad, healthVerdict(full).level)
        val eighty = status().let { it.copy(server = it.server!!.copy(diskUsed = 850.0)) }
        assertEquals(HealthLevel.Warn, healthVerdict(eighty).level)
    }

    @Test
    fun errorBurstInLastHourWarns() {
        val burst = status().copy(errors = ErrorSummary(30, listOf(0, 12)))
        assertTrue(healthVerdict(burst).issues.any { it.second.contains("12 ошибок") })
    }

    @Test
    fun failingCronCounted() {
        val bad = status().copy(crons = listOf(CronItem("a", null, 1, 500, 2, 2), CronItem("b", null, 1, 200, 2, 0)))
        assertTrue(healthVerdict(bad).issues.any { it.second.contains("кронов падает: 1") })
    }

    @Test
    fun missingSectionsDoNotCrash() {
        val s = ServerStatus.parse(json("{}"))
        assertNull(s.server)
        assertEquals(HealthLevel.Ok, healthVerdict(s).level)
    }

    // ── форматы ──

    @Test
    fun formatsBytesUptimeAgoAndDuration() {
        assertEquals("540 МБ", bytesText(540.0 * 1024 * 1024))
        assertEquals("3,2 ГБ", bytesText(3.2 * 1024 * 1024 * 1024))
        assertEquals("12 ГБ", bytesText(12.0 * 1024 * 1024 * 1024))
        assertEquals("12 дн 3 ч", uptimeText(12 * 86400L + 3 * 3600))
        assertEquals("5 ч 20 мин", uptimeText(5 * 3600L + 20 * 60))
        assertEquals("только что", agoText(100, 120))
        assertEquals("5 мин назад", agoText(0, 300))
        assertEquals("3 ч назад", agoText(0, 3 * 3600))
        assertEquals("2 дн назад", agoText(0, 2 * 86400))
        assertEquals("45 с", durationText(45))
        assertEquals("3 мин 05 с", durationText(185))
        assertEquals("", durationText(null))
    }

    @Test
    fun errorsReportGroupsAndSamples() {
        val r = ErrorsReport.parse(
            json(
                """{"now":5000,"period":"24h","total":4,"truncated":false,"groups":[
              {"area":"api/x","message":"boom","scope":"server","count":3,"first_at":10,"last_at":20,"users":2,
               "samples":[{"at":20,"actor":"a@b.c","pathname":"/income","full_message":"boom full","stack":"at x"}]},
              {"area":"client","message":"oops","scope":"client","count":1,"first_at":30,"last_at":30,"users":0,"samples":[]}]}""",
            ),
        )
        assertEquals(2, r.groups.size)
        assertEquals("/income", r.groups[0].samples.single().pathname)
        assertEquals(3, r.groups[0].count)
        assertTrue(r.groups[1].isClient)
        assertFalse(r.groups[0].isClient)
        assertTrue(ERROR_PERIODS.any { it.first == "24h" })
    }

    @Test
    fun tailKeepsLastLines() {
        assertEquals("c\nd", tailLines("a\nb\nc\nd\n\n", 2))
        assertEquals("a", tailLines("a", 5))
    }
}
