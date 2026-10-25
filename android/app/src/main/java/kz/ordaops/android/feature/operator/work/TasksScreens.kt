package kz.ordaops.android.feature.operator.work

import androidx.compose.runtime.Composable
import kz.ordaops.android.feature.operator.OperatorSection
import kz.ordaops.android.feature.operator.SectionPlaceholder

/** Заглушка: раздел переносится с iPhone-версии. */
@Composable
fun TasksTab() {
    SectionPlaceholder(OperatorSection.Tasks)
}

/** Заглушка: раздел переносится с iPhone-версии. */
@Composable
fun ChecklistsTab() {
    SectionPlaceholder(OperatorSection.Checklists)
}
