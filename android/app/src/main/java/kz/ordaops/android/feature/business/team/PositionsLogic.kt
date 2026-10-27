package kz.ordaops.android.feature.business.team

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Чистая логика страницы «Должности и доступ» (`/access`): должности офиса и
// точек, права на точке галочками, доплата за смену. Источник правды —
// `app/api/admin/positions`, `app/api/admin/point-positions`,
// `lib/core/point-permissions.ts`, `lib/core/positions.ts`. Страница и все её
// маршруты — только для владельца (и суперадмина).

/** Доступ к странице: владелец организации или суперадмин, как `isOwner` на сайте. */
fun canManageAccess(staffRole: String?, isSuperAdmin: Boolean): Boolean = isSuperAdmin || staffRole == "owner"

// ── Должности офиса ──────────────────────────────────────────────────────────

/** Встроенные роли портала хранятся латиницей, показываются по-русски (`OFFICE_BUILTIN_LABELS`). */
private val BuiltinOfficeLabels = mapOf("owner" to "Владелец", "manager" to "Руководитель", "marketer" to "Маркетолог", "other" to "Прочие")

/** Встроенные должности, которым организация может поменять подпись (`RENAMABLE_BUILTIN_POSITIONS`): владельца не переименовать. */
fun isRenamableBuiltin(name: String): Boolean = name != "owner"

data class OfficePosition(
    val id: String,
    /** Внутреннее имя: на нём держатся права. */
    val name: String,
    /** Своя подпись организации для встроенной должности. */
    val label: String?,
    val description: String?,
    val isBuiltin: Boolean,
    val people: Int,
) {
    val title: String get() = label?.trim()?.takeIf { it.isNotEmpty() } ?: BuiltinOfficeLabels[name] ?: name.replace('_', ' ')
}

data class OfficePositions(val items: List<OfficePosition>, val tableExists: Boolean) {
    companion object {
        fun from(root: JsonElement): OfficePositions {
            val o = root.asObject()
            return OfficePositions(
                items = o?.get("data").asObjects().map {
                    OfficePosition(
                        id = it.str("id") ?: "", name = it.str("name") ?: "", label = it.str("label"),
                        description = it.str("description"), isBuiltin = it.bool("is_builtin", false), people = it.int("people"),
                    )
                }.filter { it.name.isNotEmpty() },
                tableExists = o?.bool("tableExists", true) ?: true,
            )
        }
    }
}

/** Что включить новой должности: пусто, всё, или скопировать набор у другой. */
enum class PositionSeed(val raw: String, val title: String) {
    Closed("closed", "Ничего не открыто"), Open("open", "Всё открыто"), Copy("copy_from", "Как у другой должности")
}

/** Тело создания должности офиса. Имя сервер сам превращает в `snake_case`. */
fun createOfficePositionBody(name: String, description: String?, seed: PositionSeed, copyFrom: String?): JsonObject = buildJsonObject {
    put("action", "create")
    put("name", name.trim())
    if (!description.isNullOrBlank()) put("description", description.trim())
    put("seed", seed.raw)
    if (seed == PositionSeed.Copy && !copyFrom.isNullOrBlank()) put("copy_from_role", copyFrom)
}

fun officePositionProblem(name: String, seed: PositionSeed, copyFrom: String?): String? = when {
    name.trim().length < 2 -> "Название — не меньше двух символов"
    seed == PositionSeed.Copy && copyFrom.isNullOrBlank() -> "Выберите, у кого скопировать права"
    else -> null
}

fun renameBuiltinBody(name: String, label: String): JsonObject = buildJsonObject {
    put("action", "renameBuiltin"); put("name", name); put("label", label.trim())
}

fun updateOfficePositionBody(id: String, name: String, description: String?): JsonObject = buildJsonObject {
    put("action", "update"); put("id", id); put("name", name.trim())
    if (!description.isNullOrBlank()) put("description", description.trim())
}

fun deleteOfficePositionBody(id: String): JsonObject = buildJsonObject { put("action", "delete"); put("id", id) }

// ── Права офисной должности на страницы ──────────────────────────────────────

/** Эффективные права ролей: `GET /api/admin/org-role-capabilities` → `{ items: [{ role, capability, granted }] }`. */
class RoleRights(private val granted: Map<String, Set<String>>) {
    fun has(role: String, capability: String): Boolean = capability in granted[role].orEmpty()

    fun count(role: String): Int = granted[role]?.size ?: 0

    companion object {
        fun from(root: JsonElement): RoleRights {
            val map = HashMap<String, MutableSet<String>>()
            for (o in root.asObject()?.get("items").asObjects()) {
                if (!o.bool("granted", false)) continue
                val role = o.str("role") ?: continue
                val cap = o.str("capability") ?: continue
                map.getOrPut(role) { mutableSetOf() }.add(cap)
            }
            return RoleRights(map)
        }
    }
}

