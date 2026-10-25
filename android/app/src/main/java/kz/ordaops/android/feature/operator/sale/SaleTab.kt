package kz.ordaops.android.feature.operator.sale

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.key
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kz.ordaops.android.feature.operator.shift.OperatorStore
import kz.ordaops.android.feature.operator.shift.OrdaSheet
import kz.ordaops.android.feature.operator.shift.SaleCatalogItem
import kz.ordaops.android.feature.operator.shift.ordaFieldColors
import kz.ordaops.android.feature.operator.shift.pluralize
import kz.ordaops.android.feature.operator.shift.rememberOperatorStore
import kz.ordaops.android.ui.components.AnimatedText
import kz.ordaops.android.ui.components.EmptyState
import kz.ordaops.android.ui.components.pressScale
import kz.ordaops.android.ui.components.rememberHaptics
import kz.ordaops.android.ui.components.ErrorBanner
import kz.ordaops.android.ui.components.LoadingView
import kz.ordaops.android.ui.components.OrdaCard
import kz.ordaops.android.ui.components.PrimaryButton
import kz.ordaops.android.ui.components.money
import kz.ordaops.android.ui.theme.Orda
import kz.ordaops.android.ui.theme.Radius
import kz.ordaops.android.ui.theme.Spacing
import java.net.URL

