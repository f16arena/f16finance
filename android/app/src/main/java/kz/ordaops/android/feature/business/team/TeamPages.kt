package kz.ordaops.android.feature.business.team

import androidx.compose.runtime.Composable

/** Нативные экраны раздела: `pageID` → экран. Подключены в [kz.ordaops.android.feature.business.NativePage]. */
internal val teamPages: Map<String, @Composable () -> Unit> = mapOf(
    "operators" to { OperatorsScreen() },
    "salary" to { SalaryScreen() },
    "staff" to { StaffScreen() },
    "shifts" to { ShiftsScreen() },
    "shifts-reports" to { ShiftReportsScreen() },
    "birthdays" to { BirthdaysScreen() },
    "salary-rules" to { SalaryRulesScreen() },
    "hr" to { HrScreen() },
    "structure" to { StructureScreen() },
)