/** Включить/выключить одно право. Включая действие, сервер сам включает и просмотр страницы. */
fun setRightBody(role: String, capability: String, granted: Boolean): JsonObject = buildJsonObject {
    put("action", "set"); put("role", role); put("capability", capability); put("granted", granted)
}

/** Готовые наборы: только просмотр страниц, всё закрыто, вернуть умолчание. */
enum class RightsPreset(val raw: String, val title: String, val hint: String) {
    ViewOnly("view_only", "Только просмотр", "Страницы открываются, но менять ничего нельзя."),
    ClearAll("clear_all", "Закрыть всё", "У должности не останется ни одного права."),
    Reset("reset_role", "Вернуть по умолчанию", "Свои настройки организации снимутся.");

    fun body(role: String): JsonObject = buildJsonObject { put("action", raw); put("role", role) }
}

// ── Должности на точках ──────────────────────────────────────────────────────

/** Право человека на точке — галочка у должности (`lib/core/point-permissions.ts`). */
data class PointPermissionDef(val key: String, val label: String, val hint: String)

/** Права «старшего на точке»: кабинет старшего на сайте и в приложении. */
val LeadPermissions = listOf(
    PointPermissionDef("schedule_requests", "Разбирать заявки на замену в графике", "Видит заявки операторов своей точки и предлагает: оставить, снять, заменить."),
    PointPermissionDef("point_tasks", "Вести задачи точки", "Берёт задачи точки в работу, отмечает выполненными, пишет комментарии."),
    PointPermissionDef("shift_request_alerts", "Получать уведомления о заявках на смену", "Telegram, когда оператор точки просит поменять смену."),
)

const val BigDiscountPercent = 10

val KassaPermissions = listOf(
    PointPermissionDef("kassa_refunds", "Делать возвраты", "Вернуть деньги покупателю за товар."),
    PointPermissionDef("kassa_big_discount", "Давать скидку больше $BigDiscountPercent%", "В продаже и на сеанс в арене. Скидка бонусами лояльности сюда не считается."),
    PointPermissionDef("kassa_free_price", "Продавать универсальный товар", "Название и цену вводит кассир — например, товар до оприходования."),
    PointPermissionDef("kassa_correct_payment", "Исправлять оплату в чеке", "Поменять наличные и безнал в уже пробитом чеке."),
    PointPermissionDef("kassa_delete_debts", "Удалять чужие долги", "Свой долг или только что записанный (15 минут) можно удалить всегда."),
)

private val AllLead = LeadPermissions.map { it.key }
private val AllKassa = KassaPermissions.map { it.key }
private val AllPermissions = AllLead + AllKassa

/** Права кассы, которые были у всех до галочек; «удалять чужие долги» владелец выдаёт сам. */
val DefaultKassaPermissions: List<String> = AllKassa.filter { it != "kassa_delete_debts" }

/** Права по базовой роли — для должностей без списка (как до галочек). */
fun legacyPointPermissions(baseRole: String?): List<String> =
    if (baseRole == "senior_operator" || baseRole == "senior_cashier") AllLead + DefaultKassaPermissions else DefaultKassaPermissions

/** Откуда доплата: правила зарплаты (с бонусом старшего или без) или своя сумма. */
enum class BonusMode(val raw: String, val title: String) {
    Operator("rules:operator", "Ставка из правил зарплаты, без бонуса старшего"),
    SeniorOperator("rules:senior_operator", "Ставка + бонус старшего оператора из правил"),
    SeniorCashier("rules:senior_cashier", "Ставка + бонус старшего кассира из правил"),
    Fixed("fixed", "Ставка + своя доплата за смену");

    companion object {
        fun of(raw: String): BonusMode = entries.firstOrNull { it.raw == raw } ?: Operator
    }
}

data class PointPosition(
    val id: String,
    val companyId: String?,
    val name: String,
    val baseRole: String,
    /** `null` — бонус старшего берётся из правил зарплаты по базовой роли. */
    val shiftBonus: Double?,
    /** `null` — права по базовой роли. */
    val storedPermissions: List<String>?,
    val isActive: Boolean,
    val people: Int,
) {
    /** Действующие права: список должности, а без него — по базовой роли. */
    val permissions: List<String>
        get() = storedPermissions?.filter { it in AllPermissions } ?: legacyPointPermissions(baseRole)

    /** «старший, касса без ограничений» / «не старший, касса: 3 из 5 прав». */
    val permissionsSummary: String
        get() {
            val lead = AllLead.count { it in permissions }
            val kassa = AllKassa.count { it in permissions }
            val leadText = when (lead) { 0 -> "не старший"; AllLead.size -> "старший"; else -> "старший: $lead из ${AllLead.size}" }
            val kassaText = if (kassa == AllKassa.size) "касса без ограничений" else "касса: $kassa из ${AllKassa.size} прав"
            return "$leadText, $kassaText"
        }

    val payDescription: String
        get() = when {
            shiftBonus != null -> if (shiftBonus > 0) "+${Math.round(shiftBonus)} ₸ за смену" else "без доплаты"
            baseRole == "senior_cashier" -> "бонус старшего кассира из правил"
            baseRole == "senior_operator" -> "бонус старшего оператора из правил"
            else -> "ставка из правил"
        }
}

