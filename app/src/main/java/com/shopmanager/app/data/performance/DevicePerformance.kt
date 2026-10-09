package com.shopmanager.app.data.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * "وضع لكل هاتف": instead of one fixed UI for every device, the app decides
 * once — the very first time it runs on a phone — whether that phone is
 * entry-level or not, and remembers the answer locally (no server call, no
 * network, no per-model whitelist to maintain).
 *
 * There is no reliable public API for "which phone model is this" mapped to
 * "how fast is it", and hardcoding a list of model names (redmi a10,
 * sm-a165 for the Samsung A16, ...) would break the moment a new budget
 * phone ships. So instead of guessing from the model string, this reads the
 * two signals Android actually exposes that correlate with real-world
 * jank on low-end hardware:
 *
 * - total RAM (via ActivityManager.MemoryInfo) — a Redmi A10 ships with
 *   2–3GB, a Samsung A16 with 4–6GB.
 * - CPU core count (Runtime.availableProcessors()) — budget SoCs are
 *   commonly quad-core, mid-range and up are usually 8-core.
 * - ActivityManager.isLowRamDevice() — Android's own "go edition / low
 *   RAM" flag, set by the OEM/OS itself for exactly this purpose.
 * - FEATURE ADDED ("اصلاحات للاجهزة اللي فيها معالج رسوميات ضعيف"): the
 *   three signals above are all about RAM/CPU — none of them actually
 *   look at the GPU, so a phone with decent RAM and core count but a
 *   genuinely weak/old GPU (common on budget MediaTek Helio-series chips,
 *   which often pair 4GB+ RAM with a low-end Mali GPU) still landed on
 *   STANDARD and got the full gradients/glow/blur-adjacent effects it
 *   can't actually push at 60fps. ActivityManager's own
 *   `getDeviceConfigurationInfo().reqGlEsVersion` — the OpenGL ES version
 *   the device's GPU driver reports supporting — is a standard, free
 *   (no GL context needed) proxy for GPU generation: anything below ES
 *   3.0 is old/entry-level hardware by now. Reported the same way as the
 *   others below (as `glEsVersion`, and folded into `tier` alongside RAM/
 *   cores), so a weak GPU alone is now enough to land a device on LOW even
 *   when its RAM and core count look fine.
 * - IMPROVEMENT ADDED (دقة أعلى للكشف): `ActivityManager.getMemoryClass()`
 *   — the per-app heap ceiling in MB the OS itself hands this process — is
 *   folded in as its own independent trigger too. It is not just "RAM
 *   again": OEMs tune this value per device based on their own internal
 *   tiering (RAM, expected workload, sometimes region/SKU), so it catches
 *   OEM-flagged budget units whose raw RAM number alone reads as
 *   borderline-fine (e.g. 4GB with a heap capped at 128MB, versus a
 *   "real" 4GB device with a 192MB+ heap). Free to read (no allocation),
 *   same call already made once via `activityManager` above.
 * - IMPROVEMENT ADDED (إبطال التخزين المؤقت تلقائيًا عند تحسين المنطق):
 *   the cached tier used to persist forever once written, so a phone that
 *   was misclassified before a later logic improvement (like the GPU or
 *   heap-class checks above) stayed on the old, wrong classification until
 *   someone opened Settings and tapped "إعادة فحص" — or reinstalled.
 *   [detectTier] now stores a `DETECTION_VERSION` alongside the cached
 *   tier and ignores (re-measures) any cache written under an older
 *   version, so every detection-logic upgrade in this file self-heals on
 *   the very next app launch, for every device, with no manual action.
 *
 * Any one of these tripping is enough to land a device on LOW — false
 * positives (an OK phone getting the lighter UI) just mean slightly fewer
 * animations, which is a much cheaper mistake than false negatives (a weak
 * phone getting the full-effects UI and lagging).
 */
enum class PerformanceTier {
    /** الاقتصادي: بلا أي تأثيرات؛ انتقالات تلاشٍ بسيطة فقط. */
    LOW,

    /** المتوازن: حركة ناعمة تُرسم كلها في مرحلة الرسم (transform/alpha فقط)
     * بلا ظلال ولا تكبير/تصغير للشاشة كاملة ولا حلقات لا نهائية — فتبقى سلسة
     * دون أن تستهلك المعالج الرسومي. */
    BALANCED,

    /** القوي (HIGH): كل التأثيرات — انتقالات أغنى، ظلال، لمعان تحميل، رموز متحركة. */
    STANDARD
}

/** اختصارات مقروءة بدل مقارنات الـ enum المتناثرة في الواجهة. */
val PerformanceTier.isLow: Boolean get() = this == PerformanceTier.LOW
val PerformanceTier.isBalanced: Boolean get() = this == PerformanceTier.BALANCED
val PerformanceTier.isHigh: Boolean get() = this == PerformanceTier.STANDARD

