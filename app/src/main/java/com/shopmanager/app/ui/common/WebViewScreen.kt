package com.shopmanager.app.ui.common

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * A small, self-contained in-app browser used for the "دليل الاستخدام"
 * (help) and "سياسة الخصوصية" (privacy) pages and any future in-app link,
 * so the person never has to leave the app to a separate Chrome window.
 *
 * Android-11-and-up notes (why this isn't just `WebView(context)`):
 *  - Force-dark: there is no single API that works the same from Android 11
 *    through today. `WebSettingsCompat` (androidx.webkit) is the only way to
 *    ask for dark WebView content that degrades gracefully across API
 *    levels instead of crashing or silently no-op'ing on some of them.
 *  - Mixed content / cleartext: Android is stricter about this release over
 *    release. Loading the bundled local asset (`file:///android_asset/...`)
 *    sidesteps it entirely — no network permission dance, works the same on
 *    every Android version, and never breaks if the user is offline.
 *
 * FEATURES ADDED (بحث + حجم الخط): a "بحث في الصفحة" (find-in-page) field
 * using WebView's own native find API (WebView.findAllAsync/findNext —
 * these work with JavaScript disabled, since they operate on the rendered
 * DOM directly, not through injected JS) and a small A-/A+ text-size
 * control for anyone who wants the help/privacy text bigger or smaller
 * than the page's own default. Both are genuinely useful specifically for
 * a text-heavy reference page like help.html, not generic chrome.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(url: String, title: String, onBack: () -> Unit) {
    var isLoading by remember { mutableStateOf(true) }
    // BUG FIXED (سبينر لا نهائي عند فشل التحميل): the WebViewClient below
    // used to only ever flip `isLoading` to false from `onPageFinished`,
    // which never fires if the load itself fails (e.g. a corrupted asset,
    // or — for a future non-local `url` — no connectivity). The person
    // would be stuck looking at a spinner over a blank screen forever with
    // no indication anything went wrong. `onReceivedError` now also stops
    // the spinner and shows a real "تعذّر تحميل الصفحة" state with a retry
    // button, but only for the *main* page request — a failed sub-resource
    // (e.g. one broken inline asset reference) no longer wrongly blanks
    // out an otherwise-fine page.
    var loadError by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    // THEME-CONSISTENCY FIX ("حسّن الثيم بشكل كامل"): this used to read the
    // raw OS dark-mode flag directly, which ignores an explicit Settings →
    // المظهر light/dark override. If someone forces the app to Dark while
    // their phone's system setting is Light (or the reverse), the WebView's
    // force-dark — and help.html/privacy.html's own `prefers-color-scheme`
    // styling, which also only sees the OS setting — would render in the
    // wrong shade compared to every other screen in the app.
    // `LocalIsDarkTheme` (see ShopManagerTheme) is the theme's actual
    // resolved state after that override, so this page now always matches
    // the rest of the app.
    val isDark = com.shopmanager.app.ui.theme.LocalIsDarkTheme.current
    val focusManager = LocalFocusManager.current

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActiveIndex by remember { mutableIntStateOf(0) }
    var searchTotalCount by remember { mutableIntStateOf(0) }
    var textZoom by remember { mutableStateOf(100) }
    val searchFocusRequester = remember { FocusRequester() }

    fun reload() {
        loadError = false
        isLoading = true
        webViewRef?.reload()
    }

    LaunchedEffect(showSearch) {
        if (showSearch) runCatching { searchFocusRequester.requestFocus() }
    }

    Scaffold(
        // Off-pager screen (no bottom nav bar of its own) — the outer app
        // Scaffold already reserves the real bottom/horizontal safe-area
        // space one level up in NavHost's padding, so this Scaffold's own
        // content insets are zeroed to avoid reserving that same space
        // twice. The TopAppBar below still handles the status bar inset
        // entirely on its own regardless of this setting.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        GlassIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            onClick = {
                                val wv = webViewRef
                                if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
                            },
                            // BUG FIXED (نفس مشكلة زر الرجوع لاصق بالعنوان):
                            // missing `end` padding meant nothing separated
                            // the button from the title next to it — added
                            // to match the fix on the other screens' back
                            // buttons (Settings/MaterialCatalog/PersonDetail).
                            modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                            size = 36.dp
                        )
                    },
                    actions = {
                        // FEATURE ADDED: A-/A+ text size, independent of the
                        // device's own system font scale — useful when the
                        // person wants this one page bigger/smaller without
                        // changing the whole phone's text size.
                        IconButton(onClick = {
                            textZoom = (textZoom - 15).coerceAtLeast(70)
                            webViewRef?.settings?.textZoom = textZoom
                        }) {
                            Icon(Icons.Default.TextDecrease, contentDescription = "تصغير الخط")
                        }
                        IconButton(onClick = {
                            textZoom = (textZoom + 15).coerceAtMost(200)
                            webViewRef?.settings?.textZoom = textZoom
                        }) {
                            Icon(Icons.Default.TextIncrease, contentDescription = "تكبير الخط")
                        }
                        IconButton(onClick = {
                            showSearch = !showSearch
                            if (!showSearch) {
                                searchQuery = ""
                                webViewRef?.clearMatches()
                            }
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "بحث في الصفحة")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = BrandOnGradient,
                        navigationIconContentColor = BrandOnGradient,
                        actionIconContentColor = BrandOnGradient
                    )
                    // UNIFIED ON CLAUDE'S DESIGN: removed the old boxed
                    // liquidGlassSurface panel this bar used to sit on — it
                    // now sits flush on the plain background like Home's own
                    // header and Claude's own "Settings" screen.
                )

                // FEATURE ADDED: find-in-page bar — appears under the top
                // bar only while active, native WebView find (no JS
                // needed), with a live "current/total" match counter and
                // next/previous, matching the pattern of a normal browser's
                // in-page search.
                if (showSearch) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { query ->
                                searchQuery = query
                                if (query.isEmpty()) {
                                    webViewRef?.clearMatches()
                                    searchTotalCount = 0
                                } else {
                                    webViewRef?.findAllAsync(query)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            placeholder = { Text("ابحث بالصفحة...") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                webViewRef?.findNext(true)
                                focusManager.clearFocus()
                            })
                        )
                        if (searchTotalCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${searchActiveIndex + 1}/$searchTotalCount",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        IconButton(onClick = { webViewRef?.findNext(false) }, enabled = searchTotalCount > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "السابق")
                        }
                        IconButton(onClick = { webViewRef?.findNext(true) }, enabled = searchTotalCount > 0) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "التالي")
                        }
                        IconButton(onClick = {
                            showSearch = false
                            searchQuery = ""
                            webViewRef?.clearMatches()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق البحث")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false // static help content only — keeps the surface area minimal
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.textZoom = textZoom

                        // Force-dark support, resolved via the compat shim so it
                        // behaves correctly from Android 11 up without per-version
                        // branching in this file.
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isDark)
                        }

                        setFindListener { activeMatchIndex, numberOfMatches, _ ->
                            searchActiveIndex = activeMatchIndex
                            searchTotalCount = numberOfMatches
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, startedUrl: String, favicon: android.graphics.Bitmap?) {
                                loadError = false
                            }

                            override fun onPageFinished(view: WebView, finishedUrl: String) {
                                isLoading = false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                // Only the main page failing is a "page won't
                                // show" situation — a broken sub-resource
                                // request (an inline image reference, etc.)
                                // shouldn't blank out an otherwise-working page.
                                if (request.isForMainFrame) {
                                    isLoading = false
                                    loadError = true
                                }
                            }

                            // Keep our own bundled pages inside the WebView, but
                            // hand off any real external link (http/https) to the
                            // system browser instead of rendering it in-app.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val target = request.url
                                return if (target.scheme == "http" || target.scheme == "https") {
                                    // BUG FIXED: an unguarded startActivity here
                                    // would crash the whole screen with an
                                    // ActivityNotFoundException on the rare
                                    // device with no browser at all (e.g. some
                                    // locked-down kiosk/emulator setups) instead
                                    // of just quietly failing to open the link.
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                        loadUrl(url)
                        webViewRef = this
                    }
                }
            )

            if (isLoading && !loadError) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    com.shopmanager.app.ui.common.AppSpinner(size = 36.dp)
                }
            }

            if (loadError) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("تعذّر تحميل الصفحة", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { reload() }) { Text("إعادة المحاولة") }
                    }
                }
            }
        }
    }
}

