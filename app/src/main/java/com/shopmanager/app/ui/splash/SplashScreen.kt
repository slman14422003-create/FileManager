package com.shopmanager.app.ui.splash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shopmanager.app.R
import com.shopmanager.app.ui.common.MotionSpecs

/**
 * شاشة البداية بلغة التصميم الموحّدة (نفس الإعدادات والحوارات):
 *  - خلفية surface مسطّحة بلا توهّج ولا تدرجات ولا دوران.
 *  - أيقونة التطبيق داخل لوح مسطّح بزوايا 28dp على تعبئة surfaceContainerHigh،
 *    تظهر بتكبير خفيف (0.9 ← 1) مع تلاشٍ، بنفس منحنى الحركة المستعمل بالتطبيق.
 *  - اسم التطبيق بخط السيريف (نفس عناوين الشاشات) ينزلق من تحت قليلاً.
 *  - الاعتماد "SEMO STUDIO" في حبّة مسطّحة صغيرة (نفس شارة الإصدار) تظهر أخيراً.
 *
 * حركة واحدة تُشغَّل مرة وحدة: لا تكرار ولا حلقات، وتتبع [MotionSpecs.durationScale]
 * بحيث تقصر على الشاشات 60Hz. الألوان كلها من MaterialTheme لتتبع وضع التطبيق
 * (فاتح/داكن) المختار من الإعدادات وليس وضع النظام فقط.
 */
@Composable
fun AppSplashScreen(modifier: Modifier = Modifier) {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }

    val scale = MotionSpecs.durationScale()
    fun ms(base: Int) = (base * scale).toInt()

    val tileProgress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(ms(520), easing = MotionSpecs.claudeEasing),
        label = "splashTile"
    )
    val titleProgress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(ms(460), delayMillis = ms(140), easing = MotionSpecs.claudeEasing),
        label = "splashTitle"
    )
    val creditProgress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(ms(420), delayMillis = ms(280), easing = MotionSpecs.claudeEasing),
        label = "splashCredit"
    )

    val cs = MaterialTheme.colorScheme

    Box(
        modifier
            .fillMaxSize()
            .background(cs.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // لوح مسطّح بتعبئة الصفوف خلف أيقونة التطبيق الحقيقية.
            Box(
                Modifier
                    .size(124.dp)
                    .graphicsLayer {
                        val s = 0.9f + 0.1f * tileProgress
                        scaleX = s
                        scaleY = s
                        alpha = tileProgress
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_splash_mark),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(20.dp))
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "إدارة المحل",
                color = cs.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = titleProgress
                    translationY = (1f - titleProgress) * 12.dp.toPx()
                }
            )
        }

        Surface(
            shape = CircleShape,
            color = cs.surfaceContainerHigh,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp)
                .graphicsLayer { alpha = creditProgress }
        ) {
            Text(
                "SEMO STUDIO",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                fontWeight = FontWeight.Medium
            )
        }
    }
}
