package com.shopmanager.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role

// ============================================================================
// لغة التصميم الموحّدة (مأخوذة من شاشة الإعدادات الجديدة):
//  - كل عنصر قائمة صفٌّ مسطّح على surfaceContainerHigh بلا حدود ولا ظلال.
//  - صفوف المجموعة الواحدة متلاصقة بفاصل صغير (3dp): الأول فقط بزوايا علوية
//    كبيرة، والأخير فقط بزوايا سفلية كبيرة، وما بينهما زوايا صغيرة جداً.
//  - بين المجموعات فاصل أكبر (14dp).
//  - شريط علوي بلا خلفية: زر في الطرف، عنوان سيريف في المنتصف.
//  - الأزرار الرئيسية "حبّة" بلون onSurface ونص بلون surface.
// كل الشاشات تستعمل هذه المكوّنات حتى تبقى متطابقة مع الإعدادات.
// ============================================================================

val AppGroupLargeRadius = 20.dp
val AppGroupSmallRadius = 4.dp
val AppGroupGap = 3.dp
val AppSectionGap = 14.dp
val AppScreenPadding = 14.dp

/** شكل صف داخل مجموعة عمودية: أول/وسط/أخير. */
fun groupedRowShape(index: Int, lastIndex: Int): Shape {
    val top = if (index == 0) AppGroupLargeRadius else AppGroupSmallRadius
    val bottom = if (index == lastIndex) AppGroupLargeRadius else AppGroupSmallRadius
    return RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
}

/** شكل بلاطة داخل مجموعة أفقية (شريط إحصاءات): أول/وسط/أخير. */
fun groupedTileShape(index: Int, lastIndex: Int): Shape {
    val start = if (index == 0) AppGroupLargeRadius else AppGroupSmallRadius
    val end = if (index == lastIndex) AppGroupLargeRadius else AppGroupSmallRadius
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

/** صفٌّ مسطّح (قابل للّمس اختيارياً) بنفس تعبئة صفوف الإعدادات. */
@Composable
fun AppRowSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = color,
            modifier = modifier.fillMaxWidth(),
            content = content
        )
    } else {
        Surface(
            shape = shape,
            color = color,
            modifier = modifier.fillMaxWidth(),
            content = content
        )
    }
}

/** بطاقة مسطّحة مستقلة (مثل بطاقة الحساب في الإعدادات). */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AppGroupLargeRadius),
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = color,
        content = content
    )
}

/**
 * الشريط العلوي الموحّد: زر في البداية، عنوان سيريف في المنتصف، أزرار في النهاية.
 * [reservedIcons] عدد الأزرار الأكبر في أحد الطرفين، يُترك له مكان حتى لا يتداخل العنوان معها.
 */
@Composable
fun ScreenTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    reservedIcons: Int = 1,
    topInset: Boolean = true
) {
    val reserved = reservedIcons.coerceAtLeast(1)
    Box(
        modifier
            .fillMaxWidth()
            .then(if (topInset) Modifier.statusBarsPadding() else Modifier)
            .height(64.dp)
            .padding(horizontal = 6.dp)
    ) {
        if (navigation != null) {
            Box(Modifier.align(Alignment.CenterStart)) { navigation() }
        }
        Text(
            title,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 48.dp * reserved),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = if (reserved >= 2) 22.sp else 24.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (actions != null) {
            Row(
                Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

/** زر أيقونة دائري للشريط العلوي (رجوع/قائمة) بتعبئة صفوف التطبيق. */
@Composable
fun ScreenIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Box(
        modifier
            .padding(horizontal = 4.dp)
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** وصف زر داخل [AppActionPill]. */
class ActionSpec(
    val icon: ImageVector,
    val contentDescription: String,
    val tint: Color? = null,
    val onClick: () -> Unit
)

/**
 * كبسولة أزرار موحّدة: عدة أزرار أيقونة داخل حبّة واحدة بفواصل رفيعة، فلا يمكن أن
 * تتلامس أو تتداخل الدوائر. تُستعمل في الشريط العلوي (بحث/مشاركة) وفي صفوف القوائم
 * (تم/تعديل/حذف). مع زر واحد تصير دائرة.
 */
@Composable
fun AppActionPill(
    actions: List<ActionSpec>,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest
) {
    val haptics = LocalHapticFeedback.current
    val divider = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Row(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(container),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(height * 0.45f)
                        .background(divider)
                )
            }
            Box(
                Modifier
                    .width(if (actions.size == 1) height else 46.dp)
                    .fillMaxHeight()
                    .clickable(role = Role.Button) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        action.onClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    action.icon,
                    contentDescription = action.contentDescription,
                    tint = action.tint ?: MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** عنوان قسم صغير رمادي فوق مجموعة، مع عنصر اختياري في النهاية. */
@Composable
fun AppSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trailing?.invoke()
    }
}

/** سطر رمادي صغير (ملاحظة/حالة) مثل SettingsFootnote. */
@Composable
fun AppFootnote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** زر "حبّة": الرئيسي بلون onSurface، والثانوي (tonal) على تعبئة الصفوف. */
@Composable
fun AppPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tonal: Boolean = false,
    height: Dp = 52.dp
) {
    val cs = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (tonal) cs.surfaceContainerHighest else cs.onSurface,
            contentColor = if (tonal) cs.onSurface else cs.surface,
            disabledContainerColor = cs.onSurface.copy(alpha = 0.12f),
            disabledContentColor = cs.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
        modifier = modifier.height(height)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (height < 48.dp) 18.dp else 20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontSize = if (height < 48.dp) 15.sp else 16.sp))
    }
}

/** شريحة تصفية على شكل حبّة: المحددة بلون onSurface. */
@Composable
fun AppChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) cs.onSurface else cs.surfaceContainerHigh,
        modifier = modifier
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.surface else cs.onSurface
        )
    }
}

/** دائرة ملوّنة صغيرة (أفاتار/أيقونة عنصر). */
@Composable
fun AppIconCircle(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** حالة فارغة موحّدة: دائرة بأيقونة + نص رمادي. */
@Composable
fun AppEmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp).floatingIcon()
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

data class AppStat(
    val label: String,
    val value: Double,
    val accent: Color? = null,
    val format: (Double) -> String
)

/** شريط إحصاءات: بلاطات مسطّحة متلاصقة أفقياً بنفس منطق زوايا مجموعة الإعدادات. */
@Composable
fun AppStatsStrip(stats: List<AppStat>, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(AppGroupGap)
    ) {
        stats.forEachIndexed { index, stat ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = groupedTileShape(index, stats.lastIndex),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    Modifier.padding(horizontal = 8.dp, vertical = if (compact) 9.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides (stat.accent ?: MaterialTheme.colorScheme.onSurface)
                    ) {
                        AnimatedCounterText(
                            targetValue = stat.value,
                            format = stat.format,
                            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(if (compact) 0.dp else 2.dp))
                    Text(
                        stat.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
