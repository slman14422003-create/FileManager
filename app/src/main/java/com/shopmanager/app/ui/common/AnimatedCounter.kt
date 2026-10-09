package com.shopmanager.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.shopmanager.app.data.performance.LocalPerformanceTier
import com.shopmanager.app.data.performance.PerformanceTier

/**
 * Animates a numeric value counting up (or down) to [targetValue] whenever
 * it changes, instead of the label just snapping to the new number. Small
 * touch, but it makes totals feel alive/responsive after every add or edit
 * instead of static text.
 *
 * PERF (low-end tier): the count-up itself is what's expensive, not the
 * number — animating this Text recomposes it on every animation frame for
 * its duration. On a LOW-tier device that's extra recompositions on top of
 * everything else already redrawing, so LOW settles in a single frame
 * (duration 0), same as if there were no animation at all.
 *
 * FIX (feel): STANDARD/HIGH previously used a flat/linear tween, which is
 * what a count-up looks like when it's "just" animating rather than
 * feeling designed — it starts and ends at the same constant speed with
 * no ease-out, so the final digits change at the same pace as the middle
 * ones and the stop reads as abrupt. [MotionSpecs.claudeEasing] (the same
 * no-bounce curve every other transition in the app now uses) starts fast
 * and settles gently instead, which is what makes the same animation read
 * as smooth rather than mechanical.
 *
 * BUG FIXED (cold-start "jitter"): [animate] lets a caller mark that its
 * data hasn't actually loaded yet (e.g. `!debtsState.isLoading`). While
 * that's false, any target this receives (typically the 0.0 loading
 * placeholder) is applied instantly with no animation. The very first time
 * it flips to true — the frame the real total first appears — is *also*
 * snapped instantly instead of counted up, since there was never a real
 * previous number on screen to count up *from*; animating that first
 * reveal is exactly what read as the totals visibly assembling/shaking
 * right when the app opens. Only changes *after* that first reveal (a
 * debt actually added or edited while the screen is open) get the
 * count-up animation — which is when it actually reads as "alive"
 * instead of as loading noise.
 */
@Composable
fun AnimatedCounterText(
    targetValue: Double,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    fontWeight: FontWeight = FontWeight.Bold,
    animate: Boolean = true
) {
    val isLowTier = LocalPerformanceTier.current == PerformanceTier.LOW

    // BUG FIXED (رقم خاطئ يظهر للمبالغ الكبيرة): كانت الحركة تجري على Float
    // (`Animatable(targetValue.toFloat())`) — دقة Float نحو 7 أرقام فقط، فمجموع
    // ديون مثل 1,234,567,891 كان يُعرض بعد انتهاء الحركة كـ 1,234,567,936 (الرقم
    // الأخير الظاهر ليس ما في قاعدة البيانات!). الآن الحركة على "تقدّم" 0..1
    // (Float كافٍ له تماماً) والرقم نفسه يُحسب بـ Double، وعند التقدّم 1 يُعرض
    // الهدف الحقيقي بالضبط بلا أي تقريب.
    val progress = remember { Animatable(1f) }
    var startValue by remember { mutableStateOf(targetValue) }
    var endValue by remember { mutableStateOf(targetValue) }
    var hasRevealedOnce by remember { mutableStateOf(false) }
    val currentFormat by rememberUpdatedState(format)
    val durationScale = MotionSpecs.tierDurationScale()

    LaunchedEffect(targetValue, animate) {
        if (!animate || !hasRevealedOnce || isLowTier) {
            startValue = targetValue
            endValue = targetValue
            progress.snapTo(1f)
            if (animate) hasRevealedOnce = true
        } else {
            // ابدأ مما هو معروض الآن (لو تغيّر الهدف أثناء حركة سابقة لا يقفز الرقم).
            val shownNow = if (progress.value >= 1f) endValue else startValue + (endValue - startValue) * progress.value
            startValue = shownNow
            endValue = targetValue
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = (380 * durationScale).toInt(), easing = MotionSpecs.claudeEasing)
            )
        }
    }

    // PERF: النص هو الحالة المُراقَبة — الواجهة لا تُعاد تركيبها إلا حين يتغيّر
    // النص المعروض فعلاً (فرق صغير على مجموع كبير يعني إطارات أقل تُركَّب)، بدل
    // إعادة تركيب + تنسيق نص في كل إطار من الحركة.
    val displayText by remember {
        derivedStateOf {
            val p = progress.value
            currentFormat(if (p >= 1f) endValue else startValue + (endValue - startValue) * p)
        }
    }
    Text(displayText, modifier = modifier, style = style, fontWeight = fontWeight)
}
