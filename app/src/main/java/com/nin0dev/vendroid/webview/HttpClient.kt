package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import com.nin0dev.vendroid.BuildConfig
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.VDELog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.HttpURLConnection
import java.net.URL

object HttpClient {
    // Generous ceiling for any text body read into memory (bundle, CSS).
    const val MAX_READ_BYTES = 16 * 1024 * 1024 // 16 MB
    /**
     * Shared app-wide OkHttp client. Reuses pooled TCP/TLS connections across
     * requests to the same host, avoiding a fresh connect + TLS handshake.
     *
     * Security: redirects are disabled at the client (OkHttp defaults both to
     * true) and re-validated manually against the host allowlist by callers;
     * auto-following would be an allowlist bypass. No cookie jar (parity with
     * HttpURLConnection; Chromium owns session cookies) and no OkHttp cache
     * (the app has its own response caches).
     */
    val sharedClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)       // keep false; see security note
        .followSslRedirects(false)    // keep false; OkHttp default is true
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    @Volatile
    var VencordRuntime: String? = null
        private set
    @Volatile
    var VencordMobileRuntime: String? = null
        private set

    /**
     * True when the on-disk `vencord.js` is known to contain the patched
     * content (i.e. [applyPatches] already ran at download time). Checked by
     * the load paths to skip a redundant ~1MB regex scan on every cold start.
     */
    @Volatile
    var vencordBundlePatched = false

    @JvmStatic
    fun setVencordRuntime(value: String?) { VencordRuntime = value }

    @JvmStatic
    fun setVencordMobileRuntime(value: String?) { VencordMobileRuntime = value }

    // Vencord bundle patches applied at download time. The Slate/command-browser
    // fix is applied at runtime in vencord_mobile.js. Each patch carries a
    // marker string present in its own replacement, so applyPatches() can skip
    // it when already patched. This makes the patch idempotent: the pattern is
    // anchored on the closing quote and the marker check runs first, so
    // re-applying cannot grow the replacement.
    private data class BundlePatch(
        val pattern: Regex,
        val replacement: String,
        val marker: String
    )
    private val vencordRuntimePatches: List<BundlePatch> = listOf(
        BundlePatch(
            Regex.escape("\"chat input type must be set\"").toRegex(),
            "\"chat input type must be set__VENDROID_DISABLED\"",
            "chat input type must be set__VENDROID_DISABLED"
        )
    )

    /** SharedPreferences key recording that the on-disk bundle is already patched. */
    const val PREF_BUNDLE_PATCHED = "vencordBundlePatched"

    /**
     * Loads the on-disk bundle, applying patches only when the persisted
     * patched-flag indicates they have not been applied. Reads the flag from
     * prefs (not the in-memory volatile) so a cold start in a fresh process
     * skips the ~1MB regex scan on the already-patched file.
     */
    @JvmStatic
    fun readBundleFromDisk(
        sPrefs: SharedPreferences,
        vendroidFile: File
    ): String {
        val raw = vendroidFile.readText()
        return if (sPrefs.getBoolean(PREF_BUNDLE_PATCHED, false)) raw
        else applyPatches(raw)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun fetchVencord(activity: Activity) {
        val sPrefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val bundleURLToUse = if(sPrefs.getString("clientMod", "vencord") == "equicord") Constants.EQUICORD_BUNDLE_URL else Constants.JS_BUNDLE_URL
        val vencordLocation = sPrefs.getString("vencordLocation", bundleURLToUse) ?: bundleURLToUse
        val vencordHost = Uri.parse(vencordLocation).host
        // Log only the host, not the full URL (a custom URL could carry a token
        // in a query string, which would leak into the shareable log).
        VDELog.i("HTTP", "Fetching bundle from host: $vencordHost")
        // Reject null host explicitly; the bundle is arbitrary JS executed in
        // the Discord origin, so the host whitelist must be a hard gate.
        if (vencordHost == null || !Constants.isAllowedVencordHost(vencordHost)) {
            throw IOException("Vencord location host '$vencordHost' is not in allowed list")
        }
        if (!vencordLocation.startsWith("https://")) {
            throw IOException("Vencord location must use HTTPS: $vencordLocation")
        }
        val vendroidFile = File(activity.filesDir, "vencord.js")
        // Discard a zero-length file (interrupted write) so the cache branches
        // below don't load an empty bundle.
        if (vendroidFile.exists() && vendroidFile.length() == 0L) {
            VDELog.w("HTTP", "Cached vencord.js is empty, discarding")
            vendroidFile.delete()
            vencordBundlePatched = false
            sPrefs.edit()
                .remove("vencordEtag")
                .remove(PREF_BUNDLE_PATCHED)
                .apply()
        }

        // Forced redownload on app version bump, custom bundle URL, or debug
        // builds. Runs before any cache short-circuit so a pre-load can't skip
        // it; deletes the cached file to force a full download below.
        val needsRedownload = sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE
                || (vencordLocation != Constants.JS_BUNDLE_URL && vencordLocation != Constants.EQUICORD_BUNDLE_URL)
                || BuildConfig.DEBUG

        if (needsRedownload) {
            val versionBump = sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE
            val customUrl = vencordLocation != Constants.JS_BUNDLE_URL && vencordLocation != Constants.EQUICORD_BUNDLE_URL
            // Original behavior: toast when a custom URL is set (any build) or
            // on DEBUG builds; a pure release version-bump showed no toast.
            // Preserve that while fixing the double-toast the two independent
            // `if` branches produced on a fresh DEBUG install.
            if (customUrl || BuildConfig.DEBUG) {
                val msg = when {
                    customUrl -> "Debugging app or Vencord, bundle will be redownloaded. Avoid using on limited networks"
                    versionBump -> "Just updated app version, redownloading Vencord"
                    else -> "Debugging app, bundle will be redownloaded. Avoid using on limited networks"
                }
                activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_LONG).show() }
            }
            vendroidFile.delete()
            vencordBundlePatched = false
            sPrefs.edit()
                .remove("vencordEtag")
                .remove(PREF_BUNDLE_PATCHED)
                .apply()
        }

        // Warm-navigation fast path: skip the network round-trip when the
        // bundle is already in memory and no forced redownload is pending. The
        // on-disk file alone is not sufficient — it may be stale, so the
        // ETag-conditional GET below handles that case (304 when current).
        if (VencordRuntime != null && !needsRedownload) {
            VDELog.d("HTTP", "Bundle already in memory and cache valid, skipping fetch")
            return
        }

        val storedEtag = sPrefs.getString("vencordEtag", null)
        var resp: Response? = null
        try {
            // ETag-conditional GET: 304 keeps the cache (cheap), 200 swaps in
            // a newer build. Detects new Vencord builds without wiping app
            // data.
            resp = executeVencordGetResolvingRedirect(vencordLocation, storedEtag)
            var responseCode = resp.code

            when {
                responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && vendroidFile.exists() -> {
                    VDELog.i("HTTP", "Bundle not modified (304), using cache")
                    if (VencordRuntime == null) {
                        VencordRuntime = readBundleFromDisk(sPrefs, vendroidFile)
                    }
                }

                responseCode == HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    // 304 with no local file — re-request unconditionally,
                    // following one validated redirect like the primary path.
                    resp?.close()
                    resp = executeVencordGetResolvingRedirect(vencordLocation, null)
                    responseCode = resp.code
                    if (responseCode !in 200..299) {
                        throw IOException("HTTP $responseCode fetching Vencord bundle from $vencordLocation")
                    }
                    downloadAndStore(resp, vendroidFile, sPrefs)
                }

                responseCode in 200..299 -> {
                    downloadAndStore(resp, vendroidFile, sPrefs)
                }

                else -> {
                    // Fall back to the cached bundle on transient server errors
                    // so startup doesn't break; otherwise surface the failure.
                    if (vendroidFile.exists() && VencordRuntime == null) {
                        VDELog.w("HTTP", "HTTP $responseCode; falling back to cached bundle")
                        VencordRuntime = readBundleFromDisk(sPrefs, vendroidFile)
                    } else {
                        throw IOException("HTTP $responseCode fetching Vencord bundle from $vencordLocation")
                    }
                }
            }
        } catch (io: IOException) {
            // Network failure during the version check must not brick startup
            // when a cached bundle exists; only the no-cache case propagates.
            if (vendroidFile.exists() && VencordRuntime == null) {
                VDELog.w("HTTP", "Network error during version check; using cached bundle: ${io.message}")
                VencordRuntime = readBundleFromDisk(sPrefs, vendroidFile)
            } else {
                throw io
            }
        } finally {
            // Close to return the pooled connection (not disconnect()).
            resp?.close()
        }
        activity.runOnUiThread {
            (activity as? com.nin0dev.vendroid.MainActivity)?.injectVencordIfReady()
        }
    }

    /**
     * Executes a conditional GET for the Vencord bundle over the shared pooled
     * client. Redirects are never auto-followed (client config); the caller
     * validates any redirect host against the allowlist.
     */
    private fun executeVencordGet(url: String, etag: String?): Response {
        val rb = Request.Builder().url(url)
        if (etag != null) rb.header("If-None-Match", etag)
        return sharedClient.newCall(rb.build()).execute()
    }

    /**
     * Executes a conditional GET for the Vencord bundle, resolving a single
     * redirect hop against the HTTPS + host allowlist. Redirects are never
     * auto-followed by the client config, so the hop is validated here to keep
     * the allowlist a hard gate. Used by both the primary path and the
     * 304-with-no-local-file re-request so their redirect handling stays in
     * sync.
     */
    private fun executeVencordGetResolvingRedirect(url: String, etag: String?): Response {
        val resp = executeVencordGet(url, etag)
        if (resp.code !in 300..399) return resp
        val location = resp.header("Location")
            ?: run {
                resp.close()
                throw IOException("Redirect with no Location header")
            }
        resp.close()
        val redirectUrl = URL(URL(url), location)
        if (!redirectUrl.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Redirect to non-HTTPS scheme: ${redirectUrl.protocol}")
        }
        val redirectHost = redirectUrl.host
        // Reject null host so the whitelist stays a hard gate rather than
        // relying on openConnection() to throw later.
        if (redirectHost == null || !Constants.isAllowedVencordHost(redirectHost)) {
            throw IOException("Redirect to disallowed or unresolvable host: $redirectHost")
        }
        return executeVencordGet(redirectUrl.toString(), etag)
    }

    /**
     * Downloads, patches, and atomically writes the Vencord bundle, and
     * updates the [VencordRuntime] / [vencordBundlePatched] / `vencordEtag` /
     * `lastMajorUpdateThatUserHasUpdatedVencord` bookkeeping.
     *
     * Shared by the startup path so the post-write state stays consistent and
     * the next launch doesn't re-download a fresh bundle. Caller must have
     * validated HTTPS + host whitelist.
     */
    @Throws(IOException::class)
    private fun downloadAndStore(
        resp: Response,
        vendroidFile: File,
        sPrefs: SharedPreferences
    ) {
        // Clamp the declared Content-Length before toInt(): a malicious host
        // could send a huge Long that wraps to a large positive Int, causing an
        // eager oversized pre-allocation in ByteArrayOutputStream before any
        // byte is read. Clamping to the read cap bounds that pre-allocation.
        val initialSize = resp.body.contentLength().coerceIn(8192L, MAX_READ_BYTES.toLong()).toInt()
        val content = readAsText(resp.body.byteStream(), initialSize)
        VDELog.i("HTTP", "Bundle downloaded (${content.length} chars), applying patches...")
        val patched = applyPatches(content)
        // Unique temp name: VencordNative.updateVencord writes the same bundle
        // on a different executor, and a shared "vencord.js.tmp" let one
        // writer's rename install the other's truncated file.
        val tmpFile = File(vendroidFile.parent, "${vendroidFile.name}.${System.nanoTime()}.tmp")
        try {
            tmpFile.writeText(patched)
            if (!tmpFile.renameTo(vendroidFile)) {
                throw IOException("Failed to rename ${tmpFile.name} to ${vendroidFile.name}")
            }
        } finally {
            // No-op after a successful rename; removes a partial temp file on
            // failure (unique names would otherwise leak files on write
            // errors).
            tmpFile.delete()
        }
        val responseEtag = resp.header("ETag")
        val e = sPrefs.edit()
        if (responseEtag != null) {
            e.putString("vencordEtag", responseEtag)
        }
        e.putInt("lastMajorUpdateThatUserHasUpdatedVencord", BuildConfig.VERSION_CODE)
        // Persist the patched flag so a later cold start skips the ~1MB regex
        // scan instead of re-running applyPatches on the already-patched file.
        e.putBoolean(PREF_BUNDLE_PATCHED, true)
        e.apply()
        VencordRuntime = patched
        vencordBundlePatched = true
        VDELog.i("HTTP", "Bundle patched and saved to disk")
    }

    @JvmStatic
    fun applyPatches(content: String): String {
        if (vencordRuntimePatches.isEmpty()) return content
        VDELog.d("HTTP", "Applying ${vencordRuntimePatches.size} patches")
        // Few patches means sequential replace is simpler than mapping
        // combined-regex matches back to individual patches. Each replace
        // copies ~1MB, acceptable for 2-3 patches at download time.
        var result = content
        for (patch in vencordRuntimePatches) {
            VDELog.d("HTTP", "Patch: ${patch.pattern.pattern}")
            // Skip already-patched content so a re-apply (e.g. the persisted
            // flag was cleared) never double-suffixes the replacement.
            if (result.contains(patch.marker)) continue
            result = patch.pattern.replace(result, patch.replacement)
        }
        return result
    }

    /**
     * Opens an HTTPS connection to [url] and verifies the response is 2xx.
     *
     * Lifecycle: the caller owns the returned [Response] and must close it
     * (`resp.close()` or `resp.body?.close()`) in a `finally` so the pooled
     * connection is returned.
     *
     * Throws [IOException] on non-HTTPS input, redirects/errors (3xx/4xx/5xx,
     * as [HttpException]), or network failure.
     */
    @Throws(IOException::class)
    fun fetch(url: String): Response {
        if (!url.startsWith("https://")) {
            throw IOException("Non-HTTPS URL rejected: ${url.substringBefore("://")}://")
        }
        val resp = sharedClient.newCall(Request.Builder().url(url).build()).execute()
        if (resp.code >= 300) {
            val ex = HttpException(resp)
            resp.close()
            throw ex
        }
        return resp
    }

    @Throws(IOException::class)
    fun readAsText(inputStream: InputStream, initialSize: Int = 8192, maxBytes: Int = MAX_READ_BYTES): String {
        // Use a pre-sized ByteArrayOutputStream to avoid ~17 StringBuilder
        // resizes when reading a ~1MB response.
        val bos = ByteArrayOutputStream(initialSize.coerceAtLeast(8192))
        // Bound the read so a compromised/streaming host cannot balloon memory
        // or disk. The cap is generous (well above the ~1MB bundle / small CSS)
        // but finite; exceeding it fails closed rather than OOMing.
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response exceeds $maxBytes byte limit")
            bos.write(buf, 0, n)
        }
        return bos.toString("UTF-8")
    }

    /**
     * Reads the stream into a byte array, capping at [maxBytes] (default
     * [MAX_READ_BYTES]) and throwing [IOException] on overflow so callers fail
     * closed instead of ballooning memory. Mirrors the cap in [readAsText] for
     * the paths that need the raw bytes (WebView serve / disk cache).
     */
    @Throws(IOException::class)
    fun readAsBytes(
        inputStream: InputStream,
        maxBytes: Int = MAX_READ_BYTES,
        initialSize: Int = 8192
    ): ByteArray {
        // Seed the buffer from the expected content length (clamped) instead
        // of the full maxBytes ceiling, so a large max doesn't force a big
        // up-front allocation on every small fetch.
        val bos = ByteArrayOutputStream(initialSize.coerceIn(8192, maxBytes))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response exceeds $maxBytes byte limit")
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    class HttpException(resp: Response) : IOException() {
        override val message: String? = try {
            String.format(
                    Locale.ENGLISH,
                    "HTTP %d: %s (%s)",
                    resp.code,
                    resp.message,
                    resp.request.url.host
            )
        } catch (_: IOException) {
            "HTTP error for host: " + (resp.request.url.host ?: "unknown")
        }
    }
}
