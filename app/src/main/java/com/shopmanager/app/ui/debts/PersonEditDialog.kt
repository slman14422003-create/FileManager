package com.shopmanager.app.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shopmanager.app.data.debts.Person
import com.shopmanager.app.ui.common.AppGroupGap
import com.shopmanager.app.ui.common.AppSettingsState
import com.shopmanager.app.ui.common.AppTextField
import com.shopmanager.app.ui.common.GlassAlertDialog
import com.shopmanager.app.ui.common.PhoneUtils
import com.shopmanager.app.ui.common.groupedRowShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/**
 * BUG FIXED: this dialog used to have no loading/disabled state at all while
 * savePerson() was in flight (it does a name-uniqueness query, then the
 * write - both real network round trips). Nothing stopped repeated taps, and
 * there was zero visual feedback, so the dialog looked "stuck" even though
 * it had already saved - and repeated taps could race and create duplicate
 * customers. Now the confirm button disables and shows a spinner the moment
 * saving starts, until the parent screen confirms success or failure.
 *
 * BUG FIXED (خانة الملاحظات ناقصة بواجهة "عميل جديد"): "إضافة دين" على
 * عميل موجود مسبقاً (AddDebtCard بواجهة تفاصيل العميل) كان دايماً فيه خانة
 * "ملاحظة (اختياري)" تنحفظ مع الدين - لكن أول دين بينخلق تلقائياً وقت إضافة
 * عميل جديد من هالنافذة كان دايماً بملاحظة فارغة "" لأنه ما كان في خانة
 * أصلاً هون. أضفنا نفس خانة الملاحظة هون كمان، فأول دين لعميل جديد صار فيه
 * نفس ميزة الملاحظة متل أي دين ثاني.
 *
 * BUG FIXED ("بدي الضغطة بشكل مربع كامل وليس دائري" على "عميل جديد"):
 * this exact fix already existed on MaterialEditDialog's own confirm/dismiss
 * buttons — TextButton's default shape is a fully-rounded pill, so even
 * though GlassAlertDialog's DialogButtonCell already stretches the button to
 * fill its whole half of the row, the ripple/press highlight itself stayed
 * clipped to that rounded pill instead of the actual rectangular cell — it
 * had just never been applied to this dialog (or any of the many others
 * built the same way; see GlassAlertDialog.kt's class doc for the rest).
 * RectangleShape here makes the highlight fill the entire cell corner to
 * corner; GlassAlertDialog's own outer liquidGlassSurface already clips
 * everything to the dialog's rounded outline, so the bottom two cells still
 * end up visually matching that outline despite being RectangleShape
 * themselves underneath.
 */
@Composable
fun PersonEditDialog(
    initial: Person?,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (name: String, amount: Double, date: String, note: String, phone: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var amount by remember { mutableStateOf(initial?.amount?.toString() ?: "") }
    var date by remember { mutableStateOf(initial?.date?.ifBlank { today() } ?: today()) }
    var note by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    GlassAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (initial == null) "عميل جديد" else "تعديل العميل") },
        text = {
            // حقول النموذج مجمّعة كصفوف الإعدادات: متلاصقة بفاصل صغير، الأول فقط
            // بزوايا علوية كبيرة والأخير فقط بزوايا سفلية كبيرة.
            Column {
                Column(verticalArrangement = Arrangement.spacedBy(AppGroupGap)) {
                    AppTextField(
                        value = name, onValueChange = { name = it }, enabled = !isSaving,
                        label = "اسم العميل", modifier = Modifier.fillMaxWidth(),
                        shape = groupedRowShape(0, 4)
                    )
                    // رقم العميل — اختياري، للاتصال/واتساب/رسالة من صفحة العميل.
                    AppTextField(
                        value = phone, onValueChange = { phone = it }, enabled = !isSaving,
                        label = "رقم الهاتف (اختياري)",
                        placeholder = "09xxxxxxxx",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = groupedRowShape(1, 4)
                    )
                    AppTextField(
                        value = amount, onValueChange = { amount = it }, enabled = !isSaving,
                        label = if (initial == null) "الدين الأولي (${AppSettingsState.currencySymbol})" else "المبلغ (${AppSettingsState.currencySymbol})",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = groupedRowShape(2, 4)
                    )
                    AppTextField(
                        value = date, onValueChange = { date = it }, enabled = !isSaving,
                        label = "التاريخ (yyyy-MM-dd)", modifier = Modifier.fillMaxWidth(),
                        shape = groupedRowShape(3, 4)
                    )
                    AppTextField(
                        value = note, onValueChange = { note = it }, enabled = !isSaving,
                        label = "ملاحظة (اختياري)",
                        singleLine = false, minLines = 1, maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        shape = groupedRowShape(4, 4)
                    )
                }
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
                    val amountValue = amount.trim().toDoubleOrNull()
                    when {
                        name.isBlank() -> error = "الرجاء إدخال اسم العميل"
                        !PhoneUtils.isValidOrBlank(phone) -> error = "رقم الهاتف غير صحيح"
                        amountValue == null || amountValue < 0 -> error = "الرجاء إدخال مبلغ صحيح"
                        date.isBlank() -> error = "الرجاء اختيار التاريخ"
                        else -> { error = null; onSave(name.trim(), amountValue, date, note.trim(), phone.trim()) }
                    }
                }
            ) {
                if (isSaving) {
                    Row {
                        com.shopmanager.app.ui.common.AppSpinner(size = 16.dp, color = androidx.compose.material3.LocalContentColor.current)
                        Text("  جارِ الحفظ...")
                    }
                } else {
                    Text("حفظ")
                }
            }
        },
        dismissButton = { TextButton(enabled = !isSaving, shape = RectangleShape, onClick = onDismiss) { Text("إلغاء") } }
    )
}
