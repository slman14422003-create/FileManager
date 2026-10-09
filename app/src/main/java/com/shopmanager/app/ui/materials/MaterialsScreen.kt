package com.shopmanager.app.ui.materials

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import com.shopmanager.app.data.performance.LocalPerformanceTier
import com.shopmanager.app.data.performance.PerformanceTier
import com.shopmanager.app.ui.common.listItemEntrance
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopmanager.app.data.materials.Material
import com.shopmanager.app.data.materials.MaterialCatalogItem
import com.shopmanager.app.data.materials.MaterialsWeightSummary
import com.shopmanager.app.data.materials.MaterialsPriceSummary
import com.shopmanager.app.data.materials.quantityLabel
import com.shopmanager.app.data.materials.weightSummary
import com.shopmanager.app.data.materials.linePrice
import com.shopmanager.app.data.materials.priceOf
import com.shopmanager.app.data.materials.priceSummary
import androidx.compose.ui.text.input.KeyboardType
import com.shopmanager.app.ui.common.AnimatedCounterText
import androidx.compose.animation.animateContentSize
import com.shopmanager.app.ui.common.AppSearchBar
import com.shopmanager.app.ui.common.AppSettingsState
import com.shopmanager.app.ui.common.DeleteIconButton
import com.shopmanager.app.ui.common.Formatters
import com.shopmanager.app.ui.common.GlassSnackbarHost
import com.shopmanager.app.ui.common.GradientIconButton
import com.shopmanager.app.ui.common.MotionSpecs
import com.shopmanager.app.ui.common.PullToRefreshContent
import com.shopmanager.app.ui.common.avatarColorFor
import com.shopmanager.app.ui.common.BrandOnGradient
import com.shopmanager.app.ui.common.LocalFloatingBottomNavHeight
import com.shopmanager.app.ui.common.ShareFormatDialog
import com.shopmanager.app.ui.common.GlassAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shopmanager.app.ui.theme.LocalBrandGradientColors
import com.shopmanager.app.ui.theme.LocalSemanticColors
import com.shopmanager.app.ui.theme.glassHairlineColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import com.shopmanager.app.ui.common.ActionIconButton
import com.shopmanager.app.ui.common.AppCard
import com.shopmanager.app.ui.common.AppEmptyState
import com.shopmanager.app.ui.common.AppFootnote
import com.shopmanager.app.ui.common.AppGroupGap
import com.shopmanager.app.ui.common.AppIconCircle
import com.shopmanager.app.ui.common.AppPillButton
import com.shopmanager.app.ui.common.AppRowSurface
import com.shopmanager.app.ui.common.AppScreenPadding
import com.shopmanager.app.ui.common.AppSectionTitle
import com.shopmanager.app.ui.common.ScreenIconButton
import com.shopmanager.app.ui.common.ScreenTopBar
import com.shopmanager.app.ui.common.groupedRowShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import com.shopmanager.app.ui.common.ActionSpec
import com.shopmanager.app.ui.common.AppActionPill
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialsScreen(
    viewModel: MaterialsViewModel = viewModel(),
    onAddNew: () -> Unit = {},
    // "دمج زر حفظ الأسعار مع الشريط السفلي": these four let MainActivity
    // show/drive a "حفظ الأسعار" action beside the floating nav pill
    // (FloatingBottomNav's `secondaryAction`, opposite side from the
    // existing "+" — see MainActivity) instead of this screen drawing its
    // own full-width save button. Same request/handled pattern already
    // used for "عميل جديد" (see DebtsScreen's addPersonRequested): tapping
    // the pill's button just raises `savePricesRequested`, this screen
    // watches it and performs the actual save, then reports it handled.
    onPricesTabActiveChanged: (Boolean) -> Unit = {},
    onPricesChangedCountChanged: (Int) -> Unit = {},
    savePricesRequested: Boolean = false,
    onSavePricesRequestHandled: () -> Unit = {},
    // BUG FIXED ("ترابط بين الديون والملاحظات" كان يغطي الأشخاص فقط): see
    // MainActivity's pendingMaterialHighlight and NotesScreen's onOpenLink —
    // a material-linked note now hands its linked material's name through
    // here instead of just opening this screen with an empty search, so
    // tapping the note lands directly on that one item instead of the full
    // list.
    initialSearchQuery: String? = null,
    onInitialSearchConsumed: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    // REDESIGN ("شريط البحث لازم يكون زر في الشريط العلوي يتوسع بواجهة لحالة
    // اثناء البحث"): the search field used to sit permanently above the
    // list; it now lives as a small icon button in MaterialsHeader that
    // expands the header's own title row into a focused search field when
    // tapped, instead of taking up its own row on the page at all times.
    var isSearching by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var editingMaterial by remember { mutableStateOf<Material?>(null) }
    var deleteTarget by remember { mutableStateOf<Material?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showShareChoice by remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val brandColor = LocalBrandGradientColors.current.first().toArgb()

    // Consumed once (onInitialSearchConsumed clears it back to null in
    // MainActivity) so navigating away and back to this tab later, or a
    // recomposition for an unrelated reason, doesn't keep re-forcing the
    // search box back to this value if the person has since cleared it
    // themselves. Also lands on the main list tab (0), not الأسعار — a
    // material-linked note is always about the shortage-list entry, never
    // its price.
    LaunchedEffect(initialSearchQuery) {
        if (!initialSearchQuery.isNullOrBlank()) {
            search = initialSearchQuery
            tab = 0
            onInitialSearchConsumed()
        }
    }

    // REDESIGN: the "مادة جديدة" quick-add action moved out to
    // FloatingBottomNav's shared `quickAction` slot beside the nav pill
    // (wired up in MainActivity), so this screen no longer needs its own
    // FAB or a clearance value just for it.

    LaunchedEffect(message) {
        message?.let { snackbarHost.showSnackbar(it); viewModel.clearMessage() }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) searchFocusRequester.requestFocus()
    }

    // PERF: this used to re-run the .filter{} scan over the whole
    // materials list on *every* recomposition of this screen — including
    // ones triggered by completely unrelated state (a dialog opening, the
    // snackbar message clearing, etc.), not just an actual `search` or
    // `state.materials` change. `remember` keyed on the two inputs this
    // computation actually depends on makes it skip that rescan unless
    // one of them genuinely changed.
    val filtered = remember(search, state.materials) {
        if (search.isBlank()) state.materials
        else state.materials.filter { it.name.contains(search, ignoreCase = true) }
    }

    // MOVED UP from PricesList: this in-progress price-edits buffer used to
    // live entirely inside PricesList, private to that tab, since only its
    // own full-width save button ever read it. Now that saving is
    // triggered externally (from the floating pill's merged button — see
    // `savePricesRequested` above), this screen needs to reach the same
    // buffer to actually perform the save, so it's hoisted here and passed
    // down to PricesList instead of PricesList creating its own.
    val editedPrices = remember(catalog) { mutableStateMapOf<String, String>() }
    // PERF FIX (تقطيع أثناء كتابة السعر): كان العدّ يتم هنا مباشرة داخل جسم الشاشة،
    // فكل حرف يُكتب بأي حقل سعر (كتابة في editedPrices) كان يعيد تركيب الشاشة كلها
    // (الهيدر + التبويبات + القائمة). الآن القراءة داخل snapshotFlow فقط، فلا يُعاد
    // تركيب الشاشة إلا إذا تغيّر الرقم فعلاً، ويُبلَّغ به مرة واحدة عند كل تغيّر.
    val latestPrices by rememberUpdatedState(state.prices)
    val latestOnChangedCount by rememberUpdatedState(onPricesChangedCountChanged)
    LaunchedEffect(editedPrices) {
        snapshotFlow {
            editedPrices.count { (name, value) ->
                val parsed = value.toPriceOrNull()
                parsed != null && parsed != latestPrices[name]
            }
        }.distinctUntilChanged().collect { latestOnChangedCount(it) }
    }
    LaunchedEffect(tab) { onPricesTabActiveChanged(tab == 1) }
    LaunchedEffect(savePricesRequested) {
        if (savePricesRequested) {
            val toSave = editedPrices.mapNotNull { (name, value) -> value.toPriceOrNull()?.let { name to it } }.toMap()
            viewModel.setPrices(toSave)
            editedPrices.clear()
            onSavePricesRequestHandled()
        }
    }

    Scaffold(
        // Edge-to-edge: the status bar is transparent (see
        // SetSystemBarsColor/MainActivity) and this screen's own
        // MaterialsHeader below draws its liquid-glass panel all the way
        // up to the true top of the window and pads its *content* down
        // manually — so this Scaffold must not also reserve top space
        // itself, or the header would get pushed down a second time
        // leaving a plain gap above it. Bottom/horizontal safe-area insets
        // (gesture nav bar, cutouts) are kept as-is.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        snackbarHost = { GlassSnackbarHost(snackbarHost) },
        topBar = {
            MaterialsHeader(
                tab = tab,
                onTabChange = { tab = it },
                showClearAll = tab == 0 && state.materials.isNotEmpty(),
                onClearAll = { showClearAllConfirm = true },
                onShare = { showShareChoice = true },
                onOpenDrawer = onOpenDrawer,
                isSearching = isSearching,
                onSearchToggle = { isSearching = it },
                searchQuery = search,
                onSearchQueryChange = { search = it },
                searchFocusRequester = searchFocusRequester
            )
        }
        // REDESIGN: no `floatingActionButton` slot here anymore — "مادة
        // جديدة" now lives beside the floating nav pill (see
        // FloatingBottomNav's quickAction, wired up in MainActivity) so it
        // reads as one attached unit with the pill instead of a separate
        // button floating off on its own over the list.
    ) { padding ->
        PullToRefreshContent(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding)
        ) {
        // الأجهزة القوية: التبديل بين المواد/الأسعار ينزلق أفقياً بمسافة صغيرة مع تلاشٍ
        // بدل تلاشٍ فقط؛ الأجهزة الضعيفة تبقى على تلاشٍ سريع جداً بلا انزلاق.
        val strongDevice = LocalPerformanceTier.current != PerformanceTier.LOW
        val tabFadeMs = MotionSpecs.contentTween<Float>()
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (strongDevice) {
                    val dir = if (targetState > initialState) -1 else 1
                    (fadeIn(tabFadeMs) + slideInHorizontally(
                        animationSpec = tween<IntOffset>(260, easing = MotionSpecs.claudeEasing)
                    ) { full -> dir * full / 10 }) togetherWith fadeOut(tween(120, easing = MotionSpecs.claudeEasing))
                } else {
                    fadeIn(tween(90)) togetherWith fadeOut(tween(60))
                }
            },
            label = "materialsTab"
        ) { selectedTab ->
            if (selectedTab == 0) {
                // Kept deliberately minimal: just the search field above the
                // list, and materials below it - no extra banners competing
                // for attention. The "مادة جديدة" action lives only in the
                // floating button at the bottom of the screen.
                // REDESIGN ("زر اضافة مادة جديدة يجب ان يكون زر عريض مكان
                // شريط البحث للي شلته"): the permanent search field that
                // used to sit here moved into MaterialsHeader's own search
                // icon (see topBar above); this space is now a wide,
                // prominent "مادة جديدة" button instead, using the
                // `onAddNew` callback MainActivity already wires to the
                // catalog-picker screen.
                Column(Modifier.fillMaxSize()) {
                    // زر الإضافة نحيف (44dp بدل 52dp)، أما بطاقة الملخّص وعنوان
                    // القائمة فصارا أول عناصر القائمة نفسها (انظر MaterialsList) فيرتفعان
                    // مع التمرير وتأخذ المواد كامل الشاشة بدل أن تُدفع للأسفل.
                    AppPillButton(
                        label = "مادة جديدة",
                        icon = Icons.Default.Add,
                        onClick = onAddNew,
                        height = 44.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = AppScreenPadding, end = AppScreenPadding, top = 2.dp, bottom = 8.dp)
                    )
                    if (state.isLoading && filtered.isEmpty()) {
                        com.shopmanager.app.ui.common.SkeletonRowList(
                            kind = com.shopmanager.app.ui.common.SkeletonRowKind.MATERIAL,
                            count = 7
                        )
                    } else MaterialsList(
                        materials = filtered,
                        prices = state.prices,
                        animateSummary = !state.isLoading,
                        searching = search.isNotBlank(),
                        onClearAll = { showClearAllConfirm = true },
                        onEdit = { editingMaterial = it },
                        onDelete = { deleteTarget = it },
                        onToggleImportant = { viewModel.setImportant(it, !it.important) },
                        onReordered = { viewModel.reorderMaterials(it.map(Material::id)) }
                    )
                }
            } else {
                PricesList(catalogItems = catalog, materials = state.materials, prices = state.prices, edited = editedPrices, search = search)
            }
        }
        }
    }

    editingMaterial?.let { m ->
        var isSavingMaterial by remember { mutableStateOf(false) }
        MaterialEditDialog(
            initial = m,
            isSaving = isSavingMaterial,
            onDismiss = { if (!isSavingMaterial) editingMaterial = null },
            onSave = { name, qty, unit, notes ->
                isSavingMaterial = true
                viewModel.updateMaterial(m.id, name, qty, unit, notes) { success ->
                    isSavingMaterial = false
                    if (success) editingMaterial = null
                }
            }
        )
    }

    deleteTarget?.let { m ->
        GlassAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد من حذف \"${m.name}\"؟") },
            confirmButton = {
                // BUG FIXED ("بدي الضغطة بشكل مربع كامل وليس دائري"): see
                // PersonEditDialog.kt's doc comment.
                TextButton(shape = RectangleShape, onClick = { viewModel.deleteMaterial(m.id); deleteTarget = null }) { Text("حذف") }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { deleteTarget = null }) { Text("إلغاء") } }
        )
    }

    if (showShareChoice) {
        ShareFormatDialog(
            onDismiss = { showShareChoice = false },
            onPickImage = {
                // PERF: bitmap/Canvas drawing moved off the main thread —
                // with a long materials list this Canvas work is real,
                // measurable CPU time, and running it straight in the
                // onClick previously blocked the UI thread and dropped
                // frames right as the share sheet was trying to animate
                // in. Generation runs on Dispatchers.Default; only the
                // final startActivity hop is back on Main.
                scope.launch {
                    val uri = withContext(Dispatchers.Default) {
                        MaterialsReportImage.generate(context, state.materials, state.prices, brandColor)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "مشاركة قائمة المواد"))
                }
            },
            onPickText = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, buildMaterialsShareText(state.materials, state.prices))
                }
                context.startActivity(Intent.createChooser(intent, "مشاركة قائمة المواد"))
            }
        )
    }

    if (showClearAllConfirm) {
        GlassAlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("مسح كل المواد") },
            text = { Text("هل أنت متأكد من حذف كل المواد المضافة (${state.materials.size})؟ لا يمكن التراجع عن هذا الإجراء.") },
            confirmButton = {
                TextButton(shape = RectangleShape, onClick = {
                    viewModel.deleteAllMaterials()
                    showClearAllConfirm = false
                }) { Text("حذف الكل") }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { showClearAllConfirm = false }) { Text("إلغاء") } }
        )
    }
}

