package com.shopmanager.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.shopmanager.app.data.performance.LocalPerformanceTier
import com.shopmanager.app.data.performance.PerformanceTier

/**
 * تحسينات بصرية للأداء القوي فقط (STANDARD). في المتوازن والمنخفض تُعيد `this`
 * كما هي بلا أي مؤقّت ولا رسم إضافي. كلها تُرسم في مرحلة الرسم (draw) ولا تُعيد التركيب.
 */

/**
 * لمعة ضوء ناعمة تمسح العنصر قطرياً كل ~6 ثوانٍ (تتحرك في أول 40% من الدورة وتسكن
 * بقيتها). الحالة الساكنة لا تُبطل الرسم (derivedStateOf يثبت على -1) فلا كلفة بين اللمعات.
 */
@Composable
fun Modifier.sheenSweep(): Modifier {
    if (LocalPerformanceTier.current != PerformanceTier.STANDARD) return this
    val t = rememberInfiniteTransition(label = "sheenSweep")
    val phase = t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "sheenPhase"
    )
    val sweep = remember { derivedStateOf { if (phase.value < 0.4f) phase.value / 0.4f else -1f } }
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    return this.drawWithContent {
        drawContent()
        val f = sweep.value
        if (f >= 0f) {
            val band = size.width * 0.55f
            val start = -band + (size.width + band) * f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, tint, Color.Transparent),
                    start = Offset(start, 0f),
                    end = Offset(start + band, size.height * 0.5f)
                )
            )
        }
    }
}

/** هالة لونية ناعمة خلف الزر العائم (تتجاوز حدوده قليلاً) — ثابتة بلا حركة. */
@Composable
fun Modifier.glowBehind(color: Color): Modifier {
    if (LocalPerformanceTier.current != PerformanceTier.STANDARD) return this
    return this.drawBehind {
        val r = size.minDimension * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = 0.32f), Color.Transparent),
                center = center,
                radius = r
            ),
            radius = r,
            center = center
        )
    }
}
