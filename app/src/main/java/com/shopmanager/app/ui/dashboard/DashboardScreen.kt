package com.shopmanager.app.ui.dashboard

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopmanager.app.data.materials.quantityLabel
import com.shopmanager.app.ui.common.AnimatedCounterText
import com.shopmanager.app.ui.common.AppSettingsState
import com.shopmanager.app.ui.common.LocalFloatingBottomNavHeight
import com.shopmanager.app.ui.common.sheenSweep
import com.shopmanager.app.ui.common.MotionSpecs
import com.shopmanager.app.ui.common.PullToRefreshContent
import com.shopmanager.app.ui.common.avatarColorFor
import com.shopmanager.app.ui.common.liquidGlassSurface
import com.shopmanager.app.ui.debts.DebtsViewModel
import com.shopmanager.app.ui.materials.MaterialsViewModel
import com.shopmanager.app.ui.theme.LocalSemanticColors
import java.text.SimpleDateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.shopmanager.app.ui.common.AppCard
import com.shopmanager.app.ui.common.AppGroupGap
import com.shopmanager.app.ui.common.AppRowSurface
import com.shopmanager.app.ui.common.AppScreenPadding
import com.shopmanager.app.ui.common.AppSectionGap
import com.shopmanager.app.ui.common.groupedTileShape
import com.shopmanager.app.ui.common.AppGroupLargeRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator

private data class ActivityRow(
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val subtitle: String,
    val timestamp: Long
)

/**
 * REDESIGN ("تصميم صباح الخير أكثر تناسق مع الواجهة"): this used to embed a
 * raw Unicode emoji (☀️🌙👋🌇) straight inside the greeting string. Every
 * other glyph in this exact header — the settings gear, the admin shield —
 * is a monochrome Material icon tinted to match the glass panel; an emoji
 * next to them renders as a small colorful, OS-drawn picture that doesn't
 * belong to that same visual language at all (and looks different
 * device-to-device/OS-to-OS, unlike a vector icon). [TimeGreeting] now
 * pairs the text with a matching outline icon instead, drawn the same
 * tinted-white way as the rest of the header (see its usage in
 * [DashboardHeader]) — so it reads as one cohesive design instead of an
 * app icon font colliding with an emoji font.
 */
private data class TimeGreeting(val text: String, val icon: ImageVector)

