package kz.ordaops.android.feature.business.store

import androidx.compose.runtime.Composable

/** Нативные экраны раздела: `pageID` → экран. Подключены в [kz.ordaops.android.feature.business.NativePage]. */
internal val storePages: Map<String, @Composable () -> Unit> = emptyMap()
