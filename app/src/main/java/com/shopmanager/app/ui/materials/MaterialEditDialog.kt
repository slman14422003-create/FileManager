package com.shopmanager.app.ui.materials

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shopmanager.app.data.materials.Material
import com.shopmanager.app.data.materials.MaterialUnit
import com.shopmanager.app.ui.common.AppGroupGap
import com.shopmanager.app.ui.common.AppGroupLargeRadius
import com.shopmanager.app.ui.common.AppSectionTitle
import com.shopmanager.app.ui.common.AppTextField
import com.shopmanager.app.ui.common.groupedRowShape
import com.shopmanager.app.ui.common.MotionSpecs
import com.shopmanager.app.ui.common.GlassAlertDialog

@Composable
fun MaterialEditDialog(
    initial: Material?,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (name: String, quantity: Double, unit: String, notes: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // FIX: quantity used to be a free-typed decimal field, which is why
    // "1.5" (or any other decimal) could end up on a كيلو shortage instead
    // of a plain whole count. Quantity is now always a whole number - you
    // pick the size in the unit row below (كيلو / نص كيلو / ربع كيلو /
    // لوقية / نص لوقية / ربع لوقية) and this is just "how many of that
    // size", so typing/typo'd decimals can't happen anymore.
    var quantity by remember {
        mutableStateOf((initial?.quantity?.toInt() ?: 1).coerceAtLeast(1))
    }
    var unit by remember { mutableStateOf(MaterialUnit.fromLabel(initial?.unit ?: MaterialUnit.KG.label)) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    // FIX: a fixed fraction size (نص كيلو، ربع كيلو، لوقية، نص لوقية، ربع
    // لوقية) is already exactly one of itself the moment it's picked -
    // "2 نص كيلو" isn't a size anyone actually orders in, they'd just pick
    // كيلو instead. So the quantity stepper only makes sense for كيلو
    // (where "2 كيلو" is a normal amount) and بدون (a plain count with no
    // size at all, like "بيض: 6"). Switching to any of the other units
    // hides the stepper and pins quantity to 1 so no stale count "1
    // نص كيلو من 3" -> tapping "نص كيلو" would carry a leftover count.
    val showQuantityStepper = unit == MaterialUnit.KG || unit == MaterialUnit.NONE

    GlassAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(if (initial == null) "إضافة نقص" else "تعديل النقص") },
        text = {
            Column {
                // الاسم + الكمية كمجموعة صفوف متلاصقة (مثل مجموعات الإعدادات).
                Column(verticalArrangement = Arrangement.spacedBy(AppGroupGap)) {
                    AppTextField(
                        value = name, onValueChange = { name = it }, enabled = !isSaving,
                        label = "اسم المادة", modifier = Modifier.fillMaxWidth(),
                        shape = groupedRowShape(0, 1)
                    )
                    if (showQuantityStepper) {
                        QuantityStepper(
                            value = quantity,
                            unitLabel = unit.label,
                            enabled = !isSaving,
                            shape = groupedRowShape(1, 1),
                            onValueChange = { quantity = it.coerceAtLeast(1) }
                        )
                    } else {
                        // Quantity is implicitly 1 for a fixed fraction size -
                        // nothing to step, so this just confirms what will be
                        // saved instead of showing a stepper with nothing to do.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clip(groupedRowShape(1, 1))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "الكمية المطلوبة",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                unit.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                AppSectionTitle("الوحدة", Modifier.padding(top = 18.dp, bottom = 8.dp))
                UnitPicker(
                    selected = unit,
                    enabled = !isSaving,
                    onSelected = {
                        unit = it
                        if (it != MaterialUnit.KG && it != MaterialUnit.NONE) quantity = 1
                    }
                )

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
            // BUG FIXED ("بدي الضغطة بشكل مربع كامل وليس دائري"): TextButton's
            // default shape is a fully-rounded pill, so even though
            // GlassAlertDialog's DialogButtonCell already forces this button
            // to fill its whole half of the row, the ripple/press highlight
            // itself stayed clipped to that rounded outline instead of the
            // actual rectangular cell. RectangleShape makes the press
            // highlight fill the entire square cell, corner to corner.
            TextButton(
                enabled = !isSaving,
                shape = RectangleShape,
                onClick = {
                    when {
                        name.isBlank() -> error = "يرجى إدخال اسم المادة"
                        quantity <= 0 -> error = "يرجى إدخال كمية صحيحة"
                        else -> { error = null; onSave(name.trim(), quantity.toDouble(), unit.label, notes.trim()) }
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

/**
 * عدّاد الكمية (أعداد صحيحة فقط): صفٌّ مسطّح بلغة صفوف الإعدادات، اسم الوحدة
 * المختارة في البداية (مثل "2 نص كيلو") وأزرار [-] الرقم [+] في النهاية. الرقم
 * قابل للكتابة المباشرة وتُحذف منه كل الرموز غير الرقمية، فلا طريق لقيمة عشرية.
 *
 * FIX: الكمية والوحدة لا تُدمجان أبداً في نص واحد: الحقل يحمل أرقام الكمية فقط،
 * واسم الوحدة نص منفصل غير قابل للتعديل (ويختفي لـ [MaterialUnit.NONE] لأن
 * تسميته فارغة).
 */
@Composable
fun QuantityStepper(
    value: Int,
    unitLabel: String,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppGroupLargeRadius)
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(cs.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            unitLabel,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = cs.onSurfaceVariant
        )

        StepButton(
            icon = Icons.Default.Remove,
            contentDescription = "إنقاص",
            enabled = enabled && value > 1,
            onClick = { onValueChange(value - 1) }
        )

        BasicTextField(
            value = value.toString(),
            onValueChange = { raw ->
                val digitsOnly = raw.filter { it.isDigit() }
                onValueChange(digitsOnly.toIntOrNull() ?: 0)
            },
            modifier = Modifier.width(64.dp),
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(
                fontSize = 20.sp,
                color = cs.onSurface,
                textAlign = TextAlign.Center
            ),
            cursorBrush = SolidColor(cs.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        StepButton(
            icon = Icons.Default.Add,
            contentDescription = "زيادة",
            enabled = enabled,
            onClick = { onValueChange(value + 1) }
        )
    }
}

/** زر دائري صغير للعدّاد: دائرة بلون surface فوق تعبئة الصف، أيقونة بلون onSurface. */
@Composable
private fun StepButton(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(cs.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = cs.onSurface.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The text shown ON the picker pill for each unit. Every weight unit shows
 * its own label as-is; [MaterialUnit.NONE] has an empty stored label (so a
 * saved quantity like "6" has nothing appended to it - see
 * [com.shopmanager.app.data.materials.quantityLabel]), but the pill itself
 * still needs something to display, hence "بدون" here instead of the blank
 * stored value.
 */
private val MaterialUnit.pickerLabel: String
    get() = if (this == MaterialUnit.NONE) "بدون" else label

/**
 * منتقي الوحدة: حبّات على نمط [com.shopmanager.app.ui.common.AppChip] — المحددة
 * بلون onSurface ونص surface، وغير المحددة على تعبئة الصفوف. ثلاث حبّات في كل
 * صف (عائلة الكيلو ثم عائلة اللوقية) و"بدون" وحدها بعرض كامل لأنها نوع مختلف من
 * الاختيار (لا وحدة أصلاً).
 */
@Composable
fun UnitPicker(selected: MaterialUnit, enabled: Boolean = true, onSelected: (MaterialUnit) -> Unit, modifier: Modifier = Modifier) {
    val weightRows = listOf(
        listOf(MaterialUnit.KG, MaterialUnit.HALF_KG, MaterialUnit.QUARTER_KG),
        listOf(MaterialUnit.OKE, MaterialUnit.HALF_OKE, MaterialUnit.QUARTER_OKE)
    )
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        weightRows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { option ->
                    UnitPill(option = option, isSelected = option == selected, enabled = enabled, onSelected = onSelected, modifier = Modifier.weight(1f))
                }
            }
        }
        UnitPill(option = MaterialUnit.NONE, isSelected = selected == MaterialUnit.NONE, enabled = enabled, onSelected = onSelected, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun UnitPill(option: MaterialUnit, isSelected: Boolean, enabled: Boolean = true, onSelected: (MaterialUnit) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) cs.onSurface else cs.surfaceContainerHighest,
        animationSpec = MotionSpecs.quickSpring(), label = "unitPillBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) cs.surface else cs.onSurface,
        animationSpec = MotionSpecs.quickSpring(), label = "unitPillText"
    )

    Box(
        modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = { onSelected(option) }),
        contentAlignment = Alignment.Center
    ) {
        Text(
            option.pickerLabel,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center
        )
    }
}
