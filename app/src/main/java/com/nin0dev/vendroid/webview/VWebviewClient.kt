package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.nin0dev.vendroid.utils.Constants
import java.io.ByteArrayInputStream
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
        val host = url.host
        val isDiscordDomain = host != null && Constants.isDiscordDomain(host)
        if (isDiscordDomain || url.scheme == "about") {
            return false
        }
        val scheme = url.scheme
        if (scheme == "http" || scheme == "https") {
            val intent = Intent(Intent.ACTION_VIEW, url)
            try {
                view.context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(appContext, "No app found to open this link", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private val disableHighlightCss = "(function(){if(document.getElementById('vendroid-disable-highlight'))return;var s=document.createElement('style');s.id='vendroid-disable-highlight';s.textContent='*,*::before,*::after{-webkit-tap-highlight-color:transparent!important;outline:none!important}';var t=document.head||document.documentElement;if(t)t.appendChild(s)})()"

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        view.evaluateJavascript("typeof Vencord!=='undefined'&&typeof VencordMobile!=='undefined'") { result ->
            if (result?.trim() == "true") return@evaluateJavascript
            val runtime = HttpClient.VencordRuntime
            val mobileRuntime = HttpClient.VencordMobileRuntime
            if (runtime != null && mobileRuntime != null) {
                // Must NOT wrap in an IIFE — Vencord's bundle uses top-level
                // var/let/const declarations that need global scope to be
                // visible to Discord's code.
                val script = buildString {
                    append(runtime)
                    append(';')
                    append(mobileRuntime)
                    append(';')
                }
                view.evaluateJavascript(script, null)
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        // Inject after the page (and Discord's CSS) has fully loaded so our
        // !important rules are present late in the stylesheet cascade and can't
        // be overridden by subsequently loaded theme CSS. Only inject once here
        // instead of both onPageStarted+onPageFinished to avoid double style
        // recalculation.
        view.evaluateJavascript(disableHighlightCss, null)

        val activity = activityRef.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            (activity as? com.nin0dev.vendroid.MainActivity)?.scheduleLoadingScreenDismiss(500)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        if (!shouldInterceptForCspStripping(req)) return null
        val urlString = req.url.toString() // cache once — Uri.toString() allocates each call
        val isCss = req.url.path?.endsWith(".css") == true
        // Only force no-cache on Vencord/Equicord theme CSS — Discord's own CSS
        // should use normal browser caching to avoid unnecessary network round-trips
        // that block rendering and make interactions feel sluggish.
        val isThemeCss = isCss && isVencordCssUrl(req.url)
        var conn: HttpURLConnection? = null
        try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.requestMethod = req.method
            if (isThemeCss) {
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache")
                conn.setRequestProperty("Pragma", "no-cache")
            }
            for ((key, value) in req.requestHeaders) {
                val lowerKey = key.lowercase()
                // Strip conditional headers for theme CSS only — prevents stale
                // theme caches while letting Discord CSS use normal 304 responses.
                if (isThemeCss && lowerKey in STRIPPED_CONDITIONAL_HEADERS) continue
                conn.setRequestProperty(key, value)
            }
            return doFetch(req, conn, isCss)
        } catch (_: Exception) {
            conn?.disconnect()
            return null
        }
    }

    /** Only Vencord/Equicord theme CSS needs to bypass cache — not Discord's own CSS. */
    private fun isVencordCssUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!isForgeHost(host)) return false
        val urlLower = uri.toString().lowercase()
        return urlLower.contains("vencord") || urlLower.contains("equicord") || urlLower.contains("vendroid")
    }

    private fun isForgeHost(host: String): Boolean =
        host == "github.com" || host == "raw.githubusercontent.com" || host.endsWith("github.io")
                || host == "codeberg.org" || host.endsWith("codeberg.page")

    private fun shouldInterceptForCspStripping(req: WebResourceRequest): Boolean {
        val scheme = req.url.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false

        // Intercept forge-served CSS for Content-Type fixing and theme no-cache policy.
        if (req.url.path?.endsWith(".css") == true) {
            val host = req.url.host ?: return false
            if (isForgeHost(host)) return true
        }

        if (req.isForMainFrame) {
            val host = req.url.host ?: return false
            if (Constants.isDiscordDomain(host)) return true
        }

        return false
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doFetch(req: WebResourceRequest, conn: HttpURLConnection, isCss: Boolean): WebResourceResponse {
        val host = req.url.host ?: ""
        val isDiscordDomain = Constants.isDiscordDomain(host)

        val statusCode = conn.responseCode

        val modifiedHeaders = HashMap<String, String>(conn.headerFields?.size ?: 16)
        var i = 1
        while (true) {
            val key = conn.getHeaderFieldKey(i) ?: break
            val value = conn.getHeaderField(i) ?: break
            i++
            val lowerKey = key.lowercase()
            if (isDiscordDomain && lowerKey == "content-security-policy") continue
            if (isDiscordDomain && lowerKey == "content-security-policy-report-only") continue
            modifiedHeaders[key] = value
        }
        if (isCss) modifiedHeaders["Content-Type"] = "text/css"
        val contentType = modifiedHeaders.getOrDefault("Content-Type", "application/octet-stream")
        val reasonPhrase = conn.responseMessage.takeIf { it.isNotEmpty() } ?: "OK"
        val stream = if (statusCode >= 400) {
            try { conn.errorStream } catch (_: Exception) { null } ?: ByteArrayInputStream(ByteArray(0))
        } else {
            conn.inputStream
        }
        return WebResourceResponse(contentType, "utf-8", statusCode, reasonPhrase, modifiedHeaders, stream)
    }

    companion object {
        private val STRIPPED_CONDITIONAL_HEADERS = setOf(
            "if-none-match", "if-modified-since", "if-unmodified-since", "if-match"
        )
    }
}
