package kz.ordaops.android.feature.business

import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Зеркало `AnalyticsPeriodTests` из apple/OrdaKit/Tests/OwnerAnalyticsTests.swift. */
class AnalyticsPeriodTest {
    /** Вторник, 22 сентября 2026. */
    private val today = LocalDate.of(2026, 9, 22)

    @Test
    fun `неделя начинается с понедельника`() {
        assertEquals("2026-09-21" to "2026-09-27", AnalyticsPeriod.ThisWeek.bounds(today))
        assertEquals("2026-09-14" to "2026-09-20", AnalyticsPeriod.LastWeek.bounds(today))
    }

    @Test
    fun `идущий месяц кончается последним днём месяца, а не сегодня`() {
        assertEquals("2026-09-01" to "2026-09-30", AnalyticsPeriod.ThisMonth.bounds(today))
        assertEquals("2026-08-01" to "2026-08-31", AnalyticsPeriod.LastMonth.bounds(today))
    }

    @Test
    fun `квартал, год, скользящие периоды, сегодня и вчера`() {
        assertEquals("2026-07-01" to "2026-09-30", AnalyticsPeriod.ThisQuarter.bounds(today))
        assertEquals("2026-01-01" to "2026-12-31", AnalyticsPeriod.ThisYear.bounds(today))
        assertEquals("2026-08-24" to "2026-09-22", AnalyticsPeriod.Last30Days.bounds(today))
        assertEquals("2026-09-16" to "2026-09-22", AnalyticsPeriod.Last7Days.bounds(today))
        assertEquals("2025-10-01" to "2026-09-22", AnalyticsPeriod.Last12Months.bounds(today))
        assertEquals("2026-09-22" to "2026-09-22", AnalyticsPeriod.Today.bounds(today))
        assertEquals("2026-09-21" to "2026-09-21", AnalyticsPeriod.Yesterday.bounds(today))
    }

    @Test
    fun `свои даты в обратном порядке переставляются`() {
        assertEquals("2026-09-01" to "2026-09-10", AnalyticsPeriod.Custom("2026-09-10", "2026-09-01").bounds(today))
    }

    @Test
    fun `подпись периода короткая и без года для текущего`() {
        assertEquals("3–9 сен", AnalyticsPeriod.rangeLabel("2026-09-03", "2026-09-09", currentYear = 2026))
        assertEquals("28 авг – 3 сен", AnalyticsPeriod.rangeLabel("2026-08-28", "2026-09-03", currentYear = 2026))
        assertEquals("1 янв 2025 – 31 мар 2025", AnalyticsPeriod.rangeLabel("2025-01-01", "2025-03-31", currentYear = 2026))
        assertEquals("22 сен", AnalyticsPeriod.rangeLabel("2026-09-22", "2026-09-22", currentYear = 2026))
    }

    @Test
    fun `период переживает перезапуск через ключ`() {
        (AnalyticsPeriod.presets + AnalyticsPeriod.Custom("2026-01-01", "2026-02-01")).forEach {
            assertEquals(it, AnalyticsPeriod.fromKey(it.key))
        }
        assertNull(AnalyticsPeriod.fromKey("мусор"))
        assertNull(AnalyticsPeriod.fromKey(null))
    }

    @Test
    fun `свой период длиннее 400 дней укорачивается`() {
        val p = customPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 1)) as AnalyticsPeriod.Custom
        assertEquals("2025-01-01", p.from)
        assertEquals("2026-02-04", p.to) // 399 дней от начала
        val reversed = customPeriod(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 1)) as AnalyticsPeriod.Custom
        assertEquals("2026-03-01" to "2026-03-05", reversed.from to reversed.to)
    }

    @Test
    fun `запрос к серверу собирает все параметры`() {
        val q = OwnerAnalyticsQuery("2026-09-01", "2026-09-30", listOf("b", "a"), AnalyticsCompare.Year, includeExtra = true, lite = true)
        assertEquals(
            mapOf("from" to "2026-09-01", "to" to "2026-09-30", "compare" to "year", "include_extra" to "1", "lite" to "1", "company_ids" to "a,b"),
            q.queryParams,
        )
        assertEquals(setOf("from", "to", "compare"), OwnerAnalyticsQuery("a", "b").queryParams.keys)
    }

    @Test
    fun `проценты и изменение`() {
        assertEquals("+23 %", BizFormat.percent(23.0, signed = true))
        assertEquals("23,4 %", BizFormat.percent(23.4))
        assertEquals("−4 %", BizFormat.percent(-4.0))
        assertEquals("—", BizFormat.percent(null))
        assertNull(BizFormat.change(100.0, 0.0)) // рост «с нуля» — не процент
        assertEquals(50.0, BizFormat.change(150.0, 100.0))
        assertEquals("1,8 млн ₸", BizFormat.axisTick(1_800_000.0))
        assertEquals("120к ₸", BizFormat.axisTick(120_000.0))
        assertTrue(BizFormat.pointsWord(3) == "точки" && BizFormat.pointsWord(11) == "точек" && BizFormat.pointsWord(21) == "точка")
    }
}
