package kz.ordaops.android.feature.business.team

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Данные для входа»: текст для передачи оператору. */
class PassLogicTest {
    private fun op(id: String, username: String?, chat: String? = null) = TeamOperator(
        id = id, name = "Алия К.", shortName = null, isActive = true, role = "operator", fullName = "Алия Касымова",
        phone = "+7 700", position = null, photoUrl = null, hireDate = null, username = username, lastLogin = null,
        authUserId = if (username != null) "u$id" else null, telegramChatId = chat, hasStaffLink = true,
        stats = OperatorStats(),
    )

    @Test
    fun `в тексте новый пароль только у тех, кому его выдали`() {
        val text = passClipboardText(listOf(op("1", "aliya"), op("2", "bek")), mapOf("1" to "abc23xyz"), "https://x.kz/login")
        assertTrue(text.contains("https://x.kz/login"))
        assertTrue(text.contains("Алия Касымова"))
        assertTrue(text.contains("Логин: aliya"))
        assertTrue(text.contains("Пароль: abc23xyz"))
        // второй пароль не знаем — точки, а не выдумка
        assertTrue(text.contains("Пароль: ••••••••"))
    }

    @Test
    fun `без аккаунта входа так и сказано`() {
        val text = passClipboardText(listOf(op("3", null)), emptyMap(), "u")
        assertTrue(text.contains("нет (аккаунт входа не создан)"))
        assertFalse(text.contains("Логин: null"))
    }
}
