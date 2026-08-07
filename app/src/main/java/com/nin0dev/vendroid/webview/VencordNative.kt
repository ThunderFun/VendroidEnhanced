package com.nin0dev.vendroid.webview

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.edit
import com.nin0dev.vendroid.MainActivity
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.FirewallConfig
import com.nin0dev.vendroid.utils.ShareHelper
import com.nin0dev.vendroid.utils.VDELog
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import okhttp3.Response

class VencordNative(private val activity: WeakReference<MainActivity>, wv: WebView) {
    private val wvRef: WeakReference<WebView> = WeakReference(wv)

    companion object {
        private const val CSS_CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        // Maximum length of a single String value a page script may persist via
        // setString; bounds the settings XML size from any single write.
        private const val MAX_STRING_VALUE_LENGTH = 64 * 1024 // 64 KB
        // Settings keys the rest of the app reads as Booleans (via
        // SharedPreferences.getBoolean). Writing a String to any of these (e.g.
        // via setString) would make getBoolean throw ClassCastException — a
        // crash loop at startup for vendroid_confirmExternalLinks and a silent
        // toggle defeat for the others. setString must never write to them.
        private val BOOLEAN_SETTING_KEYS = setOf(
            "vendroid_confirmExternalLinks",
            "vendroid_blockTypingIndicator",
            // Migrated by MainActivity.migrateSettings() and read by the plugin.
            // Guarded here so setString can't type-poison them into Strings.
            "checkVDEUpdates",
            "checkAnnouncements"
        )

        // Settings keys outside the Vencord-/vendroid_ prefixes that the plugin
        // may legitimately read/write via the bridge.
        private val EXTRA_ALLOWED_KEYS = setOf(
            "checkVDEUpdates",
            "checkAnnouncements"
        )
        private val ICON_NAMES = setOf("Main", "Jolly", "Discord", "Retro", "TS12")
        @Volatile
        private var currentIcon: String = "Main"
        private val iconLock = Any()
        private val gson = com.google.gson.Gson()

        // Capability token, injected only into the top-level Discord document
        // (see bridgeBootstrapJs). addJavascriptInterface exposes the bridge to
        // every frame, so each bridge method requires this token. The token is
        // captured in the bootstrap's closure scope, never exposed as a window
        // global, so subframes can't read it and native checks reject their
        // calls.
        //
        // NOTE: the token is generated once per PROCESS (never rotated on
        // navigation / MainActivity recreation), so it is process-lifetime,
        // not per-document. It excludes cross-origin contexts and (by not
        // being a window global) prevents subframes reading it; it is NOT a
        // boundary against same-origin code already running in the top Discord
        // document, which receives the token automatically via the wrapper.
        private val tokenLock = Any()
        @Volatile private var bridgeToken: String? = null
        private val tokenChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
        private val secureRandom = SecureRandom()

        private fun ensureToken(): String {
            bridgeToken?.let { return it }
            synchronized(tokenLock) {
                bridgeToken?.let { return it }
                val len = 32
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    sb.append(tokenChars[secureRandom.nextInt(tokenChars.size)])
                }
                bridgeToken = sb.toString()
                return bridgeToken!!
            }
        }

        /** True iff [token] matches the current session capability token. */
        private fun isBridgeAuthorized(token: String?): Boolean {
            val expected = bridgeToken ?: return false
            if (token == null) return false
            // Constant-time comparison to avoid timing side channels.
            val a = expected.toCharArray()
            val b = token.toCharArray()
            if (a.size != b.size) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }

        /**
         * JavaScript that runs in the TOP-LEVEL Discord document before any
         * bridge call. It wraps window.VencordMobileNative so every method call
         * is prepended with the per-session capability token. The token is held
         * ONLY in this closure, never published as a window global — so even a
         * same-origin subframe/window that can access this document's globals
         * cannot read it, nor pass the native check. Subframes hold the raw
         * injected object but no usable token.
         *
         * Idempotent: a re-entrant call (e.g. the bootstrap was embedded by
         * shouldInterceptRequest and also injected via onPageStarted) returns
         * early via the window.__vendroidBootstrapped marker. That marker is a
         * boolean, NOT the token, so it leaks nothing. Re-wrapping the bridge
         * would unshift the token twice and shift every argument position.
         */
        @Volatile
        private var cachedBootstrapJs: String? = null

        fun bridgeBootstrapJs(): String {
            cachedBootstrapJs?.let { return it }
            val t = ensureToken()
            // JSON-encode so the token is safely embedded in a JS string literal.
            val tokenLiteral = gson.toJson(t)
            return ("(function(){" +
                "'use strict';" +
                "if(window.__vendroidBootstrapped)return;" +
                "window.__vendroidBootstrapped=true;" +
                "var vendroidToken=$tokenLiteral;" +
                "var raw=window.VencordMobileNative;" +
                "if(raw){var wrap={};" +
                "for(var k in raw){if(typeof raw[k]==='function')(function(name){wrap[name]=function(){var a=Array.prototype.slice.call(arguments);a.unshift(vendroidToken);return raw[name].apply(raw,a);};})(k);}" +
                "window.VencordMobileNative=wrap;}" +
                "})();").also { cachedBootstrapJs = it }
        }

