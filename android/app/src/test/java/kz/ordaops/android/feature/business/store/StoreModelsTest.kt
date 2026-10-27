package kz.ordaops.android.feature.business.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Терпимый разбор ответов магазина: числа строками, связи массивом, лишние и
 * недостающие ключи. Зеркало StoreModelsTests / DocumentModelsTests из OrdaKit.
 */
class StoreModelsTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun balanceReadsNestedItemAndNumbersAsStrings() {
        val b = StockBalance.parse(obj("""{"item_id":42,"location_id":"loc","quantity":"12.5","item":{"name":"Вода","unit":"шт","barcode":"46","low_stock_threshold":"20"},"location":{"name":"Склад"}}"""))
        assertEquals("42", b.itemId)
        assertEquals(12.5, b.quantity, 0.0)
        assertEquals("Вода", b.name)
        assertTrue(b.isLow)
        assertEquals("Склад", b.locationName)
    }

    @Test
    fun noThresholdMeansNeverLow() {
        val b = StockBalance.parse(obj("""{"item_id":"1","location_id":"l","quantity":0,"item":{"name":"A"}}"""))
        assertFalse(b.isLow)
    }

    @Test
    fun joinedObjectMayBeArrayOfOne() {
        val m = StockMovement.parse(obj("""{"id":"m","movement_type":"sale","quantity":-2,"total_amount":"300","item":[{"name":"Чипсы"}],"from_location":{"name":"Витрина"}}"""))
        assertEquals("Чипсы", m.itemName)
        assertEquals("Продажа", m.kindLabel)
        assertEquals(-1, m.direction)
        assertEquals("Витрина", m.fromName)
        assertNull(m.toName)
    }

    @Test
    fun unknownMovementKindIsNeutral() {
        assertEquals("Движение", movementLabel("something_new"))
        assertEquals("Приёмка", movementLabel("purchase"))
    }

    @Test
    fun overviewWithoutNewMetricsHasNoAttentionBlock() {
        val ov = StoreOverview.parse(obj("""{"locations":[],"balances":[],"movements":[],"requests":[]}"""))
        assertNull(ov.metrics)
    }

    @Test
    fun overviewReadsNewAttentionFields() {
        val ov = StoreOverview.parse(
            obj(
                """{"locations":[{"id":"l","name":"Склад","location_type":"warehouse","company_id":"c","company":{"name":"Магазин"}}],
                    "balances":[],"movements":[],"requests":[{"id":"r","status":"new","items":[{"id":"1","requested_qty":"3","item":{"name":"Вода"}}]}],
                    "pendingRequests":1,"disputedRequests":2,"outOfStock":5,"outOfStockInBackroom":3,"receiptDrafts":1,"unresolvedWriteoffs":0,
                    "stockValue":{"warehouse":100000,"showcase":50000,"positions":12},
                    "unpaidSupplierDebts":{"count":2,"sum":90000,"overdueCount":1,"overdueSum":40000}}""",
            ),
        )
        val m = ov.metrics!!
        assertTrue(m.needsAttention)
        assertEquals(3, m.outOfStockInBackroom)
        assertEquals(150000.0, m.stockTotal, 0.0)
        assertEquals(40000.0, m.overdueSum, 0.0)
        assertEquals("c", ov.locations[0].companyId)
        assertEquals("Магазин", ov.locations[0].companyName)
        assertEquals(1, ov.pendingRequests.size)
        assertEquals(3.0, ov.requests[0].lines[0].requested, 0.0)
    }

    @Test
    fun zeroCountersDoNotNeedAttention() {
        val m = StoreMetrics.parse(obj("""{"pendingRequests":0,"lowStock":2}"""))
        assertFalse(m.needsAttention)
        // Заявка, ждущая решения, — уже повод заглянуть.
        assertTrue(StoreMetrics.parse(obj("""{"pendingRequests":4}""")).needsAttention)
    }

    @Test
    fun totalsFoldByItemAcrossLocations() {
        val a = StockBalance("i", "l1", "Вода", "шт", null, 5.0, 10.0, null)
        val b = StockBalance("i", "l2", "Вода", "шт", null, 3.0, 10.0, null)
        val c = StockBalance("j", "l1", "Арбуз", "кг", null, 1.0, null, null)
        val totals = totalsOf(listOf(a, b, c))
        assertEquals(listOf("Арбуз", "Вода"), totals.map { it.name })
        val water = totals.first { it.id == "i" }
        assertEquals(8.0, water.quantity, 0.0)
        assertEquals(2, water.locationCount)
        assertTrue(water.isLow)
    }

    @Test
    fun receiptLineTotalFallsBackToQuantityTimesCost() {
        val line = DocumentLine.parse(obj("""{"id":"1","quantity":"4","unit_cost":"25","total_cost":0,"item":{"name":"Сок"}}"""))
        assertEquals(100.0, line.totalCost, 0.0)
        val stored = DocumentLine.parse(obj("""{"quantity":4,"unit_cost":25,"total_cost":90,"item":{"name":"Сок"}}"""))
        assertEquals(90.0, stored.totalCost, 0.0)
    }

    @Test
    fun receiptStatusAndKind() {
        val r = Receipt.parse(obj("""{"id":"r","status":"cancelled","kind":"posting","total_amount":"10","supplier":{"name":"ООО"},"items":[]}"""))
        assertTrue(r.isCancelled)
        assertTrue(r.isPosting)
        assertEquals("Отменена", r.statusLabel)
        assertEquals("ООО", r.supplierName)
    }

    @Test
    fun writeoffReasonLabels() {
        val w = Writeoff.parse(obj("""{"id":"w","reason":"expired","total_amount":1,"location":{"name":"Склад","company":{"name":"Магазин"}}}"""))
        assertEquals("Истёк срок", w.reasonLabel)
        assertEquals("Магазин", w.companyName)
        assertEquals("Своя причина", Writeoff.parse(obj("""{"reason":"Своя причина"}""")).reasonLabel)
        assertEquals("Списание", Writeoff.parse(obj("""{}""")).reasonLabel)
    }

    @Test
    fun stocktakeValuesShortageByPurchasePrice() {
        val s = Stocktake.parse(
            obj(
                """{"id":"s","counted_at":"2026-10-20","location":{"name":"Склад","company":{"name":"Магазин"}},"created_by_staff":{"full_name":"Олжас"},
                    "items":[{"expected_qty":10,"actual_qty":8,"item":{"name":"Вода","default_purchase_price":100,"sale_price":150}},
                             {"expected_qty":5,"actual_qty":6,"item":{"name":"Чипсы","sale_price":200}}]}""",
            ),
        )
        assertEquals("Олжас", s.authorName)
        assertEquals("Магазин", s.companyName)
        assertEquals(200.0, s.shortageAmount, 0.0)
        assertEquals(-2.0, s.lines[0].delta, 0.0)
        assertTrue(s.lines[0].isShortage)
        // Без закупочной цены берём цену продажи.
        assertEquals(200.0, s.lines[1].amount, 0.0)
    }

    @Test
    fun actProgress() {
        val a = RevisionAct.parse(obj("""{"id":"a","status":"open","locationName":"Склад","totalItems":40,"countedItems":10}"""))
        assertTrue(a.isOpen)
        assertEquals(0.25, a.progress!!, 0.0)
        assertEquals("Идёт пересчёт", a.statusLabel)
        assertNull(RevisionAct.parse(obj("""{"id":"a"}""")).progress)
    }

    @Test
    fun actDetailKeepsConflicts() {
        val d = RevisionActDetail.parse(obj("""{"totalItems":2,"countedItems":1,"report":[{"item_id":"1","name":"A","expected":5,"counted":3,"variance":-2,"purchase_price":10,"conflict":true,"countedBy":"Аян"}],"uncounted":[{"item_id":"2","name":"B","expected":4}]}"""))
        assertTrue(d.report[0].conflict)
        assertEquals(-20.0, d.report[0].varianceCost, 0.0)
        assertEquals("B", d.uncounted[0].name)
    }

    @Test
    fun supplierDebtFlags() {
        val s = Supplier.parse(obj("""{"id":"s","name":"ООО Ромашка","receipts_count":"3","receipts_total":"1000","open_debts_sum":"250.5","open_debts_count":1}"""))
        assertTrue(s.hasDebt)
        assertEquals(3, s.receiptsCount)
        assertEquals(250.5, s.openDebtsAmount, 0.0)
    }

    @Test
    fun forecastStatusLabels() {
        val f = StockForecastRow.parse(obj("""{"item_id":"1","name":"Вода","balance":"6","daily_velocity":2,"days_left":3,"status":"critical"}"""))
        assertTrue(f.isUrgent)
        assertEquals("Кончается", f.statusLabel)
        assertNull(StockForecastRow.parse(obj("""{"item_id":"1","status":"no_sales"}""")).daysLeft)
    }

    @Test
    fun showcaseRowNeedsRefillWhenEmptyButStocked() {
        val p = ShowcasePage.parse(obj("""{"companies":[{"id":"c","name":"Магазин"}],"selectedCompanyId":"c","balances":[{"item_id":"1","showcase_quantity":0,"warehouse_quantity":"7","item":{"name":"Вода","unit":"шт"}}]}"""))
        assertTrue(p.rows[0].needsRefill)
        assertEquals("c", p.selectedCompanyId)
        assertEquals("Магазин", p.companies[0].name)
    }

    @Test
    fun analyticsFindsTopSoldAndStale() {
        val a = StoreAnalytics.parse(
            obj(
                """{"balances":[{"item_id":"1","location_id":"l","quantity":5,"item":{"name":"Вода"}},{"item_id":"2","location_id":"l","quantity":9,"item":{"name":"Залежалый"}}],
                    "movements":[{"id":"1","movement_type":"sale","quantity":-2,"total_amount":-300,"item":{"name":"Вода"}},
                                 {"id":"2","movement_type":"sale","quantity":-1,"total_amount":-150,"item":{"name":"Вода"}},
                                 {"id":"3","movement_type":"receipt","quantity":10,"total_amount":900,"item":{"name":"Залежалый"}}]}""",
            ),
        )
        assertEquals(450.0, a.totalSales, 0.0)
        assertEquals(2, a.salesCount)
        assertEquals("Вода", a.topSold[0].name)
        assertEquals(3.0, a.topSold[0].quantity, 0.0)
        assertEquals(listOf("Залежалый"), a.stale.map { it.name })
    }

    @Test
    fun dateParsingAcceptsDateOnlyAndFullStamps() {
        assertNotNull(parseWhen("2026-10-27"))
        assertNotNull(parseWhen("2026-10-27T10:15:00+05:00"))
        assertNotNull(parseWhen("2026-10-27T10:15:00.123456"))
        assertNull(parseWhen(""))
        assertNull(parseWhen("вчера"))
    }

    // ── Сверка с вебом 27.10 ─────────────────────────────────────────────────

    @Test
    fun requestShowsSumsByPurchaseAndSalePrice() {
        val r = StockRequest.parse(
            obj(
                """{"id":"r","status":"new","items":[
                    {"id":"1","requested_qty":10,"available_qty":4,"enough_for_requested":false,"item":{"name":"Вода","default_purchase_price":100,"sale_price":150}},
                    {"id":"2","requested_qty":"2","item":{"name":"Чипсы","default_purchase_price":"300","sale_price":"400"}}]}""",
            ),
        )
        assertEquals(1600.0, r.purchaseTotal, 0.0)
        assertEquals(2300.0, r.saleTotal, 0.0)
        assertTrue(r.hasShortage)
        assertEquals(4.0, r.lines[0].available!!, 0.0)
        assertNull(r.lines[1].available)
    }

    @Test
    fun decidedRequestIsSummedByApprovedQuantity() {
        val r = StockRequest.parse(obj("""{"id":"r","status":"approved_full","items":[{"id":"1","requested_qty":10,"approved_qty":4,"item":{"name":"Вода","default_purchase_price":100,"sale_price":150}}]}"""))
        assertEquals(400.0, r.purchaseTotal, 0.0)
    }

    @Test
    fun overviewSplitsOutOfStockIntoShelfAndBuy() {
        val m = StoreMetrics.parse(obj("""{"pendingRequests":0,"lowStock":9,"outOfStock":5,"outOfStockInBackroom":2,
            "lowStockTop":[{"item_id":"1","name":"Вода","quantity":0,"threshold":5,"location_kind":"showcase","location_name":"Магазин","backroom_qty":7}]}"""))
        assertEquals(3, m.toBuy)
        assertEquals(4, m.fewCount)
        assertEquals(7.0, m.lowTop[0].backroomQty, 0.0)
        assertTrue(m.needsAttention)
        assertFalse(StoreMetrics.parse(obj("""{}""")).needsAttention)
    }

    @Test
    fun closedOrDisabledPointsAreHiddenFromPickers() {
        val ov = StoreOverview.parse(
            obj(
                """{"locations":[{"id":"a","location_type":"warehouse","company_id":"live"},{"id":"b","location_type":"warehouse","company_id":"closed"},{"id":"c","location_type":"catalog"}],
                    "balances":[{"item_id":"1","location_id":"a","quantity":1},{"item_id":"1","location_id":"b","quantity":2}],"movements":[],"requests":[]}""",
            ),
        )
        val r = ov.restrictedTo(setOf("live"))
        assertEquals(listOf("a", "c"), r.locations.map { it.id })
        assertEquals(1, r.balances.size)
        // Конфиг не пришёл — ничего не режем.
        assertEquals(3, ov.restrictedTo(null).locations.size)
    }

    @Test
    fun suppliersCarryOverdueAndNextDue() {
        val s = Supplier.parse(obj("""{"id":"s","name":"А","open_debts_sum":1000,"overdue_debts_sum":400,"next_due_date":"2026-11-05"}"""))
        assertTrue(s.isOverdue)
        assertEquals(400.0, s.overdueAmount, 0.0)
        assertNotNull(s.nextDueDate)
        val fine = Supplier.parse(obj("""{"id":"s","name":"Б","open_debts_sum":10}"""))
        assertFalse(fine.isOverdue)
        assertNull(fine.nextDueDate)
    }

    @Test
    fun serviceItemsHaveNoStock() {
        val s = CatalogItem.parse(obj("""{"id":"1","name":"Теннис","item_type":"service","catalog_qty":0}"""))
        assertTrue(s.isService)
        assertFalse(s.isOutOfStock)
        assertTrue(CatalogItem.parse(obj("""{"id":"2","name":"Вода","catalog_qty":0}""")).isOutOfStock)
    }

    @Test
    fun receiptsDataCarriesFormDictionaries() {
        val d = ReceiptsData.parse(
            obj(
                """{"receipts":[{"id":"r","invoice_file_url":"https://x/i.pdf","items":[]}],
                    "items":[{"id":"1","name":"Йогурт","requires_expiry":true,"default_purchase_price":"80","sale_price":120},{"id":"2","name":"Теннис","item_type":"service"}],
                    "suppliers":[{"id":"s","name":"ООО"}],"locations":[{"id":"l","location_type":"warehouse"}],
                    "expense_categories":[{"id":"c1","name":"Закуп","accounting_group":"COGS"},{"id":"c2","name":"Аренда","accounting_group":"opex"}],
                    "drafts":[{"id":"d"}]}""",
            ),
        )
        assertEquals("https://x/i.pdf", d.receipts[0].invoiceUrl)
        // Услуга в приёмке не нужна.
        assertEquals(listOf("Йогурт"), d.items.map { it.name })
        assertTrue(d.items[0].requiresExpiry)
        assertEquals(80.0, d.items[0].purchasePrice, 0.0)
        assertEquals(listOf("c1"), d.categories.filter { it.isCogs }.map { it.id })
        assertEquals(1, d.draftCount)
    }

    @Test
    fun actListCarriesResultAndDetailCarriesZeroingHints() {
        val a = RevisionAct.parse(obj("""{"id":"a","status":"closed","locationName":"Склад","resultValue":-5000,"zeroedValue":-3000,"companyId":"c","locationType":"warehouse"}"""))
        assertEquals(-5000.0, a.resultValue!!, 0.0)
        assertEquals("warehouse", a.locationType)
        val d = RevisionActDetail.parse(obj("""{"otherLocationType":"point_display","report":[],"uncounted":[{"item_id":"1","name":"A","expected":4,"purchase_price":50,"sold_recent":2,"other_location_qty":1}]}"""))
        assertEquals("point_display", d.otherLocationType)
        assertEquals(200.0, d.uncounted[0].value, 0.0)
        assertEquals(2.0, d.uncounted[0].soldRecent, 0.0)
    }

    @Test
    fun auditCloseSummary() {
        val s = AuditCloseSummary.parse(obj("""{"debtsCreated":2,"summary":{"shrinkageItems":3,"shrinkageValue":900,"surplusItems":1,"surplusValue":50,"zeroedItems":4,"zeroedValue":1200}}"""))
        assertEquals(1200.0, s.zeroedValue, 0.0)
        assertEquals(2, s.debtsCreated)
    }
}
