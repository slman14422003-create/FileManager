package com.shopmanager.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.shopmanager.app.data.performance.LocalPerformanceTier
import com.shopmanager.app.data.performance.PerformanceTier
import kotlinx.coroutines.delay

/**
 * "ما احس انو في انيميشن" (on capable/"strong" devices the UI feels
 * static): every existing motion in this app is a *reaction* to input —
 * a press, a swipe, a value changing — but a list of rows just appearing
 * fully-formed the instant its data loads has no motion of its own at
 * all, which is exactly what reads as "there's no animation here" even on
 * a phone that could easily afford one. This is the missing "arrival"
 * motion: each row fades in and rises a short distance into place, so a
 * freshly-loaded/refreshed list visibly settles into view row by row
 * instead of popping in as one flat block.
 *
 * STAGGER: `index` offsets each row's start by a small, capped delay so
 * the motion reads as a single wave sweeping down the list rather than
 * every row moving in lockstep — capped at 8 rows' worth of delay so a
 * long list's *visible* rows all finish settling quickly instead of the
 * 40th row waiting a full second for its turn.
 *
 * PERF (LOW tier / this app's central performance switch — see
 * [com.shopmanager.app.data.performance.DevicePerformance]): returns
 * `this` completely unchanged on LOW tier — no [Animatable], no
 * [LaunchedEffect], no extra composable state at all, not even one that
 * finishes instantly. That's deliberate: unlike this app's other motion
 * (which degrades to a near-instant snap on LOW — see [MotionSpecs]),
 * *this* effect fires once per row every time a list is scrolled/loaded,
 * so on a weak device even a "free" near-zero-duration animation still
 * means one extra coroutine + Animatable allocation per row composed.
 * Skipping it outright is what keeps a long list's first render exactly
 * as cheap on LOW tier as it was before this existed.
 */
/** الصفوف التي فهرسها أكبر من هذا لا تُحرَّك أبداً: هي تدخل بالتمرير لا بتحميل القائمة. */
private const val MAX_ANIMATED_INDEX = 12

/**
 * PERF + FEEL (تحسين): كان الصف يعيد تشغيل حركة الدخول (Animatable + coroutine)
 * **في كل مرة** يدخل فيها التركيب — أي كلما مرّرتَ القائمة لأعلى ثم لأسفل كانت
 * الصفوف تعود شفافة وتصعد من جديد، وهذا عمل زائد على المعالج أثناء التمرير
 * تحديداً (أسوأ لحظة) ويبدو كوميضاً مزعجاً. الآن تُعرض الحركة **مرة واحدة فقط
 * لكل صف** (تُحفظ بـ rememberSaveable مربوطة بمفتاح الصف في LazyColumn، فتبقى
 * حتى لو خرج الصف من الشاشة وعاد أو دُوِّرت الشاشة)، ولا تُحرَّك الصفوف البعيدة
 * أصلاً. الصف الذي لا يتحرك لا يُنشئ أي coroutine.
 */
@Composable
fun Modifier.listItemEntrance(index: Int): Modifier {
    val tier = LocalPerformanceTier.current
    // حدود الحركة حسب الوضع:
    //  - LOW: أول 4 صفوف فقط وتلاشٍ (alpha) فقط بمدة 150ms (6 إطارات @40Hz) — بلا إزاحة ولا تكبير.
    //  - BALANCED: أول 6 صفوف، تلاشٍ + صعود 10dp، بمدة SLOW الموحّدة وفاصل 24ms.
    //  - STANDARD: أول 12 صفاً، تلاشٍ + صعود 22dp + تكبير 0.94→1 (حركة غنية).
    val maxIndex = when (tier) {
        PerformanceTier.LOW -> 3
        PerformanceTier.BALANCED -> 6
        PerformanceTier.STANDARD -> MAX_ANIMATED_INDEX
    }
    val played = rememberSaveable { mutableStateOf(false) }
    // تُحسب مرة واحدة عند دخول هذا الصف التركيب (لا تتغير بعدها) — وهذا ما
    // يجعل الشرط أدناه ثابتاً ولا يقطع الحركة حين نكتب played = true.
    val shouldAnimate = remember { !played.value && index <= maxIndex }
    val progress = remember { Animatable(if (shouldAnimate) 0f else 1f) }
    // تعلّمناها من تطبيق الملاحظات (Motion.enter: LAYER_TYPE_NONE عند انتهاء الحركة): graphicsLayer
    // يُبقي RenderNode/طبقة لكل صف حتى بعد انتهاء حركته. هنا يُزال المُعدِّل كلياً بعد الانتهاء،
    // فالصفوف الساكنة (وهي معظم القائمة) تعود بلا أي طبقة رسومية إضافية.
    var finished by remember { mutableStateOf(!shouldAnimate) }
    val durationMs = when (tier) {
        PerformanceTier.LOW -> 150
        PerformanceTier.BALANCED -> MotionSpecs.balancedSlowMs
        PerformanceTier.STANDARD -> (320 * MotionSpecs.durationScale()).toInt()
    }
    val staggerStepMs = when (tier) {
        PerformanceTier.LOW -> 25L
        PerformanceTier.BALANCED -> 24L
        PerformanceTier.STANDARD -> 30L
    }

    if (shouldAnimate) {
        LaunchedEffect(Unit) {
            played.value = true
            val staggerMs = index.coerceAtMost(8) * staggerStepMs
            if (staggerMs > 0) delay(staggerMs)
            progress.animateTo(1f, animationSpec = tween(durationMs, easing = MotionSpecs.claudeEasing))
            finished = true
        }
    }
    if (finished) return this
    return this.graphicsLayer {
        // القراءة هنا داخل كتلة graphicsLayer (مرحلة الرسم) — لا إعادة تركيب.
        val p = progress.value
        alpha = p
        when (tier) {
            PerformanceTier.LOW -> Unit
            PerformanceTier.BALANCED -> translationY = (1f - p) * 10.dp.toPx()
            PerformanceTier.STANDARD -> {
                translationY = (1f - p) * 22.dp.toPx()
                val sc = 0.94f + 0.06f * p
                scaleX = sc
                scaleY = sc
            }
        }
    }
}
