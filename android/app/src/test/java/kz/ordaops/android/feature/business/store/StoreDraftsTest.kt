package kz.ordaops.android.feature.business.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистая логика склада: проверка черновиков и тела запросов. Зеркало ReceiptDraft /
 * WriteoffDraft / StocktakeDraft / StockRequestDecision / CatalogEdit из OrdaKit.
 */
class StoreDraftsTest {

    // ── Форматирование ───────────────────────────────────────────────────────

    @Test
    fun quantityDropsTrailingZeros() {
        assertEquals("12", formatQty(12.0))
        assertEquals("1,5", formatQty(1.5))
        assertEquals("0,125", formatQty(0.125))
        assertEquals("—", formatQty(null))
        assertEquals("12 шт", formatQtyUnit(12.0, null))
        assertEquals("1,5 кг", formatQtyUnit(1.5, " кг "))
    }

    @Test
    fun pluralFollowsRussianRules() {
        assertEquals("позиция", plural(1, "позиция", "позиции", "позиций"))
        assertEquals("позиции", plural(3, "позиция", "позиции", "позиций"))
        assertEquals("позиций", plural(5, "позиция", "позиции", "позиций"))
        assertEquals("позиций", plural(11, "позиция", "позиции", "позиций"))
        assertEquals("позиция", plural(21, "позиция", "позиции", "позиций"))
    }

    @Test
    fun parseNumberAcceptsCommaAndGarbage() {
        assertEquals(1.5, parseNumber("1,5"), 0.0)
        assertEquals(0.0, parseNumber("abc"), 0.0)
        assertEquals(0.0, parseNumber(""), 0.0)
    }

    // ── Приёмка ──────────────────────────────────────────────────────────────

    private fun line(qty: Double = 2.0, cost: Double = 100.0, bonus: Boolean = false) =
        ReceiptLine("i1", "Вода", "шт", qty, cost, isBonus = bonus)

    /** Накладная, у которой есть всё, что с 27.10 требует сервер: файл, категория COGS, чек оплаты. */
    private fun draft(vararg lines: ReceiptLine) = ReceiptDraft(
        receivedAt = "2026-10-27", locationId = "loc", supplierId = "sup", lines = lines.toList(),
        invoiceFileUrl = "https://x/invoice.jpg", expenseCategoryId = "cogs", paymentReceiptUrl = "https://x/pay.jpg",
    )

    @Test
    fun receiptNeedsLocationSupplierAndLines() {
        assertEquals("Выберите, куда принимаем", ReceiptDraft("2026-10-27").validationMessage)
        assertEquals("Добавьте хотя бы одну позицию", draft().validationMessage)
        assertEquals("Поставщик обязателен", draft(line()).copy(supplierId = "").validationMessage)
        assertNull(draft(line()).validationMessage)
    }

    @Test
    fun paidLinesMustHavePriceButBonusMay() {
        assertEquals("У платных позиций должна быть цена закупки", draft(line(cost = 0.0)).validationMessage)
        assertNull(draft(line(cost = 0.0, bonus = true)).validationMessage)
        assertEquals("У каждой позиции должно быть количество", draft(line(qty = 0.0)).validationMessage)
    }

    @Test
    fun deferredPaymentNeedsDueDateUnlessConsignment() {
        val deferred = draft(line()).copy(payment = ReceiptPayment.Deferred)
        assertEquals("У долга должен быть срок оплаты", deferred.validationMessage)
        assertNull(deferred.copy(dueDate = "2026-11-01").validationMessage)
        assertNull(deferred.copy(isConsignment = true).validationMessage)
    }

    @Test
    fun receiptNeedsInvoiceFileCategoryAndPaymentReceipt() {
        val ok = draft(line())
        assertEquals("Загрузите файл накладной. Без документа приёмка запрещена.", ok.copy(invoiceFileUrl = "").validationMessage)
        assertEquals("Выберите категорию расхода COGS для автодобавления", ok.copy(expenseCategoryId = "").validationMessage)
        // Платим сразу — чек обязателен; в долг — нет.
        assertEquals("Загрузите чек об оплате или выберите «В долг»", ok.copy(paymentReceiptUrl = "").validationMessage)
        assertNull(ok.copy(paymentReceiptUrl = "", payment = ReceiptPayment.Deferred, dueDate = "2026-11-01").validationMessage)
    }

    @Test
    fun expiryIsRequiredOnlyForPerishableItems() {
        val perishable = line().copy(name = "Йогурт", requiresExpiry = true)
        assertEquals("Укажите «годен до» для: Йогурт", draft(perishable).validationMessage)
        assertNull(draft(perishable.copy(expiryDate = "2026-12-01")).validationMessage)
        assertNull(draft(line()).validationMessage)
        // Оприходование проверяется так же.
        val posting = ReceiptDraft("2026-10-27", locationId = "loc", lines = listOf(perishable.copy(unitCost = 0.0)))
        assertEquals("Укажите «годен до» для: Йогурт", posting.postingValidationMessage)
    }

