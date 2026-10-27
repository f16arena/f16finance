package kz.ordaops.android.feature.business.ops

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kz.ordaops.android.core.net.ApiJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

private fun discount(json: String): Discount = Discount.parse(ApiJson.parseToJsonElement(json).jsonObject)

/** Зеркало RetailModels (Discount / DiscountBoard / DiscountDraft) из OrdaKit. */
class OpsDiscountsTest {
    private val today = LocalDate.of(2026, 10, 27)

    @Test
    fun stateResolutionOrder() {
        assertEquals(DiscountState.Disabled, discount("""{"id":"1","is_active":false}""").state(today))
        assertEquals(DiscountState.Expired, discount("""{"id":"1","valid_to":"2026-10-26"}""").state(today))
        assertEquals(DiscountState.Live, discount("""{"id":"1","valid_to":"2026-10-27"}""").state(today)) // последний день ещё действует
        assertEquals(DiscountState.Scheduled, discount("""{"id":"1","valid_from":"2026-10-28"}""").state(today))
        assertEquals(DiscountState.Exhausted, discount("""{"id":"1","usage_limit":5,"usage_count":5}""").state(today))
        assertEquals(DiscountState.Live, discount("""{"id":"1","usage_limit":0,"usage_count":9}""").state(today)) // 0 — без лимита
    }

    @Test
    fun boardGroupsAndSorts() {
        val items = listOf(
            discount("""{"id":"a","type":"percent","value":5}"""),
            discount("""{"id":"b","type":"promo_code","value":20,"promo_code":"X","valid_to":"2026-10-30"}"""),
            discount("""{"id":"c","type":"fixed","value":500,"valid_from":"2027-01-01"}"""),
            discount("""{"id":"d","is_active":false}"""),
            discount("""{"id":"e","valid_to":"2026-01-01"}"""),
        )
        val board = DiscountBoard(items, today)
        assertEquals(listOf("b", "a"), board.live.map { it.id }) // крупные выше
        assertEquals(listOf("c"), board.scheduled.map { it.id })
        assertEquals(listOf("e", "d"), board.stopped.map { it.id })
        assertEquals(listOf("b"), board.livePromoCodes.map { it.id })
        assertEquals(listOf("b"), board.expiringSoon.map { it.id })
        assertEquals("b", board.deepestPercent?.id)
    }

    @Test
    fun draftValidation() {
        assertEquals("Название скидки обязательно", DiscountDraft().validationError)
        assertEquals("Укажите размер скидки", DiscountDraft(name = "x").validationError)
        assertEquals("Процент не может быть больше 100", DiscountDraft(name = "x", value = "120").validationError)
        assertNull(DiscountDraft(name = "x", kind = DiscountKind.Fixed, value = "1 500").validationError) // фиксированная — можно больше 100
        assertEquals("Введите промокод", DiscountDraft(name = "x", kind = DiscountKind.PromoCode, value = "10").validationError)
        assertEquals(
            "Дата окончания раньше даты начала",
            DiscountDraft(name = "x", value = "10", validFrom = "2026-10-10", validTo = "2026-10-01").validationError,
        )
    }

    @Test
    fun createBodyWritesNullsAndCompany() {
        val body = DiscountDraft(name = " Студент ", value = "10,5", companyId = "c1").createBody()
        assertEquals("createDiscount", body["action"]!!.jsonPrimitive.content)
        val payload = body["payload"]!!.jsonObject
        assertEquals("Студент", payload["name"]!!.jsonPrimitive.content)
        assertEquals(10.5, payload["value"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(JsonNull, payload["promo_code"])
        assertEquals(JsonNull, payload["usage_limit"])
        assertEquals("c1", payload["company_id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, DiscountDraft(name = "x", value = "1").createBody()["payload"]!!.jsonObject["company_id"])
    }

    @Test
    fun updateBodyHasNoCompanyAndUppercasesPromo() {
        val body = DiscountDraft(name = "x", kind = DiscountKind.PromoCode, value = "10", promoCode = " ab12 ", usageLimit = "3", companyId = "c1").updateBody("d1")
        assertEquals("updateDiscount", body["action"]!!.jsonPrimitive.content)
        assertEquals("d1", body["discountId"]!!.jsonPrimitive.content)
        val payload: JsonObject = body["payload"]!!.jsonObject
        assertTrue("company_id" !in payload)
        assertEquals("AB12", payload["promo_code"]!!.jsonPrimitive.content)
        assertEquals(3, payload["usage_limit"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun toggleAndDeleteBodies() {
        assertEquals("false", discountToggleBody("d", false)["payload"]!!.jsonObject["is_active"]!!.jsonPrimitive.content)
        assertEquals("deleteDiscount", discountDeleteBody("d")["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun generatedPromoCodeLooksRight() {
        val code = DiscountDraft.generatedPromoCode(Random(1))
        assertEquals(8, code.length)
        assertTrue(code.none { it in "01OIL" })
    }

    @Test
    fun editingRoundTripsDraft() {
        val d = discount("""{"id":"1","name":"N","type":"fixed","value":300,"min_order_amount":1000,"valid_to":"2026-12-31T00:00:00Z","usage_limit":4,"company_id":"c"}""")
        val draft = DiscountDraft.editing(d)
        assertEquals("300", draft.value)
        assertEquals("1000", draft.minOrderAmount)
        assertEquals("2026-12-31", draft.validTo)
        assertEquals("4", draft.usageLimit)
        assertEquals("c", draft.companyId)
    }
}