/**
 * Продажа: сканер, каталог витрины, корзина, оплата — SaleScreen.swift
 * (телефонная раскладка). Состояние смены общее с вкладкой «Смена».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleTab() {
    val store = rememberOperatorStore()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    var search by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var showCart by remember { mutableStateOf(false) }
    var showCheckout by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    LaunchedEffect(store.hasOpenShift) {
        if (store.hasOpenShift && store.catalog.isEmpty()) store.loadCatalog()
    }
    LaunchedEffect(toast) {
        val current = toast ?: return@LaunchedEffect
        delay(if (current.second) 2500 else 1400)
        if (toast == current) toast = null
    }

    fun handleScan(code: String) {
        val item = store.item(code)
        when {
            item == null -> {
                haptics.error()
                toast = "Штрихкод $code не найден на витрине" to true
            }
            !item.isInStock -> {
                haptics.error()
                toast = "«${item.name}» — нет на витрине" to true
            }
            else -> {
                store.add(item)
                haptics.success()
                toast = "${item.name} · ${money(item.salePrice ?: 0.0)}" to false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Orda.colors.background)) {
        when {
            // Продажа уйдёт в чужую смену и в чужой отчёт: деньги окажутся записаны не на того, кто их взял.
            store.isSomeoneElsesShift -> CenteredEmpty(
                Icons.Filled.Person,
                "На смене другой",
                "Сейчас смену ведёт ${store.shift?.operatorName ?: "сменщик"}. Продавать можно только на своей смене.",
            )
            !store.hasOpenShift -> CenteredEmpty(
                Icons.Filled.Lock,
                "Смена не открыта",
                "Продажи начинаются после открытия смены — откройте её на вкладке «Смена».",
            )
            else -> {
                val inStock = store.catalog.filter { it.isInStock }
                val needle = search.trim().lowercase()
                val items = if (needle.isEmpty()) inStock else inStock.filter {
                    it.name.lowercase().contains(needle) || (it.barcode ?: "").contains(needle)
                }
                Column(Modifier.fillMaxSize()) {
                    SearchBar(search, { search = it }) { showScanner = true }
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { scope.launch { refreshing = true; store.loadCatalog(); refreshing = false } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        when {
                            store.isLoadingCatalog && store.catalog.isEmpty() -> LoadingView()
                            store.catalogError != null && store.catalog.isEmpty() -> Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                                ErrorBanner(store.catalogError.orEmpty())
                                PrimaryButton("Повторить") { scope.launch { store.loadCatalog() } }
                            }
                            items.isEmpty() -> EmptyState(
                                icon = Icons.Filled.Search,
                                title = if (search.isEmpty()) "Витрина пуста" else "Ничего не найдено",
                                message = if (search.isEmpty()) "На витрине нет товаров с остатком. Оформите заявку со склада."
                                else "Попробуйте другое название или отсканируйте штрихкод.",
                            )
                            else -> LazyColumn(
                                Modifier.fillMaxSize(),
                                // Место под панель корзины, иначе она перекрывает последнюю позицию.
                                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = if (store.cart.isEmpty()) Spacing.lg else 110.dp),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                items(items, key = { it.id }) { item ->
                                    val inCart = store.cart.firstOrNull { it.itemId == item.id }?.quantity ?: 0.0
                                    SaleItemRow(
                                        item = item,
                                        inCart = inCart,
                                        onAdd = {
                                            store.add(item)
                                            haptics.tick()
                                        },
                                        onRemove = { store.setQuantity(inCart - 1, item.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (store.cart.isNotEmpty()) {
                    CartBar(store, Modifier.align(Alignment.BottomCenter), onOpenCart = { showCart = true }, onCheckout = { showCheckout = true })
                }
            }
        }

        toast?.let { (text, isError) ->
            Text(
                text,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(Spacing.lg)
                    .background(if (isError) Orda.colors.negative else Orda.colors.positive, RoundedCornerShape(Radius.md))
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }

    if (showScanner) {
        OrdaSheet("Сканирование", onDismiss = { showScanner = false }) {
            ScannerPane(onCode = ::handleScan)
            if (store.cart.isNotEmpty()) {
                OrdaCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "В чеке ${store.cartCount} ${pluralize(store.cartCount, "позиция", "позиции", "позиций")}",
                            color = Orda.colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(money(store.cartTotal), color = Orda.colors.text, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // Тост поверх листа: сканируют, глядя на лист, а не на список под ним.
            toast?.let { (text, isError) ->
                Text(
                    text,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isError) Orda.colors.negative else Orda.colors.positive, RoundedCornerShape(Radius.md))
                        .padding(Spacing.md),
                )
            }
        }
    }
    if (showCart) {
        CartSheet(store, onDismiss = { showCart = false }, onCheckout = { showCart = false; showCheckout = true })
    }
    if (showCheckout) {
        CheckoutSheet(store) { showCheckout = false }
    }
    store.lastSale?.let { feedback ->
        SaleReceiptSheet(feedback) { store.dismissLastSale() }
    }
}

@Composable
private fun CenteredEmpty(icon: ImageVector, title: String, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { EmptyState(icon, title, message) }
}

@Composable
private fun SearchBar(search: String, onChange: (String) -> Unit, onScan: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Название товара") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Orda.colors.textDim) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    Icon(Icons.Filled.Close, contentDescription = "Очистить", tint = Orda.colors.textDim, modifier = Modifier.clickable { onChange("") })
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape = RoundedCornerShape(Radius.md),
            colors = ordaFieldColors(),
        )
        Box(
            Modifier
                .size(48.dp)
                .background(Orda.colors.brand.copy(alpha = 0.14f), RoundedCornerShape(Radius.md))
                .clickable(onClick = onScan),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = "Сканировать", tint = Orda.colors.brand)
        }
    }
}

/**
 * Строка товара витрины. Пока товара нет в чеке — «+»; когда есть — «− количество +»:
 * убрать лишнее можно прямо в списке, не открывая оплату.
 */
