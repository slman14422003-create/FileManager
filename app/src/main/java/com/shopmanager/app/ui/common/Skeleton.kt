package com.shopmanager.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shopmanager.app.data.performance.LocalPerformanceTier
import com.shopmanager.app.data.performance.PerformanceTier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * هياكل التحميل (Skeleton) — بديل الدائرة الدوّارة في وسط شاشة فارغة، ومنسّقة مع
 * بقية الواجهة: نفس أشكال الصفوف المتلاصقة [groupedRowShape]، ونفس تعبئة
 * surfaceContainerHigh، ونفس هوامش الشاشات.
 *
 * السلوك حسب وضع الأداء (يتبع [LocalPerformanceTier]):
 *  - STANDARD (قوي): لمعة تمسح كل كتلة (تُرسم كلها في مرحلة الرسم، بلا إعادة تركيب).
 *  - BALANCED (متوازن): نبض شفافية ناعم فقط عبر graphicsLayer — بلا gradient shader.
 *  - LOW (اقتصادي): كتل ثابتة تماماً بلا أي حركة ولا مؤقّت.
 *
 * ساعة اللمعة واحدة لكل [SkeletonHost] فتتحرك كل الكتل معاً بتزامن بدل مؤقّت لكل كتلة.
 */
private val LocalShimmerPhase = compositionLocalOf<State<Float>?> { null }

@Composable
fun SkeletonHost(content: @Composable () -> Unit) {
    val tier = LocalPerformanceTier.current
    val phase: State<Float>? = when (tier) {
        PerformanceTier.LOW -> null
        PerformanceTier.BALANCED -> {
            val t = rememberInfiniteTransition(label = "skeletonPulse")
            t.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "skeletonPulsePhase"
            )
        }
        PerformanceTier.STANDARD -> {
            val t = rememberInfiniteTransition(label = "skeletonShimmer")
            t.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
                label = "skeletonShimmerPhase"
            )
        }
    }
    CompositionLocalProvider(LocalShimmerPhase provides phase) { content() }
}

/** كتلة رمادية واحدة داخل هيكل تحميل. تُستعمل داخل [SkeletonHost] (أو بدونه فتبقى ثابتة). */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    val tier = LocalPerformanceTier.current
    val phase = LocalShimmerPhase.current
    val cs = MaterialTheme.colorScheme
    val base = cs.surfaceContainerHighest
    val sheen = cs.onSurface.copy(alpha = 0.10f)

    // الترتيب مهم: graphicsLayer قبل clip/background كي يشمل النبضُ الخلفيةَ نفسها.
    val layered = if (tier == PerformanceTier.BALANCED && phase != null) {
        modifier.graphicsLayer { alpha = 0.55f + 0.45f * phase.value }
    } else modifier

    val withSheen = if (tier == PerformanceTier.STANDARD && phase != null) {
        Modifier.drawWithContent {
            drawContent()
            val band = size.width * 0.6f
            val start = -band + (size.width + band) * phase.value
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, sheen, Color.Transparent),
                    startX = start,
                    endX = start + band
                )
            )
        }
    } else Modifier

    Box(
        if (tier == PerformanceTier.LOW) layered.background(base, shape)
        else layered.clip(shape).background(base).then(withSheen)
    )
}

/** نوع الصف الذي يحاكيه الهيكل، ليطابق شكل الصف الحقيقي في كل شاشة. */
enum class SkeletonRowKind { PERSON, MATERIAL, NOTE }

/**
 * قائمة هياكل صفوف متلاصقة بنفس تجميع الصفوف الحقيقية. تُعرض مكان القائمة أثناء
 * التحميل الأول فقط (قبل وصول أول لقطة من Firestore) بدل شاشة فارغة أو "لا توجد بيانات".
 */
@Composable
fun SkeletonRowList(
    kind: SkeletonRowKind,
    modifier: Modifier = Modifier,
    count: Int = 7,
    contentPadding: PaddingValues = PaddingValues(horizontal = AppScreenPadding, vertical = 2.dp)
) {
    SkeletonHost {
        // قابلة للتمرير حتى لا تُقصّ على الشاشات الصغيرة، لكن بلا مدخلات لمس.
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), enabled = false)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(AppGroupGap)
        ) {
            repeat(count) { index ->
                AppRowSurface(shape = groupedRowShape(index, count - 1)) {
                    SkeletonRowContent(kind, index)
                }
            }
        }
    }
}

