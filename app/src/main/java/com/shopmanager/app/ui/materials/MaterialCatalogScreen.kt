package com.shopmanager.app.ui.materials

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shopmanager.app.data.materials.MaterialCatalogItem
import com.shopmanager.app.data.materials.MaterialUnit
import com.shopmanager.app.ui.common.AppSectionTitle
import com.shopmanager.app.ui.common.AppTextField
import com.shopmanager.app.ui.common.BrandOnGradient
import com.shopmanager.app.ui.common.GlassIconButton
import com.shopmanager.app.ui.common.GlassSnackbarHost
import com.shopmanager.app.ui.common.MotionSpecs
import com.shopmanager.app.ui.common.listItemEntrance
import com.shopmanager.app.ui.common.avatarColorFor
import com.shopmanager.app.ui.common.GlassAlertDialog
import com.shopmanager.app.ui.theme.glassHairlineColor
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import com.shopmanager.app.ui.common.AppEmptyState
import com.shopmanager.app.ui.common.AppFootnote
import com.shopmanager.app.ui.common.AppGroupGap
import com.shopmanager.app.ui.common.AppIconCircle
import com.shopmanager.app.ui.common.AppRowSurface
import com.shopmanager.app.ui.common.AppScreenPadding
import com.shopmanager.app.ui.common.AppSearchBar
import com.shopmanager.app.ui.common.ScreenIconButton
import com.shopmanager.app.ui.common.ScreenTopBar
import com.shopmanager.app.ui.common.groupedRowShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator

/**
 * Standalone screen for picking which shortage to add: pick a name from the
 * shop's standing spice catalog instead of typing it every time, then enter
 * the quantity needed - like ticking an item off at the market. New names
 * can be added to the catalog inline the first time they're needed, and
 * removed later if no longer carried. Saving one item keeps you on this
 * screen so you can add several shortages in a row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialCatalogScreen(viewModel: MaterialsViewModel, onBack: () -> Unit) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    var isAddingCatalogItem by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pickedItem by remember { mutableStateOf<MaterialCatalogItem?>(null) }
    var deleteTarget by remember { mutableStateOf<MaterialCatalogItem?>(null) }
    // FEATURE ADDED ("تعديل المواد الثابتة بعد إضافتها"): the catalog item
    // currently open for renaming, if any — same one-dialog-at-a-time
    // pattern as `pickedItem`/`deleteTarget` above.
    var editTarget by remember { mutableStateOf<MaterialCatalogItem?>(null) }
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbarHost.showSnackbar(it); viewModel.clearMessage() }
    }

    // PERF: same remember-keyed fix as MaterialsScreen — skip re-filtering
    // the whole catalog on every unrelated recomposition (dialogs opening,
    // the snackbar message clearing), only on an actual search/catalog
    // change.
    val filtered = remember(search, catalog) {
        if (search.isBlank()) catalog else catalog.filter { it.name.contains(search, ignoreCase = true) }
    }

    Scaffold(
        // Off-pager screen (no bottom nav bar of its own) — the outer app
        // Scaffold already reserves the real bottom/horizontal safe-area
        // space one level up in NavHost's padding, so this Scaffold's own
        // content insets are zeroed to avoid reserving that same space
        // twice. The TopAppBar below still handles the status bar inset
        // entirely on its own regardless of this setting.
        contentWindowInsets = WindowInsets(0.dp),
        snackbarHost = { GlassSnackbarHost(snackbarHost) },
        topBar = {
            ScreenTopBar(
                title = "اختر مادة",
                navigation = {
                    ScreenIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        onClick = onBack
                    )
                }
            )
        },
        // FIX: adding a new catalog name used to be a permanently-visible
        // text field + button squeezed in above the list, competing with
        // search for the top of the screen. It's now a floating "+" button
        // at the bottom - same pattern as MaterialsScreen's own FAB - that
        // opens a small, focused dialog just for typing the one new name.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("مادة جديدة", fontWeight = FontWeight.SemiBold) },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AppFootnote(
                "اضغط على اسم المادة لإدخال الكمية، أو أضف اسمًا جديدًا من الزر بالأسفل",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
            )

            AppSearchBar(
                query = search,
                onQueryChange = { search = it },
                onClose = { search = "" },
                placeholder = "بحث بالقائمة...",
                showBackButton = false,
                modifier = Modifier.padding(horizontal = AppScreenPadding, vertical = 8.dp)
            )

            if (filtered.isEmpty()) {
                AppEmptyState(
                    icon = Icons.Default.Inventory2,
                    text = if (catalog.isEmpty()) "القائمة فاضية، أضف أول مادة من الزر بالأسفل" else "لا توجد نتائج"
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = AppScreenPadding, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(AppGroupGap)
                ) {
                    itemsIndexed(filtered, key = { _, catalogItem -> catalogItem.id }, contentType = { _, _ -> "catalogItem" }) { index, item ->
                        Box(Modifier.listItemEntrance(index)) {
                            CatalogRow(
                                item = item,
                                shape = groupedRowShape(index, filtered.lastIndex),
                                onClick = { pickedItem = item },
                                onEdit = { editTarget = item },
                                onDelete = { deleteTarget = item }
                            )
                        }
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCatalogItemDialog(
            isSaving = isAddingCatalogItem,
            onDismiss = { showAddDialog = false },
            onSave = { name ->
                isAddingCatalogItem = true
                viewModel.addCatalogItem(name) { success ->
                    isAddingCatalogItem = false
                    if (success) showAddDialog = false
                }
            }
        )
    }

    editTarget?.let { item ->
        var isSavingRename by remember { mutableStateOf(false) }
        EditCatalogItemDialog(
            initialName = item.name,
            isSaving = isSavingRename,
            onDismiss = { if (!isSavingRename) editTarget = null },
            onSave = { newName ->
                isSavingRename = true
                viewModel.updateCatalogItem(item.id, item.name, newName) { success ->
                    isSavingRename = false
                    if (success) editTarget = null
                }
            }
        )
    }

    pickedItem?.let { item ->
        var isAddingMaterial by remember { mutableStateOf(false) }
        QuantityEntryDialog(
            materialName = item.name,
            isSaving = isAddingMaterial,
            onDismiss = { if (!isAddingMaterial) pickedItem = null },
            onSave = { quantity, unit, notes ->
                // Every material added here is, by the nature of this list,
                // something the shop is short on and needs to buy - so it's
                // simply added with the quantity needed, no threshold or
                // stock-level bookkeeping involved.
                isAddingMaterial = true
                viewModel.addMaterial(item.name, quantity, unit, notes) { success ->
                    isAddingMaterial = false
                    if (success) pickedItem = null
                }
            }
        )
    }

    deleteTarget?.let { item ->
        GlassAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف من القائمة الثابتة") },
            text = { Text("هل تريد حذف \"${item.name}\" من القائمة الثابتة؟ (هذا لا يحذف أي كمية مخزنة سابقًا)") },
            confirmButton = {
                TextButton(shape = RectangleShape, onClick = { viewModel.deleteCatalogItem(item.id); deleteTarget = null }) { Text("حذف") }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { deleteTarget = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun CatalogRow(
    item: MaterialCatalogItem,
    shape: Shape,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val color = remember(item.name) { avatarColorFor(item.name) }
    AppRowSurface(shape = shape, onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconCircle(color = color, size = 40.dp) {
                Icon(Icons.Default.Spa, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                item.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "تعديل الاسم", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "حذف من القائمة", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddCatalogItemDialog(isSaving: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    GlassAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("مادة جديدة للقائمة الثابتة") },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = "اسم المادة",
                placeholder = "اسم المادة...",
                enabled = !isSaving,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (name.isNotBlank() && !isSaving) onSave(name.trim()) }
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !isSaving,
                shape = RectangleShape,
                onClick = { onSave(name.trim()) }
            ) {
                if (isSaving) {
                    com.shopmanager.app.ui.common.AppSpinner(size = 16.dp, color = androidx.compose.material3.LocalContentColor.current)
                } else {
                    Text("إضافة")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving, shape = RectangleShape) { Text("إلغاء") } }
    )
}

/**
 * FEATURE ADDED ("تعديل المواد الثابتة بعد إضافتها"): renames one existing
 * catalog entry in place — same shape as [AddCatalogItemDialog], pre-filled
 * with the current name, but wired to `updateCatalogItem` instead of
 * `addCatalogItem`.
 */
