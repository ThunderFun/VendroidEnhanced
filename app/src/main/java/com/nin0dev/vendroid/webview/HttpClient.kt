package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.nin0dev.vendroid.BuildConfig
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
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

    @JvmStatic
    fun setVencordRuntime(value: String?) { VencordRuntime = value }

    @JvmStatic
    fun setVencordMobileRuntime(value: String?) { VencordMobileRuntime = value }

    private val vencordRuntimePatches = listOf(
        "chat input type must be set" to "chat input type must be set__VENDROID_DISABLED"
    )

    // Pre-built regex for single-pass patching — avoids N full-copy allocations
    // on the ~1MB Vencord bundle.
    private val patchRegex: Regex by lazy {
        vencordRuntimePatches.map { Regex.escape(it.first) }
            .joinToString("|").toRegex()
    }
    private val patchReplaceMap: Map<String, String> by lazy {
        vencordRuntimePatches.toMap()
    }

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
        val vendroidFile = File(activity.filesDir, "vencord.js")

        // Version / debug checks must run BEFORE the early-return so that
        // a synchronous pre-load (in onCreate) doesn't block re-downloads.
        val needsRedownload = sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE
                || (vencordLocation != Constants.JS_BUNDLE_URL && vencordLocation != Constants.EQUICORD_BUNDLE_URL)
                || BuildConfig.DEBUG

        if (needsRedownload) {
            if (sPrefs.getInt("lastMajorUpdateThatUserHasUpdatedVencord", 0) < BuildConfig.VERSION_CODE) {
                if(BuildConfig.DEBUG) activity.runOnUiThread { Toast.makeText(activity, "Just updated app version, redownloading Vencord", Toast.LENGTH_LONG).show() }
                vendroidFile.delete()
                VencordRuntime = null
                sPrefs.edit().remove("vencordEtag").apply()
            }
            if ((vencordLocation != Constants.JS_BUNDLE_URL && vencordLocation != Constants.EQUICORD_BUNDLE_URL) || BuildConfig.DEBUG) {
                activity.runOnUiThread { Toast.makeText(activity, "Debugging app or Vencord, bundle will be redownloaded. Avoid using on limited networks", Toast.LENGTH_LONG).show() }
                vendroidFile.delete()
                VencordRuntime = null
                sPrefs.edit().remove("vencordEtag").apply()
            }
        }

        if (VencordRuntime != null) return
        if (vendroidFile.exists()) {
            val content = vendroidFile.readText()
            VencordRuntime = applyPatches(content)
        }
        else {
            val e = sPrefs.edit()
            val storedEtag = sPrefs.getString("vencordEtag", null)
            var conn: HttpURLConnection? = null
            try {
                conn = URL(vencordLocation).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                if (storedEtag != null) {
                    conn.setRequestProperty("If-None-Match", storedEtag)
                }

                var responseCode = conn.getResponseCode()

                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    if (vendroidFile.exists()) {
                        VencordRuntime = applyPatches(vendroidFile.readText())
                        return
                    }
                    conn.disconnect()
                    conn = URL(vencordLocation).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    responseCode = conn.getResponseCode()
                }

                if (responseCode >= 300) {
                    throw IOException("HTTP $responseCode fetching Vencord bundle from $vencordLocation")
                }

                val initialSize = conn.contentLength.coerceAtLeast(8192)
                val content = readAsText(conn.inputStream, initialSize)
                val patched = applyPatches(content)
                val tmpFile = File(vendroidFile.parent, "${vendroidFile.name}.tmp")
                try {
                    tmpFile.writeText(patched)
                    if (vendroidFile.exists()) vendroidFile.delete()
                    if (!tmpFile.renameTo(vendroidFile)) throw IOException("Failed to rename ${tmpFile.name} to ${vendroidFile.name}")
                } finally {
                    tmpFile.delete()
                }

                val responseEtag = conn.getHeaderField("ETag")
                if (responseEtag != null) {
                    e.putString("vencordEtag", responseEtag)
                }
                e.putInt("lastMajorUpdateThatUserHasUpdatedVencord", BuildConfig.VERSION_CODE)
                e.apply()
                VencordRuntime = patched
            } finally {
                try { conn?.inputStream?.close() } catch (_: IOException) {}
                conn?.disconnect()
            }
        }
        activity.runOnUiThread {
            (activity as? com.nin0dev.vendroid.MainActivity)?.injectVencordIfReady()
        }
    }

    @JvmStatic
    fun applyPatches(content: String): String {
        if (vencordRuntimePatches.isEmpty()) return content
        // Single-pass replacement — avoids creating N intermediate 1MB strings
        // when there are N patches (each .replace() allocates a full copy).
        return patchRegex.replace(content) { match ->
            patchReplaceMap[match.value] ?: match.value
        }
    }

    @Throws(IOException::class)
    fun fetch(url: String): HttpURLConnection {
        if (!url.startsWith("https://")) {
            throw IllegalArgumentException("Non-HTTPS URL rejected: ${url.substringBefore("://")}://")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        if (conn.getResponseCode() >= 300) {
            val ex = HttpException(conn)
            conn.disconnect()
            throw ex
        }
        return conn
    }

    @Throws(IOException::class)
    fun readAsText(`is`: InputStream, initialSize: Int = 8192): String {
        // Use pre-sized ByteArrayOutputStream to avoid ~17 StringBuilder
        // resizes when reading a ~1MB response.
        val bos = ByteArrayOutputStream(initialSize.coerceAtLeast(8192))
        `is`.use { it.copyTo(bos, 8192) }
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