@Composable
private fun SkeletonRowContent(kind: SkeletonRowKind, index: Int) {
    // أطوال أسطر مختلفة قليلاً لتبدو طبيعية وليست آلية.
    val nameFraction = listOf(0.52f, 0.38f, 0.60f, 0.45f)[index % 4]
    when (kind) {
        SkeletonRowKind.PERSON -> Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBox(Modifier.size(40.dp), CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBox(Modifier.fillMaxWidth(nameFraction).height(14.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBox(Modifier.width(76.dp).height(12.dp), CircleShape)
            }
            Spacer(Modifier.width(12.dp))
            SkeletonBox(Modifier.size(32.dp), CircleShape)
        }
        SkeletonRowKind.MATERIAL -> Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBox(Modifier.size(40.dp), CircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBox(Modifier.fillMaxWidth(nameFraction).height(14.dp))
                Spacer(Modifier.height(8.dp))
                SkeletonBox(Modifier.width(60.dp).height(12.dp), CircleShape)
            }
            Spacer(Modifier.width(12.dp))
            SkeletonBox(Modifier.width(52.dp).height(24.dp), CircleShape)
        }
        SkeletonRowKind.NOTE -> Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            SkeletonBox(Modifier.fillMaxWidth(nameFraction).height(14.dp))
            Spacer(Modifier.height(9.dp))
            SkeletonBox(Modifier.fillMaxWidth().height(11.dp))
            Spacer(Modifier.height(6.dp))
            SkeletonBox(Modifier.fillMaxWidth(0.7f).height(11.dp))
        }
    }
}

/** بطاقة هيكل عامة (للوحة الرئيسية): عنوان + أسطر. */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 3
) {
    SkeletonHost {
        AppRowSurface(shape = RoundedCornerShape(AppGroupLargeRadius), modifier = modifier) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                SkeletonBox(Modifier.width(110.dp).height(16.dp))
                Spacer(Modifier.height(14.dp))
                repeat(lines) { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBox(Modifier.size(32.dp), CircleShape)
                        Spacer(Modifier.width(10.dp))
                        SkeletonBox(Modifier.fillMaxWidth(if (i % 2 == 0) 0.6f else 0.45f).height(13.dp))
                    }
                    if (i != lines - 1) Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/**
 * مؤشر تحميل بـ 12 شعاعاً (مثل الصورة المرجعية): الأشعة تتلاشى تدريجياً خلف الشعاع
 * الرئيسي ويدور بخطوات منفصلة (12 خطوة في الثانية) كمؤشر iOS — رسم Canvas واحد بلا أي
 * عناصر Compose إضافية، والخطوة تُقرأ في مرحلة الرسم فقط. يعمل بنفس الشكل في كل
 * الأوضاع لأنه تغذية راجعة وظيفية وليس "تأثيراً"؛ كلفته إطار رسم واحد كل ~83ms.
 */
@Composable
fun AppSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val t = rememberInfiniteTransition(label = "spokeSpinner")
    val step = t.animateFloat(
        initialValue = 0f,
        targetValue = SPOKES.toFloat(),
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label = "spokeStep"
    )
    // derivedStateOf: لا يُبطل الرسم إلا حين يتغير رقم الشعاع (12 مرة/ثانية) بدل كل إطار.
    val currentSpoke = remember { derivedStateOf { step.value.toInt() % SPOKES } }
    Canvas(modifier.size(size)) {
        val current = currentSpoke.value
        val r = this.size.minDimension / 2f
        val inner = r * 0.48f
        val outer = r * 0.92f
        val stroke = r * 0.16f
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        for (i in 0 until SPOKES) {
            // المسافة (بالأشعة) خلف الشعاع الرئيسي الذي يدور باتجاه عقارب الساعة.
            val behind = (current - i + SPOKES) % SPOKES
            val alpha = (1f - behind / SPOKES.toFloat()).coerceIn(0.12f, 1f)
            val angle = (i * 360f / SPOKES - 90f) * (PI.toFloat() / 180f)
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(c.x + dx * inner, c.y + dy * inner),
                end = Offset(c.x + dx * outer, c.y + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

private const val SPOKES = 12

/** حركة عوم خفيفة جداً للرموز (الحالات الفارغة) — للأداء القوي فقط؛ غير ذلك لا شيء. */
@Composable
fun Modifier.floatingIcon(): Modifier {
    if (LocalPerformanceTier.current != PerformanceTier.STANDARD) return this
    val t = rememberInfiniteTransition(label = "floatingIcon")
    val dy = t.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "floatingIconDy"
    )
    return this.graphicsLayer { translationY = dy.value.dp.toPx() }
}
