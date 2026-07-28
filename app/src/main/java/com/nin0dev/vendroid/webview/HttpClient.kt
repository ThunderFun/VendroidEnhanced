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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object HttpClient {
    @Volatile
    var VencordRuntime: String? = null
        private set
    @Volatile
    var VencordMobileRuntime: String? = null
        private set

    /**
     * True when the `vencord.js` file on disk is known to contain the patched
     * content (i.e. [applyPatches] was already run at download time).
     * Checked by the load paths (VendroidApp preload, MainActivity safety net,
     * fetchVencord-from-disk) to skip a redundant ~1MB regex scan on every
     * cold start.
     */
    @Volatile
    var vencordBundlePatched = false

    @JvmStatic
    fun setVencordRuntime(value: String?) { VencordRuntime = value }

    @JvmStatic
    fun setVencordMobileRuntime(value: String?) { VencordMobileRuntime = value }

    // Vencord bundle patches applied at download time.
    // The Slate/command-browser fix is done at runtime in vencord_mobile.js.
    private data class BundlePatch(val pattern: Regex, val replacement: String)
    private val vencordRuntimePatches: List<BundlePatch> = listOf(
        BundlePatch(Regex.escape("chat input type must be set").toRegex(),
            "chat input type must be set__VENDROID_DISABLED")
    )

    @JvmStatic
    fun clearInjectedBundles() {
        // Keep VencordRuntime in memory — it must persist for re-injection on
        // subsequent navigations (onPageStarted). The string is ~1MB and only
        // allocated once, so holding it is worth the instant re-injection win.
        // VencordMobileRuntime is also preserved (no re-population path).
    }

    @JvmStatic
    @Throws(IOException::class)
    fun fetchVencord(activity: Activity) {
        val sPrefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val bundleURLToUse = if(sPrefs.getString("clientMod", "vencord") == "equicord") Constants.EQUICORD_BUNDLE_URL else Constants.JS_BUNDLE_URL
        val vencordLocation = sPrefs.getString("vencordLocation", bundleURLToUse) ?: bundleURLToUse
        val vencordHost = Uri.parse(vencordLocation).host
        VDELog.i("HTTP", "Fetching bundle from $vencordLocation")
        // Reject null host explicitly; the bundle is arbitrary JS executed in
        // the Discord origin, so the host whitelist must be a hard gate.
        if (vencordHost == null || !Constants.isAllowedVencordHost(vencordHost)) {
            throw IOException("Vencord location host '$vencordHost' is not in allowed list")
        }
        if (!vencordLocation.startsWith("https://")) {
            throw IOException("Vencord location must use HTTPS: $vencordLocation")
        }
        val vendroidFile = File(activity.filesDir, "vencord.js")
        // A zero-length file results from an interrupted write; discard it so
        // the cache branches below don't load an empty bundle.
        if (vendroidFile.exists() && vendroidFile.length() == 0L) {
            VDELog.w("HTTP", "Cached vencord.js is empty, discarding")
            vendroidFile.delete()
            vencordBundlePatched = false
            sPrefs.edit().remove("vencordEtag").apply()
        }

        // Forced redownload on app version bump, custom bundle URL, or debug
        // builds. Runs before any cache short-circuit so a pre-load can't skip
        // it; deletes the cached file to force a full download below.
        val needsRedownload = sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE
                || (vencordLocation != Constants.JS_BUNDLE_URL && vencordLocation != Constants.EQUICORD_BUNDLE_URL)
                || BuildConfig.DEBUG

        if (needsRedownload) {
            if (sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE) {
                if(BuildConfig.DEBUG) activity.runOnUiThread { Toast.makeText(activity, "Just updated app version, redownloading Vencord", Toast.LENGTH_LONG).show() }
                vendroidFile.delete()
                vencordBundlePatched = false
                sPrefs.edit().remove("vencordEtag").apply()
            }
            if ((vencordLocation != Constants.JS_BUNDLE_URL && vencordLocation != Constants.EQUICORD_BUNDLE_URL) || BuildConfig.DEBUG) {
                activity.runOnUiThread { Toast.makeText(activity, "Debugging app or Vencord, bundle will be redownloaded. Avoid using on limited networks", Toast.LENGTH_LONG).show() }
                vendroidFile.delete()
                vencordBundlePatched = false
                sPrefs.edit().remove("vencordEtag").apply()
            }
        }

        // Warm-navigation fast path: skip the network round-trip when the
        // bundle is already in memory and no forced redownload is pending.
        // The on-disk file alone is NOT sufficient — it may be stale, so the
        // ETag-conditional GET below handles that case (304 when current).
        if (VencordRuntime != null && !needsRedownload) {
            VDELog.d("HTTP", "Bundle already in memory and cache valid, skipping fetch")
            return
        }

        val storedEtag = sPrefs.getString("vencordEtag", null)
        var conn: HttpURLConnection? = null
        try {
            // ETag-conditional GET: 304 keeps the cache (cheap), 200 swaps in
            // a newer build. This detects new Vencord builds without wiping
            // app data.
            conn = URL(vencordLocation).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            if (storedEtag != null) {
                conn.setRequestProperty("If-None-Match", storedEtag)
            }

            var responseCode = conn.getResponseCode()

            if (responseCode in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("Redirect with no Location header")
                conn.disconnect()
                val redirectUrl = URL(URL(vencordLocation), location)
                if (!redirectUrl.protocol.equals("https", ignoreCase = true)) {
                    throw IOException("Redirect to non-HTTPS scheme: ${redirectUrl.protocol}")
                }
                val redirectHost = redirectUrl.host
                // Reject null host so the whitelist stays a hard gate rather
                // than relying on openConnection() to throw later.
                if (redirectHost == null || !Constants.isAllowedVencordHost(redirectHost)) {
                    throw IOException("Redirect to disallowed or unresolvable host: $redirectHost")
                }
                conn = redirectUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                responseCode = conn.getResponseCode()
            }

            when {
                responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && vendroidFile.exists() -> {
                    VDELog.i("HTTP", "Bundle not modified (304), using cache")
                    if (VencordRuntime == null) {
                        VencordRuntime = if (vencordBundlePatched) vendroidFile.readText() else applyPatches(vendroidFile.readText())
                    }
                }

                responseCode == HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    // 304 with no local file — re-request unconditionally.
                    conn.disconnect()
                    conn = URL(vencordLocation).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.instanceFollowRedirects = false
                    responseCode = conn.getResponseCode()
                    if (responseCode !in 200..299) {
                        throw IOException("HTTP $responseCode fetching Vencord bundle from $vencordLocation")
                    }
                    downloadAndStore(conn, vendroidFile, sPrefs)
                }

                responseCode in 200..299 -> {
                    downloadAndStore(conn, vendroidFile, sPrefs)
                }

                else -> {
                    // Fall back to the cached bundle on transient server errors
                    // so startup doesn't break; otherwise surface the failure.
                    if (vendroidFile.exists() && VencordRuntime == null) {
                        VDELog.w("HTTP", "HTTP $responseCode; falling back to cached bundle")
                        VencordRuntime = if (vencordBundlePatched) vendroidFile.readText() else applyPatches(vendroidFile.readText())
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
                VencordRuntime = if (vencordBundlePatched) vendroidFile.readText() else applyPatches(vendroidFile.readText())
            } else {
                throw io
            }
        } finally {
            try { conn?.inputStream?.close() } catch (_: IOException) {}
            conn?.disconnect()
        }
        activity.runOnUiThread {
            (activity as? com.nin0dev.vendroid.MainActivity)?.injectVencordIfReady()
        }
    }

    /**
     * Downloads, patches, and atomically writes the Vencord bundle, and
     * updates the [VencordRuntime] / [vencordBundlePatched] / `vencordEtag` /
     * `lastMajorUpdateThatUserHasUpdatedVencord` bookkeeping.
     *
     * Shared by the startup path so the post-write state stays consistent and
     * the next launch doesn't re-download a fresh bundle. Caller must have
     * validated HTTPS + host whitelist and is responsible for disconnecting.
     */
    @Throws(IOException::class)
    private fun downloadAndStore(
        conn: HttpURLConnection,
        vendroidFile: File,
        sPrefs: SharedPreferences
    ) {
        val initialSize = conn.contentLength.coerceAtLeast(8192)
        val content = readAsText(conn.inputStream, initialSize)
        VDELog.i("HTTP", "Bundle downloaded (${content.length} chars), applying patches...")
        val patched = applyPatches(content)
        val tmpFile = File(vendroidFile.parent, "${vendroidFile.name}.tmp")
        tmpFile.writeText(patched)
        if (!tmpFile.renameTo(vendroidFile)) {
            tmpFile.delete()
            throw IOException("Failed to rename ${tmpFile.name} to ${vendroidFile.name}")
        }

        val responseEtag = conn.getHeaderField("ETag")
        val e = sPrefs.edit()
        if (responseEtag != null) {
            e.putString("vencordEtag", responseEtag)
        }
        e.putInt("lastMajorUpdateThatUserHasUpdatedVencord", BuildConfig.VERSION_CODE)
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
            result = patch.pattern.replace(result, patch.replacement)
        }
        return result
    }

    @Throws(IOException::class)
    fun fetch(url: String): HttpURLConnection {
        if (!url.startsWith("https://")) {
            throw IllegalArgumentException("Non-HTTPS URL rejected: ${url.substringBefore("://")}://")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = false
        if (conn.getResponseCode() >= 300) {
            val ex = HttpException(conn)
            conn.disconnect()
            throw ex
        }
        return conn
    }

    @Throws(IOException::class)
    fun readAsText(inputStream: InputStream, initialSize: Int = 8192): String {
        // Use pre-sized ByteArrayOutputStream to avoid ~17 StringBuilder
        // resizes when reading a ~1MB response.
        val bos = ByteArrayOutputStream(initialSize.coerceAtLeast(8192))
        inputStream.use { it.copyTo(bos, 8192) }
        return bos.toString("UTF-8")
    }

    class HttpException(conn: HttpURLConnection) : IOException() {
        override val message: String? = try {
            String.format(
                    Locale.ENGLISH,
                    "HTTP %d: %s (%s)",
                    conn.getResponseCode(),
                    conn.getResponseMessage(),
                    conn.url.host
            )
        } catch (_: IOException) {
            "HTTP error for host: " + (conn.url.host ?: "unknown")
        }
    }
}