private fun timeBasedGreeting(): TimeGreeting {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        hour < 5 -> TimeGreeting("سهرانين لهلق؟", Icons.Filled.NightsStay)
        hour < 12 -> TimeGreeting("صباح الخير", Icons.Filled.WbSunny)
        hour < 17 -> TimeGreeting("أهلاً بك", Icons.Filled.WavingHand)
        hour < 21 -> TimeGreeting("مساء الخير", Icons.Filled.WbTwilight)
        else -> TimeGreeting("مساء النور", Icons.Filled.Bedtime)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    debtsViewModel: DebtsViewModel,
    materialsViewModel: MaterialsViewModel,
    onNavigateToDebts: () -> Unit = {},
    onNavigateToMaterials: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val debtsState by debtsViewModel.uiState.collectAsStateWithLifecycle()
    val materialsState by materialsViewModel.uiState.collectAsStateWithLifecycle()
    val debtsRefreshing by debtsViewModel.isRefreshing.collectAsStateWithLifecycle()
    val materialsRefreshing by materialsViewModel.isRefreshing.collectAsStateWithLifecycle()
    val nf = remember { NumberFormat.getNumberInstance(Locale("ar")) }
    // 12-hour clock (was HH:mm/24h) — "a" renders as ص/م in Arabic locale.
    val df = remember { SimpleDateFormat("d MMM، h:mm a", Locale("ar")) }

    // Every material in the list is, by definition, a shortage the shop
    // needs to buy - it's a live shopping list, not a stock count.
    val shortages = remember(materialsState.materials) { materialsState.materials }
    // UNIFIED ON CLAUDE'S DESIGN: this used to be a fixed, theme-independent
    // generic amber (WarningAmber, #F59E0B) — not Claude's own warning tone
    // in either theme. LocalSemanticColors.current.warning already resolves
    // to Claude's own gold accent (ClaudeAccentGoldLight/Dark, correctly
    // matching each theme — see semanticColorsFor in Palette.kt), so the
    // market-shortage badges below now match every other warning-colored
    // element in the app.
    val marketAccent = LocalSemanticColors.current.warning
    val topDebtors = remember(debtsState.persons) {
        debtsState.persons.filter { it.amount > 0 }.sortedByDescending { it.amount }.take(5)
    }
    val isLoading = debtsState.isLoading || materialsState.isLoading

    val recentActivity = remember(debtsState.debts, debtsState.persons, materialsState.materials) {
        val personsById = debtsState.persons.associateBy { it.id }
        val debtRows = debtsState.debts.map { debt ->
            val personName = personsById[debt.personId]?.name ?: "عميل"
            ActivityRow(
                icon = Icons.Default.AttachMoney,
                color = avatarColorFor(personName),
                title = personName,
                subtitle = "دين جديد: ${nf.format(debt.amount)} ${AppSettingsState.currencySymbol}",
                timestamp = debt.createdAt
            )
        }
        val materialRows = materialsState.materials.map { m ->
            ActivityRow(
                icon = Icons.Default.Spa,
                color = avatarColorFor(m.name),
                title = m.name,
                subtitle = "نقص مضاف: ${m.quantityLabel()}",
                timestamp = m.updatedAt
            )
        }
        (debtRows + materialRows).sortedByDescending { it.timestamp }.take(6)
    }

    Scaffold(
        // Edge-to-edge: the status bar is transparent (see
        // SetSystemBarsColor/MainActivity) and DashboardHeader below draws
        // its own liquid-glass panel all the way up to the true top of the
        // window, padding its *content* down manually — so this Scaffold
        // must not reserve top space itself, or the header would be pushed
        // down a second time, leaving a plain gap above it instead of one
        // continuous glass surface. Bottom/horizontal safe-area insets
        // (gesture nav bar, cutouts) are kept as-is.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    ) { padding ->
        PullToRefreshContent(
            isRefreshing = debtsRefreshing || materialsRefreshing,
            onRefresh = { debtsViewModel.refresh(); materialsViewModel.refresh() },
            modifier = Modifier.padding(padding)
        ) {
        // BUG FIXED ("آخر عنصر بالقائمة بيصير تحت الشريط السفلي"): same as
        // the other pager tabs — the floating nav pill floats over this
        // screen too, so the last card needs to clear the pill's real
        // measured height, not just a flat gap. The flat gap on top of that
        // was only 16.dp, noticeably thinner than every other tab's own
        // clearance (Debts/Materials/Notes all reserve 32.dp+ beyond the
        // pill) — on the one tab with the longest list (the shortage/market
        // list below, which can run past a screen's worth of rows), that
        // thinner gap was exactly what let the last row or two sit close
        // enough to peek out from behind the pill's transparent side
        // margins instead of clearing it with a visible gap like everywhere
        // else. Matched to the same 32.dp the other tabs use.
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + LocalFloatingBottomNavHeight.current),
            verticalArrangement = Arrangement.spacedBy(AppSectionGap)
        ) {
            item {
                DashboardHeader(onOpenDrawer = onOpenDrawer)
            }

            // REDESIGN ("اعد ترتيب الشاشة الرئيسية بشكل كامل وجميل"): the
            // two flat StatCards used to sit in their own row *below* the
            // quick actions — two small, same-weight numbers competing
            // with everything else on the screen for attention despite
            // being the most important thing on this whole tab. They're
            // now a single merged, gradient-tinted hero card right under
            // the greeting (see HeroStatsCard below) — the first thing
            // the eye lands on — with quick actions moved underneath it,
            // since "what do I owe/need" outranks "add something new" as
            // the opening beat of this screen.
            item {
                HeroStatsCard(
                    totalDebt = debtsState.totalAmount,
                    totalPersons = debtsState.totalPersons,
                    shortagesCount = shortages.size,
                    debtsLoading = debtsState.isLoading,
                    materialsLoading = materialsState.isLoading,
                    hasShortages = shortages.isNotEmpty(),
                    marketAccent = marketAccent,
                    topDebtorName = topDebtors.firstOrNull()?.name,
                    nf = nf
                )
            }

            item {
                QuickActionsRow(
                    onAddPerson = onNavigateToDebts,
                    onAddMaterial = onNavigateToMaterials
                )
            }

            if (isLoading && debtsState.persons.isEmpty() && materialsState.materials.isEmpty()) {
                item {
                    // هيكل بطاقة بدل الدائرة الدوّارة، بنفس شكل بطاقات اللوحة الرئيسية.
                    com.shopmanager.app.ui.common.SkeletonCard(lines = 3)
                }
            }

            // أكبر الديون: صار فوق المشتريات (مرتبط مباشرة ببطاقة الإجمالي)، ومع كل عميل
            // شريط نسبي يوضّح حجم دينه مقارنةً بأكبر دين.
            if (topDebtors.isNotEmpty()) {
                item {
                    SectionCard(title = "أكبر الديون", icon = Icons.Default.Groups) {
                        val maxAmount = topDebtors.maxOf { it.amount }.coerceAtLeast(1.0)
                        topDebtors.forEachIndexed { index, p ->
                            val barColor = avatarColorFor(p.name)
                            Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(32.dp).clip(CircleShape).background(barColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            p.name.firstOrNull()?.uppercase() ?: "?",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        p.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${nf.format(p.amount)} ${AppSettingsState.currencySymbol}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(Modifier.height(7.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((p.amount / maxAmount).toFloat().coerceIn(0.04f, 1f))
                                            .fillMaxHeight()
                                            .clip(CircleShape)
                                            .background(barColor)
                                    )
                                }
                            }
                            if (index != topDebtors.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            }
                        }
                    }
                }
            }

            if (shortages.isNotEmpty()) {
                item {
                    // BUG FIXED ("قائمة المشتريات شكلها مو مرتب"): a bare
                    // stack of name/quantity Rows with no separator between
                    // them read as one dense, undifferentiated block of
                    // text next to "آخر النشاطات" right below it (which has
                    // a proper icon, two-line text, and its own row
                    // height) — noticeably plainer than everywhere else in
                    // the app once this list runs past a few items. A thin
                    // divider between rows (not after the last one) gives
                    // each item its own visual line without adding a full
                    // bordered card per row, and the extra vertical padding
                    // gives every row a bit more room to breathe.
                    SectionCard(title = "قائمة مشتريات السوق", color = marketAccent, icon = Icons.Default.ShoppingCart) {
                        shortages.forEachIndexed { index, m ->
                            // REDESIGN (reference screenshot: each shortage
                            // row carries its own small colored icon circle
                            // on the lead side and a rounded, color-coded
                            // quantity badge on the trailing side, instead
                            // of two bare Text() values) — mirrors the same
                            // icon-circle language "آخر النشاطات" already
                            // uses below, so every row in this card family
                            // reads as one consistent pattern.
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(marketAccent.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Spa, contentDescription = null, tint = marketAccent, modifier = Modifier.size(15.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    m.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                com.shopmanager.app.ui.common.PillBadge(
                                    text = m.quantityLabel(),
                                    color = com.shopmanager.app.ui.common.pillColorForQuantity(m.quantity)
                                )
                            }
                            if (index != shortages.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            }
                        }
                    }
                }
            }

            if (recentActivity.isNotEmpty()) {
                item {
                    SectionCard(title = "آخر النشاطات", icon = Icons.Default.History) {
                        recentActivity.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(32.dp).clip(MaterialTheme.shapes.small).background(row.color),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(row.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        row.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                // BUG FIXED: this timestamp had no fixed
                                // width and no line/overflow limit, so a
                                // long title next to it (see row.title
                                // above) used to squeeze it and could wrap
                                // the time/date onto two lines, breaking
                                // this row's height versus every other row.
                                if (row.timestamp > 0) {
                                    Text(
                                        df.format(Date(row.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (debtsState.persons.isEmpty() && materialsState.materials.isEmpty() && !debtsState.isLoading && !materialsState.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Storefront, contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outlineVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "ابدأ بإضافة عملاء أو مواد من التبويبات بالأسفل",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

// REDESIGN ("افصل الشاشة الرئيسية عن بقية الشاشات" + "البار العلوي متل
// Claude AI"): every other tab (Debts/Materials/Notes) opens with the same
// boxed liquid-glass panel — a title sitting on a brand-gradient card with
// rounded bottom corners. Home used to share that exact same treatment
// ("إدارة المحل" on its own gradient card), which is exactly why it read as
// just one more tab instead of the app's actual home screen, and why the
// floating hamburger circle that used to sit on top of it visually
// collided with that card's own title text in the same top-right corner.
// Claude's own home screen ("Back at it, S") never puts its greeting on a
// boxed card at all — just a transparent top bar (a plain menu icon, no
// title) with the greeting sitting directly on the app's background right
// below it. This header now follows that same split: a slim, fully
// transparent row for the hamburger (a real layout element, not a floating
// overlay — see MainActivity, which no longer draws one at all), then the
// greeting + "إدارة المحل" underneath, also directly on the plain
// background. Nothing here can overlap the hamburger anymore because the
// hamburger now *is* part of this layout instead of a separate layer
// floating on top of it, and Home now visibly stands apart from the boxed-
// card look every other tab keeps.
//
// MOVED ("انقل ايقونة المسؤول الى المنيو الى جانب الاعدادات"): لوحة
// المسؤول's trigger button used to sit at the trailing end of this exact
// top bar. It now lives in the side drawer next to الإعدادات instead (see
// AppDrawerContent/MainActivity), so this header goes back to being just
// the hamburger — nothing trailing it anymore.
@Composable
private fun DashboardHeader(
    onOpenDrawer: () -> Unit = {}
) {
    // BUG FIXED ("صباح الخير" عالقة طول اليوم): `remember { timeBasedGreeting() }`
    // كان يُحسب مرة واحدة بس، أول ما هالهيدر يدخل التركيب — وبما إن تبويب
    // الرئيسية (صفحة بالـ HorizontalPager) يضل حي طول عمر التطبيق (ما
    // يُعاد إنشاؤه لما تبدّل تبويب)، فتح التطبيق الصبح وتركه شغّال للعصر/
    // المسا كان يخلي الترحيب عالق عالنص "صباح الخير" دايمًا، بدون ما
    // يتحدّث أبدًا. هلق بيتحقق من الوقت كل دقيقة عبر LaunchedEffect، فينتقل
    // فعليًا بين "صباح الخير"/"مساء الخير"/إلخ مع مرور اليوم، وبيرجع
    // يعكس الوقت الصحيح فورًا كل ما الهيدر يدخل التركيب من جديد (فتح
    // التطبيق من الصفر بعد إغلاقه بالكامل).
    var greeting by remember { mutableStateOf(timeBasedGreeting()) }
    val dateFormat = remember { SimpleDateFormat("EEEE، d MMMM", Locale("ar")) }
    var dateText by remember { mutableStateOf(dateFormat.format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            greeting = timeBasedGreeting()
            dateText = dateFormat.format(Date())
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            // No liquidGlassSurface here on purpose (see the class-level
            // REDESIGN note above) — Home sits directly on the app's plain
            // background, unlike the boxed gradient card every other tab's
            // header uses. This only clears the transparent status bar.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // "البار العلوي" (top bar): a slim, transparent row with just the
        // hamburger on the leading edge — laid out inline like Claude's
        // own top bar, never floating over anything below it. Settings and
        // لوحة المسؤول both now live one tap away in the drawer instead of
        // duplicating an entry point up here.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "القائمة",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.weight(1f))
            // تاريخ اليوم — شارة صغيرة بنفس لون بطاقات التطبيق.
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    dateText,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Greeting + title sit directly on the plain background, right
        // below the transparent top bar — matching Claude's own "Back at
        // it, S" home greeting instead of a boxed gradient card.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                greeting.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                greeting.text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "إدارة المحل",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 27.sp, lineHeight = 33.sp),
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun QuickActionsRow(onAddPerson: () -> Unit, onAddMaterial: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AppScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        QuickActionButton(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.PersonAdd,
            label = "عميل جديد",
            onClick = onAddPerson
        )
        QuickActionButton(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Inventory2,
            label = "مادة جديدة",
            onClick = onAddMaterial
        )
    }
}

@Composable
private fun QuickActionButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    AppRowSurface(shape = RoundedCornerShape(AppGroupLargeRadius), modifier = modifier, onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * REDESIGN ("اعد تصميم الشاشة الرئيسية"): بطاقتان صغيرتان متساويتان صارتا بطاقة
 * واحدة كبيرة: إجمالي الديون برقم كبير في الأعلى (أهم رقم بالشاشة)، وتحته
 * إحصاءان مختصران (النواقص + أكبر عميل مدين) مفصولان بخط رفيع.
 */
@Composable
private fun HeroStatsCard(
    totalDebt: Double,
    totalPersons: Int,
    shortagesCount: Int,
    debtsLoading: Boolean,
    materialsLoading: Boolean,
    hasShortages: Boolean,
    marketAccent: Color,
    topDebtorName: String?,
    nf: NumberFormat
) {
    val success = LocalSemanticColors.current.success
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard(modifier = Modifier.padding(horizontal = AppScreenPadding)) {
        // الأداء القوي: لمعة ضوء ناعمة تمسح البطاقة الرئيسية (sheenSweep لا تفعل شيئاً في غيره).
        Column(Modifier.sheenSweep().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(success.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AttachMoney, contentDescription = null, tint = success, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "إجمالي الديون",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = onSurfaceVariant
                )
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) {
                    Text(
                        "$totalPersons عميل",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            AnimatedCounterText(
                targetValue = totalDebt,
                format = { "${nf.format(it)} ${AppSettingsState.currencySymbol}" },
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 40.sp),
                animate = !debtsLoading
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                HeroMiniStat(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Inventory2,
                    accentColor = if (hasShortages) marketAccent else MaterialTheme.colorScheme.secondary,
                    title = "قائمة النواقص",
                    valueContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnimatedCounterText(
                                targetValue = shortagesCount.toDouble(),
                                format = { it.toInt().toString() },
                                style = MaterialTheme.typography.titleMedium,
                                animate = !materialsLoading
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (hasShortages) "بانتظار الشراء" else "لا يوجد",
                                style = MaterialTheme.typography.labelSmall,
                                color = onSurfaceVariant.copy(alpha = 0.85f),
                                maxLines = 1
                            )
                        }
                    }
                )
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                )
                HeroMiniStat(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Groups,
                    accentColor = MaterialTheme.colorScheme.primary,
                    title = "أكبر مدين",
                    valueContent = {
                        Text(
                            topDebtorName ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun HeroMiniStat(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    accentColor: Color,
    title: String,
    valueContent: @Composable () -> Unit
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            valueContent()
        }
    }
}

// REDESIGN ("اعد تصميم الالوان في كل التطبيق" + fixed unused `icon` param):
// same borderless `surfaceContainer` treatment as StatCard above, for the
// same reason — one card language shared with Settings instead of a
// hairline-outlined one just on Home. `icon` used to be accepted but never
// actually drawn anywhere in this composable, so every call site that
// passed one (or the leading emoji baked into a couple of titles, e.g.
// "🛒 قائمة مشتريات السوق") was really just decorating the title string by
// hand. Now rendered as a real leading glyph — plain, tinted with `color`,
// same size/spacing language as SettingsSection's own header row — so
// every call site can pass a proper Material icon instead of an emoji.
@Composable
private fun SectionCard(
    title: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = AppScreenPadding)) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        AppCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content)
        }
    }
}
