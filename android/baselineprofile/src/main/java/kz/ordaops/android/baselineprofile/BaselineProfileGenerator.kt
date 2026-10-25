package kz.ordaops.android.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает Baseline Profile: какой код нужен на старте, на экране входа и,
 * если на устройстве уже выполнен вход, — при переходах по вкладкам и прокрутке.
 *
 * Запуск: `./gradlew :app:generateReleaseBaselineProfile` с подключённым эмулятором.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "kz.ordaops.android", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        // Без входа профиль покрывает старт и экран входа; со входом — ещё и рабочие экраны.
        for (tab in listOf("Продажа", "Чат", "Задачи", "Профиль", "Смена")) {
            val node = device.wait(Until.findObject(By.text(tab)), 3_000) ?: continue
            runCatching { node.click() }
            device.waitForIdle()
            // Экран раздела въезжает анимацией — список ищем заново перед каждым жестом,
            // иначе найденный элемент успевает устареть.
            repeat(2) { i ->
                runCatching {
                    device.wait(Until.findObject(By.scrollable(true)), 2_000)?.let { list ->
                        list.setGestureMargin(device.displayWidth / 5)
                        list.fling(if (i == 0) Direction.DOWN else Direction.UP)
                    }
                }
                device.waitForIdle()
            }
        }
    }
}