@Composable
private fun EditCatalogItemDialog(initialName: String, isSaving: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    GlassAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("تعديل اسم المادة") },
        text = {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = "اسم المادة",
                placeholder = "اسم المادة...",
                enabled = !isSaving,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if (name.isNotBlank() && !isSaving) onSave(name.trim()) }
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !isSaving,
                shape = RectangleShape,
                onClick = { onSave(name.trim()) }
            ) {
                if (isSaving) {
                    com.shopmanager.app.ui.common.AppSpinner(size = 16.dp, color = androidx.compose.material3.LocalContentColor.current)
                } else {
                    Text("حفظ")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving, shape = RectangleShape) { Text("إلغاء") } }
    )
}

@Composable
private fun QuantityEntryDialog(
    materialName: String,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (quantity: Double, unit: String, notes: String) -> Unit
) {
    // FIX: same free-typed-decimal issue as MaterialEditDialog (see its
    // comment) - this is the dialog actually used every time a new
    // shortage is added from the catalog, so it needed the identical fix:
    // a whole-number stepper instead of a text field that could take
    // "1.5" for a كيلو entry, plus the two new نص كيلو / ربع كيلو units.
    var quantity by remember { mutableStateOf(1) }
    var unit by remember { mutableStateOf(MaterialUnit.KG) }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    GlassAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(materialName) },
        text = {
            Column {
                AppSectionTitle("الكمية المطلوبة", Modifier.padding(bottom = 8.dp))
                QuantityStepper(
                    value = quantity,
                    unitLabel = unit.label,
                    enabled = !isSaving,
                    onValueChange = { quantity = it.coerceAtLeast(1) }
                )
                AppSectionTitle("الوحدة", Modifier.padding(top = 18.dp, bottom = 8.dp))
                UnitPicker(selected = unit, enabled = !isSaving, onSelected = { unit = it })
                AppTextField(
                    value = notes, onValueChange = { notes = it }, enabled = !isSaving,
                    label = "ملاحظة (اختياري)",
                    singleLine = false, minLines = 1, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 10.dp, start = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                shape = RectangleShape,
                onClick = {
                    if (quantity <= 0) {
                        error = "أدخل كمية صحيحة"
                    } else {
                        error = null
                        onSave(quantity.toDouble(), unit.label, notes.trim())
                    }
                }
            ) {
                if (isSaving) {
                    com.shopmanager.app.ui.common.AppSpinner(size = 16.dp, color = androidx.compose.material3.LocalContentColor.current)
                } else {
                    Text("حفظ")
                }
            }
        },
        dismissButton = { TextButton(enabled = !isSaving, shape = RectangleShape, onClick = onDismiss) { Text("إلغاء") } }
    )
}
