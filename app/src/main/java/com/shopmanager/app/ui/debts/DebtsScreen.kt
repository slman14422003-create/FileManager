package com.shopmanager.app.ui.debts

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopmanager.app.data.debts.Person
import com.shopmanager.app.ui.common.ActionIconButton
import com.shopmanager.app.ui.common.AppSearchBar
import com.shopmanager.app.ui.common.AppSettingsState
import com.shopmanager.app.ui.common.BrandOnGradient
import com.shopmanager.app.ui.common.DeleteIconButton
import com.shopmanager.app.ui.common.Formatters
import com.shopmanager.app.ui.common.GlassIconButton
import com.shopmanager.app.ui.common.GlassSnackbarHost
import com.shopmanager.app.ui.common.LocalFloatingBottomNavHeight
import com.shopmanager.app.ui.common.listItemEntrance
import com.shopmanager.app.ui.common.MotionSpecs
import com.shopmanager.app.ui.common.PullToRefreshContent
import com.shopmanager.app.ui.common.ShareFormatDialog
import com.shopmanager.app.ui.common.avatarColorFor
import com.shopmanager.app.ui.common.GlassAlertDialog
import com.shopmanager.app.ui.theme.LocalBrandGradientColors
import com.shopmanager.app.ui.theme.LocalSemanticColors
import com.shopmanager.app.ui.theme.glassHairlineColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import com.shopmanager.app.ui.common.AppEmptyState
import com.shopmanager.app.ui.common.AppGroupGap
import com.shopmanager.app.ui.common.AppIconCircle
import com.shopmanager.app.ui.common.AppPillButton
import com.shopmanager.app.ui.common.AppRowSurface
import com.shopmanager.app.ui.common.AppScreenPadding
import com.shopmanager.app.ui.common.AppStat
import com.shopmanager.app.ui.common.AppStatsStrip
import com.shopmanager.app.ui.common.ScreenIconButton
import com.shopmanager.app.ui.common.ScreenTopBar
import com.shopmanager.app.ui.common.groupedRowShape
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import com.shopmanager.app.ui.common.ActionSpec
import com.shopmanager.app.ui.common.AppActionPill
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DebtsScreen(
    onOpenPerson: (String) -> Unit,
    viewModel: DebtsViewModel = viewModel(),
    addPersonRequested: Boolean = false,
    onAddPersonRequestHandled: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val message = viewModel.message.collectAsStateWithLifecycle().value
    val isRefreshing = viewModel.isRefreshing.collectAsStateWithLifecycle().value
    val search = remember { mutableStateOf("") }
    // REDESIGN ("شريط البحث لازم يكون زر في الشريط العلوي يتوسع بواجهة لحالة
    // اثناء البحث"): search used to be a permanently-visible field sitting
    // under the stats row, competing for space with the actual list on
    // every visit to this tab even when nobody's searching. It's now a
    // small icon button in the top bar that expands the *whole bar* into a
    // focused search field when tapped — the field itself no longer takes
    // up permanent room on the page.
    var isSearching by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val showAddDialog = remember { mutableStateOf(false) }

    // The shared "+" beside the bottom nav pill (see MainActivity) can't
    // reach into this screen's own dialog state directly, so it just raises
    // `addPersonRequested`. Watch it here and open the same "عميل جديد"
    // dialog the in-screen button uses, then immediately tell MainActivity
    // it's been handled so the flag doesn't re-fire on recomposition.
    LaunchedEffect(addPersonRequested) {
        if (addPersonRequested) {
            showAddDialog.value = true
            onAddPersonRequestHandled()
        }
    }
    val isSaving = remember { mutableStateOf(false) }
    val deleteTarget = remember { mutableStateOf<Person?>(null) }
    val payTarget = remember { mutableStateOf<Person?>(null) }
    val showShareChoice = remember { mutableStateOf(false) }
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val brandColor = LocalBrandGradientColors.current.first().toArgb()

    // BUG FIXED ("زر عميل جديد صار تحت الشريط العائم"): the floating نav
    // bar is now an overlay drawn on top of this whole screen (see
    // MainActivity), not a Scaffold bottomBar that used to reserve its own
    // clearance automatically.

    // BUG FIXED ("زر عميل جديد" sitting too high / list not lining up under
    // it): the list's own bottom clearance only ever accounted for the
    // floating nav pill, never for the FAB-style quick-add button that used
    // to float on top of the pill — so a separate, hand-guessed
    // `Spacer(Modifier.height(72.dp))` item used to be tacked onto the end
    // of the list to make up the difference. That guess didn't track the
    // FAB's real height, so on the label-hidden/compact system font
    // settings it left too little clearance (last row peeking out from
    // under the button) and on larger font scales too much (a dead gap
    // before the button).
    // "عميل جديد" itself has since moved off this screen entirely (it's the
    // shared quick-add "+" beside the pill now — see the comment a few
    // lines below), so there's no separate FAB here to clear anymore. Kept
    // the same total clearance regardless — it now reads as this list's
    // general safety margin against the pill's transparent side margins
    // (see FloatingBottomNav.kt's own doc comment on why a screen still
    // needs a bit of its own gap on top of the pill's raw measured height)
    // rather than one specific button's height, and every list in the app
    // wants at least this much room.
    val bottomSafetyMargin = 56.dp + 24.dp
    val listBottomClearance = LocalFloatingBottomNavHeight.current + bottomSafetyMargin

    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(isSearching) {
        if (isSearching) searchFocusRequester.requestFocus()
    }

    Scaffold(
        // BUG FIXED (black strip above the bottom nav bar, Debts tab only):
        // unlike DashboardScreen/MaterialsScreen, this Scaffold had no
        // contentWindowInsets override, so it fell back to Material3's
        // default of WindowInsets.safeDrawing (top AND bottom). The outer
        // app-level Scaffold in MainActivity already pads this screen's
        // content for the bottom nav bar/system bar once (via its own
        // `padding`); this inner Scaffold then reserved that same bottom
        // system-bar space a *second* time here, leaving an extra empty
        // gap between the list and the bottom nav bar. That gap sits on
        // this Scaffold's own background color — colorScheme.background,
        // which is deliberately a touch darker than colorScheme.surface
        // (used by the cards, the nav bar, etc.) — so the gap read as a
        // distinct dark/black bar rather than blending in. Restricting
        // this to Bottom + Horizontal only (top is already handled by the
        // TopAppBar itself, same pattern as the other two tabs) removes
        // the double-padding and the gap with it.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        snackbarHost = { GlassSnackbarHost(snackbarHost) },
        topBar = {
            if (isSearching) {
                // البحث يحلّ محل الشريط كله: رجوع + حقل بحث على شكل حبّة.
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
                        onClick = { isSearching = false; search.value = "" }
                    )
                    AppSearchBar(
                        query = search.value,
                        onQueryChange = { search.value = it },
                        onClose = { isSearching = false; search.value = "" },
                        placeholder = "بحث عن عميل...",
                        focusRequester = searchFocusRequester,
                        showBackButton = false,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                }
            } else {
                ScreenTopBar(
                    title = "الديون",
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
                                ActionSpec(Icons.Default.Search, "بحث") { isSearching = true },
                                ActionSpec(Icons.Default.Share, "مشاركة") { showShareChoice.value = true }
                            )
                        )
                    }
                )
            }
        },
        // The "عميل جديد" FAB used to live here on its own, but that action
        // now lives as the shared quick-add "+" beside the bottom nav pill
        // (see MainActivity's `quickAction` / `addPersonRequested`), so a
        // second FAB here would just duplicate it on screen.
    ) { padding ->
        PullToRefreshContent(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding)
        ) {
        Column(Modifier.fillMaxSize()) {
            // REDESIGN ("زر اضافة عميل جديد يجب ان يكون زر عريض مكان شريط
            // البحث للي شلته"): the permanent search field that used to sit
            // here moved into the top bar itself (see topBar above, toggled
            // by the search icon there) — this space is now a wide,
            // prominent "عميل جديد" button instead, so the single most
            // common action on this screen (adding a customer) has a clear,
            // full-width target right under the stats instead of only being
            // reachable from the small floating "+" beside the nav pill.
            AppPillButton(
                label = "عميل جديد",
                icon = Icons.Default.Add,
                onClick = { showAddDialog.value = true },
                height = 44.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppScreenPadding, end = AppScreenPadding, top = 2.dp, bottom = 8.dp)
            )

            // PERF: same remember-keyed fix as the materials tabs — skip
            // re-filtering the whole person list unless `search` or
            // `state.persons` actually changed.
            val filtered = remember(search.value, state.persons) {
                if (search.value.isBlank()) state.persons
                else state.persons.filter { it.name.contains(search.value, ignoreCase = true) }
            }

            if (state.isLoading && filtered.isEmpty()) {
                // التحميل الأول: هياكل صفوف بدل شاشة فارغة/"لا يوجد عملاء" الوامضة.
                com.shopmanager.app.ui.common.SkeletonRowList(
                    kind = com.shopmanager.app.ui.common.SkeletonRowKind.PERSON,
                    count = 7
                )
            } else if (filtered.isEmpty()) {
                Box(Modifier.padding(horizontal = AppScreenPadding)) { StatsRow(state.totalPersons, state.totalDebts, state.totalAmount) }
                EmptyState(
                    icon = if (search.value.isBlank()) Icons.Default.People else Icons.Default.PersonSearch,
                    text = if (search.value.isBlank()) "لا يوجد عملاء بعد\nاضغط \"عميل جديد\" للبدء" else "لا توجد نتائج"
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    // Bottom inset sized off the pill's real measured
                    // height plus the FAB's own fixed height, so the last
                    // row in the list scrolls fully clear of both instead
                    // of stopping underneath either one.
                    contentPadding = PaddingValues(
                        start = AppScreenPadding, end = AppScreenPadding, top = 2.dp,
                        bottom = listBottomClearance
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppGroupGap)
                ) {
                    // الإحصاءات أول عنصر في القائمة: تمرّ مع التمرير فيأخذ العملاء كامل الشاشة.
                    item(key = "stats") { StatsRow(state.totalPersons, state.totalDebts, state.totalAmount) }
                    itemsIndexed(filtered, key = { _, person -> person.id }, contentType = { _, _ -> "person" }) { index, person ->
                        PersonRow(
                            person,
                            groupedRowShape(index, filtered.lastIndex),
                            Modifier
                                .animateItem(
                                    fadeInSpec = null,
                                    placementSpec = MotionSpecs.reorderSpring(),
                                    fadeOutSpec = MotionSpecs.listItemFadeOut()
                                )
                                .listItemEntrance(index),
                            onClick = { onOpenPerson(person.id) },
                            onDelete = { deleteTarget.value = person },
                            onMarkPaid = { payTarget.value = person }
                        )
                    }
                }
            }
        }
        }
    }

    if (showAddDialog.value) {
        PersonEditDialog(
            initial = null,
            isSaving = isSaving.value,
            onDismiss = { if (!isSaving.value) showAddDialog.value = false },
            onSave = { name, amount, date, note, phone ->
                isSaving.value = true
                viewModel.savePerson(null, name, amount, date, note, phone) { success ->
                    isSaving.value = false
                    if (success) showAddDialog.value = false
                }
            }
        )
    }

    deleteTarget.value?.let { person ->
        GlassAlertDialog(
            onDismissRequest = { deleteTarget.value = null },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد من حذف \"${person.name}\" وكل ديونه؟") },
            confirmButton = {
                // BUG FIXED ("بدي الضغطة بشكل مربع كامل وليس دائري"): see
                // PersonEditDialog.kt's doc comment — RectangleShape makes
                // the press highlight fill the whole cell instead of
                // TextButton's default rounded-pill outline.
                TextButton(shape = RectangleShape, onClick = { viewModel.deletePerson(person.id); deleteTarget.value = null }) { Text("حذف") }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { deleteTarget.value = null }) { Text("إلغاء") } }
        )
    }

    payTarget.value?.let { person ->
        GlassAlertDialog(
            onDismissRequest = { payTarget.value = null },
            icon = { Icon(Icons.Default.Check, contentDescription = null, tint = LocalSemanticColors.current.success) },
            title = { Text("تأكيد السداد") },
            text = { Text("هل \"${person.name}\" وفى ${Formatters.number(person.amount)} ${AppSettingsState.currencySymbol}؟ سيتم حذف كل ديونه من السجل وإرسال إشعار.") },
            confirmButton = {
                TextButton(shape = RectangleShape, onClick = {
                    viewModel.markPersonAsPaid(person)
                    payTarget.value = null
                }) { Text("تم السداد", color = LocalSemanticColors.current.success, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { payTarget.value = null }) { Text("إلغاء") } }
        )
    }

    if (showShareChoice.value) {
        ShareFormatDialog(
            onDismiss = { showShareChoice.value = false },
            onPickImage = {
                // PERF: Canvas drawing moved off the main thread — see the
                // matching note in MaterialsScreen's onPickImage.
                scope.launch {
                    val uri = withContext(Dispatchers.Default) {
                        DebtsReportImage.generate(context, state.persons, state.totalAmount, brandColor)
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "مشاركة كشف الديون"))
                }
            },
            onPickText = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, buildDebtsShareText(state.persons, state.totalAmount))
                }
                context.startActivity(Intent.createChooser(intent, "مشاركة كشف الديون"))
            }
        )
    }
}

