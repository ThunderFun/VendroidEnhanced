package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.nin0dev.vendroid.BuildConfig
import com.nin0dev.vendroid.R
import com.nin0dev.vendroid.utils.Constants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object HttpClient {
    @Volatile
    @JvmField
    var VencordRuntime: String? = null
    @Volatile
    @JvmField
    var VencordMobileRuntime: String? = null

    private val vencordRuntimePatches = listOf(
        "chat input type must be set" to "chat input type must be set__VENDROID_DISABLED"
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
            }
            if ((vencordLocation != Constants.JS_BUNDLE_URL && vencordLocation != Constants.EQUICORD_BUNDLE_URL) || BuildConfig.DEBUG) {
                activity.runOnUiThread { Toast.makeText(activity, "Debugging app or Vencord, bundle will be redownloaded. Avoid using on limited networks", Toast.LENGTH_LONG).show() }
                vendroidFile.delete()
                VencordRuntime = null
            }
        }

        if (VencordRuntime != null) return
        if (vendroidFile.exists()) {
            val content = vendroidFile.readText()
            VencordRuntime = applyPatches(content)
        }
        else {
            val e = sPrefs.edit()
            val conn = fetch(vencordLocation)
            try {
                val initialSize = conn.contentLength.coerceAtLeast(8192)
                val content = readAsText(conn.inputStream, initialSize)
                val patched = applyPatches(content)
                vendroidFile.writeText(patched)
                e.putInt("lastMajorUpdateThatUserHasUpdatedVencord", BuildConfig.VERSION_CODE)
                e.apply()
                VencordRuntime = patched
            } finally {
                conn.disconnect()
            }
        }
        activity.runOnUiThread {
            (activity as? com.nin0dev.vendroid.MainActivity)?.injectVencordIfReady()
        }
    }

    @JvmStatic
    fun applyPatches(content: String): String {
        var result = content
        for ((search, replace) in vencordRuntimePatches) {
            result = result.replace(search, replace)
        }
        return result
    }

    @Throws(IOException::class)
    fun fetch(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        if (conn.getResponseCode() >= 300) {
            throw HttpException(conn)
        }
        return conn
    }

    @Throws(IOException::class)
    fun readAsText(`is`: InputStream, initialSize: Int = 8192): String {
        return `is`.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    class HttpException(private val conn: HttpURLConnection) : IOException() {
        override var message: String? = null
            get() {
                if (field == null) {
                    try {
                        conn.errorStream.use { es ->
                            field = String.format(
                                    Locale.ENGLISH,
                                    "%d: %s (%s)\n%s",
                                    conn.getResponseCode(),
                                    conn.getResponseMessage(),
                                    conn.url.toString(),
                                    readAsText(es)
                            )
                        }
                    } catch (ex: IOException) {
                        field = "Error while building message lmao. Url is " + conn.url.toString()
                    }
                }
                return field
            }
            private set
    }
}
