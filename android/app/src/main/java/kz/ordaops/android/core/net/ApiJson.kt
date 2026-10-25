package kz.ordaops.android.core.net

import kotlinx.serialization.json.Json

/**
 * Разбор ответов сайта. Терпимый, как декодеры в apple/OrdaKit: новый ключ на
 * бэке или `null` вместо числа не должны ронять приложение.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}
