package com.shopmanager.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shopmanager.app.data.debts.Person
import com.shopmanager.app.data.materials.Material
import com.shopmanager.app.data.notes.ImportantNote
import com.shopmanager.app.data.notes.NoteLinkType
import com.shopmanager.app.ui.common.AppTextField
import com.shopmanager.app.ui.common.BrandOnGradient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.shopmanager.app.ui.common.ScreenIconButton
import com.shopmanager.app.ui.common.ScreenTopBar

private val dateFormat get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
private val timeFormat get() = SimpleDateFormat("HH:mm", Locale.US)

/**
 * Add/edit screen for a "ملاحظة هامة". Beyond the plain title+content of a
 * simple note, this is also where the two useful ties to the rest of the
 * app live:
 *  - an optional reminder date+time (plain text fields, matching the same
 *    "yyyy-MM-dd" convention [com.shopmanager.app.ui.debts.PersonEditDialog]
 *    already uses instead of a native date picker) that schedules a real
 *    local notification - see [com.shopmanager.app.data.notifications.NoteReminderWorker].
 *  - an optional link to one existing customer (persons) or shortage item
 *    (materials), so a note like "لازم أذكّر أحمد يسدد" or "اتصل بالمورد
 *    لطلب السكر" can point at the actual record it's about instead of
 *    floating disconnected from it.
 *
 * BUG FIXED ("لازم تكون واجهة مستقلة متل المذكرة وليس مربع متل الصورة"):
 * this used to be [com.shopmanager.app.ui.common.GlassAlertDialog] - a
 * small floating card centered over the still-visible notes list behind
 * it, exactly like every other confirm/cancel popup in the app. That reads
 * fine for a two-line confirmation, but for a real writing task (a title,
 * a multi-line body, a link picker, a reminder date/time) it felt cramped
 * and, per the request, didn't read as its own place the way a real Notes
 * app's compose screen does. This keeps every field/behavior identical but
 * renders them as a genuine full-screen page instead - edge-to-edge, its
 * own top bar with a back arrow (= cancel) and a save action, no centered
 * box, no dimmed backdrop peeking through. Still hosted in a Compose
 * [Dialog] (usePlatformDefaultWidth = false, own window) purely so it
 * still floats above the bottom nav / status bar exactly like the old
 * modal did and back-press still closes it for free - the *content*
 * inside that window is a plain full-size Scaffold now, not a card.
 *
 * FEATURE ADDED ("ترابط بين الديون والملاحظات"): [defaultLinkType]/
 * [defaultLinkedId]/[defaultLinkedName] let a caller open this screen for
 * a brand-new note (initial = null, so the header still correctly reads
 * "ملاحظة جديدة") that is already pre-linked to a specific customer or
 * material - e.g. the "+" inside Person Detail's own "ملاحظات مرتبطة"
 * section pre-links straight to whichever customer's page it was opened
 * from, without this looking like an *edit* of an existing note. Only
 * used as the initial value when there's no [initial] note to read the
 * link from instead, so every existing caller (NotesScreen, which never
 * passes these) keeps behaving exactly as before.
 */
