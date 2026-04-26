package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

class VWebviewClient(
    context: Context
) : WebViewClient() {
    private val appContext: Context = context.applicationContext
    private val activityRef: WeakReference<Activity> = if (context is Activity) WeakReference(context) else WeakReference(null)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if ("discord.com" == url.authority || "about:blank" == url.toString()) {
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, url)
        try {
            view.context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(appContext, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val runtime = HttpClient.VencordRuntime
        val mobileRuntime = HttpClient.VencordMobileRuntime
        if (runtime != null || mobileRuntime != null) {
            val script = buildString {
                runtime?.let { append(it).append(';') }
                mobileRuntime?.let { append(it).append(';') }
            }
            view.evaluateJavascript(script, null)
        }
        // VencordRuntime is kept alive for re-injection on subsequent navigations
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)

        val activity = activityRef.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            (activity as? com.nin0dev.vendroid.MainActivity)?.scheduleLoadingScreenDismiss(500)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        if (!shouldInterceptForCspStripping(req)) return null
        try {
            val conn = URL(req.url.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = req.method
            for ((key, value) in req.requestHeaders) {
                conn.setRequestProperty(key, value)
            }
            return doFetch(req, conn)
        } catch (_: Exception) {
            return null
        }
    }

    private fun shouldInterceptForCspStripping(req: WebResourceRequest): Boolean {
        val scheme = req.url.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false

        val host = req.url.host ?: return false

        // Intercept ALL .css requests to fix Content-Type (e.g., GitHub raw serves CSS as text/plain).
        // CSP headers are only stripped for Discord domains in doFetch().
        if (req.url.path?.endsWith(".css") == true) return true

        if (req.isForMainFrame) {
            val isDiscordDomain = host == "discord.com" || host.endsWith(".discord.com") ||
                    host == "discordapp.com" || host.endsWith(".discordapp.com")
            if (isDiscordDomain) {
                val path = req.url.path ?: return true
                val lastSegment = path.substringAfterLast('/')
                val dot = lastSegment.lastIndexOf('.')
                if (dot > 0) {
                    val ext = lastSegment.substring(dot + 1).lowercase()
                    if (ext in NON_HTML_EXTENSIONS) return false
                }
                return true
            }
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doFetch(req: WebResourceRequest, conn: HttpURLConnection): WebResourceResponse {
        val host = req.url.host ?: ""
        val isDiscordDomain = host == "discord.com" || host.endsWith(".discord.com") ||
                host == "discordapp.com" || host.endsWith(".discordapp.com")

        val modifiedHeaders = HashMap<String, String>()
        var i = 0
        while (true) {
            val key = conn.getHeaderFieldKey(i) ?: break
            val value = conn.getHeaderField(i) ?: break
            i++
            // Only strip CSP headers for Discord domains — other domains don't need CSP stripping
            if (isDiscordDomain) {
                if (key.equals("Content-Security-Policy", ignoreCase = true)) continue
                if (key.equals("Content-Security-Policy-Report-Only", ignoreCase = true)) continue
            }
            modifiedHeaders[key] = value
        }
        if (req.url.path?.endsWith(".css") == true) modifiedHeaders["Content-Type"] = "text/css"
        val contentType = modifiedHeaders.getOrDefault("Content-Type", "application/octet-stream")
        val statusCode = conn.responseCode
        val reasonPhrase = conn.responseMessage.takeIf { it.isNotEmpty() } ?: "OK"
        return WebResourceResponse(contentType, "utf-8", statusCode, reasonPhrase, modifiedHeaders, conn.inputStream)
    }

    companion object {
        private val NON_HTML_EXTENSIONS = setOf(
            "js", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp",
            "woff", "woff2", "ttf", "eot", "otf",
            "mp3", "mp4", "webm", "ogg", "wav",
            "map", "json", "xml", "wasm", "zip", "gz"
        )
    }
}