@Composable
private fun SaleItemRow(item: SaleCatalogItem, inCart: Double, onAdd: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            Modifier.weight(1f).pressScale().clickable(onClick = onAdd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Thumbnail(item.imageUrl, item.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.salePrice?.let { money(it) } ?: "—", color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "· ${quantityText(item.displayQuantity)} ${item.unit ?: "шт"}",
                        color = if (item.displayQuantity <= 3) Orda.colors.warning else Orda.colors.textDim,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        if (inCart > 0) {
            Row(Modifier.background(Orda.colors.brand, RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) {
                StepButton(Icons.Filled.Remove, "Убрать одну", Color.White, onRemove)
                Text(quantityText(inCart), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp), maxLines = 1)
                StepButton(Icons.Filled.Add, "Добавить ещё", Color.White, onAdd)
            }
        } else {
            Box(
                Modifier.size(36.dp).background(Orda.colors.brand.copy(alpha = 0.12f), CircleShape).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить в чек", tint = Orda.colors.brand, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(width = 34.dp, height = 36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/**
 * Полоса чека внизу. Левая часть открывает чек — там позицию убирают и правят
 * количество; справа — «К оплате».
 */
@Composable
private fun CartBar(store: OperatorStore, modifier: Modifier, onOpenCart: () -> Unit, onCheckout: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .background(Orda.colors.surface, RoundedCornerShape(Radius.xl))
            .border(1.dp, Orda.colors.border, RoundedCornerShape(Radius.xl))
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).pressScale().clip(RoundedCornerShape(Radius.md)).clickable(onClick = onOpenCart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(Modifier.size(44.dp).background(Orda.colors.brand.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = Orda.colors.brand)
                Text(
                    "${store.cartCount}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Orda.colors.brand, RoundedCornerShape(50))
                        .padding(horizontal = 5.dp),
                )
            }
            Column {
                AnimatedText(money(store.cartTotal), TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold), Orda.colors.text)
                Text("Открыть чек", color = Orda.colors.brand, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        PrimaryButton("К оплате", Modifier.width(150.dp), onClick = onCheckout)
    }
}

/** Чек перед оплатой: позиции с количеством, убрать одну или все — CartSheet. */
@Composable
private fun CartSheet(store: OperatorStore, onDismiss: () -> Unit, onCheckout: () -> Unit) {
    var confirmingClear by remember { mutableStateOf(false) }
    OrdaSheet("Чек", onDismiss) {
        if (store.cart.isEmpty()) {
            EmptyState(Icons.Filled.ShoppingCart, "Чек пуст", "Добавьте товары из витрины.")
        } else {
            store.cart.forEach { line ->
              key(line.itemId) {
                CartLineSwipe(onRemove = { store.remove(line.itemId) }) {
                Row(Modifier.fillMaxWidth().background(Orda.colors.background), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, color = Orda.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${money(line.unitPrice)} за шт.", color = Orda.colors.textDim, fontSize = 13.sp)
                    }
                    Row(Modifier.background(Orda.colors.surfaceRaised, RoundedCornerShape(50)), verticalAlignment = Alignment.CenterVertically) {
                        StepButton(Icons.Filled.Remove, "Меньше", Orda.colors.text) { store.setQuantity(line.quantity - 1, line.itemId) }
                        Text(quantityText(line.quantity), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp), maxLines = 1)
                        StepButton(Icons.Filled.Add, "Больше", Orda.colors.text) { store.setQuantity(line.quantity + 1, line.itemId) }
                    }
                    Text(money(line.total), color = Orda.colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(76.dp), maxLines = 1)
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Убрать",
                        tint = Orda.colors.negative,
                        modifier = Modifier.size(22.dp).clickable { store.remove(line.itemId) },
                    )
                }
                }
              }
            }
            Text(
                "Очистить чек",
                color = Orda.colors.negative,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable { confirmingClear = true }.padding(vertical = Spacing.sm),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Итого", color = Orda.colors.textDim, fontSize = 13.sp)
                    AnimatedText(money(store.cartTotal), TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), Orda.colors.text)
                }
                PrimaryButton("К оплате", Modifier.width(170.dp), onClick = onCheckout)
            }
        }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Очистить чек?") },
            confirmButton = {
                TextButton(onClick = {
                    store.clearCart()
                    confirmingClear = false
                    onDismiss()
                }) { Text("Убрать все позиции", color = Orda.colors.negative) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Отмена") } },
        )
    }
}

// ── Картинка товара ──────────────────────────────────────────────────────────

private val thumbCache = object : LruCache<String, ImageBitmap>(80) {}

/** Миниатюра товара; нет картинки или не загрузилась — буква названия. */
@Composable
private fun Thumbnail(url: String?, name: String) {
    val image by produceState<ImageBitmap?>(initialValue = url?.let { thumbCache.get(it) }, url) {
        if (url.isNullOrBlank() || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 200) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
                    ?.also { thumbCache.put(url, it) }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(48.dp).clip(shape).background(Orda.colors.surfaceRaised, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(name.take(1).uppercase(), color = Orda.colors.textMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Свайп позиции чека справа налево убирает её; красная плашка с корзиной проявляется под пальцем. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartLineSwipe(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val haptics = rememberHaptics()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                haptics.tick()
                onRemove()
                true
            } else false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(Orda.colors.negative.copy(alpha = 0.18f), RoundedCornerShape(Radius.md)).padding(end = Spacing.lg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = Orda.colors.negative)
            }
        },
    ) { content() }
}
