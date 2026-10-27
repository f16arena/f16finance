package kz.ordaops.android.feature.business.pos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kz.ordaops.android.feature.operator.shift.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosLogicTest {
    private fun item(id: String, name: String, price: Double = 100.0, qty: Double = 5.0, category: String? = null, barcode: String? = null) =
        PosItem(id = id, name = name, salePrice = price, displayQty = qty, category = category?.let { PosCategory(name = it) }, barcode = barcode)

    @Test
    fun discountIsClampedAndRoundedToKopek() {
        assertEquals(10.0, PosMath.discountAmount(100.0, 10.0), 0.0)
        assertEquals(99.0, PosMath.discountAmount(100.0, 150.0), 0.0)
        assertEquals(0.0, PosMath.discountAmount(100.0, -5.0), 0.0)
        assertEquals(33.3, PosMath.discountAmount(111.0, 30.0), 0.0)
        assertEquals(3.33, PosMath.discountAmount(10.0, 33.3), 0.0)
    }

    @Test
    fun redeemableIsCappedByShareOfReceiptAndByBalance() {
        val loyalty = PosLoyalty(isActive = true, tengePerPoint = 2.0, maxRedeemPercent = 50.0)
        // 1000 ₸ × 50% = 500 ₸ = 250 баллов по 2 ₸.
        assertEquals(250.0, PosMath.maxRedeemable(900.0, loyalty, 1000.0), 0.0)
        assertEquals(40.0, PosMath.maxRedeemable(40.0, loyalty, 1000.0), 0.0)
        assertEquals(0.0, PosMath.maxRedeemable(900.0, loyalty.copy(isActive = false), 1000.0), 0.0)
        assertEquals(0.0, PosMath.maxRedeemable(900.0, null, 1000.0), 0.0)
    }

    @Test
    fun payableNeverGoesNegative() {
        val loyaltyDiscount = PosMath.loyaltyDiscount(10_000.0, PosLoyalty(isActive = true), 300.0)
        assertEquals(300.0, loyaltyDiscount, 0.0)
        assertEquals(0.0, PosMath.payable(300.0, loyaltyDiscount), 0.0)
        assertEquals(270.0, PosMath.payable(PosMath.afterDiscount(300.0, 30.0), 0.0), 0.0)
    }

    @Test
    fun cartCannotGrowPastShowcaseBalance() {
        val milk = item("m", "Молоко", qty = 2.0)
        var cart = PosMath.add(emptyList(), milk)
        cart = PosMath.add(cart, milk)
        cart = PosMath.add(cart, milk)
        assertEquals(1, cart.size)
        assertEquals(2.0, cart[0].quantity, 0.0)
        assertEquals(2.0, PosMath.setQuantity(cart, cart[0].id, 9.0)[0].quantity, 0.0)
        assertTrue(PosMath.setQuantity(cart, cart[0].id, 0.0).isEmpty())
        assertTrue(PosMath.add(emptyList(), item("x", "Нет", qty = 0.0)).isEmpty())
    }

    @Test
    fun gridFiltersByCategoryAndBarcodeAndPutsInStockFirst() {
        val items = listOf(
            item("1", "Яблоко", qty = 0.0, category = "Фрукты"),
            item("2", "Банан", category = "Фрукты", barcode = "4600"),
            item("3", "Вода", category = null),
        )
        assertEquals(listOf("2", "1"), PosMath.visible(items, "Фрукты", "").map { it.id })
        assertEquals(listOf("2"), PosMath.visible(items, null, "4600").map { it.id })
        assertEquals(listOf("3"), PosMath.visible(items, PosItem.NO_CATEGORY, "").map { it.id })
        assertEquals(listOf("Без категории", "Фрукты"), PosMath.categories(items))
    }

    @Test
    fun mixedPaymentMustSumToPayableWithBothParts() {
        assertNull(PosMath.paymentIssue(PaymentMethod.Cash, 0.0, 0.0, 500.0))
        assertNotNull(PosMath.paymentIssue(PaymentMethod.Mixed, 100.0, 100.0, 500.0))
        assertNotNull(PosMath.paymentIssue(PaymentMethod.Mixed, 500.0, 0.0, 500.0))
        assertNull(PosMath.paymentIssue(PaymentMethod.Mixed, 200.0, 300.0, 500.0))
    }

    @Test
    fun saleBodyMatchesWebCashRegister() {
        val lines = listOf(
            PosLine(itemId = "item-1", name = "Вода", unit = "шт", quantity = 2.0, unitPrice = 150.0),
            PosLine(itemId = null, name = "Упаковка", unit = "шт", quantity = 1.0, unitPrice = 50.0),
        )
        val checkout = PosCheckout(
            method = PaymentMethod.Mixed, cash = 100.0, kaspi = 225.0, discountAmount = 25.0,
            customerId = "c-1", customerName = "Иван", pointsSpent = 10.0, loyaltyDiscount = 0.0,
            comment = " подарок ", cashReceived = null,
        )
        val body = posSaleBody("co-1", "2026-10-27", night = true, checkout = checkout, lines = lines, localRef = "ref-1")
        assertEquals("createSale", body["action"]!!.jsonPrimitive.content)
        val p = body["payload"]!!.jsonObject
        assertEquals("co-1", p["company_id"]!!.jsonPrimitive.content)
        assertEquals("night", p["shift"]!!.jsonPrimitive.content)
        assertEquals("mixed", p["payment_method"]!!.jsonPrimitive.content)
        // Kaspi целиком «до полуночи», как в вебе.
        assertEquals(225.0, p["kaspi_before_midnight_amount"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(0.0, p["kaspi_after_midnight_amount"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(25.0, p["discount_amount"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("c-1", p["customer_id"]!!.jsonPrimitive.content)
        assertEquals("подарок", p["comment"]!!.jsonPrimitive.content)
        assertEquals("ref-1", p["local_ref"]!!.jsonPrimitive.content)
        val items = p["items"]!!.jsonArray
        assertEquals("item-1", items[0].jsonObject["item_id"]!!.jsonPrimitive.content)
        assertFalse(items[0].jsonObject.containsKey("universal_name"))
        assertEquals("Упаковка", items[1].jsonObject["universal_name"]!!.jsonPrimitive.content)
        assertFalse(items[1].jsonObject.containsKey("item_id"))
    }

    @Test
    fun saleBodyWithoutCustomerSpendsNoPoints() {
        val checkout = PosCheckout(PaymentMethod.Cash, 100.0, 0.0, 0.0, null, null, 50.0, 0.0, null, null)
        val p = posSaleBody("co", "2026-10-27", false, checkout, listOf(PosLine(itemId = "i", name = "A", unit = null, quantity = 1.0, unitPrice = 100.0)), "r")["payload"]!!.jsonObject
        assertEquals("day", p["shift"]!!.jsonPrimitive.content)
        assertEquals(0.0, p["loyalty_points_spent"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertFalse(p.containsKey("customer_id"))
        assertFalse(p.containsKey("comment"))
    }

    @Test
    fun loyaltyConfigAcceptsNumbersAndStrings() {
        val json = Json.parseToJsonElement("""{"is_active":true,"tenge_per_point":"2","max_redeem_percent_per_purchase":30}""")
        val loyalty = PosLoyalty.from(json)!!
        assertTrue(loyalty.isActive)
        assertEquals(2.0, loyalty.tengePerPoint, 0.0)
        assertEquals(30.0, loyalty.maxRedeemPercent, 0.0)
        assertNull(PosLoyalty.from(null))
    }

    @Test
    fun receiptChangeOnlyForEnoughCash() {
        val receipt = PosReceipt(
            saleId = "abc123456", companyName = "Точка", soldAtMillis = 0, lines = emptyList(), subtotal = 900.0,
            discount = 100.0, loyaltyDiscount = 0.0, total = 800.0, method = PaymentMethod.Cash,
            cash = 800.0, kaspi = 0.0, cashReceived = 1000.0, customerName = null,
        )
        assertEquals(200.0, receipt.change, 0.0)
        assertEquals(100.0, receipt.totalDiscount, 0.0)
        assertEquals(0.0, receipt.copy(cashReceived = null).change, 0.0)
    }
}
