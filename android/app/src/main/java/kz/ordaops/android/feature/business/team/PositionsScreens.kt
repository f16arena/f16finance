package kz.ordaops.android.feature.business.team

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kz.ordaops.android.core.net.ApiRequest
import kz.ordaops.android.core.net.userMessage
import kz.ordaops.android.feature.business.LocalBusiness
import kz.ordaops.android.feature.business.OwnerScreenColumn
import kz.ordaops.android.feature.business.OwnerSection
import kz.ordaops.android.feature.business.PillSegment
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.StateContainer
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Spacing

// «Должности и доступ» (`/access`): должности офиса — названия и права на страницы,
// должности на точках — права галочками и доплата за смену. Страница и все её
// маршруты открыты только владельцу (и суперадмину): остальным — заглушка.
// Аккаунты сотрудников (вход, пароли) живут в «Сотрудниках» и «Операторах».

private const val POSITIONS = "/api/admin/positions"
private const val POINT_POSITIONS = "/api/admin/point-positions"
private const val ROLE_RIGHTS = "/api/admin/org-role-capabilities"

@Composable
fun AccessScreen() {
    val env = LocalBusiness.current
    val session = env.resolver.session
    if (!canManageAccess(session.staffRole, session.isSuperAdmin)) {
        EmptyState(
            Icons.Filled.Lock, "Только для владельца",
            "Должности, права и доступ настраивает владелец организации. Если нужен доступ — обратитесь к нему.",
        )
        return
    }
    var tab by rememberSaveable { mutableStateOf("office") }
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            PillSegment(listOf("office" to "В офисе", "point" to "На точках"), tab) { tab = it }
        }
        Column(Modifier.weight(1f)) {
            if (tab == "office") OfficePositionsTab() else PointPositionsTab()
        }
    }
}

// ── Офис ─────────────────────────────────────────────────────────────────────

