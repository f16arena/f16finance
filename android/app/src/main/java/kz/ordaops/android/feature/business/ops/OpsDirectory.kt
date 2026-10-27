package kz.ordaops.android.feature.business.ops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.feature.business.LocalBusiness

// Справочники для выпадающих списков листов: исполнители, сотрудники, точки.

/** Человек для выбора: идентификатор и подпись. */
data class OpsPerson(val id: String, val name: String)

/** Оператор из `/api/admin/operators`: полное имя профиля, если есть, иначе служебное. */
fun parseOperators(root: JsonObject, key: String = "data"): List<OpsPerson> =
    root.items(key).filter { it.bool("is_active") != false }.map { o ->
        val profile = (o["operator_profiles"] as? JsonArray)?.firstOrNull() as? JsonObject ?: o.obj("operator_profiles")
        val full = profile?.str("full_name")?.trim().orEmpty()
        OpsPerson(o.str("id") ?: "", full.ifEmpty { o.str("short_name", "name") ?: "Оператор" })
    }.filter { it.id.isNotEmpty() }

/** Сотрудники из `/api/admin/staff` (ответ без конверта `data`). */
fun parseStaffPeople(root: JsonObject): List<OpsPerson> =
    root.items("staff").filter { it.bool("is_active") != false }.map {
        OpsPerson(it.str("id") ?: "", it.str("short_name", "full_name") ?: "Сотрудник")
    }.filter { it.id.isNotEmpty() }

/** Список операторов, загруженный при первом показе листа. */
@Composable
fun rememberOperators(enabled: Boolean = true): List<OpsPerson> {
    val env = LocalBusiness.current
    var people by remember { mutableStateOf<List<OpsPerson>>(emptyList()) }
    LaunchedEffect(enabled) {
        if (enabled) runCatching { parseOperators(env.api.send<JsonObject>(ApiRequest("/api/admin/tasks", query = mapOf("page_size" to "1"))), "operators") }.onSuccess { people = it }
    }
    return people
}

/** Сотрудники (для «кого касается» в инциденте). */
@Composable
fun rememberStaffPeople(): List<OpsPerson> {
    val env = LocalBusiness.current
    var people by remember { mutableStateOf<List<OpsPerson>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { parseStaffPeople(env.api.send<JsonObject>(ApiRequest("/api/admin/staff"))) }.onSuccess { people = it }
    }
    return people
}

/** Точки организации (кэш `BusinessViewModel`), подгружаются, если ещё нет. */
@Composable
fun rememberCompanyOptions(): List<OpsPerson> {
    val env = LocalBusiness.current
    val companies by env.vm.companies.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (companies.isEmpty()) env.vm.loadCompanies() }
    return companies.map { OpsPerson(it.id, it.name) }
}
