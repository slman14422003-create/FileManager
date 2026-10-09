package com.shopmanager.app.ui.debts

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PriceCheck
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopmanager.app.data.debts.Debt
import com.shopmanager.app.data.debts.Person
import com.shopmanager.app.data.notes.ImportantNote
import com.shopmanager.app.data.notes.NoteLinkType
import com.shopmanager.app.ui.common.ActionIconButton
import com.shopmanager.app.ui.common.AppSettingsState
import com.shopmanager.app.ui.common.AppTextField
import com.shopmanager.app.ui.common.GlassIconButton
import com.shopmanager.app.ui.common.GlassSnackbarHost
import com.shopmanager.app.ui.common.listItemEntrance
import com.shopmanager.app.ui.common.BrandOnGradient
import com.shopmanager.app.ui.common.DeleteIconButton
import com.shopmanager.app.ui.common.avatarColorFor
import com.shopmanager.app.ui.common.GlassAlertDialog
import com.shopmanager.app.ui.common.PhoneUtils
import com.shopmanager.app.ui.common.GlassCard
import com.shopmanager.app.ui.notes.NoteEditScreen
import com.shopmanager.app.ui.notes.NotesViewModel
import com.shopmanager.app.ui.theme.LocalSemanticColors
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.sp
import com.shopmanager.app.ui.common.AppCard
import com.shopmanager.app.ui.common.AppFootnote
import com.shopmanager.app.ui.common.AppGroupGap
import com.shopmanager.app.ui.common.AppGroupLargeRadius
import com.shopmanager.app.ui.common.AppIconCircle
import com.shopmanager.app.ui.common.AppPillButton
import com.shopmanager.app.ui.common.AppRowSurface
import com.shopmanager.app.ui.common.AppScreenPadding
import com.shopmanager.app.ui.common.AppSectionGap
import com.shopmanager.app.ui.common.AppSectionTitle
import com.shopmanager.app.ui.common.ScreenIconButton
import com.shopmanager.app.ui.common.ScreenTopBar
import com.shopmanager.app.ui.common.groupedRowShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import com.shopmanager.app.ui.common.ActionSpec
import com.shopmanager.app.ui.common.AppActionPill
import androidx.compose.material.icons.filled.Close

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    person: Person,
    onBack: () -> Unit,
    viewModel: DebtsViewModel = viewModel(),
    // "ترابط بين الديون والملاحظات": نفس نسخة NotesViewModel المشتركة اللي
    // يستخدمها تبويب "ملاحظات هامة" - راجع MainActivity، اللي يمررها هون
    // بدل ما يترك القيمة الافتراضية تنشئ نسخة جديدة منفصلة (تصير عندها
    // مستمع Firestore خاص فيها وتخرج عن مزامنة مع باقي الشاشة).
    notesViewModel: NotesViewModel = viewModel()
) {
    // PERF FIX: debtsForPerson() builds a brand-new callbackFlow on every call.
    // Calling it straight inside collectAsState meant a NEW flow instance on
    // every recomposition (typing in a field, opening a dialog, ...), which
    // tore down and re-registered this customer's Firestore listener each time -
    // flicker, extra reads, wasted battery. Cached per customer id now.
    val debtsFlow = remember(person.id) { viewModel.debtsForPerson(person.id) }
    val debts by debtsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var editingDebt by remember { mutableStateOf<Debt?>(null) }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(today()) }
    var note by remember { mutableStateOf("") }
    var showDeletePersonConfirm by remember { mutableStateOf(false) }
    var deleteDebtTarget by remember { mutableStateOf<String?>(null) }
    var payDebtTarget by remember { mutableStateOf<Debt?>(null) }
    // "ملاحظات مرتبطة": كل الملاحظات اللي نوعها PERSON ومربوطة بهذا العميل
    // بالذات - نفس منطق الفلترة اللي NotesScreen يعرضه بتبويب "الديون".
    val allPersons = viewModel.uiState.collectAsStateWithLifecycle().value.persons
    val notesState = notesViewModel.uiState.collectAsStateWithLifecycle().value
    val linkedNotes = remember(notesState.notes, person.id) {
        notesState.notes.filter { it.linkType == NoteLinkType.PERSON && it.linkedId == person.id }
    }
    var editingLinkedNote by remember { mutableStateOf<ImportantNote?>(null) }
    var showAddLinkedNote by remember { mutableStateOf(false) }
    var isSavingLinkedNote by remember { mutableStateOf(false) }
    var deleteNoteTarget by remember { mutableStateOf<ImportantNote?>(null) }
    // FEATURE ADDED ("تعديل اسم الشخص من واجهة تفاصيل الديون"): the
    // update-person write path (DebtsViewModel.savePerson with a non-null
    // existingId -> DebtsRepository.updatePerson) already existed, but
    // nothing in the UI ever called it with an id — DebtsScreen only ever
    // calls savePerson(null, ...) to create a *new* person, so renaming an
    // existing customer had no entry point anywhere in the app. This adds
    // one here, right where it's needed most: while already looking at
    // that specific customer's page.
    var showEditNameDialog by remember { mutableStateOf(false) }
    var editNameText by remember { mutableStateOf("") }
    var editPhoneText by remember { mutableStateOf("") }
    var editPhoneError by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var isSavingName by remember { mutableStateOf(false) }
    val nf = remember { NumberFormat.getNumberInstance(Locale("ar")) }
    val avatarColor = remember(person.name) { avatarColorFor(person.name) }

    LaunchedEffect(message) {
        message?.let { snackbarHost.showSnackbar(it); viewModel.clearMessage() }
    }

    val total = debts.sumOf { it.amount }

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
                title = person.name,
                reservedIcons = 2,
                navigation = {
                    ScreenIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        onClick = onBack
                    )
                },
                actions = {
                    AppActionPill(
                        height = 42.dp,
                        container = MaterialTheme.colorScheme.surfaceContainerHigh,
                        actions = listOf(
                            ActionSpec(Icons.Default.Edit, "تعديل بيانات العميل") {
                                editNameText = person.name
                                editPhoneText = person.phone
                                editPhoneError = false
                                showEditNameDialog = true
                            },
                            ActionSpec(Icons.Default.Delete, "حذف العميل", LocalSemanticColors.current.danger) {
                                showDeletePersonConfirm = true
                            }
                        )
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = AppScreenPadding, end = AppScreenPadding, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(AppGroupGap)
        ) {
            item {
                PersonHeader(
                    name = person.name,
                    avatarColor = avatarColor,
                    total = total,
                    debtsCount = debts.size,
                    nf = nf,
                    phone = person.phone,
                    onCall = { PhoneUtils.call(context, person.phone) },
                    onWhatsApp = { PhoneUtils.whatsApp(context, person.phone) },
                    onSms = { PhoneUtils.sms(context, person.phone) }
                )
            }

            item { Spacer(Modifier.height(AppSectionGap - AppGroupGap)) }

            item {
                AddDebtCard(
                    isEditing = editingDebt != null,
                    amount = amount,
                    date = date,
                    note = note,
                    onAmountChange = { amount = it },
                    onDateChange = { date = it },
                    onNoteChange = { note = it },
                    onCancelEdit = { editingDebt = null; amount = ""; date = today(); note = "" },
                    onSubmit = {
                        val a = amount.trim().toDoubleOrNull()
                        if (a != null && a > 0 && date.isNotBlank()) {
                            viewModel.addOrUpdateDebt(editingDebt?.id, person.id, a, date, note.trim())
                            amount = ""
                            date = today()
                            note = ""
                            editingDebt = null
                        }
                    }
                )
            }

            item { Spacer(Modifier.height(AppSectionGap - AppGroupGap)) }

            item { AppSectionTitle("سجل الديون") }

            item { Spacer(Modifier.height(4.dp)) }

            if (debts.isEmpty()) {
                item {
                    AppRowSurface(shape = RoundedCornerShape(AppGroupLargeRadius)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PriceCheck, contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "لا يوجد ديون مسجلة",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(debts, key = { _, debt -> debt.id }, contentType = { _, _ -> "debt" }) { index, debt ->
                    DebtRow(
                        debt = debt,
                        nf = nf,
                        shape = groupedRowShape(index, debts.lastIndex),
                        onEdit = {
                            editingDebt = debt
                            amount = debt.amount.toString()
                            date = debt.date
                            note = debt.note
                        },
                        onDelete = { deleteDebtTarget = debt.id },
                        onMarkPaid = { payDebtTarget = debt },
                        modifier = Modifier.listItemEntrance(index)
                    )
                }
            }

            item { Spacer(Modifier.height(AppSectionGap - AppGroupGap)) }

            item {
                AppSectionTitle(
                    text = "ملاحظات مرتبطة",
                    trailing = {
                        ActionIconButton(
                            icon = Icons.Default.Add,
                            tint = MaterialTheme.colorScheme.onSurface,
                            contentDescription = "إضافة ملاحظة لهذا العميل",
                            onClick = { showAddLinkedNote = true }
                        )
                    }
                )
            }

            item { Spacer(Modifier.height(4.dp)) }

            if (linkedNotes.isEmpty()) {
                item {
                    AppFootnote("لا توجد ملاحظات مرتبطة بهذا العميل بعد")
                }
            } else {
                itemsIndexed(linkedNotes, key = { _, n -> "linkedNote_${n.id}" }, contentType = { _, _ -> "linkedNote" }) { index, linkedNote ->
                    LinkedNoteRow(
                        note = linkedNote,
                        shape = groupedRowShape(index, linkedNotes.lastIndex),
                        onToggleDone = { notesViewModel.setDone(linkedNote, !linkedNote.isDone) },
                        onEdit = { editingLinkedNote = linkedNote },
                        onDelete = { deleteNoteTarget = linkedNote }
                    )
                }
            }
        }
    }

    if (showDeletePersonConfirm) {
        GlassAlertDialog(
            onDismissRequest = { showDeletePersonConfirm = false },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد من حذف \"${person.name}\" وكل ديونه؟") },
            confirmButton = {
                // BUG FIXED ("بدي الضغطة بشكل مربع كامل وليس دائري"): see
                // PersonEditDialog.kt's doc comment.
                TextButton(shape = RectangleShape, onClick = {
                    viewModel.deletePerson(person.id)
                    showDeletePersonConfirm = false
                    onBack()
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { showDeletePersonConfirm = false }) { Text("إلغاء") } }
        )
    }

    deleteDebtTarget?.let { id ->
        GlassAlertDialog(
            onDismissRequest = { deleteDebtTarget = null },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد من حذف هذا الدين؟") },
            confirmButton = {
                TextButton(shape = RectangleShape, onClick = {
                    viewModel.deleteDebt(id)
                    deleteDebtTarget = null
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { deleteDebtTarget = null }) { Text("إلغاء") } }
        )
    }

    payDebtTarget?.let { debt ->
        GlassAlertDialog(
            onDismissRequest = { payDebtTarget = null },
            icon = { Icon(Icons.Default.Check, contentDescription = null, tint = LocalSemanticColors.current.success) },
            title = { Text("تأكيد السداد") },
            text = { Text("هل \"${person.name}\" وفى ${nf.format(debt.amount)} ${AppSettingsState.currencySymbol}؟ سيتم حذف هذا الدين من السجل وإرسال إشعار.") },
            confirmButton = {
                TextButton(shape = RectangleShape, onClick = {
                    viewModel.markDebtAsPaid(debt, person.name)
                    payDebtTarget = null
                }) { Text("تم السداد", color = LocalSemanticColors.current.success, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { payDebtTarget = null }) { Text("إلغاء") } }
        )
    }
    if (showEditNameDialog) {
        GlassAlertDialog(
            onDismissRequest = { if (!isSavingName) showEditNameDialog = false },
            icon = { Icon(Icons.Default.Edit, contentDescription = null, tint = LocalSemanticColors.current.info) },
            title = { Text("تعديل بيانات العميل") },
            text = {
                Column {
                    Column(verticalArrangement = Arrangement.spacedBy(AppGroupGap)) {
                        AppTextField(
                            value = editNameText,
                            onValueChange = { editNameText = it },
                            label = "اسم العميل",
                            enabled = !isSavingName,
                            shape = groupedRowShape(0, 1)
                        )
                        AppTextField(
                            value = editPhoneText,
                            onValueChange = { editPhoneText = it; editPhoneError = false },
                            label = "رقم الهاتف (اختياري)",
                            placeholder = "09xxxxxxxx",
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                            enabled = !isSavingName,
                            isError = editPhoneError,
                            shape = groupedRowShape(1, 1)
                        )
                    }
                    if (editPhoneError) {
                        Text(
                            "رقم الهاتف غير صحيح",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 10.dp, start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = editNameText.isNotBlank() && !isSavingName,
                    shape = RectangleShape,
                    onClick = {
                        val newName = editNameText.trim()
                        val newPhone = editPhoneText.trim()
                        if (!PhoneUtils.isValidOrBlank(newPhone)) {
                            editPhoneError = true
                        } else if (newName.isNotEmpty() && (newName != person.name || newPhone != person.phone)) {
                            isSavingName = true
                            // Only the name/phone change here — amount/date are
                            // passed through unchanged (updatePerson writes
                            // them together), so this can't silently
                            // clobber the customer's existing balance/date.
                            viewModel.savePerson(person.id, newName, person.amount, person.date, phone = newPhone) { success ->
                                isSavingName = false
                                if (success) showEditNameDialog = false
                            }
                        } else {
                            showEditNameDialog = false
                        }
                    }
                ) {
                    if (isSavingName) {
                        com.shopmanager.app.ui.common.AppSpinner(size = 16.dp, color = androidx.compose.material3.LocalContentColor.current)
                    } else {
                        Text("حفظ")
                    }
                }
            },
            dismissButton = {
                TextButton(enabled = !isSavingName, shape = RectangleShape, onClick = { showEditNameDialog = false }) { Text("إلغاء") }
            }
        )
    }

    // "ترابط بين الديون والملاحظات": إضافة ملاحظة جديدة مربوطة تلقائيًا
    // بهذا العميل - initial = null (فالعنوان يضل "ملاحظة جديدة" فعليًا،
    // مو "تعديل") مع defaultLinkType/defaultLinkedId/defaultLinkedName
    // لتعبئة الربط مسبقًا - راجع NoteEditDialog.kt للتفصيل.
    if (showAddLinkedNote) {
        NoteEditScreen(
            initial = null,
            persons = allPersons,
            materials = emptyList(),
            isSaving = isSavingLinkedNote,
            defaultLinkType = NoteLinkType.PERSON,
            defaultLinkedId = person.id,
            defaultLinkedName = person.name,
            onDismiss = { if (!isSavingLinkedNote) showAddLinkedNote = false },
            onSave = { title, content, linkType, linkedId, linkedName, reminderAt ->
                isSavingLinkedNote = true
                notesViewModel.addNote(title, content, linkType, linkedId, linkedName, reminderAt) { success ->
                    isSavingLinkedNote = false
                    if (success) showAddLinkedNote = false
                }
            }
        )
    }

    editingLinkedNote?.let { current ->
        NoteEditScreen(
            initial = current,
            persons = allPersons,
            materials = emptyList(),
            isSaving = isSavingLinkedNote,
            onDismiss = { if (!isSavingLinkedNote) editingLinkedNote = null },
            onSave = { title, content, linkType, linkedId, linkedName, reminderAt ->
                isSavingLinkedNote = true
                notesViewModel.updateNote(current, title, content, linkType, linkedId, linkedName, reminderAt) { success ->
                    isSavingLinkedNote = false
                    if (success) editingLinkedNote = null
                }
            }
        )
    }

    deleteNoteTarget?.let { targetNote ->
        GlassAlertDialog(
            onDismissRequest = { deleteNoteTarget = null },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد من حذف الملاحظة \"${targetNote.title}\"؟") },
            confirmButton = {
                TextButton(shape = RectangleShape, onClick = { notesViewModel.deleteNote(targetNote); deleteNoteTarget = null }) { Text("حذف") }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { deleteNoteTarget = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun PersonHeader(
    name: String,
    avatarColor: Color,
    total: Double,
    debtsCount: Int,
    nf: NumberFormat,
    phone: String = "",
    onCall: () -> Unit = {},
    onWhatsApp: () -> Unit = {},
    onSms: () -> Unit = {}
) {
    // بطاقة الملخص بنفس شكل بطاقة الحساب في الإعدادات: مسطّحة وزواياها كبيرة.
    AppCard {
        Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconCircle(color = avatarColor, size = 52.dp) {
                Text(
                    name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "إجمالي الديون",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "${nf.format(total)} ${AppSettingsState.currencySymbol}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(12.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface) {
                Text(
                    "$debtsCount عملية",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.surface
                )
            }
        }
        // رقم العميل (اختياري): يظهر مع أزرار تواصل سريعة فقط إن كان محفوظاً.
        if (phone.isNotBlank()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
            Text(
                "\u200E$phone",
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ContactActionButton(Modifier.weight(1f), Icons.Default.Call, "اتصال", MaterialTheme.colorScheme.onSurface, onCall)
                ContactActionButton(Modifier.weight(1f), Icons.AutoMirrored.Filled.Chat, "واتساب", LocalSemanticColors.current.success, onWhatsApp)
                ContactActionButton(Modifier.weight(1f), Icons.AutoMirrored.Filled.Send, "رسالة", LocalSemanticColors.current.info, onSms)
            }
        }
        }
    }
}

@Composable
private fun ContactActionButton(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDebtCard(
    isEditing: Boolean,
    amount: String,
    date: String,
    note: String,
    onAmountChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSubmit: () -> Unit
) {
    // طلب "دمج نمط الـ Glassmorphism": نفس البطاقة، بستايل الزجاج الموحّد
    // (GlassCard) بدل الـ Surface المسطحة - راجع الشرح الكامل بـ GlassCard.kt.
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (isEditing) "تعديل الدين" else "إضافة دين جديد",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Top) {
                AppTextField(
                    value = amount, onValueChange = onAmountChange,
                    label = "المبلغ (${AppSettingsState.currencySymbol})",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                AppTextField(
                    value = date, onValueChange = onDateChange,
                    label = "التاريخ",
                    leadingIcon = Icons.Default.CalendarMonth,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            AppTextField(
                value = note, onValueChange = onNoteChange,
                label = "ملاحظة (اختياري)",
                leadingIcon = Icons.Default.Notes,
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppPillButton(
                    label = if (isEditing) "حفظ التعديل" else "إضافة الدين",
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f)
                )
                if (isEditing) {
                    AppPillButton(label = "إلغاء", onClick = onCancelEdit, tonal = true)
                }
            }
        }
    }
}

@Composable
private fun DebtRow(
    debt: Debt,
    nf: NumberFormat,
    shape: Shape,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMarkPaid: () -> Unit,
    modifier: Modifier = Modifier
) {
    AppRowSurface(shape = shape, modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${nf.format(debt.amount)} ${AppSettingsState.currencySymbol}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    debt.date,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (debt.note.isNotBlank()) {
                    Row(
                        Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Notes, contentDescription = null,
                            modifier = Modifier.size(14.dp).padding(top = 2.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            debt.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            AppActionPill(
                actions = listOf(
                    ActionSpec(Icons.Default.Check, "تسجيل السداد", LocalSemanticColors.current.success, onMarkPaid),
                    ActionSpec(Icons.Default.Edit, "تعديل", null, onEdit),
                    ActionSpec(Icons.Default.Close, "حذف الدين", LocalSemanticColors.current.danger, onDelete)
                )
            )
        }
    }
}

/**
 * "ترابط بين الديون والملاحظات": صف مختصر لملاحظة مربوطة بهذا العميل -
 * نفس تخطيط [DebtRow] فوق (Surface بحدّ رفيع بدل ظل، وزرّي تعديل/حذف
 * دائريين بنفس الحجم) عشان يبين كإكمال طبيعي لنفس القائمة، بدل قسم بستايل
 * مختلف. التبديل/التعديل/الحذف الفعلي بيصير عبر NotesViewModel مباشرة -
 * نفس الكائن المشترك اللي تبويب "ملاحظات هامة" يستخدمه.
 */
@Composable
private fun LinkedNoteRow(
    note: ImportantNote,
    shape: Shape,
    onToggleDone: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AppRowSurface(shape = shape) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionIconButton(
                icon = if (note.isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                tint = if (note.isDone) LocalSemanticColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = "تم",
                onClick = onToggleDone
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    note.title,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (note.isDone) TextDecoration.LineThrough else null,
                    color = if (note.isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (note.content.isNotBlank()) {
                    Text(
                        note.content,
                        style = MaterialTheme.typography.labelSmall,
                        // LIGHT-MODE CONTRAST FIX: see the matching note on
                        // DebtRow's debt.note text above — `outline` fails
                        // text contrast in light mode, onSurfaceVariant is
                        // this app's established secondary-text color.
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                if (note.reminderAt > 0) {
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule, contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            remember(note.reminderAt) {
                                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(note.reminderAt))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            AppActionPill(
                actions = listOf(
                    ActionSpec(Icons.Default.Edit, "تعديل الملاحظة", null, onEdit),
                    ActionSpec(Icons.Default.Close, "حذف الملاحظة", LocalSemanticColors.current.danger, onDelete)
                )
            )
        }
    }
}
