package com.shopmanager.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.shopmanager.app.data.performance.LocalPerformanceTier
import com.shopmanager.app.data.performance.PerformanceTier
import com.shopmanager.app.data.performance.LocalRefreshRateHz

/**
 * Single place that turns "تفضيل الأداء" (Settings → الأداء) into the
 * actual animation specs every small interactive motion in the app uses —
 * button-press scale, list-reorder, expand/collapse.
 *
 * REPLACED WITH CLAUDE.AI-STYLE MOTION ("ازل جميع الانميشن واضف انميشن نمط
 * تطبيق كلاود ai"): every spec here used to be a bouncy spring
 * (DampingRatioMediumBouncy/LowBouncy) that visibly overshot and settled
 * back — a lively, "iOS 26" feel, but not what the reference Claude.ai
 * design actually does. Claude's own UI never overshoots: every transition
 * (a panel opening, a control responding to a tap) is a short,
 * critically-damped ease-out — it moves briskly and stops exactly where
 * it's going, with zero wobble. [claudeEasing] is that same curve (a
 * fast-start/gentle-stop "ease-out expo" shape) used everywhere a
 * duration-based tween is needed, and every spring below is now
 * [Spring.DampingRatioNoBouncy] — only the stiffness (how quickly it gets
 * there) still varies by call site and by performance tier:
 *
 * - LOW ("الوضع الاقتصادي"): بلا نوابض إطلاقاً — كل حركة تفاعلية (ضغط، ترتيب، توسيع) تقفز فوراً [snap]؛ سابقاً [Spring.StiffnessHigh] — settles in a couple
 *   of frames, as close to an instant snap as a spring gets — and every
 *   duration-based effect drops to a fraction of its normal length, so
 *   battery/CPU cost stays minimal without the UI going fully static.
 * - BALANCED ("المتوازن"): نوابض أصلب قليلاً ومدد أقصر ~15% — ناعمة لكن بعمل رسومي أقل.
 * - STANDARD/HIGH ("وضع الأداء العالي"): quick but perceptible, so a tap,
 *   reorder or expand still visibly responds — just without any bounce.
 */
object MotionSpecs {

    /** Claude.ai's own transition curve — quick to start, gently easing to
     * a dead stop with no overshoot. Used for every tween in this object. */
    val claudeEasing: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    @Composable
    private fun isLowTier(): Boolean = LocalPerformanceTier.current == PerformanceTier.LOW

    @Composable
    private fun isBalancedTier(): Boolean = LocalPerformanceTier.current == PerformanceTier.BALANCED

    /** المتوازن: نوابض أصلب قليلاً من القوي (تستقر بإطارات أقل = عمل رسومي أقل) لكنها
     * ما تزال ناعمة، بلا ارتداد. القوي يبقى بالأزمنة الأنعم الأصلية. */
    @Composable
    private fun tierStiffness(high: Float, balanced: Float): Float = when {
        isLowTier() -> Spring.StiffnessHigh
        isBalancedTier() -> balanced
        else -> high
    }

    /** مضروب مدد الـ tween: القوي 1، المتوازن ~0.85 (أقصر قليلاً)، والاقتصادي له قيم ثابتة خاصة. */
    @Composable
    fun tierDurationScale(): Float = if (isBalancedTier()) 0.85f * durationScale() else durationScale()

    /** شاشة 60Hz: نرفع صلابة النوابض ونقصّر المدد ~20% فتصل الحركة لهدفها بإطارات أقل
     * ويبدو التطبيق أسرع استجابة ("كأنه 90Hz")؛ على 90Hz+ تبقى الأزمنة الأنعم الأصلية. */
    @Composable
    private fun snap(): Float = if (LocalRefreshRateHz.current < 80f) 1.3f else 1f

    @Composable
    fun durationScale(): Float = if (LocalRefreshRateHz.current < 80f) 0.8f else 1f