/**
 * Large-title header matching [com.shopmanager.app.ui.dashboard.DashboardScreen]'s
 * new look: bold oversized title + opaque circular share button on a flat
 * brand-gradient panel with rounded bottom corners, and a pill-shaped
 * segmented control for the المواد/الأسعار tabs instead of Material's
 * underlined [TabRow] - the underline style read as mismatched sitting
 * right below a solid gradient block. The whole thing is one continuous
 * panel so title, actions and tabs read as one cohesive header instead of
 * two stacked bars.
 */
@Composable
private fun MaterialsHeader(
    tab: Int,
    onTabChange: (Int) -> Unit,
    showClearAll: Boolean,
    onClearAll: () -> Unit,
    onShare: () -> Unit,
    onOpenDrawer: () -> Unit = {},
    isSearching: Boolean = false,
    onSearchToggle: (Boolean) -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchFocusRequester: FocusRequester? = null
) {
    Column(Modifier.fillMaxWidth()) {
        if (isSearching) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(64.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScreenIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "إغلاق البحث",
                    onClick = { onSearchToggle(false); onSearchQueryChange("") }
                )
                AppSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    onClose = { onSearchToggle(false); onSearchQueryChange("") },
                    placeholder = if (tab == 0) "بحث عن مادة..." else "بحث عن الأسعار...",
                    focusRequester = searchFocusRequester,
                    showBackButton = false,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
            }
        } else {
            ScreenTopBar(
                title = "المواد والأسعار",
                reservedIcons = 2,
                navigation = {
                    ScreenIconButton(
                        icon = Icons.Default.Menu,
                        contentDescription = "القائمة",
                        onClick = onOpenDrawer
                    )
                },
                actions = {
                    AppActionPill(
                        height = 42.dp,
                        container = MaterialTheme.colorScheme.surfaceContainerHigh,
                        actions = listOf(
                            ActionSpec(Icons.Default.Search, "بحث") { onSearchToggle(true) },
                            ActionSpec(Icons.Rounded.Share, "مشاركة") { onShare() }
                        )
                    )
                }
            )
        }
        Box(Modifier.padding(start = AppScreenPadding, end = AppScreenPadding, bottom = 6.dp)) {
            SegmentedTabs(
                selectedIndex = tab,
                options = listOf(
                    SegmentOption("المواد", Icons.Default.Inventory2),
                    SegmentOption("الأسعار", Icons.Default.Sell)
                ),
                onSelect = onTabChange
            )
        }
    }
}

