package kz.ordaops.android.feature.business.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор остальных страниц магазина по текущему вебу. */
class ExtraModelsTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // ── Смены: расхождение кассы ─────────────────────────────────────────────

    @Test
    fun shiftTakesCashReportDiffFirst() {
        val s = ShiftRow.parse(obj("""{"id":"1","status":"closed","cash_report_diff":-1500,"totals_json":{"sales_total":50000,"cash_difference":999}}"""))
        assertEquals(-1500.0, s.cashDiff!!, 0.0)
        assertEquals(50000.0, s.sales!!, 0.0)
        assertTrue(s.hasDiff)
    }

    @Test
    fun shiftFallsBackToTotalsJsonDiff() {
        val s = ShiftRow.parse(obj("""{"id":"1","status":"closed","totals_json":{"total_sales":"1000","cash_difference":"300"}}"""))
        assertEquals(300.0, s.cashDiff!!, 0.0)
        assertEquals(1000.0, s.sales!!, 0.0)
    }

    @Test
    fun noDiffMeansNoNumber() {
        val s = ShiftRow.parse(obj("""{"id":"1","status":"closed"}"""))
        assertNull(s.cashDiff)
        assertFalse(s.hasDiff)
        // Меньше тенге — не расхождение.
        assertFalse(ShiftRow.parse(obj("""{"id":"1","status":"closed","cash_report_diff":0.4}""")).hasDiff)
    }

    @Test
    fun openShiftUsesLiveTotals() {
        val s = ShiftRow.parse(obj("""{"id":"1","status":"open","live_totals":{"sales":7000,"cash":3000,"kaspi":4000,"count":5},"operator":{"full_name":"Аян"}}"""))
        assertTrue(s.isOpen)
        assertEquals(7000.0, s.sales!!, 0.0)
        assertEquals(3000.0, s.liveCash, 0.0)
        assertEquals("Аян", s.operatorName)
    }

    @Test
    fun summarySeparatesShortageFromSurplus() {
        val shifts = listOf(
            ShiftRow.parse(obj("""{"id":"1","status":"closed","cash_report_diff":-2000,"totals_json":{"sales_total":1000}}""")),
            ShiftRow.parse(obj("""{"id":"2","status":"closed","cash_report_diff":-500,"totals_json":{"sales_total":3000}}""")),
            ShiftRow.parse(obj("""{"id":"3","status":"closed","cash_report_diff":700,"totals_json":{"sales_total":2000}}""")),
            ShiftRow.parse(obj("""{"id":"4","status":"open","live_totals":{"sales":0}}""")),
        )
        val sum = ShiftSummary.of(shifts)
        assertEquals(2, sum.shortCount)
        assertEquals(-2500.0, sum.shortSum, 0.0)
        assertEquals(1, sum.overCount)
        assertEquals(700.0, sum.overSum, 0.0)
        assertEquals(1, sum.opened)
        assertEquals(6000.0, sum.revenue, 0.0)
        assertEquals(1500.0, sum.average, 0.0)
    }

    @Test
    fun shiftDetailReadsSalesNames() {
        val d = ShiftDetail.parse(
            obj(
                """{"shift":{"id":"1","status":"closed"},"sales":[{"id":"s","sold_at":"2026-10-27T10:00:00+05:00","payment_method":"kaspi","total_amount":"900",
                    "items":[{"item":{"name":"Вода"}},{"universal_name":"Свободная цена"}]}],"returns":[{"id":"r","total_amount":100,"payment_method":"cash"}]}""",
            ),
        )
        assertEquals(listOf("Вода", "Свободная цена"), d.sales[0].names)
        assertEquals("Безнал", paymentLabel(d.sales[0].payment))
        assertEquals(100.0, d.returns[0].total, 0.0)
    }

    // ── Настройки ────────────────────────────────────────────────────────────

    @Test
    fun configHidesArchivedPointsFromShops() {
        val c = StoreConfig.parse(
            obj(
                """{"store_company_id":"a","can_manage":true,"companies":[
                    {"id":"a","name":"Магазин","store_enabled":true},
                    {"id":"b","name":"Закрытая","store_enabled":true,"archived_at":"2026-10-01"},
                    {"id":"c","name":"Клуб","store_enabled":false}]}""",
            ),
        )
        assertEquals(listOf("a"), c.shops.map { it.id })
        assertTrue(c.canManage)
        assertEquals("a", c.defaultCompanyId)
    }

    @Test
    fun receiptSettingsBodyWrapsFormInSettings() {
        val body = ReceiptSettingsForm(taxPayerName = "ИП", isVatPayer = true, vatRate = 12.0, receiptLanguage = "both").body("co")
        assertEquals("co", body["company_id"]!!.jsonPrimitive.content)
        val settings = body["settings"]!!.jsonObject
        assertEquals("ИП", settings["tax_payer_name"]!!.jsonPrimitive.content)
        assertTrue(settings["is_vat_payer"]!!.jsonPrimitive.boolean)
        assertEquals("both", settings["receipt_language"]!!.jsonPrimitive.content)
        assertEquals("ИП", ReceiptSettingsForm.parse(settings).taxPayerName)
    }

    // ── Закупки ──────────────────────────────────────────────────────────────

    @Test
    fun debtsReadServerTotalsAndOverdueFlag() {
        val d = DebtsData.parse(
            obj(
                """{"debts":[{"id":"1","status":"open","total_amount":"500","due_date":"2026-10-01","is_overdue":true,"supplier":{"name":"ООО"},"receipt":{"invoice_number":"7","invoice_file_url":"https://x"}}],
                    "totals":{"open":500,"open_count":1,"overdue":500,"overdue_count":1}}""",
            ),
        )
        assertEquals(500.0, d.overdue, 0.0)
        assertEquals("Просрочен", d.debts[0].statusLabel)
        assertEquals("7", d.debts[0].invoiceNumber)
        assertEquals("К оплате", SupplierDebtRow.parse(obj("""{"id":"2","status":"open"}""")).statusLabel)
        assertEquals("Оплачен", SupplierDebtRow.parse(obj("""{"id":"2","status":"paid"}""")).statusLabel)
    }

    @Test
    fun purchaseOrderDetail() {
        val o = PurchaseOrderRow.parse(
            obj(
                """{"id":"1","status":"sent","supplier":{"name":"ООО","sales_rep_name":"Иван","sales_rep_phone":"+77"},
                    "items":[{"suggested_qty":"12","current_qty":3,"threshold":5,"item":{"name":"Вода","unit":"шт"}}]}""",
            ),
        )
        assertEquals("Отправлена", o.statusLabel)
        assertEquals(1, o.itemCount)
        assertEquals(12.0, o.lines[0].suggested, 0.0)
        assertEquals("Иван", o.salesRep)
    }

    @Test
    fun purchasePlanGroupsBySupplier() {
        val p = PurchasePlan.parse(
            obj(
                """{"weekStart":"2026-11-02","total":15000,"revenue4wPerWeek":90000,
                    "bySupplier":[{"supplier":"ООО","total":15000,"items":[{"name":"Вода","order":24,"packs":2,"packSize":12,"amount":15000,"wasOutOfStock":true}]}],
                    "doNotBuy":[{"name":"Залежалый","stock":50,"weeklyDemand":1,"coverageWeeks":50}]}""",
            ),
        )
        assertEquals(1, p.groups.size)
        assertTrue(p.groups[0].items[0].wasOutOfStock)
        assertEquals(2.0, p.groups[0].items[0].packs, 0.0)
        assertEquals("Залежалый", p.doNotBuy[0].name)
    }

    // ── Расходники и техкарты ────────────────────────────────────────────────

    @Test
    fun consumableStockDaysAgainstNorm() {
        val c = Consumables.parse(
            obj(
                """{"items":[{"id":"i","name":"Перчатки","unit":"пар"}],
                    "norms":[{"id":"n","item_id":"i","location_id":"l","monthly_qty":300,"alert_days":14}],
                    "balances":[{"item_id":"i","location_id":"l","quantity":100,"item":{"name":"Перчатки"},"location":{"name":"Склад"}},
                                {"item_id":"i","location_id":"other","quantity":5}],
                    "locations":[{"id":"p","name":"Точка","location_type":"point_display"},{"id":"w","name":"Склад","location_type":"warehouse"}],
                    "issues":[{"id":"x","status":"received","comment":"[consumable-issue] 2026-10-20 · коробка","items":[{"approved_qty":10,"item":{"name":"Перчатки"}}]}]}""",
            ),
        )
        // 100 / (300/30) = 10 дней при тревоге за 14 — красная зона.
        assertEquals(10, c.stockDays(c.balances[0]))
        assertNull(c.stockDays(c.balances[1]))
        assertEquals(listOf("p"), c.points.map { it.locationId })
        assertEquals("2026-10-20 · коробка", c.issues[0].comment)
    }

    @Test
    fun recipeFoodCost() {
        val r = Recipe.parse(obj("""{"id":"1","name":"Бургер","output_qty":"1","portion_cost":300,"recipe_cost":300,"sale_item_id":"s","components":[{"name":"Булка","qty":1,"unit":"шт","waste_pct":5}]}"""))
        assertEquals(30.0, r.foodCostPct(1000.0)!!, 0.0001)
        assertNull(r.foodCostPct(null))
        assertNull(r.foodCostPct(0.0))
        assertEquals(5.0, r.components[0].wastePct, 0.0)
    }

    @Test
    fun productionDataReadsTopLevelKeys() {
        val d = ProductionData.parse(
            obj("""{"ok":true,"recipes":[{"id":"1","name":"Бургер","sale_item_id":"s"}],"ingredients":[{"id":"i","name":"Мука","unit":"кг","purchase_price":"400","stock_qty":"-2"}],"saleItems":[{"id":"s","sale_price":1200}]}"""),
        )
        assertEquals(1200.0, d.salePrices["s"]!!, 0.0)
        assertEquals(-2.0, d.ingredients[0].stock, 0.0)
        val a = ProductionAnalysis.parse(obj("""{"rows":[{"name":"Бургер","sold_qty":10,"food_cost":3000,"revenue":12000,"food_cost_pct":25}],"totals":{"sold":10,"food_cost":3000,"revenue":12000,"margin":9000,"food_cost_pct":25}}"""))
        assertEquals(9000.0, a.margin, 0.0)
        assertEquals(25.0, a.rows[0].foodCostPct, 0.0)
    }
}