    // ───────────────────────── رموز الزمن الموحّدة ─────────────────────────
    // المتوازن (تناغم وتناسق): كل حركة فيه تأخذ واحدة فقط من ثلاث مدد — سريعة 120ms
    // (تفاعل لحظي)، أساسية 180ms (توسيع/تبديل)، بطيئة 240ms (دخول/انتقال) — وبمنحنى
    // [claudeEasing] نفسه، فتتزامن الحركات وتستقر معاً بإيقاع واحد بدل نوابض مختلفة
    // المدة. المنخفض: مضاعفات 25ms (إطار واحد @40Hz) كي تتوزع الإطارات بالتساوي على
    // شاشة 40Hz فلا يظهر تقطّع في التوقيت.
    const val balancedFastMs = 120
    const val balancedBaseMs = 180
    const val balancedSlowMs = 240

    /** حجم الانكماش عند الضغط: أخفّ في الأوضاع الأضعف، أوضح في القوي. */
    @Composable
    fun pressScale(): Float = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> 0.95f
        PerformanceTier.BALANCED -> 0.94f
        PerformanceTier.STANDARD -> 0.91f
    }

    /** Button/row press scale-down feedback, and any other quick single-value
     * spring (color/dp highlight, etc.) that should track the same feel. */
    @Composable
    fun <T> quickSpring(): FiniteAnimationSpec<T> = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> tween<T>(100, easing = claudeEasing)
        PerformanceTier.BALANCED -> tween<T>(balancedFastMs, easing = claudeEasing)
        PerformanceTier.STANDARD -> spring<T>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow * 2.2f * snap()
        )
    }

    /** Button/row press scale-down feedback. */
    @Composable
    fun pressSpring(): FiniteAnimationSpec<Float> = quickSpring()

    /** List-item reorder/insert/remove placement (LazyColumn animateItem's placementSpec). */
    @Composable
    fun reorderSpring(): FiniteAnimationSpec<IntOffset> = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> tween<IntOffset>(150, easing = claudeEasing)
        PerformanceTier.BALANCED -> tween<IntOffset>(balancedSlowMs, easing = claudeEasing)
        PerformanceTier.STANDARD -> spring<IntOffset>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow * 1.6f * snap()
        )
    }

    /** expandVertically/shrinkVertically size animation. */
    @Composable
    fun expandSpring(): FiniteAnimationSpec<IntSize> = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> tween<IntSize>(125, easing = claudeEasing)
        PerformanceTier.BALANCED -> tween<IntSize>(balancedBaseMs, easing = claudeEasing)
        PerformanceTier.STANDARD -> spring<IntSize>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow * snap()
        )
    }

    /** اختفاء الصف عند حذفه/تسديده (fadeOutSpec في animateItem). */
    @Composable
    fun listItemFadeOut(): FiniteAnimationSpec<Float> =
        tween<Float>(durationMillis = fadeMillis(), easing = claudeEasing)

    @Composable
    fun expandMillis(): Int = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> 125
        PerformanceTier.BALANCED -> balancedBaseMs
        PerformanceTier.STANDARD -> (200 * durationScale()).toInt()
    }

    @Composable
    fun collapseMillis(): Int = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> 100
        PerformanceTier.BALANCED -> balancedFastMs + 20
        PerformanceTier.STANDARD -> (160 * durationScale()).toInt()
    }

    @Composable
    fun fadeMillis(): Int = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> 75
        PerformanceTier.BALANCED -> balancedFastMs
        PerformanceTier.STANDARD -> (140 * durationScale()).toInt()
    }

    /**
     * "Pop in" لكل ما يظهر فوق المحتوى (حوارات، أزرار عائمة، لافتات).
     * القوي: نابض بارتداد خفيف جداً (0.72) فيبدو العنصر "حياً"؛ المتوازن: tween بالمدة
     * الأساسية؛ المنخفض: tween قصير 125ms.
     */
    @Composable
    fun popInSpring(): FiniteAnimationSpec<Float> = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> tween<Float>(125, easing = claudeEasing)
        PerformanceTier.BALANCED -> tween<Float>(balancedBaseMs, easing = claudeEasing)
        PerformanceTier.STANDARD -> spring<Float>(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMedium * snap()
        )
    }

    /** مؤشر التحديد المنزلق بين التبويبات. */
    @Composable
    fun tabIndicatorSpring(): FiniteAnimationSpec<androidx.compose.ui.unit.Dp> = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> tween<androidx.compose.ui.unit.Dp>(100, easing = claudeEasing)
        PerformanceTier.BALANCED -> tween<androidx.compose.ui.unit.Dp>(balancedBaseMs, easing = claudeEasing)
        PerformanceTier.STANDARD -> spring<androidx.compose.ui.unit.Dp>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium * snap()
        )
    }

    /** Smooth content fade+slide for anything that swaps in place. */
    @Composable
    fun <T> contentTween(): FiniteAnimationSpec<T> = tween(
        durationMillis = when (LocalPerformanceTier.current) {
            PerformanceTier.LOW -> 100
            PerformanceTier.BALANCED -> balancedBaseMs
            PerformanceTier.STANDARD -> (220 * durationScale()).toInt()
        },
        easing = claudeEasing
    )

    // ───────────────────────── التنقل بين الشاشات ─────────────────────────
    // قاعدة المستويات الثلاثة (طلب: "متوازن بحيث الانميشن ما تستهلك الجرافك"):
    //  - STANDARD (قوي): انزلاق + تصغير الشاشة المغطّاة (94%) — أغنى حركة.
    //  - BALANCED: انزلاق + تلاشٍ فقط، بدون scale للشاشة كاملة (تصغير شاشة
    //    كاملة بكل بطاقاتها كل إطار هو أغلى جزء رسومياً)، وبمدة أقصر.
    //  - LOW: تلاشٍ بسيط قصير جداً فقط (بلا انزلاق ولا scale).

    /** مدة انتقال دفع/سحب الشاشة (ms). */
    @Composable
    fun navMillis(): Int = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> 150
        PerformanceTier.BALANCED -> balancedSlowMs
        PerformanceTier.STANDARD -> (300 * durationScale()).toInt()
    }

    /** مدة انتقال التبويبات (الـ pager) بالـ ms. */
    @Composable
    fun pagerMillis(): Int = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> 175
        PerformanceTier.BALANCED -> balancedSlowMs
        PerformanceTier.STANDARD -> (320 * durationScale()).toInt()
    }

    /** مدة الانتقال من السبلاش إلى التطبيق. */
    @Composable
    fun handoffMillis(): Int = when (LocalPerformanceTier.current) {
        PerformanceTier.LOW -> 100
        PerformanceTier.BALANCED -> balancedSlowMs
        PerformanceTier.STANDARD -> 360
    }
}

/**
 * مأخوذ من تطبيق الملاحظات (Motion.track: PRESS_IN بعد 60ms): أثر الضغط لا يظهر إلا إن بقي
 * الإصبع 60ms، فبداية تمرير فوق بطاقة لا تُشغّل حركة ضغط ثم تلغيها (عمل رسومي مهدور في أسوأ
 * لحظة). النقرة السريعة جداً تُظهر الأثر ~90ms كي يُرى. بلا أي مؤقّت إضافي ما دام لا ضغط.
 */
@Composable
fun androidx.compose.foundation.interaction.InteractionSource.collectDelayedPressedAsState(
    delayMs: Long = 60L
): androidx.compose.runtime.State<Boolean> {
    val raw = collectIsPressedAsState()
    val shown = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val started = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(raw.value) {
        if (raw.value) {
            started.value = true
            kotlinx.coroutines.delay(delayMs)
            shown.value = true
        } else {
            if (started.value && !shown.value) {
                shown.value = true
                kotlinx.coroutines.delay(90L)
            }
            shown.value = false
            started.value = false
        }
    }
    return shown
}