        private fun resolveCurrentIcon(activity: MainActivity?): String {
            val act = activity ?: return "Main"
            val pm = act.packageManager
            val pkg = act.applicationContext
            for (name in ICON_NAMES) {
                val state = pm.getComponentEnabledSetting(
                    ComponentName(pkg, "com.nin0dev.vendroid.${name}MainActivity")
                )
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return name
                }
                // Main is enabled by default in the manifest; if never
                // explicitly toggled, getComponentEnabledSetting returns DEFAULT.
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && name == "Main") {
                    return "Main"
                }
            }
            return "Main"
        }

        fun initCurrentIcon(activity: MainActivity?) {
            synchronized(iconLock) {
                if (currentIcon == "Main") {
                    currentIcon = resolveCurrentIcon(activity)
                }
            }
        }
    }

    init {
        initCurrentIcon(activity.get())
    }

    @Volatile
    var overlayActive = false
        private set

    @Volatile
    private var logsDialogActive = false

    @Volatile
    private var firewallDialogActive = false

    private var originalStatusBarColor: Int? = null
    private var originalNavBarColor: Int? = null

    // Eagerly initialize SharedPreferences in the constructor (which runs on
    // the main thread during WebView setup) instead of lazily on the JS bridge
    // thread. The first getSharedPreferences() reads+parses the XML file from
    // disk, taking 50-100ms on eMMC; doing it on the bridge thread would stall
    // all @JavascriptInterface methods.
    private val settingsPrefs: SharedPreferences? = activity.get()
        ?.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // Dedicated SharedPreferences for CSS cache entries. Isolating CSS from
    // the main "settings" prefs avoids rewriting the entire settings XML on
    // every CSS write and keeps the settings file small (faster cold-start
    // parse, no contention between CSS churn and settings).
    private val cssCachePrefs: SharedPreferences? = activity.get()
        ?.getSharedPreferences("css_cache", Context.MODE_PRIVATE)

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var isShutdown = false

    private var cssCacheEvictionCounter = 0

    private fun evictStaleCssCache() {
        val cssPrefs = cssCachePrefs ?: return
        val now = System.currentTimeMillis()
        val editor = cssPrefs.edit()
        var evicted = false
        for (key in cssPrefs.all.keys) {
            if (!key.startsWith("css_cache_") || key.endsWith("_ts")) continue
            val ts = cssPrefs.getLong("${key}_ts", 0)
            if (now - ts > CSS_CACHE_TTL_MS) {
                editor.remove(key)
                editor.remove("${key}_ts")
                evicted = true
            }
        }
        if (evicted) editor.apply()
    }

    // Per-key rate limiter — prevents rapid re-writes to the same key while
    // allowing independent keys to be written in parallel. Uses
    // System.nanoTime() (monotonic) instead of currentTimeMillis() (wall clock,
    // which can jump on clock adjustments and break the limiter). Bounded:
    // rateLimitWrite is called with attacker-controlled ids before
    // isKeyAllowed, so a page script could grow this unbounded; cap and evict
    // the oldest entries on overflow.
    private val lastWriteTime = ConcurrentHashMap<String, Long>()
    private val LAST_WRITE_CAP = 2048

    // Total number of distinct keys a page script may persist. The per-key
    // rate limiter only bounds the in-memory timing map (it evicts the oldest
    // entry on overflow); without this, a script could write an unbounded
    // number of distinct Vencord-* keys, growing the settings XML without
    // limit. This set never evicts, so once the cap is reached no new distinct
    // key is accepted.
    private val persistedDistinctKeys = ConcurrentHashMap.newKeySet<String>()
    private val MAX_DISTINCT_WRITE_KEYS = 256

    private fun canWriteNewDistinctKey(id: String): Boolean {
        if (persistedDistinctKeys.contains(id)) return true
        if (persistedDistinctKeys.size >= MAX_DISTINCT_WRITE_KEYS) return false
        persistedDistinctKeys.add(id)
        return true
    }

    private fun rateLimitWrite(id: String): Boolean = rateLimitWrite(id, 500_000_000L)

    private fun rateLimitWrite(id: String, minIntervalNanos: Long): Boolean {
        val now = System.nanoTime()
        // Use presence, not a 0L sentinel: System.nanoTime() is ~0 shortly
        // after boot, so `?: 0L` silently rejected the first write within the
        // interval.
        val last = lastWriteTime[id]
        if (last != null && now - last < minIntervalNanos) return false
        if (lastWriteTime.size >= LAST_WRITE_CAP && !lastWriteTime.containsKey(id)) {
            // Cap reached and this is a new key: evict an old entry so the map
            // stays bounded. Pick the oldest single entry (cheap, no full
            // scan on the hot path).
            var oldestId: String? = null
            var oldestTs = Long.MAX_VALUE
            for ((k, v) in lastWriteTime) {
                if (v < oldestTs) { oldestTs = v; oldestId = k }
            }
            if (oldestId != null) lastWriteTime.remove(oldestId)
        }
        lastWriteTime[id] = now
        return true
    }

    private fun isKeyAllowed(id: String, forWrite: Boolean): Boolean {
        if (id == "vencordLocation") return false
        if (id == "clientMod") return true
        if (id in EXTRA_ALLOWED_KEYS) return true
        if (id.startsWith("Vencord-") || id.startsWith("vendroid_") || id.startsWith("Vencord_") || id.startsWith("css_cache_")) return true
        val op = if (forWrite) "write" else "read"
        VDELog.w("VN", "Blocked $op for disallowed key: $id")
        return false
    }

    /**
     * Shared guarded path for the JS bridge get/set prefs methods. Centralizes
     * the domain gate, per-key write rate limit, allowed-key allowlist check,
     * ClassCastException corruption recovery, and the outer defensive
     * Throwable catch across getString/getBool/setString/setBool and getCssCache.
     */
    private fun <T> guardedPrefs(
        op: String,
        id: String?,
        default: T,
        forWrite: Boolean,
        strictDomain: Boolean,
        block: (SharedPreferences) -> T
    ): T {
        val safeId = id ?: return default
        return try {
            val onDomain = if (strictDomain) isOnDiscordDomainStrict() else isOnDiscordDomain()
            if (!onDomain) return default
            if (forWrite && !rateLimitWrite(safeId)) return default
            if (!isKeyAllowed(safeId, forWrite)) return default
            // Bound the number of distinct persisted keys a script can
            // introduce (the per-key rate limiter only bounds the timing map).
            // css_cache_* keys are write-rejected in the block, so they are
            // never counted.
            if (forWrite && !safeId.startsWith("css_cache_") && !canWriteNewDistinctKey(safeId)) {
                VDELog.w("VN", "Rejected write to new distinct key past cap: $safeId")
                return default
            }
            val prefs: SharedPreferences = if (safeId.startsWith("css_cache_")) {
                cssCachePrefs ?: return default
            } else {
                settingsPrefs ?: return default
            }
            try {
                block(prefs)
            } catch (e: ClassCastException) {
                // Only purge a corrupted key on a WRITE path. On a READ, the
                // caller may be probing a key type it doesn't know (e.g. a
                // page script calling getBool on an Int-typed Vencord setting);
                // a destructive remove there would let page JS permanently
                // delete settings it couldn't otherwise touch.
                if (forWrite) {
                    VDELog.e("VN", "$op($safeId) ClassCastException — removing corrupted key", e)
                    prefs.edit().remove(safeId).apply()
                } else {
                    VDELog.w("VN", "$op($safeId) ClassCastException on read — returning default")
                }
                default
            }
        } catch (t: Throwable) {
            VDELog.e("VN", "$op($safeId) failed", t)
            default
        }
    }

    private fun isOnDiscordDomain(): Boolean {
        return Constants.isDiscordAppOrigin(activity.get()?.currentHostForBridge ?: return false)
    }

    /**
     * Defense-in-depth domain check for sensitive bridge methods. Re-reads the
     * WebView's current URL on the UI thread in addition to the cached
     * [currentHostForBridge], closing the TOCTOU window between a navigation
     * and the cached host being refreshed. Returns false if the live URL is
     * not a Discord domain.
     *
     * The UI-thread read is bounded to 50 ms so a busy UI thread cannot stall
     * the shared JS bridge thread. If the live read times out, the method
     * fails CLOSED (returns false) rather than trusting the cached host, so a
     * slow UI thread cannot widen the TOCTOU window for sensitive methods.
     */
    private fun isOnDiscordDomainStrict(): Boolean {
        if (!isOnDiscordDomain()) return false
        val wvActivity = activity.get() ?: return false
        // Fast path: with no navigation in flight, the cached host was written
        // after the current document committed, so it cannot be stale across a
        // navigation — skip the UI-thread round trip. This makes steady-state
        // bridge writes (setString/setBool) latch-free instead of blocking the
        // shared JS bridge thread on a possibly-busy UI thread for up to 50ms.
        if (!wvActivity.navigationInProgress) return true
        val wv = wvRef.get() ?: return false
        // getUrl() must run on the UI thread; bridge methods run on a Chromium
        // thread, so bound the read to avoid stalling the shared bridge thread.
        var liveHost: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        wvActivity.runOnUiThread {
            try { liveHost = wv.url?.let { Uri.parse(it).host } } catch (_: Exception) {}
            latch.countDown()
        }
        try {
            // Fail closed on timeout rather than trusting a possibly-stale
            // cached host across a navigation.
            if (!latch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return false
            }
        } catch (_: InterruptedException) {
            return false
        }
        return liveHost != null && Constants.isDiscordAppOrigin(liveHost!!)
    }

    fun shutdown() {
        isShutdown = true
        executor.shutdown()
    }

    /**
     * Bridges run tasks on the executor; they can be invoked by the JS bridge
     * during teardown (between wv.destroy() and executor.shutdown()), so we
     * drop the task instead of throwing RejectedExecutionException on the
     * Chromium bridge thread.
     */
    private fun safeExecute(block: () -> Unit) {
        if (isShutdown) return
        try {
            executor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shut down between the check and the submit; nothing useful to do.
        }
    }

    @JavascriptInterface
    fun setOverlayActive(token: String?, active: Boolean) {
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomain()) return
        overlayActive = active
        val act = activity.get() ?: return
        act.runOnUiThread {
            // Don't fight the fullscreen video path over status/nav bar colors.
            if (act.isVideoFullscreen()) return@runOnUiThread
            @Suppress("DEPRECATION")
            if (active) {
                if (originalStatusBarColor == null) {
                    originalStatusBarColor = act.window.statusBarColor
                }
                if (originalNavBarColor == null) {
                    originalNavBarColor = act.window.navigationBarColor
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    act.window.isStatusBarContrastEnforced = false
                    act.window.isNavigationBarContrastEnforced = false
                }
                act.window.statusBarColor = Color.BLACK
                act.window.navigationBarColor = Color.BLACK
            } else {
                act.window.statusBarColor = originalStatusBarColor ?: act.window.statusBarColor
                act.window.navigationBarColor = originalNavBarColor ?: act.window.navigationBarColor
            }
        }
    }

    @JavascriptInterface
    fun goBack(token: String?) {
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomain()) return
        activity.get()?.runOnUiThread {
            val wv = wvRef.get() ?: return@runOnUiThread
            if (wv.canGoBack()) wv.goBack() else
                activity.get()?.finish()
        }
    }

    // Native-gated actions for the app-owned UI buttons rendered in
    // vencord_mobile.js ("View logs" / "Open firewall editor"). These two
    // buttons live in code this project ships, so the app routes them through a
    // single request channel. The other privileged methods (openQuickCss,
    // updateVencord, updateVendroid) are called directly by the required
    // first-party vendroidEnhancements plugin and must remain JS-callable —
    // they are not collapsed into this channel.
    private val ALLOWED_NATIVE_ACTIONS = setOf("openLogs", "openFirewallEditor")

    @JavascriptInterface
    fun requestNative(token: String?, action: String?) {
        // Token is required as defense-in-depth, but the real boundary is that
        // page JS cannot reach these two app-owned actions except through this
        // single gated channel.
        if (!isBridgeAuthorized(token)) return
        val safeAction = action ?: return
        if (safeAction !in ALLOWED_NATIVE_ACTIONS) return
        when (safeAction) {
            "openLogs" -> openLogs()
            "openFirewallEditor" -> openFirewallEditor()
        }
    }

    @JavascriptInterface
    fun updateVencord(token: String?) {
        // Strict check before any work — this overwrites vencord.js via a
        // network download, so non-Discord whitelisted pages must not invoke it.
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomainStrict()) return
        if (!rateLimitWrite("updateVencord", 5 * 60 * 1_000_000_000L)) return
        // Resolve and validate the bundle location before scheduling any
        // network work, so a misconfigured vencordLocation fails fast.
        val sPrefs = settingsPrefs ?: return
        val defaultUrl = if (
            sPrefs.getString("clientMod", "vencord") == "equicord"
        ) Constants.EQUICORD_BUNDLE_URL else Constants.JS_BUNDLE_URL
        val vencordLocation = sPrefs.getString("vencordLocation", defaultUrl) ?: defaultUrl
        // Enforce HTTPS here (as fetchVencord does) so a clear-text bundle
        // download cannot be MITM-ed regardless of HttpClient.fetch's check.
        if (!vencordLocation.startsWith("https://")) {
            VDELog.e("VN", "Vencord location must use HTTPS: ${UrlNormalizer.redactForLog(vencordLocation)}")
            return
        }
        val vencordHost = Uri.parse(vencordLocation).host
        if (vencordHost == null || !Constants.isAllowedVencordHost(vencordHost)) {
            VDELog.e("VN", "Vencord location host '$vencordHost' is not in allowed list")
            return
        }
        safeExecute {
            var resp: Response? = null
            var vendroidTmpFile: File? = null
            try {
                val act = activity.get() ?: return@safeExecute
                val vendroidFile = File(act.filesDir, "vencord.js")
                // Unique temp name: the startup path
                // (HttpClient.downloadAndStore) writes the same bundle on a
                // different executor, and a shared "vencord.js.tmp" let one
                // writer's rename install the other's truncated file.
                vendroidTmpFile = File(act.filesDir, "vencord.js.${System.nanoTime()}.tmp")
                resp = HttpClient.fetch(vencordLocation)
                val content = HttpClient.readAsText(resp.body.byteStream())
                val patched = HttpClient.applyPatches(content)
                vendroidTmpFile.writeText(patched)
                if (!vendroidTmpFile.renameTo(vendroidFile)) {
                    vendroidTmpFile.delete()
                    throw IOException("Failed to rename ${vendroidTmpFile.name} to ${vendroidFile.name}")
                }
                // Sync ETag/patched-flag with the startup path so the next
                // launch's conditional GET returns 304. VencordRuntime is left
                // untouched; the user is prompted to restart.
                val responseEtag = resp.header("ETag")
                val editor = sPrefs.edit()
                if (responseEtag != null) {
                    editor.putString("vencordEtag", responseEtag)
                }
                editor.putInt("lastMajorUpdateThatUserHasUpdatedVencord", com.nin0dev.vendroid.BuildConfig.VERSION_CODE)
                // Persist the patched flag so the next cold start skips the
                // redundant ~1MB regex scan (matches downloadAndStore).
                editor.putBoolean(HttpClient.PREF_BUNDLE_PATCHED, true)
                editor.apply()
                HttpClient.vencordBundlePatched = true
                act.runOnUiThread {
                    act.showDiscordToast("Updated Vencord, restart to apply changes!", "SUCCESS")
                }
            } catch (ex: Exception) {
                activity.get()?.let { VDELog.e("VN", "Failed to update Vencord", ex) }
            } finally {
                vendroidTmpFile?.delete()
                // Close to return the pooled connection (not disconnect()).
                resp?.close()
            }
        }
    }

    @JavascriptInterface
    fun updateVendroid(token: String?) {
        // Mirrors updateVencord: strict domain gate + rate limit before any
        // network work. The update check is informational only — this app has no
        // self-update installer, so it surfaces a toast telling the user to grab
        // the new build from the project page.
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomainStrict()) return
        if (!rateLimitWrite("updateVendroid", 5 * 60 * 1_000_000_000L)) return
        safeExecute {
            var resp: Response? = null
            try {
                // Placeholder endpoint — replace with the real update manifest.
                resp = HttpClient.fetch("https://vde-builds.nin0.dev/vendroid/version.json")
                val body = HttpClient.readAsText(resp.body.byteStream())
                val latest = org.json.JSONObject(body).optInt("versionCode", 0)
                val act = activity.get() ?: return@safeExecute
                act.runOnUiThread {
                    if (latest > com.nin0dev.vendroid.BuildConfig.VERSION_CODE) {
                        act.showDiscordToast("VendroidEnhanced update available", "INFO")
                    } else {
                        act.showDiscordToast("VendroidEnhanced is up to date", "SUCCESS")
                    }
                }
            } catch (ex: Exception) {
                VDELog.e("VN", "updateVendroid failed", ex)
                val act = activity.get()
                act?.runOnUiThread {
                    act.showDiscordToast("Update check failed", "ERROR")
                }
            } finally {
                // Close to return the pooled connection (not disconnect()).
                resp?.close()
            }
        }
    }

    @JavascriptInterface
    fun getString(token: String?, id: String?, defaultValue: String?): String {
        if (!isBridgeAuthorized(token)) return defaultValue ?: ""
        val safeId = id ?: return defaultValue ?: ""
        val safeDefault = defaultValue ?: ""
        // Single access route for CSS cache values — delegate to getCssCache
        // rather than reading the dedicated cache prefs a second way here.
        if (safeId.startsWith("css_cache_")) return getCssCache(token, safeId) ?: safeDefault
        return guardedPrefs("getString", safeId, safeDefault, false, false) { prefs ->
            prefs.getString(safeId, safeDefault) ?: safeDefault
        }
    }

    @JavascriptInterface
    fun getBool(token: String?, id: String?, defaultValue: Boolean): Boolean {
        if (!isBridgeAuthorized(token)) return defaultValue
        val safeId = id ?: return defaultValue
        return guardedPrefs("getBool", safeId, defaultValue, false, false) { prefs ->
            prefs.getBoolean(safeId, defaultValue)
        }
    }

    @JavascriptInterface
    fun setString(token: String?, id: String?, value: String?) {
        if (!isBridgeAuthorized(token)) return
        val safeId = id ?: return
        val safeValue = value ?: return
        // Type-safety: never write a String to a key the app reads as a
        // Boolean. Writing a String to
        // vendroid_confirmExternalLinks/vendroid_blockTypingIndicator would
        // make the startup getBoolean() throw ClassCastException — a crash
        // loop for the former and a silent toggle defeat for the latter — and
        // would bypass the dedicated setBool guard on the external-link
        // confirm.
        if (safeId in BOOLEAN_SETTING_KEYS) {
            VDELog.w("VN", "Rejected setString on Boolean-key: $safeId")
            return
        }
        // Bound the persisted value length so a script cannot grow the settings
        // XML from a single write (distinct keys are separately capped).
        if (safeValue.length > MAX_STRING_VALUE_LENGTH) {
            VDELog.w("VN", "Rejected setString with oversized value (${safeValue.length} chars): $safeId")
            return
        }
        guardedPrefs("setString", safeId, Unit, true, true) { prefs ->
            // The CSS cache is READ-ONLY from page JS. It is populated only by
            // the native prefetch in VendroidApp from the two
            // operator-controlled CSS URLs. Letting JS write css_cache_* keys
            // would let any Discord-origin script poison the cache with
            // attacker CSS re-injected on every page load, or fill ~128 MB of
            // storage via the predictable key scheme. Reject all writes.
            if (safeId.startsWith("css_cache_")) {
                VDELog.w("VN", "Rejected JS write to read-only CSS cache key: $safeId")
                return@guardedPrefs
            }
            if (safeId == "clientMod") {
                // Only the two known mods are valid; reject anything else.
                if (safeValue != "vencord" && safeValue != "equicord") {
                    VDELog.w("VN", "Rejected invalid clientMod value: $safeValue")
                    return@guardedPrefs
                }
                // Invalidate the stale bundle so the next launch downloads
                // the new mod cleanly, rather than injecting both the old
                // (from preload) and new (from fetchVencord) runtimes.
                prefs.edit {
                    putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0)
                    remove("vencordEtag")
                    remove(HttpClient.PREF_BUNDLE_PATCHED)
                    activity.get()?.filesDir?.let { File(it, "vencord.js").delete() }
                    HttpClient.vencordBundlePatched = false
                    HttpClient.setVencordRuntime(null)
                }
            }
            prefs.edit {
                putString(safeId, safeValue)
            }
            Unit
        }
    }

    @JavascriptInterface
    fun setBool(token: String?, id: String?, value: Boolean) {
        if (!isBridgeAuthorized(token)) return
        val safeId = id ?: return
        guardedPrefs("setBool", safeId, Unit, true, true) { prefs ->
            // The CSS cache is READ-ONLY from page JS (populated only by the
            // native prefetch in VendroidApp). Mirror the setString guard: a
            // Boolean write to a css_cache_* key would type-poison that key so
            // the prefetch's getString() throws ClassCastException, aborting
            // the prefetch loop on every launch.
            if (safeId.startsWith("css_cache_")) {
                VDELog.w("VN", "Rejected JS write to read-only CSS cache key: $safeId")
                return@guardedPrefs
            }
            // The external-link confirmation is the app's last line of defense
            // against phishing (Safe Browsing is disabled) and must not be
            // disabled from page JS, even legitimately.
            if (safeId == "vendroid_confirmExternalLinks") {
                VDELog.w("VN", "Rejected JS attempt to set vendroid_confirmExternalLinks=$value (forced on for security)")
                prefs.edit { putBoolean(safeId, true) }
                com.nin0dev.vendroid.webview.LinkHandler.updateConfirmExternalLinks(true)
                return@guardedPrefs
            }
            prefs.edit {
                putBoolean(safeId, value)
            }
            // Live-update the typing indicator filter so the toggle takes
            // effect without an app restart.
            if (safeId == "vendroid_blockTypingIndicator") {
                com.nin0dev.vendroid.webview.VWebviewClient.updateTypingBlock(value)
            }
            Unit
        }
    }

    @JavascriptInterface
    fun changeAppIcon(token: String?, id: String?) {
        if (!isBridgeAuthorized(token)) return
        val rawId = id ?: run {
            val a = activity.get()
            a?.runOnUiThread { Toast.makeText(a, "Icon change: null id", Toast.LENGTH_SHORT).show() }
            return
        }
        if (!isBridgeAuthorized(token)) return
        val safeId = ICON_NAMES.find { it.equals(rawId, ignoreCase = true) }
        if (safeId == null) {
            val a = activity.get()
            a?.runOnUiThread { Toast.makeText(a, "Icon change: unknown id '$rawId'", Toast.LENGTH_SHORT).show() }
            return
        }
        try {
            if (!isOnDiscordDomainStrict()) {
                val a = activity.get()
                a?.runOnUiThread { Toast.makeText(a, "Icon change: not on Discord domain", Toast.LENGTH_SHORT).show() }
                return
            }
            synchronized(iconLock) {
                if (safeId == currentIcon) {
                    val a = activity.get()
                    a?.runOnUiThread { Toast.makeText(a, "Icon '$safeId' is already active", Toast.LENGTH_SHORT).show() }
                    return
                }
                val act = activity.get() ?: return
                val oldIcon = currentIcon
                currentIcon = safeId
                val pm = act.packageManager
                val pkg = act.applicationContext
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "com.nin0dev.vendroid.${safeId}MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, "com.nin0dev.vendroid.${oldIcon}MainActivity"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                act.runOnUiThread {
                    Toast.makeText(act, "Icon changed to $safeId. Restart launcher if it doesn't update.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            VDELog.e("VN", "changeAppIcon failed for id=$safeId", t)
            val a = activity.get()
            a?.runOnUiThread { Toast.makeText(a, "Icon change failed: ${t.message}", Toast.LENGTH_LONG).show() }
        }
    }

    @JavascriptInterface
    fun openQuickCss(token: String?, quickCss: String?) {
        // Strict check — this opens a WebView with a JS interface, so a
        // non-Discord whitelisted page must not invoke it. Called directly by
        // the required vendroidEnhancements plugin, which passes the current
        // QuickCSS from IndexedDB.
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomainStrict()) return
        openQuickCssInternal(quickCss)
    }

    private fun openQuickCssInternal(quickCss: String?) {
        val act = activity.get() ?: return
        val safeQuickCss = quickCss ?: ""
        openAssetEditor(
            act = act,
            assetUrl = "file:///android_asset/quickcss_editor.html",
            createBridge = { wv, dialog -> QuickCssBridge(act, wv, dialog) },
            onShown = {},
            onDialogDismiss = {},
            onPageFinished = { view ->
                if (safeQuickCss.isNotEmpty()) {
                    view.evaluateJavascript(
                        "window.qcssSet?.(${gson.toJson(safeQuickCss)})", null
                    )
                } else {
                    // Fallback when the bundle didn't supply the CSS. Vencord
                    // stores QuickCSS in IndexedDB, so it must be read via the
                    // loaded bundle; if Vencord isn't ready the editor opens
                    // empty. In practice the bundle always passes the CSS, so
                    // this branch is rarely hit.
                    val mainWv = wvRef.get() ?: return@openAssetEditor
                    val currentAct = activity.get()
                    if (currentAct == null || currentAct.isFinishing || currentAct.isDestroyed) return@openAssetEditor
                    mainWv.evaluateJavascript("VencordNative.quickCss.get()") { result ->
                        // This callback runs asynchronously — the editor may
                        // have been dismissed (destroying its WebView) or the
                        // activity finished by the time it fires. Guard before
                        // touching the editor WebView.
                        if (currentAct.isFinishing || currentAct.isDestroyed) return@evaluateJavascript
                        // result is a JSON-encoded string; pass it to JS via
                        // JSON.parse. Manual unescaping breaks on literal backslashes.
                        if (result != null && result != "null") {
                            try {
                                view.evaluateJavascript(
                                    "window.qcssSet?.(JSON.parse($result))", null
                                )
                            } catch (_: IllegalStateException) {
                                // Editor WebView was destroyed before this callback.
                            }
                        }
                    }
                }
            }
        )
    }

    /** Interface implemented by the per-editor bridges so the shared
     *  [openAssetEditor] helper can mark the page origin as committed. */
    private interface EditorOrigin {
        var originCommitted: Boolean
    }

    /**
     * Shared wiring for the QuickCSS / log-viewer / firewall editor dialogs:
     * the runOnUiThread + isFinishing/isDestroyed guard, the WebView,
     * SecureWebViewDialog configure/create (with [onDialogDismiss]), the shared
     * webViewClient that sets the bridge's originCommitted flag then calls
     * [onPageFinished], show(), loadUrl([assetUrl]), and a single inner/outer
     * try-catch with a VDELog failure.
     */    private fun openAssetEditor(
        act: MainActivity,
        assetUrl: String,
        createBridge: (WebView, android.app.Dialog) -> EditorOrigin,
        onShown: () -> Unit,
        onDialogDismiss: () -> Unit,
        onPageFinished: (WebView) -> Unit
    ) {
        act.runOnUiThread {
            try {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                val wv = WebView(act)
                SecureWebViewDialog.configure(wv)

                val dialog = SecureWebViewDialog.create(act, wv) { onDialogDismiss() }

                val bridge = createBridge(wv, dialog)
                wv.addJavascriptInterface(bridge, "VencordMobileNative")

                wv.webViewClient = object : android.webkit.WebViewClient() {
                    // Fail closed: only ever load the expected bundled asset.
                    // Any navigation away from it (a link tap, meta refresh, or
                    // a future asset change) is blocked so the
                    // un-token-gated editor bridge is never exposed to a
                    // remote/attacker document.
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest
                    ): Boolean {
                        return request.url.toString() != assetUrl
                    }

                    // Block every subresource except the bundled asset itself,
                    // so a static editor page cannot pull remote
                    // scripts/CSS/frames into the privileged (un-token-gated)
                    // editor WebView.
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        return if (request.url.toString() == assetUrl) null
                        else android.webkit.WebResourceResponse(
                            "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // Bind the origin flag to the exact asset URL, and reset it if
                        // we ever finish on something else, so the bridge stays dead
                        // until the correct local document is committed.
                        bridge.originCommitted = url == assetUrl
                        if (bridge.originCommitted) {
                            view?.let { onPageFinished(it) }
                        }
                    }
                }

                onShown()
                dialog.show()
                wv.loadUrl(assetUrl)
            } catch (e: Throwable) {
                onDialogDismiss()
                VDELog.e("VN", "openAssetEditor($assetUrl) failed", e)
            }
        }
    }

    private class QuickCssBridge(
        private val activity: MainActivity,
        private val editorWebView: WebView,
        private val dialog: android.app.Dialog
    ) : EditorOrigin {
        // Set from onPageFinished (UI thread). WebView.getUrl() must be called
        // on the UI thread, but @JavascriptInterface methods run on a Chromium
        // internal thread, so checking getUrl() directly is unreliable.
        @Volatile override var originCommitted = false

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun quickCssSet(css: String?) {
            if (!isExpectedOrigin()) return
            val safe = css ?: ""
            activity.runOnUiThread {
                val mainWv = activity.findViewById<WebView>(R.id.webview)
                mainWv?.evaluateJavascript(
                    "VencordNative.quickCss.set(${gson.toJson(safe)})", null
                )
                Toast.makeText(activity, "Saved QuickCSS", Toast.LENGTH_SHORT).show()
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        @android.webkit.JavascriptInterface
        fun quickCssClose() {
            if (!isExpectedOrigin()) return
            activity.runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    @JavascriptInterface
    fun getCssCache(token: String?, cacheKey: String?): String? {
        if (!isBridgeAuthorized(token)) return null
        val safeKey = cacheKey ?: return null
        if (!safeKey.startsWith("css_cache_")) return null
        if (++cssCacheEvictionCounter % 50 == 0) {
            safeExecute { evictStaleCssCache() }
        }
        return guardedPrefs("getCssCache", safeKey, null, false, false) { prefs ->
            prefs.getString(safeKey, null)
        }
    }

    @JavascriptInterface
    fun dismissLoadingScreen(token: String?) {
        if (!isBridgeAuthorized(token)) return
        if (!isOnDiscordDomain()) return
        val act = activity.get() ?: return
        act.runOnUiThread {
            act.loadingScreen.dismiss()
        }
    }

    @JavascriptInterface
    fun isDebugBuild(token: String?): Boolean {
        if (!isBridgeAuthorized(token)) return false
        if (!isOnDiscordDomain()) return false
        return com.nin0dev.vendroid.BuildConfig.DEBUG
    }

    private fun openLogs() {
        // Gate on Discord domain. A failed Discord load still leaves
        // currentHostForBridge on a Discord host, so troubleshooting
        // remains possible; non-Discord whitelisted pages cannot open
        // the viewer or read app diagnostics.
        if (!isOnDiscordDomain()) return
        val act = activity.get() ?: return
        if (logsDialogActive) return
        openAssetEditor(
            act = act,
            assetUrl = "file:///android_asset/log_viewer.html",
            createBridge = { wv, dialog -> LogViewerBridge(act, wv, dialog) },
            onShown = { logsDialogActive = true },
            onDialogDismiss = { logsDialogActive = false },
            onPageFinished = { view ->
                val logs = VDELog.getRecentLogs(500)
                view.evaluateJavascript(
                    "window.vdeSetLogs?.(${gson.toJson(logs)})", null
                )
            }
        )
    }

    private class LogViewerBridge(
        private val activity: MainActivity,
        private val viewerWebView: WebView,
        private val dialog: android.app.Dialog
    ) : EditorOrigin {
        @Volatile override var originCommitted = false

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun close() {
            if (!isExpectedOrigin()) return
            if (dialog.isShowing) dialog.dismiss()
        }

        @android.webkit.JavascriptInterface
        fun clearLogs() {
            if (!isExpectedOrigin()) return
            VDELog.clearLogs()
        }

        @android.webkit.JavascriptInterface
        fun refreshLogs(): String {
            if (!isExpectedOrigin()) return ""
            return VDELog.getRecentLogs(500)
        }

        @android.webkit.JavascriptInterface
        fun shareLogs() {
            if (!isExpectedOrigin()) return
            val text = VDELog.getLogFileContents()
            activity.runOnUiThread {
                ShareHelper.shareLogs(activity, text)
            }
        }
    }

    private fun openFirewallEditor() {
        // Strict check — this mutates the firewall config, so a non-Discord
        // page must not invoke it even if currentHostForBridge is stale.
        if (!isOnDiscordDomainStrict()) return
        val act = activity.get() ?: return
        if (firewallDialogActive) return
        openAssetEditor(
            act = act,
            assetUrl = "file:///android_asset/firewall_editor.html",
            createBridge = { wv, dialog -> FirewallEditorBridge(act, wv, dialog) },
            onShown = { firewallDialogActive = true },
            onDialogDismiss = { firewallDialogActive = false },
            onPageFinished = { view ->
                val json = gson.toJson(FirewallConfig.toJson())
                view.evaluateJavascript(
                    "window.vdeFirewallInit?.($json)", null
                )
            }
        )
    }

    private class FirewallEditorBridge(
        private val activity: MainActivity,
        private val editorWebView: WebView,
        private val dialog: android.app.Dialog
    ) : EditorOrigin {
        @Volatile override var originCommitted = false
        @Volatile private var lastError: String? = null

        private fun isExpectedOrigin(): Boolean = originCommitted

        @android.webkit.JavascriptInterface
        fun close() {
            try {
                if (!isExpectedOrigin()) return
                if (dialog.isShowing) dialog.dismiss()
            } catch (t: Throwable) {
                lastError = "close: ${t.message}"
            }
        }

        @android.webkit.JavascriptInterface
        fun getFirewallConfig(): String {
            try {
                if (!isExpectedOrigin()) return "{\"error\":\"origin\"}"
                return FirewallConfig.toJson()
            } catch (t: Throwable) {
                lastError = "getFirewallConfig: ${t.javaClass.name}: ${t.message}"
                return "{\"categories\":[],\"customDomains\":[],\"error\":\"${sanitize(lastError)}\"}"
            }
        }

        // Bound the error string and keep it JSON-safe for config.error.
        private fun sanitize(s: String?): String {
            val raw = s ?: ""
            return raw.take(200)
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ")
        }

        @android.webkit.JavascriptInterface
        fun saveFirewallConfig(json: String): Boolean {
            try {
                if (!isExpectedOrigin()) return false
                val ok = FirewallConfig.fromJsonAndSave(json)
                if (ok) {
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity, "Firewall saved", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return ok
            } catch (t: Throwable) {
                lastError = "saveFirewallConfig: ${t.message}"
                return false
            }
        }

        @android.webkit.JavascriptInterface
        fun resetFirewallConfig() {
            try {
                if (!isExpectedOrigin()) return
                FirewallConfig.resetToDefaults()
                activity.runOnUiThread {
                    android.widget.Toast.makeText(
                        activity, "Firewall reset to defaults", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                lastError = "resetFirewallConfig: ${t.message}"
            }
        }
    }
}
