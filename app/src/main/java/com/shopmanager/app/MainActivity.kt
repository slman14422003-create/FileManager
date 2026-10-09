package com.shopmanager.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.shopmanager.app.data.FirebaseModule
import com.shopmanager.app.data.backup.DailyBackupWorker
import com.shopmanager.app.data.notifications.BackgroundSyncWorker
import com.shopmanager.app.data.notifications.BatteryOptimizationHelper
import com.shopmanager.app.data.notifications.NotificationAction
import com.shopmanager.app.data.notifications.NotificationHelper
import com.shopmanager.app.data.notifications.NotificationSync
import com.shopmanager.app.data.performance.DevicePerformance
import com.shopmanager.app.data.performance.LocalPerformanceTier
import com.shopmanager.app.data.performance.PerformanceMode
import com.shopmanager.app.data.performance.PerformanceTier
import com.shopmanager.app.data.performance.resolvePerformanceTier
import com.shopmanager.app.data.security.AdminPasswordRepository
import com.shopmanager.app.data.security.PinAttemptThrottle
import com.shopmanager.app.data.settings.SettingsRepository
import com.shopmanager.app.ui.admin.AdminPanelScreen
import com.shopmanager.app.ui.dashboard.DashboardScreen
import com.shopmanager.app.ui.debts.DebtsScreen
import com.shopmanager.app.ui.debts.DebtsViewModel
import com.shopmanager.app.ui.debts.PersonDetailScreen
import com.shopmanager.app.ui.lock.BiometricAuth
import com.shopmanager.app.ui.lock.LockScreen
import com.shopmanager.app.ui.materials.MaterialCatalogScreen
import com.shopmanager.app.ui.materials.MaterialsScreen
import com.shopmanager.app.ui.materials.MaterialsViewModel
import com.shopmanager.app.ui.notes.NotesScreen
import com.shopmanager.app.ui.notes.NotesViewModel
import com.shopmanager.app.ui.common.AppSettingsState
import com.shopmanager.app.ui.common.AppDrawerContent
import com.shopmanager.app.ui.common.AppTextField
import com.shopmanager.app.ui.common.FloatingQuickActions
import com.shopmanager.app.ui.common.LocalFloatingBottomNavHeight
import com.shopmanager.app.ui.common.QuickAction
import com.shopmanager.app.ui.common.QuickActionFab
import com.shopmanager.app.ui.common.WebViewScreen
import com.shopmanager.app.ui.common.GlassAlertDialog
import com.shopmanager.app.ui.common.MotionSpecs
import com.shopmanager.app.ui.settings.SettingsScreen
import com.shopmanager.app.ui.splash.AppSplashScreen
import com.shopmanager.app.ui.theme.AppThemeMode
import com.shopmanager.app.ui.theme.SetSystemBarsColor
import com.shopmanager.app.ui.theme.ShopManagerTheme
import com.shopmanager.app.ui.theme.rememberIsDarkTheme
import com.shopmanager.app.data.performance.LocalRefreshRateHz
import com.shopmanager.app.data.performance.RefreshRatePolicy


// FIX: Home/Debts/Materials/Notes all live as pages of one HorizontalPager
// so every destination shares the exact same page-transition animation and
// state-preservation machinery instead of each screen wiring its own.
// REMOVED ("ميزة التنقل بين الشاشات الغيها"): a flick right/left used to
// move between pages directly, the same as tapping a tab in the drawer —
// two different gestures doing the same navigation, and a stray drag over
// any tab's own content (a long list, a swipe-to-delete row, ...) could
// switch the whole screen by accident. Navigation between the four tabs
// now only ever happens through the drawer or a quick-action tap
// (`openPager()` below); the pager itself is scrolled purely
// programmatically, never by the user's finger — see `userScrollEnabled`
// on the HorizontalPager further down.
private const val ROUTE_MAIN_PAGER = "mainPager"
private const val PAGE_DASHBOARD = 0
private const val PAGE_DEBTS = 1
private const val PAGE_MATERIALS = 2
// ملاحظات هامة (Important Notes) - the 4th main tab, added alongside
// Home/Debts/Materials in the same HorizontalPager/FloatingBottomNav so it
// shares the exact same tab-switch animation described above for free.
private const val PAGE_NOTES = 3
private const val ROUTE_SETTINGS = "settings"
// لوحة المسؤول السرية: reached only via the small admin-panel button
// pinned next to الإعدادات at the bottom of the side drawer (see
// AppDrawerContent) + the PIN dialog it opens (AdminPinDialog below).
// MOVED ("انقل ايقونة المسؤول الى المنيو الى جانب الاعدادات"): this used
// to be a hidden button on DashboardScreen's own header, reachable only
// from the Home tab. The trigger — and the PIN-gate state/dialog that
// guards it — now live here at the same level as the drawer itself, so
// لوحة المسؤول is reachable from the drawer regardless of which tab is
// open, exactly like الإعدادات already is.
private const val ROUTE_ADMIN = "adminPanel"
// SECURITY: كلمة مرور لوحة المسؤول لم تعد مكتوبة بالكود — محفوظة في Firebase فقط
// (Firestore ← config ← admin ← password)، وتُغيَّر وتُقرأ من Firebase Console.
// راجع AdminPasswordRepository. المحاولات تبقى مُقيَّدة عبر PinAttemptThrottle
// (انظر AdminPinDialog تحت) فلا يمكن تخمينها من لوحة المفاتيح.
private const val ROUTE_MATERIAL_CATALOG = "materialCatalog"
private const val ROUTE_HELP = "help"
private const val ROUTE_PRIVACY = "privacy"
private const val ROUTE_PERSON_DETAIL = "personDetail/{personId}"

// The in-app liquid-glass splash (see AppSplashScreen) is a calm branded
// moment, not a progress readout — on a fast device real init can finish
// in well under 200ms, too quick to register as anything but a flicker.
// This is the one artificial delay in the whole startup path, just long
// enough for the splash to actually be seen before it crossfades away.
// PERF FIX: كان الحد الأدنى للهاتف السريع (1000ms) أطول من الهاتف الضعيف (650ms)،
// أي عكس المطلوب؛ الآن الهاتف السريع يكفيه أنيميشن السبلاش (~420ms) فقط.
private const val SPLASH_MIN_DISPLAY_MS = 450L
private const val SPLASH_MIN_DISPLAY_LOW_MS = 650L

// REPLACED WITH CLAUDE.AI-STYLE MOTION: this used to be Apple's own
// UIView screen-transition curve. Every major transition in the app
// (splash→app hand-off, bottom-nav page transitions further down this
// file) now shares [MotionSpecs.claudeEasing] instead — the reference
// Claude.ai design's own quick-start/gentle-stop, no-overshoot curve —
// so nothing in the app still reads as borrowed from iOS.
private val claudeStandardEasing = MotionSpecs.claudeEasing

private const val UI_SCALE = 1.08f

class MainActivity : FragmentActivity() {
    // Class-level (not inside setContent) so onNewIntent below - fired when
    // the app is already running and a *second* notification is tapped -
    // can update it too. A local `remember { mutableStateOf(...) }` created
    // inside setContent would only ever be initialized once, from the
    // Intent MainActivity happened to start with, and would never see a
    // later Intent onNewIntent hands us.
    private var pendingNotificationAction by mutableStateOf<NotificationAction?>(null)

    // True once the app has left the screen (home / app switch / screen off)
    // while a PIN is set — see onStop below and the lock overlay in setContent.
    private var lockedByLeave by mutableStateOf(false)
    private val lockSettings by lazy { SettingsRepository(applicationContext) }

