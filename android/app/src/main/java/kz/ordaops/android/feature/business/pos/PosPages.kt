package kz.ordaops.android.feature.business.pos

import androidx.compose.runtime.Composable

/**
 * Нативные экраны раздела «Касса»: `pageID` → экран. Подключены в
 * [kz.ordaops.android.feature.business.NativePage]. Разбор и тела записей сверены с текущим
 * вебом (`app/pos`, `app/api/admin/pos`), а не с iOS, который отстал.
 */
internal val posPages: Map<String, @Composable () -> Unit> = mapOf(
    "pos" to { PosScreen() },
)
