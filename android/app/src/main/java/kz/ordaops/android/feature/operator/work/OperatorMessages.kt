package kz.ordaops.android.feature.operator.work

import kz.ordaops.android.core.net.ApiError
import kz.ordaops.android.core.net.userMessage

/** Связи нет — то же сообщение, что и на айфоне. */
const val OFFLINE_NOTICE = "Связи нет — сохранено на устройстве и уйдёт само, как появится сеть."

/**
 * Текст отказа в операторских сценариях. Сервер отвечает кодами вроде
 * `point-shift-no-open` — кассиру их показывать нельзя, переводим в объяснение.
 */
fun Throwable.operatorMessage(): String {
    if (this is ApiError) {
        when (this) {
            is ApiError.Conflict -> when (code) {
                "point-shift-no-open" -> return "Сначала откройте смену — без неё продавать нельзя."
                "point-shift-already-open" -> return "На точке уже открыта смена."
                "point-shift-required-checklists-missing" -> return "Перед закрытием завершите обязательные чек-листы."
                "act-not-open" -> return "Акт ревизии уже закрыт."
                else -> {}
            }
            is ApiError.BadRequest -> if (code == "opening-cash-required") {
                return "Перед открытием смены укажите, сколько денег в кассе."
            }
            else -> {}
        }
    }
    return userMessage()
}