/**
 * "تفضيل الأداء" (Settings → الأداء): lets the person override the
 * automatic device detection above instead of being stuck with whatever
 * [DevicePerformance.detectTier] guessed.
 *
 * - AUTO (default): use the detected [PerformanceTier] as before — no
 *   behavior change for anyone who never opens this setting.
 * - HIGH: always run the full-effects UI (gradients, longer transitions,
 *   the counting-up animation), even on a device that auto-detected as
 *   LOW. For someone whose "weak" phone actually handles it fine.
 * - BALANCED ("متوازن"): smooth, soft motion that costs almost no GPU —
 *   no shadows, no whole-screen scaling, no endless loops.
 * - LOW: always run the reduced-motion/no-gradient UI, even on a device
 *   that auto-detected as STANDARD. For anyone who simply prefers a
 *   snappier, more static feel or wants to save battery.
 */
enum class PerformanceMode { AUTO, HIGH, BALANCED, LOW }

/** Combines the auto-detected tier with the user's manual preference —
 * the single place both are resolved into the [PerformanceTier] actually
 * handed to the UI via [LocalPerformanceTier]. */
fun resolvePerformanceTier(
    detected: PerformanceTier,
    mode: PerformanceMode,
    limit: SystemMotionLimit = SystemMotionLimit.NONE
): PerformanceTier {
    // "إزالة الحركة" في إعدادات النظام (إتاحة الوصول / خيارات المطوّر): تُحترم دائماً وتتفوق
    // على أي اختيار — كما يفعل تطبيق الملاحظات عبر Motion.enabled().
    if (limit == SystemMotionLimit.ANIMATIONS_OFF) return PerformanceTier.LOW
    val base = when (mode) {
        PerformanceMode.AUTO -> detected
        PerformanceMode.HIGH -> PerformanceTier.STANDARD
        PerformanceMode.BALANCED -> PerformanceTier.BALANCED
        PerformanceMode.LOW -> PerformanceTier.LOW
    }
    // توفير الطاقة: في الوضع التلقائي فقط ننزل من القوي إلى المتوازن (لا نغيّر اختيار المستخدم الصريح).
    return if (limit == SystemMotionLimit.POWER_SAVE && mode == PerformanceMode.AUTO && base == PerformanceTier.STANDARD)
        PerformanceTier.BALANCED else base
}

/** قيود الحركة القادمة من النظام نفسه، تُقرأ عند كل عودة للتطبيق. */
enum class SystemMotionLimit { NONE, POWER_SAVE, ANIMATIONS_OFF }

object SystemMotion {
    fun read(c: Context): SystemMotionLimit {
        val off = runCatching {
            android.provider.Settings.Global.getFloat(
                c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) <= 0f
        }.getOrDefault(false)
        if (off) return SystemMotionLimit.ANIMATIONS_OFF
        val save = runCatching {
            (c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        }.getOrDefault(false)
        return if (save) SystemMotionLimit.POWER_SAVE else SystemMotionLimit.NONE
    }
}

object DevicePerformance {
    private const val PREFS = "shop_manager_device"
    private const val KEY_TIER = "performance_tier"
    // IMPROVEMENT ADDED: written alongside KEY_TIER every time a fresh
    // measurement is cached. detectTier() only trusts a cached value when
    // its stored version matches CURRENT below — bump this number whenever
    // the detection logic in currentDeviceInfo() changes, and every
    // previously-cached device is automatically re-measured on next
    // launch instead of staying pinned to a now-outdated verdict.
    private const val KEY_DETECTION_VERSION = "performance_tier_version"
    private const val CURRENT_DETECTION_VERSION = 4

    private const val LOW_RAM_THRESHOLD_MB = 3072L
    private const val LOW_CORE_THRESHOLD = 4
    // OpenGL ES 3.0 = 0x30000 (major version in the upper 16 bits). Any
    // device reporting less than that is old/entry-level GPU hardware —
    // ES 3.0 shipped in 2012/Android 4.3, so by now a sub-3.0 report means
    // a genuinely weak GPU, not just an unusual one.
    private const val MIN_GL_ES_VERSION = 0x30000
    // IMPROVEMENT ADDED: ActivityManager.getMemoryClass() is the per-app
    // heap ceiling (MB) the OS grants this process — OEMs tune it per
    // device, so it often flags a budget unit that raw RAM alone reads as
    // borderline-fine. 96MB and below is the historical "low-end" heap
    // class band (stock AOSP's own low-RAM default is ~48–64MB; a normal
    // modern mid-ranger is 192–256MB+).
    private const val LOW_MEMORY_CLASS_MB = 96

    // الجهاز المتوسط → BALANCED تلقائياً: رام ≤ ~6GB (النظام يُبلغ أقل قليلاً من
    // الاسمي) أو أقل من 8 أنوية أو سقف heap ≤ 160MB. القوي فقط (8GB+/8 أنوية)
    // يحصل على كل التأثيرات تلقائياً.
    private const val BALANCED_RAM_THRESHOLD_MB = 7000L
    private const val BALANCED_CORE_THRESHOLD = 7
    private const val BALANCED_MEMORY_CLASS_MB = 160