@Composable
fun NoteEditScreen(
    initial: ImportantNote?,
    persons: List<Person>,
    materials: List<Material>,
    isSaving: Boolean = false,
    defaultLinkType: NoteLinkType = NoteLinkType.NONE,
    defaultLinkedId: String = "",
    defaultLinkedName: String = "",
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, linkType: NoteLinkType, linkedId: String, linkedName: String, reminderAt: Long) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var linkType by remember { mutableStateOf(initial?.linkType ?: defaultLinkType) }
    var linkedId by remember { mutableStateOf(initial?.linkedId ?: defaultLinkedId) }
    var linkedName by remember { mutableStateOf(initial?.linkedName ?: defaultLinkedName) }

    var reminderEnabled by remember { mutableStateOf((initial?.reminderAt ?: 0L) > 0L) }
    val initialCal = remember {
        Calendar.getInstance().apply {
            val savedReminder = initial?.reminderAt ?: 0L
            if (savedReminder > 0L) timeInMillis = savedReminder
            else add(Calendar.HOUR_OF_DAY, 1)
        }
    }
    var reminderDate by remember { mutableStateOf(dateFormat.format(initialCal.time)) }
    var reminderTime by remember { mutableStateOf(timeFormat.format(initialCal.time)) }

    var linkMenuExpanded by remember { mutableStateOf(false) }
    var pickerMenuExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun computeReminderMillis(): Long? {
        if (!reminderEnabled) return 0L
        val parsed = runCatching { dateFormat.parse(reminderDate) }.getOrNull() ?: return null
        val timeParts = reminderTime.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (timeParts.size != 2) return null
        val cal = Calendar.getInstance().apply {
            time = parsed
            set(Calendar.HOUR_OF_DAY, timeParts[0])
            set(Calendar.MINUTE, timeParts[1])
            set(Calendar.SECOND, 0)
        }
        return cal.timeInMillis
    }

    fun trySave() {
        val reminderMillis = computeReminderMillis()
        when {
            title.isBlank() -> error = "الرجاء إدخال عنوان للملاحظة"
            reminderEnabled && reminderMillis == null -> error = "الرجاء إدخال تاريخ ووقت صحيحين"
            else -> {
                error = null
                onSave(title.trim(), content.trim(), linkType, linkedId, linkedName, reminderMillis ?: 0L)
            }
        }
    }

    // BUG-RISK AVOIDED: deliberately NOT re-wrapping this Dialog's content
    // in its own ShopManagerTheme - Dialog only opens a new Android Window,
    // it does not leave the surrounding Composition, so MaterialTheme's
    // colors/typography (already provided once, up in MainActivity, from
    // the person's actual saved theme/color-mode/palette settings) are
    // still inherited here for free. Re-wrapping with a fresh
    // `ShopManagerTheme { ... }` would silently reset every color back to
    // that composable's own defaults (SYSTEM/MANUAL/INDIGO) regardless of
    // what the person actually picked in Settings - this screen would be
    // the one place in the whole app that ignored their theme choice.
    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
            NoteEditScreenContent(
                isNew = initial == null,
                isSaving = isSaving,
                onBack = { if (!isSaving) onDismiss() },
                onSave = ::trySave
            ) { innerPadding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        // KEYBOARD FIX: innerPadding (which carries the IME
                        // inset) used to sit INSIDE the scroll, so the scroll
                        // viewport still spanned the whole window and the
                        // keyboard covered the lower fields. Outside the
                        // scroll, the viewport itself ends above the keyboard.
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    AppTextField(
                        value = title, onValueChange = { title = it }, enabled = !isSaving,
                        label = "العنوان", modifier = Modifier.fillMaxWidth()
                    )
                    AppTextField(
                        value = content, onValueChange = { content = it }, enabled = !isSaving,
                        label = "التفاصيل (اختياري)", singleLine = false, minLines = 4, maxLines = 10,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    )

                    // --- الربط بعميل/مادة ---
                    Text(
                        "ربط الملاحظة (اختياري)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp)
                    )
                    Box {
                        LinkTypeRow(
                            linkType = linkType,
                            linkedName = linkedName,
                            enabled = !isSaving,
                            onClick = { linkMenuExpanded = true }
                        )
                        DropdownMenu(expanded = linkMenuExpanded, onDismissRequest = { linkMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("بدون ربط") }, onClick = {
                                linkType = NoteLinkType.NONE; linkedId = ""; linkedName = ""
                                linkMenuExpanded = false
                            })
                            DropdownMenuItem(text = { Text("عميل (من الديون)") }, onClick = {
                                linkType = NoteLinkType.PERSON; linkedId = ""; linkedName = ""
                                linkMenuExpanded = false; pickerMenuExpanded = true
                            })
                            DropdownMenuItem(text = { Text("مادة (من النواقص)") }, onClick = {
                                linkType = NoteLinkType.MATERIAL; linkedId = ""; linkedName = ""
                                linkMenuExpanded = false; pickerMenuExpanded = true
                            })
                        }
                        // Picking "عميل"/"مادة" above immediately opens this
                        // second menu so choosing the *specific* one is a single
                        // continuous flow instead of a separate follow-up tap.
                        DropdownMenu(expanded = pickerMenuExpanded, onDismissRequest = { pickerMenuExpanded = false }) {
                            val options: List<Pair<String, String>> = when (linkType) {
                                NoteLinkType.PERSON -> persons.map { it.id to it.name }
                                NoteLinkType.MATERIAL -> materials.map { it.id to it.name }
                                NoteLinkType.NONE -> emptyList()
                            }
                            if (options.isEmpty()) {
                                DropdownMenuItem(text = { Text("لا يوجد عناصر بعد") }, onClick = { pickerMenuExpanded = false }, enabled = false)
                            }
                            options.forEach { (id, name) ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    linkedId = id; linkedName = name; pickerMenuExpanded = false
                                })
                            }
                        }
                    }

                    // --- التذكير ---
                    Row(
                        Modifier.fillMaxWidth().padding(top = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("تذكير بموعد محدد", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.material3.Switch(
                            checked = reminderEnabled,
                            enabled = !isSaving,
                            onCheckedChange = { reminderEnabled = it }
                        )
                    }
                    if (reminderEnabled) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppTextField(
                                value = reminderDate, onValueChange = { reminderDate = it }, enabled = !isSaving,
                                label = "التاريخ (yyyy-MM-dd)", modifier = Modifier.weight(1f)
                            )
                            AppTextField(
                                value = reminderTime, onValueChange = { reminderTime = it }, enabled = !isSaving,
                                label = "الوقت (HH:mm)", modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

/**
 * The full-screen chrome shared by the "ملاحظة جديدة"/"تعديل الملاحظة"
 * page: same liquid-glass TopAppBar recipe as every other screen in the
 * app (NotesScreen/DashboardScreen/...), with a back arrow standing in for
 * "إلغاء" and a check-mark save action standing in for the old dialog's
 * "حفظ" button - a real page's header, not a dialog's button row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditScreenContent(
    isNew: Boolean,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            ScreenTopBar(
                title = if (isNew) "ملاحظة جديدة" else "تعديل الملاحظة",
                reservedIcons = 1,
                navigation = {
                    ScreenIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "إلغاء",
                        onClick = { if (!isSaving) onBack() }
                    )
                },
                actions = {
                    if (isSaving) {
                        com.shopmanager.app.ui.common.AppSpinner(
                            modifier = Modifier.padding(end = 16.dp),
                            size = 20.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        ScreenIconButton(
                            icon = Icons.Default.Check,
                            contentDescription = "حفظ",
                            onClick = onSave
                        )
                    }
                }
            )
        }
    ) { padding -> content(padding) }
}

@Composable
private fun LinkTypeRow(linkType: NoteLinkType, linkedName: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (linkType) {
            NoteLinkType.PERSON -> Icon(Icons.Default.AttachMoney, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            NoteLinkType.MATERIAL -> Icon(Icons.Default.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            NoteLinkType.NONE -> {}
        }
        Text(
            when {
                linkType == NoteLinkType.NONE -> "بدون ربط"
                linkedName.isNotBlank() -> linkedName
                linkType == NoteLinkType.PERSON -> "اختر عميلاً..."
                else -> "اختر مادة..."
            },
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
}
