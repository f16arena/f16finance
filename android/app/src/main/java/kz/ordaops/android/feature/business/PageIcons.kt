package kz.ordaops.android.feature.business

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.LocalDining
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoneyOff
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Значок раздела. Своя иконка у каждого — не украшение: в списке под восемьдесят
 * пунктов её ищут раньше, чем читают текст. Порт `BusinessRootView.icon(forPage:)`.
 */
fun pageIcon(pageId: String): ImageVector = when (pageId) {
    // Деньги
    "income" -> Icons.Filled.ArrowCircleDown
    "expenses" -> Icons.Filled.ArrowCircleUp
    "expense-whitelist" -> Icons.Filled.Verified
    "expenses-pending" -> Icons.Filled.PendingActions
    "expense-analysis" -> Icons.Filled.BarChart
    "categories" -> Icons.Filled.Folder
    "cashflow" -> Icons.Filled.Payments
    "profitability" -> Icons.Filled.PieChart
    "valuation" -> Icons.Filled.Apartment
    "simulation" -> Icons.Filled.Tune
    "tax" -> Icons.Filled.AccountBalance
    "point-debts" -> Icons.Filled.MoneyOff
    "goals" -> Icons.Filled.Flag
    // Отчёты и аналитика
    "reports" -> Icons.Filled.Description
    "analytics" -> Icons.Filled.ShowChart
    "weekly-report" -> Icons.Filled.EventAvailable
    "forecast" -> Icons.Filled.TrendingUp
    "analysis" -> Icons.Filled.Analytics
    "ai-cfo" -> Icons.Filled.Psychology
    "team-analysis" -> Icons.Filled.Groups
    // Склад и магазин
    "store" -> Icons.Filled.Storefront
    "store-warehouse" -> Icons.Filled.Inventory2
    "store-showcase" -> Icons.Filled.ViewCarousel
    "store-catalog" -> Icons.Filled.LibraryBooks
    "store-receipts" -> Icons.Filled.MoveToInbox
    "store-postings" -> Icons.Filled.AddBox
    "store-writeoffs" -> Icons.Filled.Delete
    "store-requests" -> Icons.Filled.Inbox
    "store-requests-journal" -> Icons.Filled.History
    "store-movements" -> Icons.Filled.SwapHoriz
    "store-revisions" -> Icons.Filled.Checklist
    "store-suppliers" -> Icons.Filled.LocalShipping
    "store-billing" -> Icons.AutoMirrored.Filled.ReceiptLong
    "store-purchase-plan" -> Icons.Filled.AddShoppingCart
    "store-purchase-orders" -> Icons.Filled.ShoppingCart
    "store-consumables" -> Icons.Filled.WaterDrop
    "store-analytics" -> Icons.Filled.BarChart
    "store-forecast" -> Icons.Filled.TrendingDown
    "store-advertising" -> Icons.Filled.Campaign
    "store-settings" -> Icons.Filled.SettingsApplications
    "store-shifts" -> Icons.Filled.Schedule
    "store-receipt-settings" -> Icons.Filled.Print
    "production" -> Icons.Filled.LocalDining
    // Люди
    "operators" -> Icons.Filled.Group
    "staff" -> Icons.Filled.Badge
    "hr" -> Icons.Filled.BusinessCenter
    "structure" -> Icons.Filled.AccountTree
    "salary" -> Icons.Filled.AccountBalanceWallet
    "salary-rules" -> Icons.Filled.Rule
    "pass" -> Icons.Filled.Key
    "performance" -> Icons.Filled.Speed
    "ratings" -> Icons.Filled.Star
    "operator-achievements" -> Icons.Filled.EmojiEvents
    "birthdays" -> Icons.Filled.Cake
    // Смены и операционка
    "shifts" -> Icons.Filled.CalendarMonth
    "shifts-reports" -> Icons.Filled.EventNote
    "tasks" -> Icons.Filled.Checklist
    "incidents" -> Icons.Filled.Warning
    // Касса и клиенты
    "pos" -> Icons.Filled.CreditCard
    "pos-receipts" -> Icons.Filled.Receipt
    "pos-returns" -> Icons.AutoMirrored.Filled.Undo
    "customers" -> Icons.Filled.AccountCircle
    "discounts" -> Icons.Filled.LocalOffer
    // Точки и оборудование
    "point-devices" -> Icons.Filled.Computer
    "stations" -> Icons.Filled.SportsEsports
    // Общение
    "news" -> Icons.AutoMirrored.Filled.Article
    "team-chat" -> Icons.Filled.Forum
    "messages" -> Icons.Filled.Email
    "moderation" -> Icons.Filled.Shield
    "telegram" -> Icons.AutoMirrored.Filled.Send
    // Системное
    "access" -> Icons.Filled.AdminPanelSettings
    "settings" -> Icons.Filled.Settings
    "logs" -> Icons.AutoMirrored.Filled.ListAlt
    "debug" -> Icons.Filled.BugReport
    "knowledge-admin" -> Icons.AutoMirrored.Filled.MenuBook
    "dashboard" -> Icons.Filled.GridView
    // Новые страницы каталога, для которых значок ещё не подобрали.
    else -> if (pageId.startsWith("store")) Icons.Filled.Inventory else Icons.Filled.Description
}

/**
 * Цвет группы разделов — одна и та же иконка всегда одного цвета, по нему раздел
 * находят быстрее, чем по названию. Спокойная палитра: кобальт, бирюза, янтарь,
 * индиго, небесный и графит.
 */
object OwnerTint {
    fun forGroup(groupId: String): Color = when (groupId) {
        "finance" -> Color(0xFF2563EB)
        "inventory" -> Color(0xFFD97706)
        "shifts" -> Color(0xFF4F46E5)
        "staff" -> Color(0xFF0D9488)
        "points" -> Color(0xFF0284C7)
        "pos" -> Color(0xFF7C3AED)
        "operations" -> Color(0xFFEA580C)
        "system" -> Color(0xFF475569)
        else -> Color(0xFF0284C7)
    }

    /** Точки красятся по кругу — у каждой свой цвет в списке. */
    private val points = listOf(
        Color(0xFF2563EB), Color(0xFF0D9488), Color(0xFFD97706),
        Color(0xFF4F46E5), Color(0xFF0284C7), Color(0xFF475569),
    )

    fun point(index: Int): Color = points[index % points.size]
}