private data class SegmentOption(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

/**
 * REDESIGN ("الشريط... أعد تصميمه"): rebuilt on a tighter, more deliberate
 * grid instead of the previous plain label-only pill. Three precise
 * changes from before:
 * 1. Each segment now carries a small leading glyph (📦 for المواد, 🏷 for
 *    الأسعار) so the two tabs are told apart at a glance, not just by
 *    reading the Arabic label — the same icon/label pairing pattern used
 *    for every row lower on this screen (MaterialRow's avatar, the price
 *    row's tag icon).
 * 2. The selected thumb's own size is now driven by real measurement
 *    (`Modifier.onSizeChanged` + `animateDpAsState` for its offset), so it
 *    slides between segments as one continuous pill instead of each
 *    segment independently cross-fading its own background — a small but
 *    real "precision" difference: there is exactly one thumb, always
 *    exactly the width of its segment, always exactly aligned under it.
 * 3. Track/thumb metrics tightened (6.dp track padding, 4.dp icon-label
 *    gap, fixed 46.dp row height) so the control reads as one crisp,
 *    consistently-measured control rather than padding that happened to
 *    look right on one label length.
 */
@Composable
private fun SegmentedTabs(selectedIndex: Int, options: List<SegmentOption>, onSelect: (Int) -> Unit) {
    // نفس لغة أزرار الإعدادات: مسار مسطّح بتعبئة الصفوف والمؤشر حبّة بلون onSurface.
    val cs = MaterialTheme.colorScheme
    var trackWidthPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val segmentWidth = with(density) {
        if (trackWidthPx == 0) 0.dp else (trackWidthPx / options.size).toDp()
    }
    val thumbOffset by animateDpAsState(
        targetValue = segmentWidth * selectedIndex,
        animationSpec = MotionSpecs.quickSpring(),
        label = "segmentThumbOffset"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .padding(3.dp)
            .onSizeChanged { trackWidthPx = it.width }
    ) {
        if (segmentWidth > 0.dp) {
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(thumbOffset.roundToPx(), 0) }
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(cs.onSurface)
            )
        }

        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex
                val labelColor by animateColorAsState(
                    targetValue = if (selected) cs.surface else cs.onSurfaceVariant,
                    animationSpec = MotionSpecs.quickSpring(),
                    label = "segmentLabelColor"
                )
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) }
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        option.icon,
                        contentDescription = null,
                        tint = labelColor,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        option.label,
                        color = labelColor,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MaterialsList(
    materials: List<Material>,
    prices: Map<String, Double>,
    animateSummary: Boolean,
    searching: Boolean,
    onClearAll: () -> Unit,
    onEdit: (Material) -> Unit,
    onDelete: (Material) -> Unit,
    onToggleImportant: (Material) -> Unit,
    onReordered: (List<Material>) -> Unit
) {
    if (materials.isEmpty()) {
        EmptyState(
            icon = if (searching) Icons.Default.SearchOff else Icons.Default.Inventory2,
            text = if (searching) "لا توجد نتائج" else "لا توجد نواقص حالياً\nاضغط \"مادة جديدة\" لإضافة أول نقص"
        )
        return
    }
    // BUG FIXED: the trailing `Spacer(height = 72.dp)` used to be a guessed
    // stand-in for "roughly the FAB's height" so the last row could clear
    // it. It never accounted for the floating nav pill sitting below the
    // FAB too, and a fixed 72dp silently drifts wrong the moment either
    // one's real size changes. Replaced with real contentPadding sized off
    // the pill's actual measured height (LocalFloatingBottomNavHeight) plus
    // a fixed safety margin, computed once below, rather than a hardcoded
    // row. "مادة جديدة" has since moved off this screen too (same shared
    // quick-add "+" beside the pill as every other tab), so this margin is
    // no longer one specific FAB's height either — kept at the same size
    // regardless, as this list's general clearance against the pill's
    // transparent side margins, matching every other list in the app.
    val bottomSafetyMargin = 56.dp + 24.dp
    val bottomClearance = LocalFloatingBottomNavHeight.current + bottomSafetyMargin

    // FEATURE ADDED ("ترتيب المواد بالضغط المطول وتحريكها"): a local copy
    // that the drag gesture below reorders live, in real time, as the
    // finger moves — synced back to the real `materials` list on every
    // change from Firestore *except* while a drag is actually in progress,
    // so an incoming snapshot (e.g. from another device) never yanks the
    // row out from under the finger mid-drag. Reordering only makes sense
    // against the full, unfiltered list, so it's disabled entirely while
    // searching (see `canReorder` below) — dragging a filtered subset
    // would silently scramble the position of every hidden item too.
    var orderedItems by remember { mutableStateOf(materials) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(materials) {
        if (draggingId == null) orderedItems = materials
    }
    val canReorder = !searching
    // Real per-row height in px (row height + the 8.dp spacedBy gap),
    // measured live via onSizeChanged below — used to decide, as the
    // finger moves, when it's dragged far enough past a neighbor to swap
    // places with it.
    val itemHeightsPx = remember { mutableStateMapOf<String, Int>() }
    var dragOffset by remember { mutableStateOf(0f) }

    // تُحسب مرة لكل تغيّر فعلي في القائمة المعروضة أو في الأسعار.
    val weightSummary = remember(materials) { materials.weightSummary() }
    val priceSummary = remember(materials, prices) { materials.priceSummary(prices) }

    LazyColumn(
        Modifier.fillMaxSize(),
        // BUG FIXED ("السحب والافلات... كلشي يخفتي فوقها فما بقدر ارجع
        // للأعلى"): this screen sits inside PullToRefreshContent, whose
        // pull-to-refresh gesture and this LazyColumn's own scroll both
        // listen for the same vertical drag through Compose's nested-
        // scroll system. A row's reorder-drag only consumes pointer
        // *events* (`change.consume()` below), which stops the LazyColumn
        // from treating that drag as a scroll — but it doesn't stop the
        // list from being a live nested-scroll participant, so a drag that
        // starts right at the top of the list (dragging the very first
        // item) could still get read as a pull-to-refresh gesture at the
        // same time, leaving the refresh indicator dimming the top of the
        // list and stuck mid-gesture, unable to scroll, until released.
        // Disabling the list's own scrolling for the whole duration of a
        // row-drag removes it from nested scroll entirely, so no drag can
        // ever be mistaken for a pull-to-refresh again.
        userScrollEnabled = draggingId == null,
        contentPadding = PaddingValues(start = AppScreenPadding, end = AppScreenPadding, top = 2.dp, bottom = bottomClearance),
        verticalArrangement = Arrangement.spacedBy(AppGroupGap)
    ) {
        // ملخّص القائمة (الأوزان + إجمالي السعر) وعنوانها: أول عنصرين في القائمة
        // نفسها فيتمرّران معها. يتبعان القائمة المعروضة (يتغيّران مع البحث).
        item(key = "summary") {
            MaterialsSummaryCard(
                weights = weightSummary,
                prices = priceSummary,
                animate = animateSummary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        item(key = "listTitle") {
            AppSectionTitle(
                text = "قائمة النواقص (${materials.size})",
                modifier = Modifier.padding(horizontal = 0.dp),
                trailing = {
                    TextButton(onClick = onClearAll) {
                        Text(
                            "مسح الكل",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            )
        }
        itemsIndexed(
            orderedItems,
            key = { _, m -> m.id },
            // PERF: كل الصفوف من نوع واحد — يسمح لـ Compose بإعادة استخدام تركيبها بين الصفوف.
            contentType = { _, _ -> "materialRow" }
        ) { rowIndex, m ->
            val isDragging = m.id == draggingId
            Box(
                Modifier
                    .fillMaxWidth()
                    // Items not currently being dragged still animate into
                    // their new slot when a drag (or a delete/search
                    // change) shifts them — skipped for the dragged item
                    // itself so its own manual `dragOffset` below (which
                    // already tracks the finger exactly) isn't fought by a
                    // second, competing placement animation.
                    .then(if (!isDragging) Modifier.animateItem(
                        // السلاسة: المادة المضافة حديثاً تظهر بتلاشٍ قصير بدل القفز المفاجئ.
                        fadeInSpec = tween(durationMillis = MotionSpecs.fadeMillis(), easing = MotionSpecs.claudeEasing),
                        placementSpec = MotionSpecs.reorderSpring(),
                        fadeOutSpec = MotionSpecs.listItemFadeOut()
                    ) else Modifier)
                    .onSizeChanged { itemHeightsPx[m.id] = it.height }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    // حركة دخول لأول ظهور للصف (تُعطَّل تلقائياً على الأجهزة الضعيفة).
                    .listItemEntrance(rowIndex)
                    .then(
                        if (canReorder) {
                            Modifier.pointerInput(m.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = m.id
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        if (draggingId != null) onReordered(orderedItems)
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                    // BUG FIXED ("تقنية السحب والافلات... بدها تحسين وتطوير
                                    // بآلية العمل بشكل دقيق"): the swap threshold and the
                                    // amount `dragOffset` got corrected by on every swap used
                                    // to both be the *dragged* item's own height alone (`step`
                                    // above). That's only correct when every row is the same
                                    // height. Rows here aren't — a material whose name wraps
                                    // to two lines (e.g. "بذور القرع / اليقطين") is visibly
                                    // taller than a one-line row like "بيتزا" right next to it
                                    // (see the reference screenshot). Using only the dragged
                                    // row's own height meant swapping past a taller neighbor
                                    // triggered too early (threshold too small) and left the
                                    // dragged row's finger-relative offset wrong by the
                                    // difference in height (correction too small), so the row
                                    // under the finger visibly snapped to the wrong spot —
                                    // worse the more the two rows' heights differed.
                                    // Fixed by looking up the *actual* neighbor being swapped
                                    // past (not assuming it's the same height as the dragged
                                    // row): the swap now triggers at the midpoint between the
                                    // two rows' real heights, and the offset is corrected by
                                    // the neighbor's real height (the exact distance the
                                    // dragged row visually jumps as it trades places), so the
                                    // row tracks the finger precisely regardless of how the
                                    // heights differ.
                                    onDrag = { change, delta ->
                                        change.consume()
                                        dragOffset += delta.y
                                        val gap = AppGroupGap.toPx()
                                        val myHeight = (itemHeightsPx[m.id] ?: 0).toFloat()
                                        val currentIndex = orderedItems.indexOfFirst { it.id == m.id }
                                        // BUG FIXED (part of the same
                                        // "السحب... فما بقدر ارجع للأعلى" fix
                                        // as userScrollEnabled above): the
                                        // first item has no neighbor above it
                                        // to swap with, so nothing here ever
                                        // stopped `dragOffset` itself from
                                        // still climbing negative as the
                                        // finger kept moving up — the row
                                        // just kept sliding up past its own
                                        // resting slot with no floor,
                                        // visually detaching from the list
                                        // (and symmetrically for the last
                                        // item dragged past the bottom).
                                        // Clamped to 0 in whichever
                                        // direction has no neighbor to swap
                                        // into, so the dragged row never
                                        // moves further than the real list
                                        // actually allows.
                                        if (currentIndex == 0 && dragOffset < 0f) dragOffset = 0f
                                        if (currentIndex == orderedItems.lastIndex && dragOffset > 0f) dragOffset = 0f
                                        if (dragOffset > 0f && currentIndex < orderedItems.lastIndex) {
                                            val neighbor = orderedItems[currentIndex + 1]
                                            val neighborHeight = (itemHeightsPx[neighbor.id] ?: myHeight.toInt()).toFloat()
                                            val threshold = (myHeight + neighborHeight) / 2f + gap
                                            if (dragOffset > threshold) {
                                                orderedItems = orderedItems.toMutableList().apply {
                                                    add(currentIndex + 1, removeAt(currentIndex))
                                                }
                                                dragOffset -= (neighborHeight + gap)
                                            }
                                        } else if (dragOffset < 0f && currentIndex > 0) {
                                            val neighbor = orderedItems[currentIndex - 1]
                                            val neighborHeight = (itemHeightsPx[neighbor.id] ?: myHeight.toInt()).toFloat()
                                            val threshold = (myHeight + neighborHeight) / 2f + gap
                                            if (-dragOffset > threshold) {
                                                orderedItems = orderedItems.toMutableList().apply {
                                                    add(currentIndex - 1, removeAt(currentIndex))
                                                }
                                                dragOffset += (neighborHeight + gap)
                                            }
                                        }
                                    }
                                )
                            }
                        } else Modifier
                    )
            ) {
                MaterialRow(
                    material = m,
                    shape = groupedRowShape(rowIndex, orderedItems.lastIndex),
                    onEdit = { onEdit(m) },
                    onDelete = { onDelete(m) },
                    onToggleImportant = { onToggleImportant(m) },
                    isDragging = isDragging,
                    linePrice = m.linePrice(prices)
                )
            }
        }
    }
}

@Composable
private fun MaterialRow(
    material: Material,
    shape: Shape,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleImportant: () -> Unit,
    isDragging: Boolean = false,
    linePrice: Double? = null,
    modifier: Modifier = Modifier
) {
    val avatarColor = remember(material.name) { avatarColorFor(material.name) }
    // LAG FIX: قراءة الـ scale في مرحلة الرسم بدل إعادة تركيب الصف كاملاً في كل إطار.
    val scale = animateFloatAsState(
        targetValue = if (isDragging) 1.03f else 1f,
        animationSpec = MotionSpecs.pressSpring(),
        label = "materialRowScale"
    )
    val rowFill = MaterialTheme.colorScheme.surfaceContainerHigh
    // الصف "المهم جداً" يأخذ لمسة ذهبية خفيفة بدل الإطار الملوّن القديم.
    val fill = if (material.important) {
        LocalSemanticColors.current.warning.copy(alpha = 0.14f).compositeOver(rowFill)
    } else rowFill

    Box(modifier.fillMaxWidth().graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
        Surface(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = fill,
            shadowElevation = if (isDragging) 6.dp else 0.dp
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .padding(start = 2.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleImportant, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (material.important) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (material.important) "إلغاء الأهمية" else "وضع كهامة جداً",
                        tint = if (material.important) LocalSemanticColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AppIconCircle(color = avatarColor, size = 38.dp) {
                    Icon(Icons.Default.Spa, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        material.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    // سعر هذا الصف = سعر الكيلو × الوزن (يظهر فقط إذا كانت المادة مسعّرة).
                    if (linePrice != null) {
                        Text(
                            "${Formatters.number(linePrice)} ${AppSettingsState.currencySymbol}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (material.notes.isNotBlank()) {
                        Text(
                            material.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                com.shopmanager.app.ui.common.PillBadge(
                    text = material.quantityLabel(),
                    color = com.shopmanager.app.ui.common.pillColorForQuantity(material.quantity)
                )
                Spacer(Modifier.width(8.dp))
                AppActionPill(
                    height = 36.dp,
                    actions = listOf(
                        ActionSpec(Icons.Default.Edit, "تعديل", null, onEdit),
                        ActionSpec(Icons.Default.Close, "حذف المادة", LocalSemanticColors.current.danger, onDelete)
                    )
                )
            }
        }
    }
}

/**
 * REDESIGN ("لوحة الأسعار... تصميم كامل ومحسّن"): rebuilt from a bare
 * name+field list into an actual priced overview:
 * - A summary card up top totals how many catalog items already have a
 *   price set out of the total, plus the running sum of every priced item
 *   (edited, unsaved values included) — the same "total at a glance" idea
 *   the shortage tab already has, brought over to this tab too.
 * - A search field to filter the catalog by name, matching the pattern
 *   already used on the المواد tab, since a real catalog can run long.
 * - Each row now carries a small colored tag-icon avatar (the same avatar
 *   pattern MaterialRow uses) instead of bare text, a currency suffix
 *   directly in the price field, and a "priced"/"unpriced" visual state so
 *   it's obvious at a glance which items still need a price.
 * - The "حفظ الأسعار" action itself now lives beside the floating nav pill
 *   at the bottom of the screen (see MaterialsScreen's `editedPrices`/
 *   `savePricesRequested` and FloatingBottomNav's `secondaryAction`,
 *   wired up in MainActivity) instead of a full-width button drawn here —
 *   it reads as one attached unit with the pill, the same way "مادة
 *   جديدة" already does, rather than a separate button with its own
 *   reserved strip of empty space below the list.
 */
@Composable
private fun PricesList(
    catalogItems: List<MaterialCatalogItem>,
    materials: List<Material>,
    prices: Map<String, Double>,
    edited: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>,
    search: String
) {
    if (catalogItems.isEmpty()) {
        EmptyState(icon = Icons.Default.Inventory2, text = "أضف مواد للقائمة الثابتة أولاً لتسعيرها")
        return
    }

    val currency = AppSettingsState.currencySymbol
    val filtered = remember(search, catalogItems) {
        if (search.isBlank()) catalogItems
        else catalogItems.filter { it.name.contains(search, ignoreCase = true) }
    }

    // صفوف النواقص مجمّعة باسم المادة (مقصوص) لربطها بعنصر الكتالوج نفسه.
    val shortageByName = remember(materials) { materials.groupBy { it.name.trim() } }

    // إعادة ترتيب: المواد الموجودة حالياً في قائمة النواقص أولاً (هي التي تحتاج سعراً الآن
    // ويظهر لها حساب الإجمالي)، ثم بقية الكتالوج بترتيبها الأصلي.
    val inShortage = remember(filtered, shortageByName) { filtered.filter { shortageByName.containsKey(it.name.trim()) } }
    val others = remember(filtered, shortageByName) { filtered.filter { !shortageByName.containsKey(it.name.trim()) } }

    val bottomClearance = LocalFloatingBottomNavHeight.current + 32.dp
    Column(Modifier.fillMaxSize()) {
        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(icon = Icons.Default.SearchOff, text = "لا توجد نتائج")
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = AppScreenPadding, end = AppScreenPadding, top = 2.dp, bottom = bottomClearance),
                verticalArrangement = Arrangement.spacedBy(AppGroupGap)
            ) {
                item(key = "priceHint") {
                    AppFootnote(
                        "السعر المكتوب هو سعر الكيلو، ويُضرب تلقائياً بوزن المادة في قائمة النواقص (نص كيلو، ربع كيلو، لوقية…)",
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (inShortage.isNotEmpty()) {
                    item(key = "titleShortage") {
                        AppSectionTitle(text = "في قائمة النواقص (${inShortage.size})", modifier = Modifier.padding(bottom = 2.dp))
                    }
                    itemsIndexed(inShortage, key = { _, c -> c.id }, contentType = { _, _ -> "priceRow" }) { rowIndex, item ->
                        val effective = edited[item.name]?.toPriceOrNull() ?: prices.priceOf(item.name)
                        PriceRow(
                            shape = groupedRowShape(rowIndex, inShortage.lastIndex),
                            item = item,
                            currency = currency,
                            // قيمة الحقل نص قابل للتحليل مجدداً (بلا ".0" ولا 1.1E7).
                            value = edited[item.name] ?: prices[item.name]?.toEditText() ?: "",
                            effectivePrice = effective,
                            shortageRows = shortageByName[item.name.trim()].orEmpty(),
                            onValueChange = { edited[item.name] = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '٫' } }
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item(key = "titleOthers") {
                        AppSectionTitle(
                            text = if (inShortage.isEmpty()) "القائمة الثابتة (${others.size})" else "بقية القائمة (${others.size})",
                            modifier = Modifier.padding(top = if (inShortage.isEmpty()) 0.dp else 10.dp, bottom = 2.dp)
                        )
                    }
                    itemsIndexed(others, key = { _, c -> c.id }, contentType = { _, _ -> "priceRow" }) { rowIndex, item ->
                        val effective = edited[item.name]?.toPriceOrNull() ?: prices.priceOf(item.name)
                        PriceRow(
                            shape = groupedRowShape(rowIndex, others.lastIndex),
                            item = item,
                            currency = currency,
                            value = edited[item.name] ?: prices[item.name]?.toEditText() ?: "",
                            effectivePrice = effective,
                            shortageRows = emptyList(),
                            onValueChange = { edited[item.name] = it.filter { c -> c.isDigit() || c == '.' || c == ',' || c == '٫' } }
                        )
                    }
                }
            }
        }
    }
}

/**
 * صف كتالوج في تبويب الأسعار: أيقونة، الاسم، (إن كانت المادة في النواقص) سطر الحساب
 * "كميتها = سعرها"، وحقل سعر الكيلو مضغوط الارتفاع.
 */
@Composable
private fun PriceRow(
    shape: Shape,
    item: MaterialCatalogItem,
    currency: String,
    value: String,
    effectivePrice: Double?,
    shortageRows: List<Material>,
    onValueChange: (String) -> Unit
) {
    val avatarColor = remember(item.name) { avatarColorFor(item.name) }
    val hasPrice = effectivePrice != null
    val cs = MaterialTheme.colorScheme
    AppRowSurface(shape = shape) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconCircle(
                color = if (hasPrice) avatarColor else avatarColor.copy(alpha = 0.35f),
                size = 36.dp
            ) {
                Icon(
                    Icons.Default.Sell,
                    contentDescription = null,
                    tint = if (hasPrice) Color.White else avatarColor,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (shortageRows.isNotEmpty()) {
                    val qty = shortageRows.joinToString(" + ") { it.quantityLabel() }
                    val total = if (effectivePrice == null) null
                    else shortageRows.sumOf { it.linePrice(effectivePrice) ?: 0.0 }
                    Text(
                        if (total == null) "$qty — بدون سعر" else "$qty = ${Formatters.number(total)} $currency",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.width(138.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface, fontWeight = FontWeight.Medium),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(cs.onSurface),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cs.surfaceContainerHighest)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text("0", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                            inner()
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(currency, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    AppEmptyState(icon = icon, text = text)
}

private fun buildMaterialsShareText(materials: List<Material>, prices: Map<String, Double>): String {
    val currency = AppSettingsState.currencySymbol
    val sb = StringBuilder("📦 قائمة المواد والأسعار\n\n")
    materials.sortedBy { it.name }.forEach { m ->
        sb.append("• ${m.name}: ${m.quantityLabel()}")
        // سعر الصف = سعر الكيلو × الوزن (لا سعر الكيلو الخام).
        m.linePrice(prices)?.let { sb.append(" — ${Formatters.number(it)} $currency") }
        sb.append("\n")
    }
    sb.append("\nالإجمالي: ${materials.size} مادة")
    val w = materials.weightSummary()
    if (w.hasAnything) {
        val parts = buildList {
            if (w.kilos > 0.0) add("${Formatters.number(w.kilos)} كيلو")
            if (w.okes > 0.0) add("${Formatters.number(w.okes)} لوقية")
            if (w.pieces > 0.0) add("${Formatters.number(w.pieces)} بالعدد")
        }
        sb.append("\nالأوزان: ${parts.joinToString(" + ")}")
    }
    val ps = materials.priceSummary(prices)
    if (ps.total > 0.0) {
        sb.append("\nإجمالي السعر: ${Formatters.number(ps.total)} $currency")
        if (ps.unpriced > 0) sb.append(" (${ps.unpriced} مادة بلا سعر غير محسوبة)")
    }
    return sb.toString()
}

/**
 * ملخّص القائمة المضغوط (بطاقة واحدة بسطر واحد بدل بطاقة طويلة):
 *  - خلية الأوزان: الكيلو / اللوقية / بالعدد (لا يظهر منها إلا ما فيه قيمة)، بلا أي
 *    تحويل بين العائلتين في العرض.
 *  - خلية إجمالي السعر: مجموع (سعر الكيلو × الوزن) لكل مادة مسعّرة. وإن وُجدت مواد بلا
 *    سعر يُذكر عددها تحت الرقم حتى لا يُظنّ أنها داخلة في المجموع.
 */
@Composable
private fun MaterialsSummaryCard(
    weights: MaterialsWeightSummary,
    prices: MaterialsPriceSummary,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val currency = AppSettingsState.currencySymbol
    val weightCells = remember(weights) {
        buildList {
            if (weights.kilos > 0.0) add(weights.kilos to "كيلو")
            if (weights.okes > 0.0) add(weights.okes to "لوقية")
            if (weights.pieces > 0.0) add(weights.pieces to "بالعدد")
        }
    }
    val showWeights = weightCells.isNotEmpty()
    val showPrice = prices.total > 0.0
    if (!showWeights && !showPrice) return

    AppCard(modifier = modifier.animateContentSize(animationSpec = MotionSpecs.expandSpring())) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showWeights) {
                Column(Modifier.weight(1f)) {
                    Text("الأوزان المطلوبة", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                        weightCells.forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.Bottom) {
                                AnimatedCounterText(
                                    targetValue = value,
                                    format = { Formatters.number(it) },
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    animate = animate
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
            if (showWeights && showPrice) {
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(cs.outlineVariant)
                )
            }
            if (showPrice) {
                Column(Modifier.weight(1f)) {
                    Text("إجمالي السعر", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedCounterText(
                            targetValue = prices.total,
                            format = { Formatters.number(it) },
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                            fontWeight = FontWeight.SemiBold,
                            animate = animate
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            currency,
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                    if (prices.unpriced > 0) {
                        Text(
                            "${prices.unpriced} بلا سعر (غير محسوبة)",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** تنظيف رقم السعر المكتوب: أرقام عربية→لاتينية، فاصلة عشرية عربية→نقطة، حذف فواصل الآلاف والمسافات. */
private fun String.toPriceOrNull(): Double? {
    val cleaned = buildString {
        for (c in this@toPriceOrNull) {
            when (c) {
                in '٠'..'٩' -> append('0' + (c - '٠'))
                in '۰'..'۹' -> append('0' + (c - '۰'))
                '٫' -> append('.')
                ',', '٬', ' ' -> Unit
                else -> append(c)
            }
        }
    }
    val v = cleaned.toDoubleOrNull() ?: return null
    return if (v.isFinite() && v >= 0.0) v else null
}

/** نص قابل للتحرير لسعر محفوظ: بلا ".0" ولا صيغة علمية (1.1E7). */
private fun Double.toEditText(): String =
    if (this == this.toLong().toDouble()) this.toLong().toString()
    else java.math.BigDecimal(this).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