data class PointPositions(val items: List<PointPosition>, val migrationPending: Boolean) {
    companion object {
        fun from(root: JsonElement): PointPositions {
            val o = root.asObject()
            return PointPositions(
                items = o?.get("data").asObjects().map {
                    PointPosition(
                        id = it.str("id") ?: "", companyId = it.str("company_id"), name = it.str("name") ?: "Должность",
                        baseRole = it.str("base_role") ?: "operator", shiftBonus = it.dblOrNull("shift_bonus"),
                        storedPermissions = (it["permissions"] as? JsonArray)?.mapNotNull { e -> (e as? JsonPrimitive)?.content },
                        isActive = it.bool("is_active", true), people = it.int("people"),
                    )
                }.filter { it.id.isNotEmpty() },
                migrationPending = o?.bool("migrationPending", false) ?: false,
            )
        }
    }
}

/** Черновик формы должности на точке. */
data class PointPositionDraft(
    val id: String? = null,
    val name: String = "",
    val companyId: String = "",
    val permissions: Set<String> = DefaultKassaPermissions.toSet(),
    val bonusMode: BonusMode = BonusMode.Operator,
    val bonusAmount: String = "0",
) {
    fun toggled(key: String): PointPositionDraft = copy(permissions = if (key in permissions) permissions - key else permissions + key)

    val problem: String?
        get() = when {
            name.isBlank() -> "Укажите название должности"
            name.trim().length > 60 -> "Название — до 60 символов"
            bonusMode == BonusMode.Fixed && (bonusAmount.replace(',', '.').toDoubleOrNull() ?: -1.0) < 0 -> "Доплата за смену — 0 или больше"
            else -> null
        }

    /** `base_role` теперь только про деньги: какой бонус старшего брать из правил. */
    fun payload(): JsonObject {
        val fixed = bonusMode == BonusMode.Fixed
        val chosen = AllPermissions.filter { it in permissions }
        return buildJsonObject {
            put("name", name.trim().replace(Regex("\\s+"), " "))
            if (companyId.isEmpty()) put("company_id", JsonNull) else put("company_id", companyId)
            put("permissions", buildJsonArray { chosen.forEach { add(JsonPrimitive(it)) } })
            put("base_role", if (fixed) (if (chosen.isNotEmpty()) "senior_operator" else "operator") else bonusMode.raw.removePrefix("rules:"))
            if (fixed) put("shift_bonus", Math.round(bonusAmount.replace(',', '.').toDoubleOrNull() ?: 0.0)) else put("shift_bonus", JsonNull)
        }
    }

    fun body(): JsonObject = buildJsonObject {
        put("action", if (id == null) "create" else "update")
        if (id != null) put("id", id)
        put("payload", payload())
    }

    companion object {
        fun from(p: PointPosition) = PointPositionDraft(
            id = p.id, name = p.name, companyId = p.companyId.orEmpty(), permissions = p.permissions.toSet(),
            bonusMode = if (p.shiftBonus == null) BonusMode.of("rules:${p.baseRole}") else BonusMode.Fixed,
            bonusAmount = (p.shiftBonus ?: 0.0).let { Math.round(it).toString() },
        )
    }
}

fun archivePointPositionBody(id: String, archive: Boolean): JsonObject =
    buildJsonObject { put("action", if (archive) "archive" else "restore"); put("id", id) }

// ── Охват сотрудника по точкам ───────────────────────────────────────────────

/** Подпись охвата: «Все точки» / «2 точки: Север, Юг». */
fun scopeSummary(companyIds: List<String>?, names: Map<String, String>): String {
    if (companyIds.isNullOrEmpty()) return "Все точки"
    val shown = companyIds.mapNotNull { names[it] }
    return "${companyIds.size} ${plural(companyIds.size, "точка", "точки", "точек")}" + if (shown.isNotEmpty()) ": ${shown.joinToString(", ")}" else ""
}

/** Пустой список сервер читает как «все точки». */
fun setStaffCompaniesBody(staffId: String, companyIds: Set<String>): JsonObject = buildJsonObject {
    put("action", "setStaffCompanies")
    put("staffId", staffId)
    if (companyIds.isEmpty()) put("companyIds", JsonNull) else put("companyIds", buildJsonArray { companyIds.sorted().forEach { add(JsonPrimitive(it)) } })
}
