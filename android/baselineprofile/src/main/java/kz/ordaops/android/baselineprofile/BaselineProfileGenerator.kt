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
            node.click()
            device.waitForIdle()
            device.findObject(By.scrollable(true))?.let { list ->
                list.setGestureMargin(device.displayWidth / 5)
                list.fling(Direction.DOWN)
                list.fling(Direction.UP)
            }
        }
    }
}
