package com.shopmanager.app.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopmanager.app.ui.common.MotionSpecs
import com.shopmanager.app.ui.theme.ClaudeOrangeDark
import com.shopmanager.app.ui.theme.ClaudeOrangeLight
import com.shopmanager.app.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch

// ============================================================================
// إعادة تصميم شاشة الإعدادات على نمط شاشة Settings في تطبيق Claude:
//  - كل خيار صفٌّ مستقل (أيقونة خطية + عنوان + سطر ثانوي رمادي) على بطاقة مسطّحة.
//  - صفوف المجموعة الواحدة متلاصقة بفاصل صغير (3dp)؛ الصف الأول فقط له زوايا
//    علوية كبيرة، والأخير فقط زوايا سفلية كبيرة، وما بينهما زوايا صغيرة جداً.
//  - بين المجموعات فاصل أكبر (14dp).
//  - الخيارات المتعددة (المظهر/الأداء) تُفتح في ورقة سفلية بدل سرد كل
//    الخيارات في الصفحة، فتقصر الشاشة وتصبح أسرع في الرسم والتمرير.
// ============================================================================

internal val GroupLargeRadius = 20.dp
internal val GroupSmallRadius = 4.dp
internal val GroupItemGap = 3.dp
internal val ScreenGap = 14.dp

private fun rowShape(index: Int, lastIndex: Int): Shape {
    val top = if (index == 0) GroupLargeRadius else GroupSmallRadius
    val bottom = if (index == lastIndex) GroupLargeRadius else GroupSmallRadius
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** يجمع صفوف المجموعة ليعرف كل صف موقعه (أول/وسط/أخير) فيحدد زواياه تلقائياً —
 * حتى لو كانت بعض الصفوف شرطية (تظهر وتختفي حسب الحالة). */
internal class SettingsGroupScope {
    internal val entries = mutableListOf<@Composable (Shape) -> Unit>()

    fun item(
        icon: ImageVector? = null,
        title: String,
        subtitle: String? = null,
        enabled: Boolean = true,
        danger: Boolean = false,
        onClick: (() -> Unit)? = null,
        trailing: (@Composable () -> Unit)? = null
    ) {
        entries.add { shape ->
            SettingsRow(shape, icon, title, subtitle, enabled, danger, onClick, trailing)
        }
    }

    fun switchItem(
        icon: ImageVector? = null,
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit
    ) {
        entries.add { shape ->
            SettingsSwitchRow(shape, icon, title, subtitle, checked, enabled, onCheckedChange)
        }
    }

    /** صف خيار مفرد: علامة ✓ في النهاية للخيار المحدد. */
    fun selectItem(
        icon: ImageVector? = null,
        title: String,
        subtitle: String? = null,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        item(
            icon = icon,
            title = title,
            subtitle = subtitle,
            onClick = onClick,
            trailing = if (selected) CheckTrailing else null
        )
    }

    fun custom(content: @Composable (Shape) -> Unit) {
        entries.add(content)
    }
}

@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: SettingsGroupScope.() -> Unit
) {
    val entries = SettingsGroupScope().apply(content).entries
    Column(
        modifier
            .fillMaxWidth()
            // تغيّر ارتفاع المجموعة (ظهور/اختفاء صف) ينزلق بدل أن يقفز.
            .animateContentSize(tween(MotionSpecs.expandMillis(), easing = MotionSpecs.claudeEasing)),
        verticalArrangement = Arrangement.spacedBy(GroupItemGap)
    ) {
        val last = entries.lastIndex
        entries.forEachIndexed { index, entry -> entry(rowShape(index, last)) }
    }
}

@Composable
private fun SettingsRow(
    shape: Shape,
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    danger: Boolean,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)?
) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val content: @Composable () -> Unit = {
        val titleColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = titleColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = fill,
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    } else {
        Surface(shape = shape, color = fill, modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsSwitchRow(
    shape: Shape,
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    SettingsRow(
        shape = shape,
        icon = icon,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        danger = false,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckedChange(!checked)
        },
        trailing = {
            // الصف كله قابل للّمس، فالمفتاح نفسه للعرض فقط (onCheckedChange = null).
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = settingsSwitchColors()
            )
        }
    )
}

@Composable
private fun settingsSwitchColors(): SwitchColors {
    val blue = if (LocalIsDarkTheme.current) ClaudeOrangeDark else ClaudeOrangeLight
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = blue,
        checkedBorderColor = Color.Transparent,
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        uncheckedBorderColor = MaterialTheme.colorScheme.outline
    )
}

private val CheckTrailing: @Composable () -> Unit = { SettingsCheck() }

@Composable
internal fun SettingsCheck() {
    Icon(
        Icons.Filled.Check,
        contentDescription = "محدد",
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp)
    )
}

internal val SpinnerTrailing: @Composable () -> Unit = { SettingsSpinner() }

@Composable
internal fun SettingsSpinner() {
    com.shopmanager.app.ui.common.AppSpinner(
        size = 20.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun SettingsStatusDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** سطر رمادي صغير تحت المجموعة (رسالة حالة / ملاحظة). */
@Composable
internal fun SettingsFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** الشريط العلوي: رجوع في البداية، عنوان سيريف في المنتصف، "i" في النهاية. */
@Composable
internal fun SettingsTopBar(title: String, onBack: () -> Unit, onInfo: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 6.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "رجوع",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            title,
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(onClick = onInfo, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = "حول التطبيق",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** بطاقة الحساب العلوية: الاسم في طرف وشارة الإصدار (حبة بيضاء) في الطرف الآخر. */
@Composable
internal fun SettingsAccountCard(name: String, badge: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GroupLargeRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(12.dp))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onSurface) {
                Text(
                    badge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

/** بطاقة بحدّ خارجي (مثل "Want more Claude?"): عنوان + وصف + زر حبّة بيضاء. */
@Composable
internal fun SettingsPromoCard(
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GroupLargeRadius),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(18.dp))
            SettingsPillButton(buttonLabel, onClick)
        }
    }
}

@Composable
internal fun SettingsPillButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface
        ),
        contentPadding = PaddingValues(horizontal = 28.dp),
        modifier = Modifier.height(48.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp))
    }
}

/**
 * ورقة سفلية لاختيارات الإعدادات. [content] يستلم دالة close تُغلق الورقة
 * بحركة الانزلاق الكاملة ثم تستدعي [onDismiss] — بدل إزالتها فجأة من الشجرة.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenGap, end = ScreenGap, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ScreenGap)
        ) {
            Text(
                title,
                modifier = Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content(close)
        }
    }
}
