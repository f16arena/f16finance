package kz.ordaops.android.core.access

import android.content.Context
import kotlinx.serialization.Serializable
import kz.ordaops.android.core.net.ApiJson

/** Страница каталога прав: `income`, `store-warehouse`… Как `CapabilityPage` в apple/OrdaKit. */
@Serializable
data class CatalogPage(
    val id: String,
    val path: String,
    val extraPaths: List<String> = emptyList(),
    val label: String,
) {
    /** Право на просмотр — именно его проверяет серверный proxy. */
    val viewCapabilityId: String get() = "$id.view"
}

/** Раздел каталога (Финансы, Склад, Смены…). */
@Serializable
data class CatalogGroup(val id: String, val label: String, val pages: List<CatalogPage> = emptyList())

/** Продаваемый модуль: какие маршруты он открывает. */
@Serializable
data class CatalogAddon(val code: String, val name: String = "", val pages: List<String> = emptyList())

@Serializable
private data class CatalogFile(val groups: List<CatalogGroup> = emptyList(), val addons: List<CatalogAddon> = emptyList())

/**
 * Каталог прав и модулей — тот же `apple/Contracts/capabilities.json`, что читает
 * айфон. Файл подключён к сборке как asset (см. app/build.gradle.kts), копии нет.
 */
class CapabilityCatalog(val groups: List<CatalogGroup>, private val addons: List<CatalogAddon>) {
    private val pagesById: Map<String, CatalogPage> =
        groups.flatMap { it.pages }.associateBy { it.id }

    fun page(id: String): CatalogPage? = pagesById[id]

    /** Модуль, которому принадлежит маршрут. Зеркало `AddonCatalog.addonCode(forPath:)`. */
    fun addonCode(path: String): String? {
        val clean = path.substringBefore('?')
        for (addon in addons) {
            for (page in addon.pages) {
                if (clean == page || clean.startsWith("$page/")) return addon.code
            }
        }
        return null
    }

    companion object {
        const val ASSET = "capabilities.json"

        fun parse(json: String): CapabilityCatalog {
            val file = ApiJson.decodeFromString(CatalogFile.serializer(), json)
            return CapabilityCatalog(file.groups, file.addons)
        }

        /** Читает файл целиком (~120 КБ) — звать вне главного потока. */
        fun load(context: Context): CapabilityCatalog =
            parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}