    @Test
    fun expiryMessageNamesAtMostFiveItems() {
        val many = (1..7).map { line().copy(itemId = "i$it", name = "Т$it", requiresExpiry = true) }
        assertEquals("Укажите «годен до» для: Т1, Т2, Т3, Т4, Т5 и ещё 2", draft(*many.toTypedArray()).expiryMessage())
    }

    @Test
    fun bonusIsNotCountedInTotal() {
        val d = draft(line(qty = 2.0, cost = 100.0), line(qty = 5.0, cost = 50.0, bonus = true))
        assertEquals(200.0, d.total, 0.0)
        assertEquals(1, d.bonusCount)
    }

    @Test
    fun receiptBodyMatchesWhatTheSiteSends() {
        val body = draft(line(), line(cost = 70.0, bonus = true)).copy(invoiceNumber = " 77 ", paymentMethod = "kaspi").receiptBody("company")
        assertEquals("createReceipt", body["action"]!!.jsonPrimitive.content)
        assertEquals("company", body["company_id"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("loc", payload["location_id"]!!.jsonPrimitive.content)
        assertEquals("77", payload["invoice_number"]!!.jsonPrimitive.content)
        assertEquals("https://x/invoice.jpg", payload["invoice_file_url"]!!.jsonPrimitive.content)
        assertEquals("cogs", payload["expense_category_id"]!!.jsonPrimitive.content)
        assertEquals("https://x/pay.jpg", payload["payment_receipt_file_url"]!!.jsonPrimitive.content)
        assertEquals("now", payload["payment_mode"]!!.jsonPrimitive.content)
        assertEquals("kaspi", payload["payment_method"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, payload["due_date"])
        val items = payload["items"]!!.jsonArray
        assertEquals(100.0, items[0].jsonObject["unit_cost"]!!.jsonPrimitive.double, 0.0)
        // Бонус уходит с нулевой ценой, как бы её ни ввели.
        assertEquals(0.0, items[1].jsonObject["unit_cost"]!!.jsonPrimitive.double, 0.0)
        assertTrue(items[1].jsonObject["is_bonus"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun deferredBodyHasNoPaymentMethodButDueDate() {
        val body = draft(line()).copy(payment = ReceiptPayment.Deferred, dueDate = "2026-11-05").receiptBody(null)
        val payload = body["payload"]!!.jsonObject
        assertEquals(JsonNull, payload["payment_method"])
        // Чек оплаты уходит только при оплате сразу.
        assertEquals(JsonNull, payload["payment_receipt_file_url"])
        assertEquals("2026-11-05", payload["due_date"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["company_id"])
    }

    @Test
    fun postingNeedsNoSupplierOrPrice() {
        val d = ReceiptDraft("2026-10-27", locationId = "loc", lines = listOf(ReceiptLine("i", "Чипсы", "шт", quantity = 3.0)))
        assertNull(d.postingValidationMessage)
        assertEquals("Выберите, куда оприходовать", ReceiptDraft("2026-10-27").postingValidationMessage)
        val body = d.postingBody("c")
        assertEquals("createPosting", body["action"]!!.jsonPrimitive.content)
        assertNotNull(body["posting"]!!.jsonObject["items"])
    }

    // ── Списание ─────────────────────────────────────────────────────────────

    @Test
    fun writeoffRequiresReasonAndQuantities() {
        val base = WriteoffDraft(writtenAt = "2026-10-27", locationId = "loc")
        assertEquals("Причина обязательна", base.validationMessage)
        val withReason = base.copy(reason = "разбилось")
        assertEquals("Добавьте хотя бы одну позицию", withReason.validationMessage)
        assertEquals("У каждой позиции должно быть количество", withReason.copy(lines = listOf(WriteoffLine("i", "Банка"))).validationMessage)
        assertNull(withReason.copy(lines = listOf(WriteoffLine("i", "Банка", 2.0))).validationMessage)
    }

    @Test
    fun writeoffBodyTrimsReasonAndDropsBlankComments() {
        val body = WriteoffDraft("2026-10-27", "loc", " брак ", "  ", listOf(WriteoffLine("i", "Банка", 2.0, " "))).body("c")
        val payload = body["payload"]!!.jsonObject
        assertEquals("брак", payload["reason"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, payload["comment"])
        assertEquals(JsonNull, payload["items"]!!.jsonArray[0].jsonObject["comment"])
    }

    // ── Ревизия ──────────────────────────────────────────────────────────────

    @Test
    fun uncountedLinesNeverGoToTheServer() {
        val d = StocktakeDraft(
            countedAt = "2026-10-27", locationId = "loc",
            lines = listOf(
                StocktakeLine("a", "A", "шт", null, expected = 5.0, actual = 4.0),
                StocktakeLine("b", "B", "шт", null, expected = 3.0, actual = null),
                StocktakeLine("c", "C", "шт", null, expected = 1.0, actual = 0.0),
            ),
        )
        assertEquals(2, d.countedLines.size)
        assertEquals(1, d.remaining)
        // Ноль, введённый руками, — подсчёт; пустое поле — «не дошли».
        assertEquals(listOf("a", "c"), d.body(null)["payload"]!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject["item_id"]!!.jsonPrimitive.content })
        assertEquals(2, d.mismatchedLines.size) // a и c расходятся, b не считали
    }

    @Test
    fun stocktakeNeedsAtLeastOneCountedLine() {
        val d = StocktakeDraft("2026-10-27", "loc", lines = listOf(StocktakeLine("a", "A", "шт", null, 1.0)))
        assertEquals("Ни одна позиция не пересчитана", d.validationMessage)
        assertNull(d.copy(lines = d.lines.map { it.copy(actual = 1.0) }).validationMessage)
    }

    @Test
    fun negativeCountIsRejected() {
        val d = StocktakeDraft("2026-10-27", "loc", lines = listOf(StocktakeLine("a", "A", "шт", null, 1.0, actual = -1.0)))
        assertEquals("Количество не может быть отрицательным", d.validationMessage)
    }

    // ── Заявки: решение одним запросом ───────────────────────────────────────

    @Test
    fun approvalCarriesRequestedQuantitiesForEveryLine() {
        val lines = listOf(RequestLine("l1", "Вода", "шт", 10.0, null), RequestLine("l2", "Чипсы", "шт", 4.0, null))
        val body = decideRequestBody("req", approved = true, lines = lines)
        assertEquals("decideRequest", body["action"]!!.jsonPrimitive.content)
        assertEquals("req", body["requestId"]!!.jsonPrimitive.content)
        assertTrue(body["approved"]!!.jsonPrimitive.boolean)
        val items = body["items"]!!.jsonArray
        // Функция в базе требует строку на каждую позицию — без неё решение не проходит.
        assertEquals(2, items.size)
        assertEquals("l1", items[0].jsonObject["request_item_id"]!!.jsonPrimitive.content)
        assertEquals(10.0, items[0].jsonObject["approved_qty"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun rejectionSendsNoLinesAndKeepsComment() {
        val body = decideRequestBody("req", approved = false, lines = listOf(RequestLine("l1", "Вода", "шт", 10.0, null)), comment = " нет товара ")
        assertFalse(body["approved"]!!.jsonPrimitive.boolean)
        assertEquals(JsonArray(emptyList()), body["items"])
        assertEquals("нет товара", body["decision_comment"]!!.jsonPrimitive.content)
    }

    @Test
    fun requestChainMovesApprovedToIssuedToReceived() {
        fun req(status: String) = StockRequest("r", status, null, null, null, null, null, emptyList())
        assertTrue(req("new").isPending)
        assertEquals(RequestStage.Issued, req("approved_full").nextStage)
        assertEquals(RequestStage.Issued, req("approved_partial").nextStage)
        assertEquals(RequestStage.Received, req("issued").nextStage)
        assertNull(req("received").nextStage)
        assertNull(req("new").nextStage)
        assertEquals("issued", transitionBody("r", RequestStage.Issued)["status"]!!.jsonPrimitive.content)
        assertEquals("undecideRequest", undecideBody("r", " ошибка ")["action"]!!.jsonPrimitive.content)
        assertEquals("ошибка", undecideBody("r", " ошибка ")["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun showcaseBodies() {
        val request = showcaseRequestBody("c", "i", 3.0, null)
        assertEquals("createRequest", request["action"]!!.jsonPrimitive.content)
        assertEquals(null, request["items"]!!.jsonArray[0].jsonObject["comment"])
        val back = showcaseReturnBody("c", "i", 2.0)
        assertEquals("returnToWarehouse", back["action"]!!.jsonPrimitive.content)
        assertEquals(2.0, back["items"]!!.jsonArray[0].jsonObject["quantity"]!!.jsonPrimitive.double, 0.0)
    }

    // ── Каталог ──────────────────────────────────────────────────────────────

    @Test
    fun catalogDraftNeedsNameBarcodeAndNonNegativePrices() {
        assertEquals("Укажите название", CatalogItemDraft().problem)
        assertEquals("Укажите штрихкод", CatalogItemDraft(name = "Вода").problem)
        assertEquals("Цена не может быть меньше нуля", CatalogItemDraft(name = "Вода", barcode = "1", salePrice = -1.0).problem)
        assertNull(CatalogItemDraft(name = "Вода", barcode = "1").problem)
    }

    @Test
    fun catalogUpdateSendsEveryField() {
        val fields = CatalogItemDraft(name = " Вода ", barcode = "4600", unit = " ", notes = " ", lowStockThreshold = 5.0).updateBody("item")["fields"]!!.jsonObject
        assertEquals("Вода", fields["name"]!!.jsonPrimitive.content)
        assertEquals("шт", fields["unit"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, fields["notes"])
        assertEquals(JsonNull, fields["category_id"])
        assertEquals(5.0, fields["low_stock_threshold"]!!.jsonPrimitive.double, 0.0)
        assertEquals("updateItem", CatalogItemDraft(name = "a", barcode = "b").updateBody("item")["action"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("co"), CatalogItemDraft(name = "a", barcode = "b").createBody("co")["company_id"])
    }

    @Test
    fun markupIsRelativeToPurchasePrice() {
        assertEquals(50.0, markupPercent(100.0, 150.0)!!, 0.0001)
        assertNull(markupPercent(0.0, 150.0))
        assertNull(markupPercent(100.0, null))
    }
}

class StoreAuditCloseTest {
    private fun pending(id: String, expected: Double = 10.0, price: Double = 100.0, sold: Double = 0.0) =
        RevisionActDetail.Pending(id, "Т$id", expected, price, sold, 0.0)

    @Test
    fun softCloseSendsNoZeroing() {
        val body = closeActBody("act")
        assertEquals("close", body["action"]!!.jsonPrimitive.content)
        assertEquals("act", body["act_id"]!!.jsonPrimitive.content)
        assertFalse(body["assignDebt"]!!.jsonPrimitive.boolean)
        assertNull(body["zero_uncounted"])
        assertNull(body["zero_item_ids"])
    }

    @Test
    fun selectiveZeroingSendsExactlyTheChosenItems() {
        val body = closeActBody("act", zeroItemIds = setOf("b", "a"), assignDebtZeroed = true)
        assertTrue(body["zero_uncounted"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("a", "b"), body["zero_item_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(body["assignDebtZeroed"]!!.jsonPrimitive.boolean)
        // Пустой выбор — «ничего не обнулять», но не «обнулить всё».
        assertEquals(0, closeActBody("act", zeroItemIds = emptySet())["zero_item_ids"]!!.jsonArray.size)
    }

    @Test
    fun itemsSoldThisWeekAreNotPreselectedForZeroing() {
        val rows = listOf(pending("1"), pending("2", sold = 3.0), pending("3"))
        assertEquals(setOf("1", "3"), defaultZeroSelection(rows))
    }

    @Test
    fun bigZeroingIsDetectedByCountOrValue() {
        // Больше 5 позиций и больше 5 % снимка — громко.
        assertTrue(isBigZeroing((1..6).map { pending("$it", price = 1.0) }, 100))
        // Мало позиций, но дорого — тоже.
        assertTrue(isBigZeroing(listOf(pending("1", expected = 10.0, price = 6000.0)), 100))
        assertFalse(isBigZeroing(listOf(pending("1", price = 10.0)), 100))
    }

    private fun act(id: String, type: String, closed: Long?, result: Double?, company: String? = "c") =
        RevisionAct(id, "closed", null, "Склад", 0L, closed, 10, 10, company, type, result, null)

    @Test
    fun pairedActIsTheOtherLocationOfTheSamePointWithinThreeDays() {
        val day = 86_400_000L
        val warehouse = act("w", "warehouse", 10 * day, -100.0)
        val showcase = act("s", "point_display", 11 * day, 100.0)
        val farAway = act("f", "point_display", 20 * day, 5.0)
        val otherPoint = act("o", "point_display", 10 * day, 1.0, company = "other")
        val sameType = act("t", "warehouse", 10 * day, 1.0)
        val all = listOf(warehouse, showcase, farAway, otherPoint, sameType)
        assertEquals("s", pairedAct(warehouse, all)?.id)
        assertEquals("w", pairedAct(showcase, all)?.id)
        assertNull(pairedAct(farAway, all))
        // Склад -100 и витрина +100 дают по точке ноль.
        assertEquals(0.0, warehouse.resultValue!! + pairedAct(warehouse, all)!!.resultValue!!, 0.0)
    }

    @Test
    fun openActHasNoPair() {
        val open = RevisionAct("o", "open", null, "Склад", 0L, null, 1, 0, "c", "warehouse", null, null)
        assertNull(pairedAct(open, listOf(open)))
    }
}