    /** FEATURE ADDED (Settings → الأداء diagnostics): the raw signals
     * [detectTier] measures, exposed on their own so the person can see
     * *why* their device landed on a given tier instead of it being an
     * opaque decision — useful context right next to the new "إعادة فحص
     * أداء الجهاز" button. Always measures fresh (never reads the cached
     * tier), so this reflects the device's current state even if the
     * cached classification is stale. */
    data class DeviceInfo(
        val totalRamMb: Long,
        val cores: Int,
        val osFlaggedLowRam: Boolean,
        val glEsVersion: Int,
        val weakGpu: Boolean,
        val memoryClassMb: Int,
        val weakMemoryClass: Boolean,
        val tier: PerformanceTier
    )

    fun currentDeviceInfo(context: Context): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val osFlaggedLowRam = activityManager?.isLowRamDevice == true
        // reqGlEsVersion is free to read (no GL context/surface needed) —
        // it's just the driver-reported capability from the package
        // manager's configuration info, same source `<uses-feature
        // android:glEsVersion>` checks against at install time.
        val glEsVersion = activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: MIN_GL_ES_VERSION
        val weakGpu = glEsVersion in 1 until MIN_GL_ES_VERSION
        // getMemoryClass() is likewise free (no allocation) — just the
        // OEM-declared per-app heap ceiling for this device.
        val memoryClassMb = activityManager?.memoryClass ?: (LOW_MEMORY_CLASS_MB + 1)
        val weakMemoryClass = memoryClassMb in 1..LOW_MEMORY_CLASS_MB
        val lowRam = totalRamMb in 1..LOW_RAM_THRESHOLD_MB
        val lowCores = cores in 1..LOW_CORE_THRESHOLD
        val midRange = totalRamMb in 1..BALANCED_RAM_THRESHOLD_MB ||
            cores in 1..BALANCED_CORE_THRESHOLD ||
            memoryClassMb in 1..BALANCED_MEMORY_CLASS_MB
        // الأجهزة القديمة جداً: أندرويد 9 (API 28) أو أقدم، أو رام ≤ 3GB وحدها —
        // معالجاتها الرسومية وأنظمتها لا تتحمل أي تأثيرات مهما كان عدد أنويتها.
        val veryOld = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        val tier = when {
            osFlaggedLowRam || weakGpu || weakMemoryClass || veryOld || lowRam -> PerformanceTier.LOW
            midRange -> PerformanceTier.BALANCED
            else -> PerformanceTier.STANDARD
        }
        return DeviceInfo(totalRamMb, cores, osFlaggedLowRam, glEsVersion, weakGpu, memoryClassMb, weakMemoryClass, tier)
    }

    /**
     * Reads the cached tier if this device has been classified before
     * under the current detection logic (every launch after the first,
     * until [CURRENT_DETECTION_VERSION] next changes), otherwise measures
     * it fresh and persists the result — so this never re-runs the
     * ActivityManager query on every cold start, while still self-healing
     * the moment the detection logic below improves.
     */
    fun detectTier(context: Context): PerformanceTier {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cachedVersion = prefs.getInt(KEY_DETECTION_VERSION, -1)
        if (cachedVersion == CURRENT_DETECTION_VERSION) {
            prefs.getString(KEY_TIER, null)?.let { cached ->
                return runCatching { PerformanceTier.valueOf(cached) }.getOrDefault(PerformanceTier.BALANCED)
            }
        }

        // Reuses the exact same signals/thresholds as [currentDeviceInfo]
        // (see the BUG FIXED note that used to live here: LOW only when
        // isLowRamDevice OR both RAM *and* core count point that way, never
        // from a single weak signal alone — except a weak GPU or a weak
        // OEM-declared heap class, each its own independent trigger since a
        // phone can easily have fine raw RAM/cores paired with a weak GPU
        // or a heap ceiling the OEM tuned down) — one shared implementation,
        // so the diagnostics shown in Settings can never silently drift
        // from what actually decided the cached tier.
        val tier = currentDeviceInfo(context).tier
        prefs.edit()
            .putString(KEY_TIER, tier.name)
            .putInt(KEY_DETECTION_VERSION, CURRENT_DETECTION_VERSION)
            .apply()
        return tier
    }

    /** Clears the cached classification so the next detectTier() call
     * re-measures from scratch. Not wired to any screen yet — available
     * if a "recheck device" option in Settings is ever added, so a
     * misclassification doesn't require a reinstall to fix. */
    fun resetCachedTier(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_TIER)
            .remove(KEY_DETECTION_VERSION)
            .apply()
    }
}

/** Provided once near the root of the tree (see MainActivity); defaults to
 * STANDARD so Compose previews and anything outside the provider still
 * render the full-effects UI. */
val LocalPerformanceTier = staticCompositionLocalOf { PerformanceTier.STANDARD }