    /**
     * onStop fires both when the person leaves the app and when the screen
     * turns off, which are exactly the two moments that should re-lock it.
     * (It also fires when another full-screen activity opens on top — e.g. the
     * share sheet or APK installer — so returning from those asks again too;
     * "القفل عند الخروج" in Settings turns this behavior off.)
     */
    /**
     * FEATURE ADDED (FLAG_SECURE): while a PIN is set (and "منع لقطات الشاشة"
     * is on in Settings) the window is marked secure — screenshots and screen
     * recording show black, and the app's thumbnail in the recent-apps list is
     * blank, so shop debts/prices can't leak from there. Re-evaluated on every
     * resume and called by SettingsScreen right after the PIN/toggle changes.
     */
    fun applySecureFlag() {
        val secure = lockSettings.hasPin && lockSettings.secureScreen
        if (secure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onResume() {
        super.onResume()
        systemMotionLimit = com.shopmanager.app.data.performance.SystemMotion.read(this)
        applySecureFlag()
    }

    override fun onStop() {
        if (!isFinishing && lockSettings.hasPin && lockSettings.autoLockOnLeave) {
            lockedByLeave = true
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // PERF FIX (startup jitter): installSplashScreen() must run before
        // super.onCreate(). It puts a static app icon on a flat brand-color
        // background up immediately — nothing on that screen animates or
        // recomposes, so there is nothing to jitter while the phone is
        // still busy underneath.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Must be set before the first frame so even the splash/lock never lands in a screenshot.
        applySecureFlag()

        // "زجاجي بالكامل" (full glass, edge-to-edge): let this Activity's
        // window draw behind both system bars instead of the OS reserving
        // a solid, separately-colored strip for them. Combined with
        // transparent status/nav bar colors (see SetSystemBarsColor below),
        // this is what lets every liquid-glass header bleed all the way up
        // to the true top of the screen with no hard seam under the status
        // bar icons — previously the status bar was an opaque flat color
        // sitting directly above the gradient/glass header, and that flat
        // color meeting the header's glossy top edge was exactly the
        // visible dividing line.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val splashStartTime = System.currentTimeMillis()

        // Cold start via a notification tap (app wasn't running): the
        // Activity's very first Intent already carries the extras
        // NotificationHelper attached. Read it once, up front.
        pendingNotificationAction = NotificationAction.from(intent)

        var isReady by mutableStateOf(false)
        var detectedTier by mutableStateOf(PerformanceTier.BALANCED)
        // Tracks only whether Compose has produced its first frame — NOT
        // whether init is done. The static system splash now only needs to
        // bridge the gap until Compose can draw *something*; from that
        // first frame on, AppSplashScreen (rendered below, in Compose) is
        // what actually covers the screen and shows real init progress, so
        // there's no reason to keep the frozen system icon up any longer
        // than that. (Approximated via a LaunchedEffect(Unit) in setContent
        // — not pixel-exact to the true first draw, but close enough that
        // the handoff is invisible in practice.)
        var composeSplashAttached by mutableStateOf(false)
        splashScreen.setKeepOnScreenCondition { !composeSplashAttached }

        // معدل التحديث يُطبَّق حسب وضع الأداء داخل setContent (LaunchedEffect على performanceTier)،
        // ونتابع تغيّره الفعلي لتضبط الحركات سرعتها على 60Hz مقابل 90Hz+.
        refreshHz = currentDisplayHz()
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, null)

        // "حسن عمل التطبيق في الخلفية بشكل مخفي": one silent, at-most-once
        // system prompt so BackgroundSyncWorker/NoteReminderWorker keep
        // firing even on OEMs (Xiaomi/Huawei/Samsung, ...) that otherwise
        // freeze background work the moment the app isn't open - see
        // BatteryOptimizationHelper. Never shown more than once per
        // install and never blocks anything else in onCreate.
        BatteryOptimizationHelper.maybeRequest(this)

        // PERF FIX (startup jitter): all of Firebase init, notification
        // channel setup, device-tier detection (disk read), and scheduling
        // the background sync worker (which — see AndroidManifest.xml and
        // ShopManagerApplication.kt — is also where WorkManager's Room
        // database actually gets built the first time) used to run
        // synchronously on the main thread in onCreate, before Compose
        // ever got a chance to draw. That's real, measurable main-thread
        // work stacked right at cold start, which is what showed up as a
        // few seconds of visible jank/"shaking". None of it needs the main
        // thread, so it now all runs on a background dispatcher while the
        // splash screen (system, then in-app — see above) covers the UI.
        lifecycleScope.launch(Dispatchers.Default) {
            // STABILITY FIX: if any step below threw (Firebase init, channel
            // setup, a WorkManager scheduling error), the coroutine died
            // before `isReady = true` and the person was stuck on the splash
            // forever. Each step is now isolated, and the hand-off to the real
            // UI always happens in the finally block with a safe default tier.
            var tier = PerformanceTier.BALANCED
            try {
                runCatching { FirebaseModule.init(applicationContext) }
                runCatching { NotificationHelper.ensureChannels(applicationContext) }

                // "وضع لكل هاتف": classified once (cached after that), then
                // used below to switch off the heavier visual effects on
                // entry-level hardware — see DevicePerformance for the
                // detection signals.
                tier = runCatching { DevicePerformance.detectTier(applicationContext) }
                    .getOrDefault(PerformanceTier.BALANCED)

                // Weak/economic devices get a lighter, battery-guarded sync
                // schedule automatically — see BackgroundSyncWorker.schedule.
                runCatching { BackgroundSyncWorker.schedule(applicationContext, tier) }
                // Silent, fully local daily backup — no notification, ever
                // (see DailyBackupWorker/BackupManager).
                runCatching { DailyBackupWorker.schedule(applicationContext) }

                // "الإشعارات لا تأتي في الخلفية": يشغّل مراقب تغييرات الأجهزة الأخرى
                // (والخدمة الأمامية إذا كانت "المزامنة الفورية" مفعّلة) — مكان واحد
                // للكشف بدل منطق منفصل لكل شاشة. خارج الخيط الرئيسي، والسبلاش يغطي.
                runCatching { NotificationSync.apply(applicationContext) }

                // PERF: الحد الأدنى للسبلاش أقصر على الهاتف الضعيف؛ أنيميشن
                // السبلاش نفسه ~420ms فلا يُقطع.
                val minSplashMs = if (tier == PerformanceTier.LOW) SPLASH_MIN_DISPLAY_LOW_MS else SPLASH_MIN_DISPLAY_MS
                val elapsed = System.currentTimeMillis() - splashStartTime
                if (elapsed < minSplashMs) delay(minSplashMs - elapsed)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    detectedTier = tier
                    isReady = true
                }
            }
        }

        setContent {
            val settings = remember { SettingsRepository(applicationContext) }
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            var unlocked by remember { mutableStateOf(!settings.hasPin) }

            // FEATURE ADDED ("التحديثات اجبارية ... لما افتح التطبيق يتحقق
            // من التحديث"): a mandatory update check on every cold start,
            // separate from the optional "تحقق من التحديثات" button in
            // Settings (unchanged, still there). See ForceUpdateScreen's
            // own doc for the full rationale, including why this fails
            // OPEN (lets the person into the app) rather than closed
            // whenever the check itself can't complete — only a CONFIRMED
            // newer versionCode ever blocks entry.
            //
            // FEATURE ("والتحقق من التحديثات خليها منفصلة عن السبلاش"):
            // this check no longer gates the splash→app hand-off — see the
            // AnimatedContent block below, where the real app renders as
            // soon as `isReady` flips regardless of whether this check has
            // even finished yet. `forceUpdateManifest` becoming non-null is
            // now the ONLY thing that puts [ForceUpdateScreen] up, drawn as
            // an overlay on top of whatever the person is already looking
            // at, rather than a state the splash branch has to wait on.
            //
            // "تفعيل التحديث الاجباري" (لوحة المسؤول): a developer-only
            // on/off switch (settings.forceUpdateEnabled, default on) for
            // this whole automatic cold-start gate — see AdminPanelScreen.
            // Turning it off never touches the manual "تحقق من التحديثات"
            // button in Settings, only this silent startup check.
            var forceUpdateManifest by remember { mutableStateOf<com.shopmanager.app.data.updates.UpdateManifest?>(null) }
            // التحديث الاختياري (الحالة الطبيعية): مربع "تحديث الآن / لاحقاً".
            // شاشة الإجبار أعلاه لا تظهر إلا إذا وُضعت علامة [force] في إصدار GitHub.
            var optionalUpdateManifest by remember { mutableStateOf<com.shopmanager.app.data.updates.UpdateManifest?>(null) }

            // "تفضيل الأداء": loaded once here (not re-read from disk on
            // every recomposition), then kept in sync live when changed in
            // Settings via onPerformancePreferenceChanged below — same
            // pattern as themeMode/onThemeChanged just above.
            var performancePreference by remember { mutableStateOf(settings.performanceMode) }
            val performanceTier by remember {
                derivedStateOf { resolvePerformanceTier(detectedTier, performancePreference, systemMotionLimit) }
            }
            // الأداء القوي → ~90Hz، الاقتصادي → 60Hz؛ يُعاد التطبيق فور تغيّر الوضع من الإعدادات.
            LaunchedEffect(performanceTier) { applyRefreshRate(performanceTier) }
            // FEATURE ADDED ("إعادة فحص أداء الجهاز" in Settings → الأداء):
            // DevicePerformance.resetCachedTier existed already but had no
            // caller — wiring it up needs both steps done together (clear
            // the cached classification, then re-measure) and the result
            // written back into this same `detectedTier` state so the UI
            // actually picks up the new tier immediately, without asking
            // the person to restart the app.
            val onRecheckDevicePerformance: () -> Unit = {
                DevicePerformance.resetCachedTier(applicationContext)
                detectedTier = DevicePerformance.detectTier(applicationContext)
            }

            // Dismiss the static system splash screen as soon as Compose has
            // a frame ready to draw — see the composeSplashAttached comment
            // above. From here on AppSplashScreen below is what the person
            // actually sees while the background init finishes.
            LaunchedEffect(Unit) { composeSplashAttached = true }

            // "دعم كثافات الشاشة (DPI) بشكل أفضل": هذا التطبيق يستخدم dp/sp
            // في كل مكان أصلاً، لذا يتوسّع تلقائيًا بشكل سليم عبر كل
            // كثافات الشاشة العادية (mdpi..xxxhdpi). لكن عند تفعيل أكبر
            // إعداد نظام لحجم الخط (إعدادات إمكانية الوصول)، `fontScale`
            // قد يتجاوز 1.8-2x، وعندها تبدأ العناوين والأزرار الثابتة
            // الحجم بالتراكب فوق بعضها بدل التمرير بلطف. تحديد أعلى قيمة
            // معقولة (1.3x) يحافظ على استجابة التطبيق لتفضيل الشخص دون
            // كسر تخطيط الشاشات ذات العناصر الثابتة (الهيدر، الأزرار
            // العائمة)، نفس الأسلوب الذي توصي به وثائق Compose نفسها لدعم
            // مقاييس خط متطرفة بأمان.
            val baseDensity = LocalDensity.current
            val clampedDensity = remember(baseDensity) {
                Density(
                    // تكبير بسيط (8%) لكل العناصر والخطوط في كل الشاشات لأنها كانت
                    // صغيرة؛ غيّر UI_SCALE إذا بدك أكبر أو أصغر (1.0 = بدون تكبير).
                    density = baseDensity.density * UI_SCALE,
                    fontScale = baseDensity.fontScale.coerceIn(0.85f, 1.3f)
                )
            }

            CompositionLocalProvider(LocalDensity provides clampedDensity) {
            ShopManagerTheme(themeMode = themeMode) {
                // "زجاجي بالكامل" (fully glass): the status bar is now
                // always fully transparent (see the edge-to-edge window
                // setup in onCreate above and SetSystemBarsColor below) —
                // there's no separate OS-painted strip to keep color-synced
                // with the app any more, so whatever is actually behind it
                // (the splash gradient, or a screen's own liquid-glass
                // header) just shows straight through with no seam between
                // "system bar" and "app content". Only the icon *color*
                // still needs picking per screen: the splash and every
                // glass header (once unlocked) are dark enough for white
                // icons.
                //
                // BUG FIXED (LockScreen redesign — see LockScreen.kt): the
                // PIN lock screen no longer sits on the full-bleed
                // [BrandGradient] wash — Claude-style redesign made it a
                // plain neutral background like every ordinary screen (see
                // LockScreen.kt's own note). So it now needs the same
                // `!isDark`-based icon color a normal unlocked screen gets,
                // not the forced-white treatment that's still correct for
                // the splash (which stays full-bleed colored). Only the
                // splash (`!isReady`) keeps the old forced-white behavior.
                val isDark = rememberIsDarkTheme(themeMode)
                SetSystemBarsColor(
                    // "الشريط السفلي العائم بدون الخلفية السوداء": this used
                    // to switch to a solid MaterialTheme.colorScheme.background
                    // once the splash finished, which on the dark theme
                    // (near-black DarkBackground) painted a flat black strip
                    // across the whole gesture-bar area — sitting directly
                    // under FloatingBottomNav's transparent side margins, so
                    // the floating pill visibly had a black rectangle behind
                    // it instead of the page just continuing through. The nav
                    // bar now stays fully transparent always, exactly like
                    // the status bar already does, so FloatingBottomNav's
                    // margins show the real page content/background instead
                    // of a separately-painted solid color.
                    navigationBarColor = Color.Transparent,
                    // BUG FIXED ("في الوضع النهاري شريط الحالة يصبح ابيض كله"):
                    // this used to force white/light icons (`false`) for
                    // every *unlocked*, ready screen regardless of theme —
                    // only the lock screen actually read `!isDark`. In light
                    // mode that put white status-bar icons over the app's
                    // own white/cream background, so the icons themselves
                    // vanished and the whole status bar area read as a flat
                    // blank white strip. Only the splash (`!isReady`, which
                    // always sits on its own colored full-bleed background)
                    // still needs the forced-white treatment; every other
                    // ready screen — locked or not — now follows the actual
                    // theme like navigationBarDarkIcons already did.
                    statusBarDarkIcons = if (isReady) !isDark else false,
                    navigationBarDarkIcons = if (isReady) !isDark else false
                )

                Surface {
                    // A one-time transition from the in-app splash into the
                    // real UI once isReady flips true. On STANDARD/HIGH this
                    // is a soft iOS-style "zoom + fade" hand-off (the splash
                    // very slightly scales up and fades out while the real
                    // UI scales in from a touch smaller than full size) —
                    // the same "settling into place" feel as an iOS app's
                    // launch screen dissolving into its first real screen,
                    // rather than a flat opacity cut. LOW tier keeps this to
                    // a cheap, near-instant plain fade — same convention as
                    // every other animation in this file — since this only
                    // ever runs once per cold start either way, so it's not
                    // a recurring cost worth guarding further.
                    val isLowTierForHandoff = performanceTier == PerformanceTier.LOW
                    AnimatedContent(
                        targetState = isReady,
                        label = "splashToApp",
                        transitionSpec = {
                            if (isLowTierForHandoff) {
                                fadeIn(tween(90, easing = claudeStandardEasing)) togetherWith fadeOut(tween(90, easing = claudeStandardEasing))
                            } else if (performanceTier == PerformanceTier.BALANCED) {
                                // المتوازن: تلاشٍ ناعم فقط (بلا scale للشاشة كاملة).
                                fadeIn(tween(220, easing = claudeStandardEasing)) togetherWith fadeOut(tween(160, easing = claudeStandardEasing))
                            } else {
                                (fadeIn(tween(360, easing = claudeStandardEasing)) +
                                    scaleIn(
                                        initialScale = 0.96f,
                                        animationSpec = tween(360, easing = claudeStandardEasing)
                                    )) togetherWith
                                    (fadeOut(tween(260, easing = claudeStandardEasing)) +
                                        scaleOut(
                                            targetScale = 1.04f,
                                            animationSpec = tween(260, easing = claudeStandardEasing)
                                        ))
                            }
                        }
                    ) { ready ->
                        if (!ready) {
                            AppSplashScreen()
                        } else {
                            // Runs exactly once, right as the splash hands
                            // off to the real UI. Uses `ready` as the key
                            // (not Unit) purely so this can never fire
                            // during the brief `!ready` frames above —
                            // functionally it only ever runs the one time
                            // `ready` flips true for the life of this
                            // Activity instance.
                            //
                            // FEATURE ("خليها منفصلة عن السبلاش"): this no
                            // longer blocks anything from rendering below —
                            // it just runs in the background and, if it
                            // ever confirms a newer versionCode, sets
                            // `forceUpdateManifest`, which the overlay right
                            // after this AnimatedContent block picks up. The
                            // person reaches the real app immediately either
                            // way; a slow network only delays the mandatory
                            // screen showing up, never the app opening.
                            LaunchedEffect(ready) {
                                when (val result = com.shopmanager.app.data.updates.UpdateChecker.check(
                                    applicationContext,
                                    settings.updateManifestUrl,
                                    timeoutMs = com.shopmanager.app.data.updates.UpdateChecker.STARTUP_TIMEOUT_MS
                                )) {
                                    is com.shopmanager.app.data.updates.UpdateCheckResult.UpdateAvailable -> {
                                        // إجباري فقط إذا طلبه إصدار GitHub (علامة [force]) ولم يُوقف المطوّر
                                        // الخيار من لوحة المسؤول؛ غير ذلك يظهر مربع اختياري.
                                        if (result.manifest.force && settings.forceUpdateEnabled) {
                                            forceUpdateManifest = result.manifest
                                        } else if (com.shopmanager.app.data.updates.RemoteUpdateConfig
                                                .isOptionalUpdateEnabled(applicationContext)
                                        ) {
                                            // مفتاح عام من Firebase (لوحة المسؤول): إيقافه يخفي
                                            // هذا المربع عند كل المستخدمين، لا جهازك وحده.
                                            optionalUpdateManifest = result.manifest
                                        }
                                    }
                                    else -> Unit // UpToDate or Failed: fail open.
                                }
                            }

                            CompositionLocalProvider(
                                LocalPerformanceTier provides performanceTier,
                                LocalRefreshRateHz provides refreshHz
                            ) {
                                // Load the persisted currency symbol into the
                                // app-wide holder once, so every screen
                                // (dashboard, debts, materials, notifications)
                                // shows the right currency from the very
                                // first frame of the real app.
                                LaunchedEffect(Unit) { AppSettingsState.setCurrency(settings.currencySymbol) }

                                // Android 13+ requires explicit runtime
                                // permission to post notifications (needed
                                // for the low-stock shopping list and
                                // new-debt alerts). Requested once the real
                                // UI is up, not while the splash is showing.
                                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                                    ActivityResultContracts.RequestPermission()
                                ) { }
                                LaunchedEffect(Unit) {
                                    // Only when notifications are switched on in Settings and the
                                    // permission isn't already granted - it used to fire the system
                                    // request on every single cold start regardless.
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        settings.notificationsEnabled &&
                                        androidx.core.content.ContextCompat.checkSelfPermission(
                                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                    ) {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                }

                                // BUG FIXED ("الخروج من الشاشات" لا يعمل —
                                // شاشة القفل تحديدًا): this was a plain
                                // `if (!unlocked) LockScreen else
                                // ShopManagerApp` — a hard, un-animated cut
                                // the instant the PIN is accepted. Every
                                // other hand-off in this file (splash→app
                                // right above, every screen push/pop in
                                // NavHost) got the full Claude-motion
                                // treatment; this one silently never did,
                                // so "unlocking" was the one exit in the
                                // whole app with literally zero animation
                                // — exactly the kind of gap that reads as
                                // "the exit animation doesn't work" even
                                // though every other screen's exit does.
                                // Reuses the exact same fade+scale curve
                                // and `isLowTierForHandoff`/
                                // `claudeStandardEasing` as the splash→app
                                // AnimatedContent just above, so unlocking
                                // now reads as one continuous motion
                                // language with the rest of the app instead
                                // of a one-off snap.
                                AnimatedContent(
                                    targetState = unlocked,
                                    label = "lockToApp",
                                    transitionSpec = {
                                        if (isLowTierForHandoff) {
                                            fadeIn(tween(90, easing = claudeStandardEasing)) togetherWith
                                                fadeOut(tween(90, easing = claudeStandardEasing))
                                        } else if (performanceTier == PerformanceTier.BALANCED) {
                                            // المتوازن: تلاشٍ ناعم فقط (بلا scale للشاشة كاملة).
                                            fadeIn(tween(220, easing = claudeStandardEasing)) togetherWith fadeOut(tween(160, easing = claudeStandardEasing))
                                        } else {
                                            (fadeIn(tween(360, easing = claudeStandardEasing)) +
                                                scaleIn(
                                                    initialScale = 0.96f,
                                                    animationSpec = tween(360, easing = claudeStandardEasing)
                                                )) togetherWith
                                                (fadeOut(tween(260, easing = claudeStandardEasing)) +
                                                    scaleOut(
                                                        targetScale = 1.04f,
                                                        animationSpec = tween(260, easing = claudeStandardEasing)
                                                    ))
                                        }
                                    }
                                ) { isUnlocked ->
                                    if (!isUnlocked) {
                                        LockScreen(settings = settings, onUnlocked = { unlocked = true; lockedByLeave = false })
                                    } else {
                                        ShopManagerApp(
                                            settings = settings,
                                            onThemeChanged = { themeMode = it },
                                            onPerformancePreferenceChanged = { performancePreference = it },
                                            onRecheckDevicePerformance = onRecheckDevicePerformance,
                                            pendingNotificationAction = pendingNotificationAction,
                                            onConsumeNotificationAction = { pendingNotificationAction = null }
                                        )
                                    }
                                }

                                // FEATURE ADDED ("عند الخروج او اغلاق الشاشة يطلب البصمة كل مرة"):
                                // onStop() below raises [lockedByLeave]; this overlay then
                                // covers the already-open app with the lock screen. It is an
                                // overlay (not a swap) on purpose: ShopManagerApp stays composed
                                // underneath, so after unlocking the person lands exactly where
                                // they were (same tab / same open screen) instead of at the
                                // dashboard. Touches and Back are swallowed so nothing under it
                                // can be reached.
                                if (lockedByLeave && unlocked) {
                                    BackHandler { moveTaskToBack(true) }
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        awaitPointerEvent().changes.forEach { it.consume() }
                                                    }
                                                }
                                            }
                                    ) {
                                        LockScreen(
                                            settings = settings,
                                            onUnlocked = { lockedByLeave = false }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // FEATURE ("والتحقق من التحديثات خليها منفصلة عن
                    // السبلاش"): the mandatory update check above no longer
                    // gates the splash→app hand-off — the person reaches
                    // the real app immediately once `isReady` flips, and
                    // this gate appears ON TOP of it as its own overlay,
                    // only if (and whenever) the background check actually
                    // confirms a newer versionCode. Drawn as a sibling of
                    // AnimatedContent rather than nested inside its `ready`
                    // branch, the same reasoning as the admin PIN dialog /
                    // notification dialogs further down this file — it has
                    // to be able to appear over whichever screen the person
                    // is already on.
                    forceUpdateManifest?.let { manifest ->
                        com.shopmanager.app.ui.update.ForceUpdateScreen(
                            manifest = manifest,
                            currentVersion = com.shopmanager.app.data.updates.AppVersionInfo.current(applicationContext)
                        )
                    }
                    if (forceUpdateManifest == null) {
                        optionalUpdateManifest?.let { manifest ->
                            com.shopmanager.app.ui.update.UpdateAvailableDialog(
                                manifest = manifest,
                                currentVersion = com.shopmanager.app.data.updates.AppVersionInfo.current(applicationContext),
                                onLater = { optionalUpdateManifest = null }
                            )
                        }
                    }
                }
            }
            } // CompositionLocalProvider(LocalDensity) — clamped fontScale
        }
    }

    override fun onDestroy() {
        // الخدمة الأمامية (إن وُجدت) تحتفظ بمالكها الخاص فتبقى المستمعات حيّة؛
        // وإلا نغلقها بعد أن يغادر المستخدم التطبيق فعلاً (لا عند تدوير الشاشة).
        if (isFinishing) NotificationSync.onAppClosed()
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    /**
     * Warm start: the app is already running (this Activity instance is
     * still alive) and a notification is tapped. `android:launchMode=
     * "singleTop"` on MainActivity (see AndroidManifest.xml) is what
     * routes the tap here instead of spinning up a whole new Activity
     * instance - without it, this override would simply never fire and
     * the tap would silently do nothing while the app was open/backgrounded.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationAction = NotificationAction.from(intent)
    }

    /** معدل تحديث الشاشة الفعلي الآن؛ حالة Compose حتى تتفاعل الحركات مع تغيّره. */
    private var refreshHz by androidx.compose.runtime.mutableFloatStateOf(60f)
    // قيد الحركة من النظام (إزالة الحركة / توفير الطاقة) — يُحدَّث في onResume.
    private var systemMotionLimit by androidx.compose.runtime.mutableStateOf(com.shopmanager.app.data.performance.SystemMotionLimit.NONE)

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            refreshHz = currentDisplayHz()
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayHz(): Float = windowManager.defaultDisplay?.refreshRate?.takeIf { it > 0f } ?: 60f

    /**
     * الأداء القوي → أقرب وضع إلى 90Hz، الاقتصادي → 60Hz (انظر [RefreshRatePolicy]).
     * على شاشة 60Hz فقط لا يتغير شيء هنا، والإحساس "كأنها 90" يأتي من MotionSpecs.
     */
    @Suppress("DEPRECATION")
    private fun applyRefreshRate(tier: PerformanceTier) {
        RefreshRatePolicy.apply(window, windowManager.defaultDisplay, tier)
        RefreshRatePolicy.applyFrameRateHint(window, tier)
        refreshHz = currentDisplayHz()
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShopManagerApp(
    settings: SettingsRepository,
    onThemeChanged: (AppThemeMode) -> Unit,
    onPerformancePreferenceChanged: (PerformanceMode) -> Unit,
    onRecheckDevicePerformance: () -> Unit = {},
    pendingNotificationAction: NotificationAction? = null,
    onConsumeNotificationAction: () -> Unit = {}
) {
    val navController = rememberNavController()
    // Shared across screens so everyone sees the same live data instead of
    // spinning up duplicate Firestore listeners per screen.
    val debtsViewModel: DebtsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val materialsViewModel: MaterialsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val notesViewModel: NotesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    // Hoisted above the NavHost (rather than inside the pager's own
    // composable) so it survives navigating away to Settings/Help and back,
    // and so the bottom bar can read/drive the current page directly.
    val pagerState = rememberPagerState(initialPage = PAGE_DASHBOARD) { 4 }
    // REDESIGN ("زر عميل جديد بجانب الشريط السفلي"): the "+" that used to
    // be each tab's own FloatingActionButton is now a single shared button
    // rendered as part of FloatingBottomNav itself (see its `quickAction`
    // param) so it always sits attached to the pill instead of floating
    // separately over the list. It can't reach into DebtsScreen's own
    // dialog state directly, so tapping it just raises this flag; DebtsScreen
    // watches it and opens its "عميل جديد" dialog when it turns true (see
    // DebtsScreen's `addPersonRequested` parameter).
    var addPersonRequested by remember { mutableStateOf(false) }
    // Same request/handled pattern as addPersonRequested just above, for
    // the ملاحظات هامة tab's own "+" quick action.
    var addNoteRequested by remember { mutableStateOf(false) }
    // "دمج زر حفظ الأسعار مع الشريط السفلي": mirrors addPersonRequested's
    // own request/handled pattern just above, but for the الأسعار tab's
    // save action instead — see MaterialsScreen's matching parameters for
    // the full explanation. `materialsPricesTabActive` and
    // `pricesChangedCount` are reported live by MaterialsScreen (it owns
    // the tab state and the in-progress price edits) so the secondary
    // pill button below only appears, and only shows a changed-count
    // description, while that specific tab is actually on screen.
    var materialsPricesTabActive by remember { mutableStateOf(false) }
    var pricesChangedCount by remember { mutableStateOf(0) }
    var savePricesRequested by remember { mutableStateOf(false) }
    // BUG FIXED ("ترابط بين الديون والملاحظات" كان يغطي الأشخاص فقط):
    // tapping a material-linked note used to only switch to the Materials
    // tab in general (see the old onOpenMaterials below) — nothing pointed
    // at the specific item, unlike a person-linked note which opens that
    // exact customer's page. Same request/handled pattern as
    // addPersonRequested above: NotesScreen raises this with the linked
    // material's name, MaterialsScreen consumes it as its initial search.
    var pendingMaterialHighlight by remember { mutableStateOf<String?>(null) }
    val pagerScope = rememberCoroutineScope()

    // REDESIGN ("قائمة جانبية بدل الشريط السفلي"): tab switching no longer
    // happens through a floating pill glued to the screen's bottom edge —
    // a single hamburger button opens this Material3 side drawer instead,
    // laid out like Claude's own app drawer (see AppDrawerContent.kt: a
    // plain icon+label row per destination, الإعدادات pinned below a
    // divider at the bottom instead of sitting in that same list). The
    // pager/openPager() machinery underneath is unchanged — the drawer
    // just drives the same `pagerState` the old pill used to.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    // لوحة المسؤول السرية: PIN-gate state, hoisted up here from
    // DashboardScreen now that the trigger button lives in the drawer
    // instead of Home's own header (see the ROUTE_ADMIN comment above).
    var showAdminPinDialog by remember { mutableStateOf(false) }
    val adminContext = LocalContext.current
    val adminThrottle = remember { PinAttemptThrottle(adminContext, "shop_manager_admin_throttle") }

    // BUG FIXED ("ترابط بين جميع الانميشن"): tapping a bottom-nav tab (or a
    // notification landing on a specific tab) drove the SAME pager as
    // swiping between Home/Debts/Materials/Notes, but through
    // `animateScrollToPage()`'s own default spec — a generic Compose
    // spring, completely unrelated to the deliberately-tuned iOS-style
    // curve (`MotionSpecs.claudeEasing`/`pushSlideSpec` below) every *pushed* screen
    // (Settings, Person Detail, ...) already animates with. The two kinds
    // of navigation in this same app were animating on two unrelated
    // curves/timings, which is exactly what reads as inconsistent even
    // though each one individually looked fine. This uses the identical
    // cubic-bezier shape (and the same LOW-tier "skip it" convention) so a
    // tab switch and a screen push now feel like the same design language
    // instead of two different ones stitched together.
    val isLowTierForPager = LocalPerformanceTier.current == PerformanceTier.LOW
    val pagerTabAnimationSpec: FiniteAnimationSpec<Float> =
        tween(MotionSpecs.pagerMillis().coerceAtLeast(150), easing = MotionSpecs.claudeEasing)

    @OptIn(ExperimentalFoundationApi::class)
    fun openPager(page: Int) {
        navigateTopLevel(navController, ROUTE_MAIN_PAGER)
        pagerScope.launch { pagerState.animateScrollToPage(page, animationSpec = pagerTabAnimationSpec) }
    }

    // Tapping a notification should land on the screen it's about, not just
    // pop a dialog over whatever tab happened to be open. Debts stay on the
    // الديون tab, the shopping-list one jumps to المواد والأسعار.
    LaunchedEffect(pendingNotificationAction) {
        when (pendingNotificationAction) {
            is NotificationAction.DebtPaid, is NotificationAction.NewDebt -> openPager(PAGE_DEBTS)
            is NotificationAction.ShoppingList -> openPager(PAGE_MATERIALS)
            is NotificationAction.NoteReminder -> openPager(PAGE_NOTES)
            null -> {}
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == ROUTE_MAIN_PAGER
    // BUG FIXED ("مساحة سوداء فوق الكيبورد بتغطي المادة والسعر"): the
    // floating pill is a fixed overlay (see the Box below) that never
    // moves when the keyboard opens — it just ends up sitting underneath
    // the keyboard, invisible. But every tab that reads
    // [LocalFloatingBottomNavHeight] (see PricesList/MaterialsList below)
    // was still reserving that exact same clearance at its own bottom
    // regardless, on the assumption the pill was still there to avoid.
    // With the keyboard up that clearance reserves for nothing — it's
    // dead space between the last visible row and the keyboard, which is
    // exactly the black gap being reported. `imeVisible` (real-time,
    // driven by the live `WindowInsets.ime` bottom inset — see the
    // `adjustResize` manifest fix alongside this one) collapses that
    // reservation to 0 the instant the keyboard opens, and the pill
    // itself fades out below rather than sitting there uselessly hidden
    // behind the keyboard. Kept as its own `pillVisible` flag rather than
    // folded into `showBottomBar` itself — NavHost's own bottom-inset
    // toggle just below still needs `showBottomBar` to mean "is this the
    // pager route", independent of the keyboard, or it would start
    // double-padding for the exact same reason `adjustResize` was added
    // for in the first place.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // Still named `pillVisible` for the smallest possible diff against the
    // FAB-clearance machinery below ([LocalFloatingBottomNavHeight],
    // [FloatingQuickActions]) — it now also gates the hamburger menu
    // button instead of a floating tab pill, same "hide it on screens
    // that aren't the main pager, or while the keyboard covers it" rule
    // as before.
    val pillVisible = showBottomBar && !imeVisible

    // PERF (low-end tier): a fade still allocates a graphicsLayer and runs
    // a compositor pass every frame of the transition. That's cheap on a
    // Samsung A16-class device but is exactly the kind of per-frame cost
    // that shows up as stutter on a 2GB/quad-core phone like the Redmi
    // A10. On LOW tier screens simply swap with no transition at all.
    val performanceTier = LocalPerformanceTier.current
    val isLowTier = performanceTier == PerformanceTier.LOW
    val isBalancedTier = performanceTier == PerformanceTier.BALANCED

    // ROOT FIX ("الشريط العائم خلفيته لسه بيضاء/سوداء"): a Scaffold(bottomBar
    // = ...) was the actual root cause, not a styling detail inside
    // FloatingBottomNav. Two things about Scaffold made a flat white/black
    // rectangle unavoidable no matter what color/transparency was tried on
    // the bar itself:
    //  1) Scaffold paints its own `containerColor` (default
    //     colorScheme.background) behind whatever is in the bottomBar slot.
    //  2) Scaffold shrinks NavHost's content to stop short of that slot
    //     (via the `padding` it hands down), so there was never any real
    //     page content behind the bar's transparent side margins either —
    //     just empty space over that flat Scaffold color.
    // Both of those were true regardless of FloatingBottomNav's own
    // background, so nothing changed there could ever fix it. This now
    // uses a plain Box instead of Scaffold: NavHost fills the *entire*
    // screen (see the pager tabs' own contentWindowInsets below — Dashboard/
    // Debts/Materials already reserve their own bottom safe-area space, so
    // no double-padding), and FloatingBottomNav is layered on top as a true
    // overlay via Modifier.align(Alignment.BottomCenter). The margins
    // around the pill are now genuinely transparent over the live page
    // (list rows, cards) scrolling underneath — never a separately-painted
    // rectangle.
    // BUG FIXED ("زر عميل جديد/مادة جديدة صار تحت الشريط"): a direct side
    // effect of the overlay fix above. Previously the outer Scaffold's
    // `padding` shrank NavHost so screens never even extended into the
    // pill's area — a screen's own FloatingActionButton (bottom-end of ITS
    // OWN Scaffold) naturally landed above the pill with no extra work.
    // Now that NavHost fills the true screen height, each tab's FAB anchors
    // to the *real* bottom edge same as the pill does — same strip, so the
    // pill (drawn after, on top) covers most of it, leaving only the
    // sliver that peeks out past the pill's own width.
    // FIX: measure the pill's actual rendered height right where it's
    // drawn (`onSizeChanged` below — real layout size, not a guessed
    // constant) and thread it down via [LocalFloatingBottomNavHeight] so
    // DebtsScreen/MaterialsScreen (the two tabs with a FAB) can pad their
    // FAB, and every tab's list, clear of it by that exact amount.
    val density = LocalDensity.current
    var floatingNavHeight by remember { mutableStateOf(0.dp) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only swipe-openable from the main pager tabs — same screens the
        // old pill only ever showed on (`showBottomBar`) — so a swipe on
        // Settings/PersonDetail/etc. doesn't fight that screen's own back
        // gesture.
        gesturesEnabled = showBottomBar,
        drawerContent = {
            AppDrawerContent(
                selectedPage = pagerState.currentPage,
                onSelectPage = { page ->
                    openPager(page)
                    drawerScope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    navController.safeNavigate(ROUTE_SETTINGS)
                    drawerScope.launch { drawerState.close() }
                },
                onOpenAdmin = {
                    drawerScope.launch { drawerState.close() }
                    // البصمة/الوجه أولاً إن كانت متاحة على الجهاز؛ وإلا (أو عند
                    // اختيار "كلمة المرور" أو فشل النظام) يظهر مربع كلمة المرور
                    // القديم بنفس قفل المحاولات.
                    val bioActivity = BiometricAuth.findActivity(adminContext)
                    if (bioActivity != null && BiometricAuth.isAvailable(adminContext) && !adminThrottle.isLocked()) {
                        BiometricAuth.prompt(
                            activity = bioActivity,
                            title = "دخول لوحة المطوّر",
                            subtitle = "أكّد هويتك للمتابعة",
                            negativeText = "استخدام كلمة المرور",
                            onSuccess = {
                                adminThrottle.registerSuccess()
                                navController.safeNavigate(ROUTE_ADMIN)
                            },
                            onFailure = { showAdminPinDialog = true }
                        )
                    } else {
                        showAdminPinDialog = true
                    }
                }
            )
        }
    ) {
    Box(
        Modifier
            .fillMaxSize()
    ) {
        // See the BUG FIXED note below (inside NavHost's transition params)
        // for why these are shaped the way they are — declared here, above
        // NavHost, since a function-call argument list can only contain
        // `name = value` arguments, not local `val` statements.
        // CLAUDE.AI-STYLE MOTION: shares [MotionSpecs.claudeEasing] — the
        // same no-overshoot curve every other transition in the app now
        // uses — instead of a separately-tuned iOS curve.
        val navMs = MotionSpecs.navMillis()
        val pushSlideSpec: FiniteAnimationSpec<IntOffset> = tween(navMs, easing = MotionSpecs.claudeEasing)
        val navFadeSpec: FiniteAnimationSpec<Float> = tween(navMs, easing = MotionSpecs.claudeEasing)
        // REPLACED THE OLD ALPHA-DIM ("الانميشن غير جميل / بدي ياه متكامل
        // وبلا تقطيع"): the covered screen used to dim via fadeOut/fadeIn
        // down to 72% alpha while it translated. Animating the *alpha* of a
        // whole subtree full of Material3 ElevatedCards means every card's
        // shadow gets re-composited at a shifting opacity on top of
        // whatever's underneath it, frame by frame — on a weaker GPU that
        // shows up as visible banding/seams right where the shadows are,
        // reading as "تقطيع" (choppy) rather than one solid, cohesive
        // sheet of UI sliding as a unit. Scale reads as pure depth instead
        // (the same "recede a step back" cue iOS/Claude modals already use
        // elsewhere in this app) with nothing to re-blend, so it stays a
        // single flat layer moving — smooth on any GPU, and the *same*
        // slide+scale language as everything else (see MotionSpecs'
        // popInSpring/pressSpring), which is what makes it read as one
        // integrated motion system instead of a one-off for this screen.
        val pushScaleSpec: FiniteAnimationSpec<Float> = tween(navMs, easing = MotionSpecs.claudeEasing)
        CompositionLocalProvider(
            LocalFloatingBottomNavHeight provides if (pillVisible) floatingNavHeight else 0.dp
        ) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_MAIN_PAGER,
            // Horizontal safe-area (cutouts/rounded corners) always
            // applies. Bottom safe-area only applies here when there's no
            // floating nav on screen (Settings, person detail, etc.) —
            // when the pager IS showing, Dashboard/Debts/Materials each
            // already reserve their own bottom inset internally (see their
            // own `contentWindowInsets`), and reserving it a second time
            // here is exactly what used to create the empty flat-colored
            // strip behind the bar in the first place.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .then(
                    if (!showBottomBar) {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    } else {
                        Modifier
                    }
                ),
            // BUG FIXED ("الرجوع مو منسق مع بقية الانميشن — كل الشاشات"):
            // the previous fix routed every back exit (swipe gesture AND
            // every screen's own tap-back-arrow) through a hand-rolled
            // `OneUiBackController`: it played the exit slide on the real
            // outgoing screen, but the screen being REVEALED underneath was
            // never the real previous screen — it was a plain, single-color
            // `OneUiBackBackdrop` panel standing in for it (see that file's
            // own doc for why: rendering the real previous screen live
            // underneath would've meant keeping two full destinations
            // composed at once). So every back exit looked like: smooth
            // slide of a blank panel into view, then — the instant
            // `popBackStack()` actually ran — an abrupt, unanimated SWAP of
            // that blank panel for the real screen (`popEnterTransition`/
            // `popExitTransition` were `None`, on purpose, to avoid
            // double-animating on top of the hack). That swap is exactly
            // the "مو منسق" (uncoordinated) cut the user kept seeing on
            // MaterialCatalogScreen (مادة جديدة), PersonDetailScreen
            // (تفاصيل الديون), SettingsScreen, and every other screen —
            // it's a structural property of the hack, not one screen's bug.
            //
            // FIX: delete the hand-rolled controller/backdrop entirely
            // (PredictiveBack.kt is no longer used anywhere and can be
            // removed from the project) and instead give `popEnterTransition`/
            // `popExitTransition` below the real mirror of the push, and let
            // Navigation Compose 2.10's own predictive-back support run it.
            // Since 2.8, NavHost keeps BOTH the outgoing and the real
            // incoming destination genuinely composed and live-tracks the
            // system swipe's progress against these exact transitions — no
            // synthetic backdrop needed — and it plays the very same
            // transitions (as a normal timed animation instead of a
            // gesture-driven one) whenever a screen's own back arrow calls
            // plain `navController.popBackStack()`. That's what makes tap
            // and swipe finally look identical: they're the same
            // AnimatedContent transition now, not two different
            // implementations.
            //  - push (enter/exit): incoming screen slides in the *entire*
            //    width from the right; the screen it's covering slides a
            //    third of its own width to the left and scales down to 94%
            //    (see the note on `pushScaleSpec` above for why this is a
            //    scale now, not an alpha dim).
            //  - pop (popEnter/popExit): the EXACT reverse — the revealed
            //    screen slides back in from a third off the left while
            //    scaling 94%→100%, and the screen being left slides the
            //    entire width out to the right. Same `pushSlideSpec`/
            //    `pushScaleSpec` (same 300ms, same `claudeEasing`), so the
            //    speed and easing match too, not just the direction.
            // PERF: LOW tier still keeps all four at zero cost (`.None`
            // below) — the fastest a screen change can be, unchanged.
            // ثلاثة مستويات (انظر MotionSpecs.navMillis):
            //  - LOW: تلاشٍ متقاطع قصير فقط، بلا انزلاق ولا scale.
            //  - BALANCED: انزلاق (transform فقط) بلا scale ولا تلاشٍ للشاشة المغطّاة
            //    — طبقة واحدة مسطّحة تتحرك، فلا تُعاد كتابة ظلال/ألوان كل إطار.
            //  - STANDARD: انزلاق + تصغير الشاشة المغطّاة إلى 94% (أغنى عمق بصري).
            enterTransition = {
                when {
                    isLowTier -> fadeIn(navFadeSpec)
                    else -> slideInHorizontally(pushSlideSpec) { fullWidth -> fullWidth }
                }
            },
            exitTransition = {
                when {
                    isLowTier -> fadeOut(navFadeSpec)
                    isBalancedTier -> slideOutHorizontally(pushSlideSpec) { fullWidth -> -fullWidth / 4 }
                    else -> slideOutHorizontally(pushSlideSpec) { fullWidth -> -fullWidth / 3 } +
                        scaleOut(pushScaleSpec, targetScale = 0.94f)
                }
            },
            popEnterTransition = {
                when {
                    isLowTier -> fadeIn(navFadeSpec)
                    isBalancedTier -> slideInHorizontally(pushSlideSpec) { fullWidth -> -fullWidth / 4 }
                    else -> slideInHorizontally(pushSlideSpec) { fullWidth -> -fullWidth / 3 } +
                        scaleIn(pushScaleSpec, initialScale = 0.94f)
                }
            },
            popExitTransition = {
                when {
                    isLowTier -> fadeOut(navFadeSpec)
                    else -> slideOutHorizontally(pushSlideSpec) { fullWidth -> fullWidth }
                }
            }
        ) {
            composable(ROUTE_MAIN_PAGER) {
                // One shared surface for Home, Debts, Materials, and Notes —
                // `userScrollEnabled = false` below means the user can never
                // drag between them directly; every switch goes through
                // `openPager()` (drawer tap / quick action / notification),
                // which still animates this exact pager with the same
                // tuned curve (`pagerTabAnimationSpec`). Each row's own
                // delete action stays a tap (see DeleteIconButton) rather
                // than a horizontal swipe either way.
                //
                // PERF: this used to wrap every page in a per-frame
                // graphicsLayer that scaled and faded it while dragging.
                // With list-heavy screens full of ElevatedCards (each one
                // already its own shadow-casting layer), animating a
                // scale/alpha transform across the whole subtree on every
                // scroll frame was expensive. Now that the pager only ever
                // moves via `animateScrollToPage()` (see `userScrollEnabled`
                // below) that per-frame drag transform has no reason to
                // exist at all — the programmatic tab-switch animation is
                // driven entirely by `pagerTabAnimationSpec` instead.
                HorizontalPager(
                    state = pagerState,
                    // PERF (تنقّل أنعم): compose الصفحة المجاورة (يمين/يسار)
                    // مسبقًا على الأجهزة العادية/القوية بدل انتظار أول سحبة
                    // إليها فعليًا - أول سحبة للصفحة التالية هيك ما عندها
                    // كلفة "compose لأول مرة" وبتبين أنعم فورًا، بنفس روح
                    // إزالة الـ graphicsLayer لكل صفحة فوق (توفير كلفة
                    // مكانها بمكان تاني). على أجهزة الأداء الضعيف نضل
                    // عالافتراضي (0) لتفادي أي عبء compose/ذاكرة إضافي مو
                    // ضروري - نفس منطق "دعم الوضعين" اللي يحدد isLowTierForPager
                    // نفسها فوق.
                    beyondViewportPageCount = if (isLowTierForPager) 0 else 1,
                    // REMOVED ("ميزة التنقل بين الشاشات الغيها"): no
                    // finger-drag between tabs anymore — see the class-level
                    // FIX/REMOVED comment above this composable() block.
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    // الأداء القوي فقط: عمق بصري أثناء تبديل التبويب — الصفحة المغادِرة/القادمة
                    // تصغر قليلاً وتخفت. كله داخل graphicsLayer (مرحلة الرسم)، وعند السكون
                    // offset = 0 فلا طبقة إضافية. المتوازن والمنخفض: انزلاق خام بلا أي طبقات.
                    val pageFx = if (performanceTier == PerformanceTier.STANDARD) {
                        Modifier.graphicsLayer {
                            val off = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                .let { if (it < 0f) -it else it }.coerceIn(0f, 1f)
                            val sc = 1f - 0.06f * off
                            scaleX = sc
                            scaleY = sc
                            alpha = 1f - 0.5f * off
                        }
                    } else Modifier
                    Box(pageFx.fillMaxSize()) {
                    when (page) {
                        PAGE_DASHBOARD -> DashboardScreen(
                            debtsViewModel = debtsViewModel,
                            materialsViewModel = materialsViewModel,
                            onNavigateToDebts = { openPager(PAGE_DEBTS) },
                            onNavigateToMaterials = { openPager(PAGE_MATERIALS) },
                            onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                        )
                        PAGE_DEBTS -> DebtsScreen(
                            viewModel = debtsViewModel,
                            onOpenPerson = { personId -> navController.safeNavigate("personDetail/$personId") },
                            addPersonRequested = addPersonRequested,
                            onAddPersonRequestHandled = { addPersonRequested = false },
                            onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                        )
                        PAGE_MATERIALS -> MaterialsScreen(
                            viewModel = materialsViewModel,
                            onAddNew = { navController.safeNavigate(ROUTE_MATERIAL_CATALOG) },
                            onPricesTabActiveChanged = { materialsPricesTabActive = it },
                            onPricesChangedCountChanged = { pricesChangedCount = it },
                            savePricesRequested = savePricesRequested,
                            onSavePricesRequestHandled = { savePricesRequested = false },
                            initialSearchQuery = pendingMaterialHighlight,
                            onInitialSearchConsumed = { pendingMaterialHighlight = null },
                            onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                        )
                        else -> NotesScreen(
                            viewModel = notesViewModel,
                            persons = debtsViewModel.uiState.collectAsStateWithLifecycle().value.persons,
                            materials = materialsViewModel.uiState.collectAsStateWithLifecycle().value.materials,
                            onOpenPerson = { personId -> openPager(PAGE_DEBTS); navController.safeNavigate("personDetail/$personId") },
                            onOpenMaterials = { materialName -> pendingMaterialHighlight = materialName; openPager(PAGE_MATERIALS) },
                            addNoteRequested = addNoteRequested,
                            onAddNoteRequestHandled = { addNoteRequested = false },
                            onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                        )
                    }
                    }
                }
            }
            composable(ROUTE_MATERIAL_CATALOG) {
                MaterialCatalogScreen(
                    viewModel = materialsViewModel,
                    onBack = { navController.safePopBackStack() }
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    onBack = { navController.safePopBackStack() },
                    onThemeChanged = onThemeChanged,
                    onPerformancePreferenceChanged = onPerformancePreferenceChanged,
                    onRecheckDevicePerformance = onRecheckDevicePerformance,
                    debtsViewModel = debtsViewModel,
                    materialsViewModel = materialsViewModel,
                    onOpenHelp = { navController.safeNavigate(ROUTE_HELP) },
                    onOpenPrivacyPolicy = { navController.safeNavigate(ROUTE_PRIVACY) }
                )
            }
            composable(ROUTE_ADMIN) {
                AdminPanelScreen(
                    onBack = { navController.safePopBackStack() },
                    debtsViewModel = debtsViewModel,
                    materialsViewModel = materialsViewModel,
                    notesViewModel = notesViewModel
                )
            }
            composable(ROUTE_HELP) {
                WebViewScreen(
                    url = "file:///android_asset/help.html",
                    title = "دليل الاستخدام",
                    onBack = { navController.safePopBackStack() }
                )
            }
            composable(ROUTE_PRIVACY) {
                WebViewScreen(
                    url = "file:///android_asset/privacy.html",
                    title = "سياسة الخصوصية",
                    onBack = { navController.safePopBackStack() }
                )
            }
            composable(
                ROUTE_PERSON_DETAIL,
                arguments = listOf(navArgument("personId") { type = NavType.StringType })
            ) { entry ->
                val personId = entry.arguments?.getString("personId")
                val personsState = debtsViewModel.uiState.collectAsStateWithLifecycle().value
                val person = personsState.persons.find { it.id == personId }
                // STABILITY FIX: if this customer disappears while their page
                // is open (deleted from another device, or an unknown id from
                // a stale notification) the screen used to render NOTHING - a
                // blank page. Once the first real data has loaded and the
                // customer still isn't there, leave the page instead.
                LaunchedEffect(person == null, personsState.isLoading) {
                    if (person == null && !personsState.isLoading) navController.safePopBackStack()
                }
                if (person != null) {
                    PersonDetailScreen(
                        person = person,
                        viewModel = debtsViewModel,
                        // "ترابط بين الديون والملاحظات": نفس نسخة NotesViewModel
                        // المشتركة اللي يستخدمها تبويب "ملاحظات هامة" (مو نسخة
                        // جديدة بـ viewModel() الافتراضي) - عشان الملاحظات
                        // المعروضة هون تضل نفس البيانات الحية، وإضافة/تعديل
                        // ملاحظة من هالشاشة ينعكس فورًا بتبويب الملاحظات وبالعكس.
                        notesViewModel = notesViewModel,
                        onBack = { navController.safePopBackStack() }
                    )
                }
            }
        }
        } // CompositionLocalProvider

        // Drawn AFTER (so visually on top of) NavHost above — a real
        // overlay, not a layout slot with its own painted background.
        if (pillVisible) {
            // BUG FIXED ("شكل الايقونة فوق الكلمة/التصميم متراكب"): the
            // hamburger used to be this exact floating glass circle,
            // pinned to Alignment.TopStart over the *entire* NavHost — in
            // this app's forced-RTL layout TopStart resolves to the
            // top-RIGHT corner, which is exactly where every tab's own
            // header already places its title text (DashboardHeader's
            // "إدارة المحل", MaterialsHeader's "المواد والأسعار", ...).
            // The floating circle and the header's own text were two
            // independent layers drawn in the same corner, so they
            // visually collided instead of one giving way to the other.
            // Claude's own top bar never floats a control over live
            // content like that — its hamburger is a real element laid
            // out *inside* the bar itself, taking up its own space so
            // nothing else can ever occupy that spot. Each screen now
            // draws its own leading hamburger button inline in its own
            // header/TopAppBar (see DashboardHeader, DebtsScreen's
            // TopAppBar `navigationIcon`, MaterialsHeader, NotesScreen's
            // TopAppBar `navigationIcon`) via the `onOpenDrawer` callback
            // threaded down from here — so this floating overlay button
            // is gone entirely; nothing replaces it at this layer.

            // REDESIGN ("زر ال+ لازم تشيلة لان في فوق زر"): الديون and
            // المواد both now have their own wide, on-screen "عميل جديد" /
            // "مادة جديدة" button right in the list (see DebtsScreen /
            // MaterialsScreen — wired to the same `addPersonRequested` /
            // ROUTE_MATERIAL_CATALOG actions this floating button used to
            // trigger), so this shared floating "+" would just be a second,
            // redundant control for the exact same action on those two
            // pages. It now only shows on الملاحظات, which has no on-screen
            // add button of its own yet.
            val quickAction = when (pagerState.currentPage) {
                PAGE_NOTES -> QuickAction(
                    icon = Icons.Default.Add,
                    contentDescription = "ملاحظة جديدة",
                    onClick = { addNoteRequested = true }
                )
                else -> null
            }
            // "من الجهة اليسرى حسب الصورة": rendered via `secondaryAction`,
            // the opposite end of the same pill row from `quickAction`
            // above (see FloatingBottomNav's own doc comment on that
            // param) — only while the المواد والأسعار page's الأسعار tab
            // is actually the one showing, same as `quickAction` itself is
            // page-scoped. Label mirrors the old full-width button's own
            // text ("حفظ N من الأسعار" once something's changed, "حفظ كل
            // الأسعار" otherwise); tapping it just raises
            // `savePricesRequested`, which MaterialsScreen watches and
            // clears once the save actually runs.
            val secondaryAction = if (pagerState.currentPage == PAGE_MATERIALS && materialsPricesTabActive) {
                QuickAction(
                    icon = Icons.Default.Save,
                    contentDescription = if (pricesChangedCount > 0) "حفظ $pricesChangedCount من الأسعار" else "حفظ كل الأسعار",
                    onClick = { savePricesRequested = true }
                )
            } else null
            FloatingQuickActions(
                quickAction = quickAction,
                secondaryAction = secondaryAction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Real measured layout height (margins included, since
                    // this size is taken at FloatingQuickActions' own root
                    // — see its Box in FloatingBottomNav.kt), converted
                    // from px to dp with the current screen density. This
                    // is what LocalFloatingBottomNavHeight above actually
                    // reflects.
                    .onSizeChanged { size ->
                        floatingNavHeight = with(density) { size.height.toDp() }
                    }
            )
        }
    }
    } // ModalNavigationDrawer

    // لوحة المسؤول السرية: PIN gate, triggered from the admin button in the
    // side drawer (see AppDrawerContent's onOpenAdmin above). Rendered as a
    // sibling of the drawer's Box, same reasoning as the notification
    // dialog just below — a dialog is its own layer, not part of that
    // layout, and this needs to be reachable regardless of which tab is
    // currently open underneath it.
    if (showAdminPinDialog) {
        AdminPinDialog(
            throttle = adminThrottle,
            onDismiss = { showAdminPinDialog = false },
            onVerify = { entered ->
                if (adminThrottle.isLocked()) {
                    AdminPasswordRepository.Outcome.Denied
                } else {
                    val outcome = AdminPasswordRepository.verify(entered)
                    when (outcome) {
                        is AdminPasswordRepository.Outcome.Granted -> {
                            adminThrottle.registerSuccess()
                            showAdminPinDialog = false
                            navController.safeNavigate(ROUTE_ADMIN)
                        }
                        is AdminPasswordRepository.Outcome.Denied -> adminThrottle.registerFailure()
                        // NotConfigured / Unavailable: ليست محاولة خاطئة — لا تُحتسب ضد المطوّر.
                        else -> Unit
                    }
                    outcome
                }
            }
        )
    }

    // The confirmation dialog a tapped notification opens the app to show
    // ("تم تسديد الدين", "عميل جديد", "قائمة المشتريات"). Rendered as a
    // sibling of the Box above (an AlertDialog is its own system window,
    // not part of that layout) so it appears on top regardless of which
    // tab openPager() just switched to.
    pendingNotificationAction?.let { action ->
        when (action) {
            is NotificationAction.DebtPaid -> GlassAlertDialog(
                onDismissRequest = onConsumeNotificationAction,
                title = { Text("✅ تم سداد دين") },
                text = { Text("${action.personName} وفى ${action.amount} ${action.currency}") },
                // BUG FIXED ("بدي الضغطة بشكل مربع كامل وليس دائري"): see
                // PersonEditDialog.kt's doc comment.
                confirmButton = { TextButton(shape = RectangleShape, onClick = onConsumeNotificationAction) { Text("موافق") } }
            )
            is NotificationAction.NewDebt -> GlassAlertDialog(
                onDismissRequest = onConsumeNotificationAction,
                title = { Text("💰 عميل جديد بالديون") },
                text = { Text("${action.personName} — ${action.amount} ${action.currency}") },
                confirmButton = { TextButton(shape = RectangleShape, onClick = onConsumeNotificationAction) { Text("موافق") } }
            )
            is NotificationAction.ShoppingList -> GlassAlertDialog(
                onDismissRequest = onConsumeNotificationAction,
                title = { Text("🛒 قائمة المشتريات") },
                text = {
                    Text(
                        if (action.materialNames.isEmpty()) "لا توجد مواد ناقصة حالياً"
                        else action.materialNames.joinToString("، ")
                    )
                },
                confirmButton = { TextButton(shape = RectangleShape, onClick = onConsumeNotificationAction) { Text("موافق") } }
            )
            // Tapping a note's reminder just needs to land on the ملاحظات
            // هامة tab (already handled by the LaunchedEffect above) - the
            // note itself is right there in the list, so a confirmation
            // dialog on top of it would just be a redundant extra tap.
            is NotificationAction.NoteReminder -> {
                LaunchedEffect(action) { onConsumeNotificationAction() }
            }
        }
    }
}

/**
 * STABILITY FIX: a fast double-tap on a back arrow (or back arrow + system
 * back together) used to call popBackStack() twice. The second call popped
 * the start destination (the main pager) off the stack and left the NavHost
 * empty - a blank screen with no way out but killing the app. This only pops
 * while there is still a screen below the current one.
 */
private fun androidx.navigation.NavController.safePopBackStack() {
    if (previousBackStackEntry != null) popBackStack()
}

// حاجز التنقل المزدوج (مأخوذ من تطبيق الملاحظات: SmoothActivity.isDuplicateNavigation): ضغطتان
// سريعتان على نفس الصف/الزر كانتا تدفعان الشاشة مرتين فتتراكم نسختان وينقطع الانتقال. الآن تُتجاهل
// الثانية إن جاءت خلال 550ms لنفس الوجهة أو أثناء تشغيل انتقال سابق (المدخل الحالي ليس RESUMED).
private var lastNavRoute = ""
private var lastNavAt = 0L
private fun androidx.navigation.NavController.safeNavigate(route: String) {
    val now = android.os.SystemClock.uptimeMillis()
    if (route == lastNavRoute && now - lastNavAt < 550L) return
    val state = currentBackStackEntry?.lifecycle?.currentState
    if (state != null && state != androidx.lifecycle.Lifecycle.State.RESUMED) return
    lastNavRoute = route
    lastNavAt = now
    navigate(route)
}

private fun navigateTopLevel(navController: androidx.navigation.NavController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

// لوحة المسؤول السرية: PIN entry dialog gating ROUTE_ADMIN. MOVED here
// from DashboardScreen ("انقل ايقونة المسؤول الى المنيو الى جانب
// الاعدادات") along with its trigger button, which now lives in the side
// drawer next to الإعدادات instead of on Home's own header — see
// showAdminPinDialog/adminThrottle above and AppDrawerContent's
// onOpenAdmin. Behavior is unchanged from before: same fixed password
// (now verified against Firebase, see AdminPasswordRepository) and the same shared PinAttemptThrottle lockout,
// just triggered from a different place now.
@Composable
private fun AdminPinDialog(
    throttle: PinAttemptThrottle,
    onDismiss: () -> Unit,
    onVerify: suspend (String) -> AdminPasswordRepository.Outcome
) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockRemaining by remember { mutableLongStateOf(throttle.lockRemainingSeconds()) }
    val isLocked = lockRemaining > 0
    val verifyScope = rememberCoroutineScope()

    LaunchedEffect(isLocked) {
        while (lockRemaining > 0) {
            delay(1000)
            lockRemaining = throttle.lockRemainingSeconds()
        }
    }

    fun submit() {
        if (checking || isLocked || pin.isEmpty()) return
        checking = true
        message = null
        verifyScope.launch {
            val outcome = onVerify(pin)
            checking = false
            lockRemaining = throttle.lockRemainingSeconds()
            message = when (outcome) {
                is AdminPasswordRepository.Outcome.Granted -> null
                is AdminPasswordRepository.Outcome.Denied -> "كلمة المرور غير صحيحة"
                is AdminPasswordRepository.Outcome.NotConfigured ->
                    "كلمة المرور غير معيّنة في Firebase (config ← admin ← password)"
                is AdminPasswordRepository.Outcome.Unavailable -> outcome.reason
            }
        }
    }

    GlassAlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text("دخول لوحة المطوّر") },
        text = {
            Column {
                AppTextField(
                    value = pin,
                    onValueChange = { pin = it.take(40); message = null },
                    label = "كلمة المرور",
                    singleLine = true,
                    enabled = !isLocked && !checking,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    isError = message != null
                )
                if (isLocked) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "محاولات كثيرة خاطئة — حاول بعد $lockRemaining ثانية",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        message ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !isLocked && !checking && pin.isNotEmpty(), shape = RectangleShape, onClick = { submit() }) {
                if (checking) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = androidx.compose.material3.LocalContentColor.current
                    )
                } else {
                    Text("دخول")
                }
            }
        },
        dismissButton = { TextButton(enabled = !checking, shape = RectangleShape, onClick = onDismiss) { Text("إلغاء") } }
    )
}