@Composable
private fun OfficePositionsTab() {
    val vm = rememberResource("access-office", Unit, { ApiRequest(POSITIONS) }, OfficePositions::from)
    val state by vm.state.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val c = Orda.colors
    StateContainer(
        state = state, onRetry = vm::reload,
        isEmpty = { it.items.isEmpty() }, emptyIcon = Icons.Filled.AdminPanelSettings,
        emptyTitle = "Должностей нет", emptyMessage = "Создайте первую должность офиса.",
    ) { data ->
        OwnerScreenColumn {
            TeamPill("Новая должность") { creating = true }
            if (!data.tableExists) TeamHint("Таблица должностей ещё не развёрнута на сервере: показаны только встроенные.", c.warning)
            OwnerSection("Должности офиса", trailing = { Text("${data.items.size}", color = c.textDim, fontSize = 14.sp) }) {
                TeamHint("Определяют, какие страницы и действия портала доступны человеку.")
                data.items.forEachIndexed { i, p ->
                    if (i > 0) TeamDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { openId = p.id }.padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        kz.ordaops.android.feature.operator.money.TintedIcon(Icons.Filled.Badge, if (p.isBuiltin) c.info else c.brand, 40, corner = 12.dp)
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                listOfNotNull(
                                    if (p.isBuiltin) "встроенная" else null,
                                    if (p.people == 0) "никого" else "${p.people} ${plural(p.people, "человек", "человека", "человек")}",
                                ).joinToString(" · "),
                                color = c.textDim, fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
        data.items.firstOrNull { it.id == openId }?.let { p ->
            OfficePositionSheet(p, data.items, onDismiss = { openId = null }, onChanged = { vm.reload() })
        }
    }
    if (creating) {
        state.data?.let { data ->
            NewOfficePositionSheet(data.items, onDismiss = { creating = false }, onDone = { creating = false; vm.reload() })
        }
    }
}

@Composable
private fun OfficePositionSheet(p: OfficePosition, all: List<OfficePosition>, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    val c = Orda.colors
    var rights by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    OrdaSheet(p.title, onDismiss) {
        p.description?.let { Text(it, color = c.textMuted, fontSize = 14.sp) }
        Text(
            if (p.people == 0) "Сейчас никого на этой должности" else "На должности: ${p.people} ${plural(p.people, "человек", "человека", "человек")}",
            color = c.textDim, fontSize = 13.sp,
        )
        TeamError(action.error)
        TeamActionRow(Icons.Filled.AdminPanelSettings, "Права на страницы", c.brand) { rights = true }
        if (!p.isBuiltin || isRenamableBuiltin(p.name)) {
            TeamActionRow(Icons.Filled.Edit, if (p.isBuiltin) "Переименовать" else "Изменить", c.info) { editing = true }
        }
        if (!p.isBuiltin) TeamActionRow(Icons.Filled.Delete, "Удалить должность", c.negative, loading = action.busy) { confirmDelete = true }
    }
    if (rights) RightsSheet(p, all, onDismiss = { rights = false })
    if (editing) EditOfficePositionSheet(p, onDismiss = { editing = false }, onDone = { editing = false; onChanged(); onDismiss() })
    if (confirmDelete) {
        ConfirmDialog(
            "Удалить «${p.title}»?", "Права этой должности тоже удалятся. Если на ней кто-то работает, сервер откажет — сначала переназначьте людей.",
            "Удалить", destructive = true, onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                action.launch(scope, onSuccess = { onChanged(); onDismiss() }) {
                    env.api.sendUnit(ApiRequest.json(POSITIONS, deleteOfficePositionBody(p.id)))
                }
            },
        )
    }
}

@Composable
private fun NewOfficePositionSheet(existing: List<OfficePosition>, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf(PositionSeed.Closed) }
    var copyFrom by remember { mutableStateOf("") }
    OrdaSheet("Новая должность", onDismiss) {
        TeamField("Название", name, { name = it }, placeholder = "Например: администратор")
        TeamField("Описание", description, { description = it }, singleLine = false)
        Text("Какие права открыть сразу", color = Orda.colors.textDim, fontSize = 13.sp)
        PositionSeed.entries.forEach { s -> TeamChoiceRow(s.title, seed == s) { seed = s } }
        if (seed == PositionSeed.Copy) {
            Text("Скопировать у", color = Orda.colors.textDim, fontSize = 13.sp)
            existing.forEach { p -> TeamChoiceRow(p.title, copyFrom == p.name) { copyFrom = p.name } }
        }
        TeamHint("«Ничего не открыто» — безопасный старт: нужное включаете в правах.")
        TeamError(action.error)
        SubmitButton("Создать", action.busy, officePositionProblem(name, seed, copyFrom)) {
            action.launch(scope, onSuccess = { onDone() }) {
                env.api.sendUnit(ApiRequest.json(POSITIONS, createOfficePositionBody(name, description, seed, copyFrom)))
            }
        }
    }
}

@Composable
private fun EditOfficePositionSheet(p: OfficePosition, onDismiss: () -> Unit, onDone: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var name by remember { mutableStateOf(if (p.isBuiltin) p.title else p.name) }
    var description by remember { mutableStateOf(p.description.orEmpty()) }
    OrdaSheet(if (p.isBuiltin) "Переименовать" else "Изменить должность", onDismiss) {
        TeamField("Название", name, { name = it })
        if (p.isBuiltin) TeamHint("Меняется только подпись: права должности останутся прежними.")
        else TeamField("Описание", description, { description = it }, singleLine = false)
        TeamError(action.error)
        SubmitButton("Сохранить", action.busy, if (name.trim().length < 2) "Название — не меньше двух символов" else null) {
            action.launch(scope, onSuccess = { onDone() }) {
                val body = if (p.isBuiltin) renameBuiltinBody(p.name, name) else updateOfficePositionBody(p.id, name, description)
                env.api.sendUnit(ApiRequest.json(POSITIONS, body))
            }
        }
    }
}

/** Права должности на страницы: тумблер «просмотр» по каждой странице каталога, готовые наборы сверху. */
@Composable
private fun RightsSheet(p: OfficePosition, all: List<OfficePosition>, onDismiss: () -> Unit) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    val loaded = rememberLoaded(p.name) { api -> RoleRights.from(api.send<JsonElement>(ApiRequest(ROLE_RIGHTS))) }
    // Переключённое руками показываем сразу, не дожидаясь перезагрузки матрицы.
    val overrides = remember { mutableStateMapOf<String, Boolean>() }
    var preset by remember { mutableStateOf<RightsPreset?>(null) }
    val isOwnerRole = p.name == "owner"
    val c = Orda.colors
    OrdaSheet("Права: ${p.title}", onDismiss) {
        if (isOwnerRole) {
            TeamHint("Владелец всегда имеет полный доступ — набор страниц задаёт пакет организации.")
            return@OrdaSheet
        }
        val rights = loaded.state.data
        if (rights == null) {
            TeamHint(loaded.state.error ?: "Загружаем права…", if (loaded.state.error != null) c.negative else c.textDim)
            if (loaded.state.error != null) TeamPill("Повторить") { loaded.reload() }
            return@OrdaSheet
        }
        TeamError(action.error)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RightsPreset.entries.forEach { r -> TeamPill(r.title) { preset = r } }
        }
        TeamHint("Тумблер открывает или закрывает страницу целиком. Отдельные действия внутри страницы настраиваются на сайте.")
        env.access.catalog.groups.forEach { group ->
            if (group.pages.isEmpty()) return@forEach
            Text(group.label, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = Spacing.sm))
            group.pages.forEach { page ->
                val cap = page.viewCapabilityId
                val on = overrides[cap] ?: rights.has(p.name, cap)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(page.label, color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { next ->
                        overrides[cap] = next
                        scope.launch {
                            action.error = null
                            try {
                                env.api.sendUnit(ApiRequest.json(ROLE_RIGHTS, setRightBody(p.name, cap, next)))
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                overrides.remove(cap)
                                action.error = e.userMessage()
                            }
                        }
                    })
                }
            }
        }
    }
    preset?.let { r ->
        ConfirmDialog(
            "${r.title}?", "${r.hint} Применится к должности «${p.title}».", "Применить", destructive = r != RightsPreset.ViewOnly,
            onDismiss = { preset = null },
            onConfirm = {
                preset = null
                action.launch(scope, onSuccess = { overrides.clear(); loaded.reload() }) {
                    env.api.sendUnit(ApiRequest.json(ROLE_RIGHTS, r.body(p.name)))
                }
            },
        )
    }
}