@Composable
private fun StatsRow(persons: Int, debts: Int, amount: Double) {
    AppStatsStrip(
        stats = listOf(
            AppStat("عملاء", persons.toDouble()) { "%.0f".format(it) },
            AppStat("ديون", debts.toDouble()) { "%.0f".format(it) },
            AppStat("الإجمالي (${AppSettingsState.currencySymbol})", amount) { Formatters.number(it) }
        ),
        compact = true,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    AppEmptyState(icon = icon, text = text)
}

@Composable
private fun PersonRow(
    person: Person,
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMarkPaid: () -> Unit
) {
    val avatarColor = remember(person.name) { avatarColorFor(person.name) }
    // صف مسطّح متلاصق مع جيرانه (نفس لغة صفوف الإعدادات): بلا حدود ولا ظل.
    AppRowSurface(shape = shape, modifier = modifier, onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconCircle(color = avatarColor, size = 40.dp) {
                Text(
                    person.name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    person.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (person.amount > 0) {
                    Spacer(Modifier.height(2.dp))
                    com.shopmanager.app.ui.common.PillBadge(
                        text = "${Formatters.number(person.amount)} ${AppSettingsState.currencySymbol}",
                        color = LocalSemanticColors.current.danger
                    )
                } else {
                    Text(
                        "لا يوجد دين حالياً",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalSemanticColors.current.success
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val semantic = LocalSemanticColors.current
            AppActionPill(
                height = 36.dp,
                actions = if (person.amount > 0) {
                    listOf(
                        ActionSpec(Icons.Default.Check, "تسجيل سداد كامل الدين", semantic.success, onMarkPaid),
                        ActionSpec(Icons.Default.Close, "حذف العميل", semantic.danger, onDelete)
                    )
                } else {
                    listOf(ActionSpec(Icons.Default.Close, "حذف العميل", semantic.danger, onDelete))
                }
            )
        }
    }
}

private fun buildDebtsShareText(persons: List<Person>, totalAmount: Double): String {
    val currency = AppSettingsState.currencySymbol
    val sb = StringBuilder("💰 كشف الديون\n\n")
    persons.sortedByDescending { it.amount }.forEach { p ->
        sb.append("• ${p.name}: ${Formatters.number(p.amount)} $currency\n")
    }
    sb.append("\nالإجمالي: ${Formatters.number(totalAmount)} $currency")
    return sb.toString()
}
