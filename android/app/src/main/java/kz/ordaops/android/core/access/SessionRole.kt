package kz.ordaops.android.core.access

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RolePermissionOverride(val path: String, val enabled: Boolean)

/**
 * Ответ `GET /api/auth/session-role` — единственный источник правды о доступе.
 * Права (`capabilities`) сервер отдаёт уже полностью разрешёнными; клиент их не
 * пересчитывает. Как `SessionRole` в apple/OrdaKit.
 */
@Serializable
data class SessionRole(
    val isSuperAdmin: Boolean = false,
    val isStaff: Boolean = false,
    val isOperator: Boolean = false,
    val isCustomer: Boolean = false,
    val persona: String? = null,
    val displayName: String? = null,
    val roleLabel: String? = null,
    val staffRole: String? = null,
    @SerialName("operatorId") val operatorId: String? = null,
    val defaultPath: String? = null,
    val capabilities: List<String> = emptyList(),
    val orgFeatures: List<String> = emptyList(),
    val featuresAllAccess: Boolean = false,
    val rolePermissionOverrides: List<RolePermissionOverride> = emptyList(),
)

enum class Workspace { Platform, Owner, Staff, Operator, Customer, None }

/** Что человеку можно — те же правила, что `AccessResolver` на айфоне. */
class AccessResolver(val session: SessionRole) {
    private val caps = session.capabilities.toSet()
    private val features = session.orgFeatures.toSet()

    val isAllAccess: Boolean get() = "*" in caps

    fun can(capability: String): Boolean = isAllAccess || capability in caps

    fun canAny(capabilities: List<String>): Boolean = isAllAccess || capabilities.any { it in caps }

    /** Модуль организации. Пустой список — легаси без пакета, всё открыто. */
    fun hasFeature(feature: String?): Boolean {
        if (feature.isNullOrEmpty() || isAllAccess || session.featuresAllAccess || features.isEmpty()) return true
        return feature in features
    }

    /** Ручное выключение маршрута для роли; самое длинное совпадение побеждает. */
    fun pathOverride(path: String): Boolean? = session.rolePermissionOverrides
        .filter { matches(path, it.path) }
        .maxByOrNull { it.path.length }
        ?.enabled

    private fun matches(path: String, rule: String): Boolean =
        if (rule.endsWith("/*")) path.startsWith(rule.dropLast(2)) else path == rule || path.startsWith("$rule/")

    val workspace: Workspace
        get() = when {
            session.isSuperAdmin || isAllAccess -> Workspace.Platform
            session.isOperator && !session.isStaff -> Workspace.Operator
            session.isStaff -> if (session.staffRole == "owner") Workspace.Owner else Workspace.Staff
            session.isCustomer -> Workspace.Customer
            else -> Workspace.None
        }
}