// ── Точки ────────────────────────────────────────────────────────────────────

@Composable
private fun PointPositionsTab() {
    val vm = rememberResource("access-point", Unit, { ApiRequest(POINT_POSITIONS) }, PointPositions::from)
    val state by vm.state.collectAsStateWithLifecycle()
    val companies = rememberCompanyChoices()
    var draft by remember { mutableStateOf<PointPositionDraft?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val c = Orda.colors
    val names = remember(companies) { companies.toMap() }
    StateContainer(state = state, onRetry = vm::reload) { data ->
        val shown = data.items.filter { showArchived || it.isActive }
        OwnerScreenColumn {
            TeamPill("Новая должность") { draft = PointPositionDraft() }
            if (data.migrationPending) TeamHint("Должности на точках ещё не включены на сервере (нужна миграция).", c.warning)
            OwnerSection("Должности на точках", trailing = { Text("${shown.size}", color = c.textDim, fontSize = 14.sp) }) {
                TeamHint("Права в кассе и кабинет старшего — галочками; доплата за смену заменяет бонус старшего из правил зарплаты.")
                if (shown.isEmpty()) TeamHint("Должностей пока нет.")
                shown.forEachIndexed { i, p ->
                    if (i > 0) TeamDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { draft = PointPositionDraft.from(p) }.padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        kz.ordaops.android.feature.operator.money.TintedIcon(Icons.Filled.Store, if (p.isActive) c.brand else c.textDim, 40, corner = 12.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name + if (p.isActive) "" else " (в архиве)", color = if (p.isActive) c.text else c.textDim, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(p.companyId?.let { names[it] } ?: "На всех точках", color = c.textMuted, fontSize = 13.sp)
                            Text("${p.permissionsSummary} · ${p.payDescription}", color = c.textDim, fontSize = 12.sp)
                            Text(
                                if (p.people == 0) "никого" else "${p.people} ${plural(p.people, "человек", "человека", "человек")}",
                                color = c.textDim, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            if (data.items.any { !it.isActive }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Switch(checked = showArchived, onCheckedChange = { showArchived = it })
                    Text("Показывать архивные", color = c.textMuted, fontSize = 14.sp)
                }
            }
        }
        draft?.let { d ->
            val original = data.items.firstOrNull { it.id == d.id }
            PointPositionSheet(d, original, companies, onDismiss = { draft = null }, onDone = { draft = null; vm.reload() })
        }
    }
}

@Composable
private fun PointPositionSheet(
    initial: PointPositionDraft,
    original: PointPosition?,
    companies: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val env = LocalBusiness.current
    val scope = rememberCoroutineScope()
    val action = rememberActionState()
    var draft by remember { mutableStateOf(initial) }
    val c = Orda.colors
    OrdaSheet(if (initial.id == null) "Новая должность" else "Должность на точке", onDismiss) {
        TeamField("Название", draft.name, { draft = draft.copy(name = it) }, placeholder = "Например: бариста")
        Text("Где", color = c.textDim, fontSize = 13.sp)
        TeamChoiceRow("На всех точках", draft.companyId.isEmpty()) { draft = draft.copy(companyId = "") }
        companies.forEach { (id, name) -> TeamChoiceRow(name, draft.companyId == id) { draft = draft.copy(companyId = id) } }
        Text("Деньги за смену", color = c.textDim, fontSize = 13.sp)
        BonusMode.entries.forEach { m -> TeamChoiceRow(m.title, draft.bonusMode == m) { draft = draft.copy(bonusMode = m) } }
        if (draft.bonusMode == BonusMode.Fixed) {
            TeamField("Доплата за смену, ₸", draft.bonusAmount, { draft = draft.copy(bonusAmount = it.filter { ch -> ch.isDigit() }) }, keyboard = KeyboardType.Number)
        }
        PermissionGroup("Старший на точке", LeadPermissions, draft) { draft = draft.toggled(it) }
        PermissionGroup("Касса", KassaPermissions, draft) { draft = draft.toggled(it) }
        TeamHint("Продавать, открывать и закрывать смену может любой человек на точке.")
        TeamError(action.error)
        SubmitButton("Сохранить", action.busy, draft.problem) {
            action.launch(scope, onSuccess = { onDone() }) {
                env.api.sendUnit(ApiRequest.json(POINT_POSITIONS, draft.body()))
            }
        }
        if (original != null) {
            TeamActionRow(Icons.Filled.Delete, if (original.isActive) "Убрать в архив" else "Вернуть из архива", if (original.isActive) c.negative else c.positive, loading = action.busy) {
                action.launch(scope, onSuccess = { onDone() }) {
                    env.api.sendUnit(ApiRequest.json(POINT_POSITIONS, archivePointPositionBody(original.id, archive = original.isActive)))
                }
            }
        }
    }
}

@Composable
private fun PermissionGroup(title: String, items: List<PointPermissionDef>, draft: PointPositionDraft, onToggle: (String) -> Unit) {
    val c = Orda.colors
    Text(title, color = c.textDim, fontSize = 13.sp)
    items.forEach { def ->
        Row(Modifier.fillMaxWidth().clickable { onToggle(def.key) }, verticalAlignment = Alignment.Top) {
            Checkbox(checked = def.key in draft.permissions, onCheckedChange = { onToggle(def.key) })
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(def.label, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(def.hint, color = c.textDim, fontSize = 12.sp)
            }
        }
    }
}

// ── Охват сотрудника по точкам ───────────────────────────────────────────────

/**
 * Секция карточки сотрудника: какие точки он видит. Границу доступа задаёт только
 * владелец; владельцу охват не задаётся. Пустой выбор — все точки.
 */
@Composable
fun StaffScopeSection(staffId: String, role: String, companyIds: List<String>?, onChanged: suspend () -> Unit) {
    val env = LocalBusiness.current
    val session = env.resolver.session
    if (role == "owner" || !canManageAccess(session.staffRole, session.isSuperAdmin)) return
    val companies = rememberCompanyChoices()
    var editing by remember { mutableStateOf(false) }
    val names = remember(companies) { companies.toMap() }
    OwnerSection("Доступ к точкам") {
        Text(scopeSummary(companyIds, names), color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        TeamHint("Сотрудник видит и правит только данные этих точек.")
        TeamActionRow(Icons.Filled.Store, "Выбрать точки", Orda.colors.info) { editing = true }
    }
    if (editing) {
        val scope = rememberCoroutineScope()
        val action = rememberActionState()
        var selected by remember { mutableStateOf(companyIds.orEmpty().toSet()) }
        OrdaSheet("Доступ к точкам", { editing = false }) {
            TeamHint("Ничего не отмечено — сотрудник видит все точки организации.")
            companies.forEach { (id, name) ->
                Row(Modifier.fillMaxWidth().clickable { selected = if (id in selected) selected - id else selected + id }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = id in selected, onCheckedChange = { selected = if (it) selected + id else selected - id })
                    Text(name, color = Orda.colors.text, fontSize = 15.sp)
                }
            }
            TeamError(action.error)
            SubmitButton("Сохранить", action.busy, null) {
                action.launch(scope, onSuccess = { onChanged(); editing = false }) {
                    env.api.sendUnit(ApiRequest.json("/api/admin/staff", setStaffCompaniesBody(staffId, selected)))
                }
            }
        }
    }
}
