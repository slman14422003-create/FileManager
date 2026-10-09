package com.shopmanager.app.data.performance

import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.abs

/** معدل تحديث الشاشة الحالي (Hz) كما يراه التطبيق؛ تقرؤه حركات التطبيق لتضبط سرعتها. */
val LocalRefreshRateHz = staticCompositionLocalOf { 60f }

/**
 * سياسة معدل التحديث حسب وضع الأداء:
 * - الأداء القوي (STANDARD): أقرب وضع إلى 90Hz (ثابت وسلس وأوفر من 120Hz). إن لم يدعم
 *   الجهاز 90 يُختار أقرب وضع أعلى منه، وإن كانت الشاشة 60Hz فقط تبقى 60 وتتولّى
 *   [com.shopmanager.app.ui.common.MotionSpecs] جعل الحركة أسرع استجابة.
 * - المتوازن (BALANCED): أقرب وضع إلى 60Hz لتوفير الطاقة وثبات توقيت الإطارات.
 * - الاقتصادي (LOW): أقرب وضع إلى 40Hz (تلميح إطارات 40 على أندرويد 15+)، وكل مدد الحركة
 *   مضاعفات 25ms = إطار واحد @40Hz فتتوزع الإطارات بالتساوي.
 * لا يتغير الـ resolution أبداً (نختار فقط من أوضاع بنفس الدقة) لتفادي وميض تبديل الوضع.
 */
object RefreshRatePolicy {
    private const val HIGH_TARGET_HZ = 90f
    private const val BALANCED_TARGET_HZ = 60f
    // الاقتصادي: 40Hz — إطارات أقل = حمل أقل على معالج الرسوميات القديم. إن لم يوفّر
    // العرض وضعاً قريباً من 40 يُختار أقرب وضع (غالباً 60) ويُرسَل تلميح إطارات 40 للنظام.
    private const val LOW_TARGET_HZ = 40f
    private const val HIGH_MIN_HZ = 85f

    fun apply(window: Window, display: Display?, tier: PerformanceTier) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || display == null) return
        val current = display.mode ?: return
        val modes = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val target = choose(modes, tier) ?: return
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == target.modeId && attrs.preferredRefreshRate == target.refreshRate) return
        window.attributes = attrs.apply {
            preferredDisplayModeId = target.modeId
            preferredRefreshRate = target.refreshRate
        }
    }

    /** تلميح معدل الإطارات للنظام (أندرويد 15+): الاقتصادي يطلب 40، غيره يرفع التلميح. */
    fun applyFrameRateHint(window: Window, tier: PerformanceTier) {
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            window.decorView.requestedFrameRate =
                if (tier == PerformanceTier.LOW) LOW_TARGET_HZ else View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
        }
    }

    private fun choose(modes: List<Display.Mode>, tier: PerformanceTier): Display.Mode? {
        if (modes.isEmpty()) return null
        return if (tier == PerformanceTier.STANDARD) {
            val fast = modes.filter { it.refreshRate >= HIGH_MIN_HZ }
            if (fast.isEmpty()) {
                modes.maxByOrNull { it.refreshRate }
            } else {
                fast.minWithOrNull(
                    compareBy<Display.Mode>({ abs(it.refreshRate - HIGH_TARGET_HZ) }, { -it.refreshRate })
                )
            }
        } else {
            val target = if (tier == PerformanceTier.LOW) LOW_TARGET_HZ else BALANCED_TARGET_HZ
            modes.minWithOrNull(compareBy<Display.Mode>({ abs(it.refreshRate - target) }, { it.refreshRate }))
        }
    }
}
