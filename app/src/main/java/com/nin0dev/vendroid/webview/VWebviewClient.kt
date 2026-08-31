package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.webkit.WebResourceResponseCompat
import androidx.webkit.WebViewFeature
import com.nin0dev.vendroid.BuildConfig
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.FirewallConfig
import com.nin0dev.vendroid.utils.JsPatches
import com.nin0dev.vendroid.utils.VDELog
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.lang.ref.WeakReference
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

class VWebviewClient(
    context: Context
) : WebViewClient() {
    private val appContext: Context = context.applicationContext
    private val activityRef: WeakReference<Activity> = if (context is Activity) WeakReference(context) else WeakReference(null)
    private val linkHandler: LinkHandler = LinkHandler(context)

    private class CachedResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val fetchedAt: Long = System.currentTimeMillis()
    )

    private enum class CacheTarget { THEME_CSS, MAIN_FRAME }

    // Static caches survive across MainActivity recreations (rotation, memory
    // pressure, etc.) so previously-fetched CSS / HTML is still warm.
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        // Subframe navigations to javascript:, data:, file:, intent: and
        // custom schemes are not network requests and never pass through
        // shouldInterceptRequest. Allow only browser/media subframe schemes so
        // a javascript: URL cannot execute in a subframe context. (Cross-origin
        // subframes can't read the top-frame capability token anyway, but fail
        // closed rather than rely on that.)
        if (!request.isForMainFrame && url.scheme != "https" && url.scheme != "http" &&
            url.scheme != "blob" && url.scheme != "data"
        ) {
            return true
        }
        // A data: SUBFRAME DOCUMENT NAVIGATION is stricter than a data:
        // SUBRESOURCE (<img>): as a document, data:image/svg+xml executes
        // inline onload script and data:text/html is full HTML. The subresource
        // path treats data:image/ as inert (Discord relies on it for
        // avatars/icons), but a subframe navigating there must not run script.
        if (!request.isForMainFrame && url.scheme == "data" && !isInertDataUrl(url.toString())) {
            return true
        }
        // Non-allowlisted links go to the link popup (Copy / Open / Share / Cancel).
        when (NavigationPolicy.decide(url, request.isForMainFrame)) {
            NavigationPolicy.Action.LOAD_IN_WEBVIEW -> return false
            NavigationPolicy.Action.SHOW_POPUP -> {
                VDELog.d("WV", "External link: ${UrlNormalizer.redactForLog(url.toString())}")
                linkHandler.showLinkPopup(url)
                return true
            }
        }
    }

    /**
     * True if a `data:` URL is inert as a DOCUMENT (cannot execute script).
     * `data:image/svg+xml` is NOT inert in a navigation context (SVG documents
     * run inline `onload` script); `data:text/html` is raw HTML.
     *
     * Uses ignoreCase startsWith instead of lowercasing the whole URL: data:
     * payloads can be hundreds of KB, and a full-string lowercase copies them.
     */    private fun isInertDataUrl(dataUrl: String): Boolean =
        dataUrl.startsWith("data:font/", ignoreCase = true) ||
            dataUrl.startsWith("data:application/font", ignoreCase = true) ||
            dataUrl.startsWith("data:text/css", ignoreCase = true) ||
            dataUrl.startsWith("data:application/octet-stream", ignoreCase = true)

    private val disableHighlightCss = "html{-webkit-tap-highlight-color:transparent}a,button,[role=\"button\"],input,textarea,select,[tabindex]:not([tabindex=\"-1\"]){outline:none}"

    private val disableHighlightTag = "<style id=\"vendroid-disable-highlight\">$disableHighlightCss</style>"

    /**
     * Injects the JS network firewall, CSP violation reporter, disable-highlight
     * CSS, and — when both runtimes are in memory — the Vencord runtimes into a
     * Discord main-frame shell at `</head>`.
     *
     * The runtimes are only embedded on Discord main-frames (matching the
     * trust gate in [onPageStarted]); they must never run on whitelisted
     * non-Discord pages, which would expose the bridge object. All blocks are
     * inserted at a single `headIdx` from the ORIGINAL text, with the style
     * placed first: the downloaded runtime may itself contain a literal
     * `</head>`, so re-scanning the patched string could relocate the style
     * into script text where it is never applied. Runtime content is escaped
     * via [escapeScriptTagContent] so a literal `</script>` cannot terminate
     * the inline tag early.
     *
     * @return the patched HTML, or null if no `</head>` was found or the
     *   runtime was not ready, so callers fall through to bridge injection.
     */    private fun injectFirewallAndCss(
        text: String,
        urlString: String,
        isDiscordMainFrame: Boolean
    ): String? {
        val headIdx = findHeadCloseIndex(text)
        if (headIdx < 0) return null
        // Gate on the local snapshot, not isMainFrameRuntimeReady(): a re-read
        // of the statics here could race a mid-session null (clientMod switch)
        // against the !! uses below.
        val runtime = HttpClient.VencordRuntime
        val mobileRuntime = HttpClient.VencordMobileRuntime
        val runtimeReady = isDiscordMainFrame && !HttpClient.vencordDisabled &&
            runtime != null && mobileRuntime != null
        val sb = StringBuilder(text.length + (runtime?.length ?: 0) + (mobileRuntime?.length ?: 0) + 256)
        sb.append(text, 0, headIdx)
        sb.append(disableHighlightTag)
        sb.append("<script>${JsPatches.NETWORK_FIREWALL_JS};${JsPatches.CSP_VIOLATION_REPORTER_JS}</script>")
        if (runtimeReady) {
            sb.append("<script>")
                .append(escapeScriptTagContent(VencordNative.bridgeBootstrapJs()))
                .append("</script>")
                // Env shim must precede the bundle (see VENCORD_PRELUDE_JS).
                .append("<script>").append(JsPatches.VENCORD_PRELUDE_JS).append(';')
                .append(escapedRuntimeOf(runtime!!)).append(';')
                .append(escapedMobileRuntimeOf(mobileRuntime!!)).append(";</script>")
        }
        sb.append(text, headIdx, text.length)
        return sb.toString()
    }

    /**
     * Identity-keyed cache for escapeScriptTagContent(). The runtime strings
     * held by HttpClient are ~1 MB and immutable until replaced, so keying on
     * reference identity avoids re-scanning/re-copying them on every cached
     * main-frame serve. A replaced runtime (clientMod switch, update) gets a
     * new String instance, which misses the cache exactly once.
     */    private class EscapedJs(val raw: String, val escaped: String)

    @Volatile
    private var escapedRuntime: EscapedJs? = null

    @Volatile
    private var escapedMobileRuntime: EscapedJs? = null

    private fun escapedRuntimeOf(raw: String): String {
        escapedRuntime?.let { if (it.raw === raw) return it.escaped }
        val e = EscapedJs(raw, escapeScriptTagContent(raw))
        escapedRuntime = e
        return e.escaped
    }

    private fun escapedMobileRuntimeOf(raw: String): String {
        escapedMobileRuntime?.let { if (it.raw === raw) return it.escaped }
        val e = EscapedJs(raw, escapeScriptTagContent(raw))
        escapedMobileRuntime = e
        return e.escaped
    }

    /**
     * Escapes [s] for safe inlining inside an HTML `<script>` block by replacing
     * `</script` (case-insensitive) with `<\/script`. The HTML parser no longer
     * recognizes the closing tag, while `\/` evaluates to `/` at runtime, so JS
     * semantics are unchanged.
     */    private fun escapeScriptTagContent(s: String): String {
        if (!s.contains("</script", ignoreCase = true)) return s
        val sb = StringBuilder(s.length + 16)
        var i = 0
        val lower = s.lowercase()
        while (i < s.length) {
            val next = lower.indexOf("</script", i)
            if (next < 0) { sb.append(s, i, s.length); break }
            sb.append(s, i, next).append("<\\/script")
            i = next + "</script".length
        }
        return sb.toString()
    }

    /**
     * The single Vencord injection gate: safe mode off and both runtimes in
     * memory. Shared by the network, disk-serve, and [onPageStarted] paths so
     * they agree on readiness. Callers holding local snapshots gate on
     * [HttpClient.vencordDisabled] directly instead (see [injectFirewallAndCss]).
     */
    private fun isMainFrameRuntimeReady(): Boolean =
        !HttpClient.vencordDisabled &&
            HttpClient.VencordRuntime != null &&
            HttpClient.VencordMobileRuntime != null

    /**
     * Finds the real closing `</head>`, skipping matches inside raw-text
     * elements (script, style, textarea, title) or HTML comments, where the
     * literal text may legally appear. Returns -1 when none is found, in
     * which case callers degrade to bridge injection in onPageStarted.
     */
    private fun findHeadCloseIndex(text: String): Int {
        val rawTextOpeners = arrayOf("<script", "<style", "<textarea", "<title")
        val rawTextClosers = arrayOf("</script", "</style", "</textarea", "</title")
        var from = 0
        while (true) {
            val headIdx = text.indexOf("</head>", from, ignoreCase = true)
            if (headIdx < 0) return -1
            var hidden = false
            for (i in rawTextOpeners.indices) {
                val open = text.lastIndexOf(rawTextOpeners[i], headIdx, ignoreCase = true)
                if (open < 0) continue
                val close = text.lastIndexOf(rawTextClosers[i], headIdx, ignoreCase = true)
                if (close < open) { hidden = true; break }
            }
            val commentOpen = text.lastIndexOf("<!--", headIdx)
            val commentClose = text.lastIndexOf("-->", headIdx)
            if (commentOpen > commentClose) hidden = true
            if (!hidden) return headIdx
            from = headIdx + 1
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        val activity = activityRef.get()
        (activity as? com.nin0dev.vendroid.MainActivity)?.let {
            it.currentUrlForBridge = url
            it.currentHostForBridge = Uri.parse(url).host
            it.navigationInProgress = true
        }
        VDELog.i("WV", "Page started: ${UrlNormalizer.redactForLog(url)}")
        VChromeClient.resetPageErrorQuota()

        // If shouldInterceptRequest already embedded the firewall (and possibly
        // the runtimes) into this URL's HTML, skip the evaluateJavascript
        // calls — the embedded scripts run at parse time. Otherwise inject the
        // combined firewall + animation patches in a single IPC call.
        if (!consumeFirewallEmbedded(url)) {
            view.evaluateJavascript(JsPatches.STARTUP_PATCHES_JS, null)
        }

        // If the runtimes weren't embedded (cold start before they were in
        // memory, or a route served without them), inject them now via the bridge.
        if (consumeRuntimeEmbedded(url)) {
            // Already parsed as part of the document.
            return
        }
        view.evaluateJavascript("typeof Vencord!=='undefined'&&typeof VencordMobile!=='undefined'") { result ->
            if (result?.trim() == "true") return@evaluateJavascript
            // Bail if the hosting activity is gone — the WebView may have been
            // destroyed (onDestroy), and calling evaluateJavascript on a
            // destroyed WebView throws IllegalStateException. This callback runs
            // asynchronously, so it can fire after onDestroy despite being
            // scheduled here.
            val activity = activityRef.get() as? com.nin0dev.vendroid.MainActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) return@evaluateJavascript
            // Only inject on Discord pages; the runtimes hook webpack and expose
            // the bridge object, so whitelisted non-Discord pages must not run them.
            val host = Uri.parse(url).host ?: ""
            if (!Constants.isDiscordAppOrigin(host)) return@evaluateJavascript
            val runtime = HttpClient.VencordRuntime
            val mobileRuntime = HttpClient.VencordMobileRuntime
            if (!HttpClient.vencordDisabled && runtime != null && mobileRuntime != null) {
                // Evaluate separately to avoid building a ~1 MB string on the UI
                // thread. The capability-token bootstrap must run first so the
                // token is in scope (closure-captured, not a window global)
                // before the runtimes call the bridge.
                try {
                    view.evaluateJavascript(VencordNative.bridgeBootstrapJs() + ";", null)
                    view.evaluateJavascript(JsPatches.VENCORD_PRELUDE_JS + ";" + runtime + ";", null)
                    view.evaluateJavascript(mobileRuntime + ";", null)
                } catch (_: IllegalStateException) {
                    // WebView was destroyed between the liveness check and these calls.
                }
                VDELog.i("WV", "Runtime injected via bridge for ${UrlNormalizer.redactForLog(url)}")
                activity.scheduleBootVerify("page-start-eval")
            } else if (!HttpClient.vencordDisabled) {
                // Only meaningful while a runtime could still arrive this
                // session; safe mode never publishes one.
                activity.missedInjection = true
            }
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        val activity = activityRef.get()
        (activity as? com.nin0dev.vendroid.MainActivity)?.let {
            it.currentUrlForBridge = url
            it.currentHostForBridge = Uri.parse(url).host
            it.navigationInProgress = false
        }
        VDELog.d("WV", "Page finished: ${UrlNormalizer.redactForLog(url)}")

        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            (activity as? com.nin0dev.vendroid.MainActivity)?.loadingScreen?.scheduleDismiss(500)
        }
        (activity as? com.nin0dev.vendroid.MainActivity)?.scheduleBootVerify("page-finished")
    }

    /**
     * Check if the firewall JS was already embedded in the HTML for [url]
     * by a prior shouldInterceptRequest pass.  Consumes the flag (removes it)
     * so it's only used once per URL.
     */    private fun consumeFirewallEmbedded(url: String): Boolean {
        return firewallEmbeddedUrls.remove(url)
    }

    /**
     * Check if the Vencord runtimes were already embedded into the HTML for
     * [url] by a prior shouldInterceptRequest pass. Consumes the flag so it's
     * only used once per URL. Returns false if they were not embedded (e.g. not
     * in memory at serve time), letting [onPageStarted] fall through to bridge
     * injection.
     */    private fun consumeRuntimeEmbedded(url: String): Boolean {
        return runtimeEmbeddedUrls.remove(url)
    }

    /**
     * Serves a stale-while-revalidate main-frame from the disk cache.
     * On a hit: injects the current firewall + CSS into the cached raw HTML and
     * returns it (no network wait), then refreshes the caches in the background
     * so the next navigation is fresh. Returns null to fall through to a
     * blocking fetch when there is no valid disk entry.
     */
    private fun serveStaleMainFrame(
        req: WebResourceRequest,
        urlString: String,
        responseCacheKey: String
    ): WebResourceResponse? {
        // Prefer the memory-preloaded shell (filled at cold start from the
        // disk cache). Other routes fall through to the per-URL disk entry via
        // the preload, so a /channels request never receives the /app body. We
        // never read from disk here: shouldInterceptRequest runs on the
        // Chromium network thread, and a blocking read would stall the shared
        // worker.
        val cached: MainFrameDiskCache.CachedMainFrame? = inMemoryShell(urlString)
        val shell = cached ?: return null
        val text = try { String(shell.body, Charsets.UTF_8) } catch (_: Exception) { return null }
        val patched = injectFirewallAndCss(text, urlString, isDiscordMainFrame = true)
        // If there was no `</head>` or the runtime wasn't ready (patched ==
        // null), serve the unpatched body and let onPageStarted apply the
        // scripts via the bridge rather than leaving the shell unpatched.
        val bodyBytes = (patched ?: text).toByteArray(Charsets.UTF_8)
        val runtimeEmbedded = patched != null && isMainFrameRuntimeReady()

        // Record that this URL's HTML already has the firewall/runtime
        // embedded so onPageStarted skips re-injecting them. Only claim
        // "embedded" when it actually was — otherwise onPageStarted would skip
        // its evaluateJavascript fallback and the page would run with no JS
        // firewall (fail-open). On overflow, clear so a new navigation is
        // still tracked.
        if (patched != null) {
            if (firewallEmbeddedUrls.size >= MAX_FIREWALL_TRACKED) firewallEmbeddedUrls.clear()
            firewallEmbeddedUrls.add(urlString)
        }
        if (runtimeEmbedded) {
            if (runtimeEmbeddedUrls.size >= MAX_RUNTIME_TRACKED) runtimeEmbeddedUrls.clear()
            runtimeEmbeddedUrls.add(urlString)
            VDELog.i("WV", "Embedded Vencord runtime into stale main frame: ${UrlNormalizer.redactForLog(urlString)}")
        }
        // Background revalidate; skip if one is already in flight for this URL.
        if (revalidatingUrls.add(urlString)) {
            // Build the refresh request ON the intercept thread and snapshot
            // the main-frame flag: `req` is a Chromium-owned
            // WebResourceRequest that is not guaranteed safe to dereference
            // off-thread or after the callback returns.
            val refreshBuilder = try { okHttpRequestBuilder(req, urlString) } catch (_: Exception) { null }
            val refreshIsMainFrame = req.isForMainFrame
            revalidateExecutor.execute {
                var refreshResponse: Response? = null
                try {
                    if (refreshBuilder != null) {
                        refreshResponse = HttpClient.sharedClient.newCall(refreshBuilder.build()).execute()
                        // Redirects are never auto-followed (client config); a 3xx
                        // here is served/ignored as-is for a best-effort refresh.
                        fetchAndProcessResponse(refreshIsMainFrame, refreshResponse, false, CacheTarget.MAIN_FRAME, urlString, responseCacheKey)
                    }
                } catch (_: Exception) {
                    // Best-effort refresh; failure just leaves the cache stale.
                } finally {
                    // Close to return the pooled connection (not disconnect()).
                    refreshResponse?.close()
                    revalidatingUrls.remove(urlString)
                }
            }
        }

        // A stale-served main frame is always HTML (the raw shell). Serve a
        // clean "text/html" MIME; the charset goes in the "encoding" arg, not
        // the MIME. Header reads are case-insensitive because entries
        // persisted by older builds keep the server's wire casing (lowercase
        // on HTTP/2, Title-Case on HTTP/1.1); an exact-case miss here silently
        // dropped CSP/HSTS from stale serves of HTTP/2-fetched shells.
        val ct = "text/html"
        val csp = ResponseHeaderMerge.valueFor(shell.headers, "content-security-policy")
        val cspRo = ResponseHeaderMerge.valueFor(shell.headers, "content-security-policy-report-only")
        val hsts = ResponseHeaderMerge.valueFor(shell.headers, "strict-transport-security")
        return WebResourceResponse(
            ct, "utf-8", 200, shell.reasonPhrase,
            buildStaleHeaders(bodyBytes.size, ct, csp, cspRo, hsts),
            ByteArrayInputStream(bodyBytes)
        )
    }

    /**
     * Rebuilds the response headers for a stale-served shell. Preserves the
     * security headers (CSP/HSTS) stored with the raw HTML and sets an accurate
     * Content-Length for the re-injected body.
     */
    private fun buildStaleHeaders(
        bodySize: Int,
        contentType: String,
        csp: String?,
        cspReportOnly: String?,
        hsts: String?
    ): Map<String, String> {
        val headers = HashMap<String, String>()
        headers["content-type"] = contentType
        headers["content-length"] = bodySize.toString()
        if (csp != null) headers["content-security-policy"] = csp
        if (cspReportOnly != null) headers["content-security-policy-report-only"] = cspReportOnly
        if (hsts != null) headers["strict-transport-security"] = hsts
        return headers
    }

    // OkHttp throws IllegalArgumentException on these reserved (hop-by-hop /
    // session) headers. They are managed by the HTTP client itself and
    // normally not surfaced in getRequestHeaders(), but we drop them
    // defensively.
    private val OKHTTP_RESTRICTED_HEADERS = setOf(
        "host", "content-length", "transfer-encoding", "connection", "keep-alive",
        "proxy-authorization", "te", "trailer", "upgrade"
    )

    // Credential-bearing request headers that must never be forwarded across
    // an origin change, so a cross-origin redirect cannot leak the origin's
    // session/authorization to the target. (proxy-authorization is already in
    // OKHTTP_RESTRICTED_HEADERS.)
    private val CREDENTIAL_HEADERS = setOf(
        "cookie", "authorization", "proxy-authorization", "authentication-info"
    )

    /**
     * Builds an OkHttp [Request.Builder] from a [WebResourceRequest], applying
     * the same header filters as the previous HttpURLConnection path:
     *  - drop OkHttp-reserved (hop-by-hop) headers,
     *  - drop accept-encoding so OkHttp's transparent gzip handling is used
     *    (the app relies on this when rewriting Content-Length/Content-Encoding),
     *  - for theme CSS, also drop the conditional headers (folded into the cache key).
     *
     * Redirects are not handled here; the caller resolves 3xx manually through
     * the allowlist gate (see [resolveRedirects]).
     */
    private fun okHttpRequestBuilder(req: WebResourceRequest, urlString: String): Request.Builder {
        val rb = Request.Builder().url(urlString)
        // GET/HEAD carry no body; use an empty body for other methods so
        // Request.Builder.method() accepts them.
        val body = if (req.method == "GET" || req.method == "HEAD") null else RequestBody.EMPTY
        rb.method(req.method, body)
        if (req.url.path?.endsWith(".css") == true && isVencordCssUrl(req.url)) {
            rb.header("Cache-Control", "no-cache")
            rb.header("Pragma", "no-cache")
        }
        for ((key, value) in req.requestHeaders) {
            val lowerKey = key.lowercase()
            if (lowerKey in OKHTTP_RESTRICTED_HEADERS) continue
            if (lowerKey == "accept-encoding") continue
            if (lowerKey in STRIPPED_CONDITIONAL_HEADERS) continue
            rb.addHeader(key, value)
        }
        return rb
    }

    /**
     * Manually follows an HTTP redirect from the pooled client (which never
     * auto-follows). Each hop is re-validated before a new connection opens:
     *  - main-frame navigations against the Discord-only navigation allowlist
     *    (mirroring [NavigationPolicy.decide]) so a redirect cannot smuggle a
     *    non-Discord page into the top frame;
     *  - subresource fetches against the broader subresource allowlist
     *    ([shouldBlockUri]).
     * All hops must be https and pass the privacy filter. Credential-bearing
     * headers are never forwarded to a different origin.
     *
     * Returns the final (non-redirect) [Response], the 3xx itself when it
     * cannot be followed (no Location, loop, or depth limit), or null when a
     * hop is rejected by the allowlist (caller serves a blocking response).
     *
     * [response] is owned by the caller; this method never closes it. It
     * closes any intermediate responses it allocates while following.
     */
    private fun resolveRedirects(
        req: WebResourceRequest,
        response: Response,
        urlString: String,
        depth: Int,
        seen: MutableSet<String>
    ): Response? {
        if (depth >= 3) return response
        if (response.code !in 300..399) return response
        // Chromium would have applied this hop's Set-Cookie natively. Login
        // flows commonly set session cookies on a 302 before redirecting, so
        // replay each into the cookie store, scoped to the hop's own URL (the
        // store enforces domain/path against it). Idempotent with the
        // final-response delivery in [fetchAndProcessResponse] when the chain
        // ends on this response.
        harvestRedirectCookies(response)
        val location = response.header("Location") ?: return response
        val resolved = response.request.url.resolve(location) ?: return response
        val target = resolved.toString()
        if (!seen.add(target)) return response  // redirect loop guard

        if (resolved.scheme != "https") return null
        val resolvedHost = resolved.host ?: return null  // fail closed on unresolvable host

        // Main-frame redirects are re-validated against the Discord-only
        // navigation allowlist (like a directly-tapped link). Using the broad
        // subresource allowlist here would let a Discord-origin page 3xx to a
        // non-Discord allowlisted host and load it as the top frame in-app,
        // bypassing the popup that forces non-Discord hosts to the browser.
        val isMainFrame = req.isForMainFrame
        if (isMainFrame) {
            if (!Constants.isNavigationAllowedDomain(resolvedHost)) return null
            // Mirror NavigationPolicy: Discord /blog pages route to the popup.
            val path = resolved.encodedPath ?: ""
            if (path == "/blog" || path.startsWith("/blog/")) return null
        } else {
            if (shouldBlockUri(resolved.scheme, resolvedHost, resolved.encodedPath)) return null
        }
        if (shouldBlockForPrivacy(resolvedHost, resolved.encodedPath) != null) return null

        // Never forward credential-bearing headers to a different origin: the
        // original request may carry them, and a cross-origin redirect would
        // leak them to the target (for subresources, potentially an
        // attacker-controllable allowlisted host such as github.io).
        // response.request.url is an OkHttp HttpUrl whose host is non-null,
        // so no null check is needed here.
        val sourceHost = response.request.url.host
        val crossOrigin = sourceHost != resolvedHost
        val rb = Request.Builder().url(target)
        for ((key, value) in response.request.headers) {
            val lowerKey = key.lowercase()
            if (lowerKey in OKHTTP_RESTRICTED_HEADERS) continue
            if (lowerKey == "accept-encoding") continue
            if (lowerKey in STRIPPED_CONDITIONAL_HEADERS) continue
            if (crossOrigin && lowerKey in CREDENTIAL_HEADERS) continue
            rb.addHeader(key, value)
        }
        val follow = HttpClient.sharedClient.newCall(rb.build()).execute()
        return try {
            val next = resolveRedirects(req, follow, urlString, depth + 1, seen)
            if (next !== follow) {
                // Close the consumed intermediate response; keep the deeper one.
                follow.close()
            }
            next
        } catch (e: Exception) {
            follow.close()
            throw e
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        // Scheme + host + privacy gate shared with the Service Worker client.
        shouldBlockForRequest(req)?.let { return it }
        if (!shouldInterceptForCspStripping(req)) return null
        val urlString = req.url.toString()
        val isCss = req.url.path?.endsWith(".css") == true
        val isThemeCss = isCss && isVencordCssUrl(req.url)
        val isMainFrame = req.isForMainFrame
        val responseCacheKey = cacheKey(req, urlString)

        if (isThemeCss) {
            themeCssCache.get(responseCacheKey)?.let { cached ->
                if (System.currentTimeMillis() - cached.fetchedAt < THEME_CSS_TTL_MS) {
                    return WebResourceResponse("text/css", "utf-8", cached.statusCode, cached.reasonPhrase, cached.headers, ByteArrayInputStream(cached.body))
                }
                themeCssCache.remove(responseCacheKey)
            }
        }

        if (isMainFrame) {
            mainFrameCache.get(responseCacheKey)?.let { cached ->
                if (System.currentTimeMillis() - cached.fetchedAt < MAIN_FRAME_TTL_MS) {
                    // The cache stores the RAW body, so inject the firewall /
                    // runtimes at serve time with the current config (same model
                    // as the disk cache). This way a tightened firewall applies
                    // on the very next serve instead of serving a stale embed.
                    val text = String(cached.body, Charsets.UTF_8)
                    val patched = injectFirewallAndCss(text, urlString, isDiscordMainFrame = true)
                    val serveBytes = (patched ?: text).toByteArray(Charsets.UTF_8)
                    if (patched != null) {
                        if (firewallEmbeddedUrls.size >= MAX_FIREWALL_TRACKED) firewallEmbeddedUrls.clear()
                        firewallEmbeddedUrls.add(urlString)
                        if (isMainFrameRuntimeReady()) {
                            if (runtimeEmbeddedUrls.size >= MAX_RUNTIME_TRACKED) runtimeEmbeddedUrls.clear()
                            runtimeEmbeddedUrls.add(urlString)
                        }
                    }
                    // Cached headers are already lowercase (fetch path
                    // canonicalizes); the puts below replace in place rather
                    // than adding a second, mixed-case line.
                    val headers = HashMap(cached.headers)
                    headers["content-type"] = "text/html"
                    headers["content-length"] = serveBytes.size.toString()
                    return WebResourceResponse(
                        "text/html", "utf-8", cached.statusCode, cached.reasonPhrase,
                        headers, ByteArrayInputStream(serveBytes)
                    )
                }
                mainFrameCache.remove(responseCacheKey)
            }

            // Stale-while-revalidate disk cache: on a cold start (empty in-memory
            // cache) serve the persisted raw HTML shell immediately, injecting the
            // current firewall, and refresh the cache in the background so the next
            // navigation is fresh. Only app-shell Discord routes are eligible.
            serveStaleMainFrame(req, urlString, responseCacheKey)?.let { return it }
        }

        var response: Response? = null
        try {
            val rb = okHttpRequestBuilder(req, urlString)
            response = HttpClient.sharedClient.newCall(rb.build()).execute()
            // Redirects are not auto-followed; resolve 3xx manually through the
            // allowlist gate. Null means a hop was rejected → block. Seed the
            // loop guard with the original URL so a self-3xx host doesn't add
            // an extra hop before the loop is detected.
            val resolved = resolveRedirects(req, response, urlString, 0, hashSetOf(urlString))
            if (resolved == null) {
                return blockedResponse()
            }
            if (resolved !== response) { response.close(); response = resolved }
            val cacheTarget = if (isThemeCss) CacheTarget.THEME_CSS else if (isMainFrame) CacheTarget.MAIN_FRAME else null
            val result = fetchAndProcessResponse(req.isForMainFrame, response, isCss, cacheTarget, urlString, responseCacheKey)
            return result
        } catch (e: Exception) {
            VDELog.w("WV", "Fetch failed for ${UrlNormalizer.redactForLog(urlString)}: ${e.javaClass.simpleName}")
            return null
        } finally {
            // Close to return the pooled connection (not disconnect()).
            response?.close()
        }
    }

    /**
     * Redirect hops' Set-Cookie headers never reach Chromium on the app-
     * followed chain (only the final response is served). Replay each into
     * the store; full Set-Cookie strings (HttpOnly/SameSite/Expires) are
     * parsed by the store's own cookie parser. Values that could not occur
     * on the wire (NUL, newline) are dropped so a malformed header cannot
     * poison the store. HSTS from hops has no public setter and is accepted
     * as a gap; the final response's HSTS still applies normally.
     */
    private fun harvestRedirectCookies(response: Response) {
        val hopUrl = response.request.url.toString()
        var applied = 0
        for ((name, value) in response.headers) {
            if (!name.equals("set-cookie", ignoreCase = true)) continue
            if (value.none { it == '\u0000' || it == '\n' }) {
                CookieManager.getInstance().setCookie(hopUrl, value)
                applied++
            }
        }
        if (applied > 0) {
            VDELog.d("WV", "Applied $applied redirect-hop cookie(s) for ${UrlNormalizer.redactForLog(hopUrl)}")
        }
    }

    private fun isVencordCssUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!isForgeHost(host)) return false
        val urlLower = uri.toString().lowercase()
        return urlLower.contains("vencord") || urlLower.contains("equicord") || urlLower.contains("vendroid")
    }

    private val FORGE_HOSTS_EXACT = hashSetOf(
        "github.com", "raw.githubusercontent.com", "codeberg.org",
        "githack.com", "raw.githack.com", "cdn.githack.com", "cbcdn.githack.com"
    )

    private val forgeHostCache = ConcurrentHashMap<String, Boolean>()

    private fun isForgeHost(host: String): Boolean =
        forgeHostCache.computeIfAbsent(host) { h ->
            h in FORGE_HOSTS_EXACT || h.endsWith(".github.io") || h.endsWith(".codeberg.page") || h.endsWith(".githack.com")
        } ?: false

    private fun shouldInterceptForCspStripping(req: WebResourceRequest): Boolean {
        val scheme = req.url.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false

        if (req.url.path?.endsWith(".css") == true) {
            val host = req.url.host ?: return false
            if (isForgeHost(host)) return true
        }

        if (req.isForMainFrame) {
            val host = req.url.host ?: return false
            // Only app-origin main frames are intercepted; CDN/media subdomains
            // never receive injection. Non-GET/HEAD main frames are let through
            // to Chromium (an OkHttp re-issue would drop the body).
            if (Constants.isDiscordAppOrigin(host)) {
                if (req.method == "GET" || req.method == "HEAD") return true
            }
        }

        return false
    }

    private fun stripVencordIncompatibleCsp(cspValue: String): String {
        return cspValue.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { directive ->
                val directiveName = directive.substringBefore(" ").lowercase()
                directiveName in VENCORD_INCOMPATIBLE_CSP_DIRECTIVES
            }
            .joinToString("; ")
            .ifEmpty { "frame-ancestors 'none'; base-uri 'none'; object-src 'none'" }
    }

    /**
     * Strict CSP for Discord main-frame responses. `connect-src` is tightened
     * to block token/message exfiltration to non-allowlisted hosts; the forge
     * hosts in `style-src` keep user themes working.
     *
     * Notes:
     *  - `script-src` needs 'unsafe-inline' + 'unsafe-eval' (webpack + the
     *    injected firewall <script>), so this guards exfil, not XSS.
     *  - `style-src` needs 'unsafe-inline' + data: for injectStyle().
     */
    private fun buildVencordCompatibleCsp(): String =
        "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
            "style-src 'self' 'unsafe-inline' data: " +
            "https://*.githack.com https://cbcdn.githack.com " +
            "https://raw.githubusercontent.com https://cdn.jsdelivr.net " +
            "https://*.github.io https://*.codeberg.page; " +
            "connect-src 'self' https://*.discord.com https://*.discordapp.com " +
            "https://*.discord.media https://*.discordapp.net " +
            "wss://gateway.discord.gg wss://remote-auth-gateway.discord.gg " +
            "https://vde-builds.nin0.dev " +
            "https://badges.vencord.dev https://vendroid.nin0.dev; " +
            "img-src * data: blob:; " +
            "media-src * blob:; " +
            "font-src * data:; " +
            "worker-src 'self' blob:; " +
            "child-src * blob:; " +
            "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

    /** Cache key = URL + method + variant/conditional headers, so a cached
     *  body is only served to an identical request.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun cacheKey(req: WebResourceRequest, urlString: String): String {
        val sb = StringBuilder(urlString)
        sb.append("|m=").append(req.method)
        for (h in VARIANT_HEADERS) {
            // Header names are case-insensitive; the map casing isn't guaranteed.
            val v = req.requestHeaders.entries
                .firstOrNull { it.key.equals(h, ignoreCase = true) }?.value
            if (v != null) sb.append('|').append(h).append('=').append(v)
        }
        return sb.toString()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun fetchAndProcessResponse(
        isForMainFrame: Boolean,
        response: Response,
        isCss: Boolean,
        cacheTarget: CacheTarget? = null,
        urlString: String = "",
        cacheKey: String = urlString
    ): WebResourceResponse {
        // The injection/CSP/MIME gates must be keyed on the FINAL response host
        // (the target after the app-followed redirect chain in
        // resolveRedirects), NOT the original request host. Otherwise a
        // Discord-origin main-frame request that the server redirects to a
        // non-Discord (but allowlisted) host would be treated as a Discord main
        // frame: CSP stripped/replaced and the capability-token bootstrap +
        // runtimes embedded into attacker-controlled content rendered in the
        // Discord origin.
        val host = response.request.url.host ?: ""
        val isDiscordDomain = Constants.isDiscordAppOrigin(host)
        val isMainFrame = isForMainFrame

        val statusCode = response.code

        // Fold duplicate header names (see ResponseHeaderMerge); Set-Cookie
        // values are diverted to setCookies and re-attached below.
        val setCookies = ArrayList<String>(2)
        val modifiedHeaders = HashMap<String, String>(response.headers.size.coerceAtLeast(16))
        for ((key, value) in response.headers) {
            val lowerKey = key.lowercase()
            if (isDiscordDomain && lowerKey == "content-security-policy") {
                if (BuildConfig.ENFORCE_STRICT_CSP) {
                    // Enforce the strict policy; the browser itself blocks
                    // exfiltration (connect-src) to non-allowlisted hosts.
                    modifiedHeaders["content-security-policy"] = buildVencordCompatibleCsp()
                } else {
                    // Report-only: keep the enforced policy loose and attach the
                    // strict policy as Report-Only to collect violations first.
                    val stripped = stripVencordIncompatibleCsp(value)
                    if (stripped.isNotEmpty()) modifiedHeaders["content-security-policy"] = stripped
                    if (isMainFrame) {
                        modifiedHeaders["content-security-policy-report-only"] = buildVencordCompatibleCsp()
                    }
                }
                continue
            }
            if (isDiscordDomain && lowerKey == "content-security-policy-report-only") continue
            ResponseHeaderMerge.merge(modifiedHeaders, setCookies, lowerKey, value)
        }
        if (isCss) modifiedHeaders["content-type"] = "text/css"
        val reasonPhrase = response.message.takeIf { it.isNotEmpty() } ?: "OK"

        // A Discord main frame is always HTML. OkHttp's transparent gzip decode
        // can drop Content-Type and strips Content-Encoding/Content-Length; the
        // body has already been fully read, so those framing headers are stale.
        // Pin a clean MIME: WebResourceResponse's mimeType must be "text/html"
        // (charset goes in the separate "encoding" arg — putting it in the MIME
        // makes WebView render the document as plain text).
        if (isDiscordDomain && isMainFrame && statusCode in 200..299) {
            modifiedHeaders["content-type"] = "text/html"
            modifiedHeaders.remove("content-encoding")
            modifiedHeaders.remove("content-length")
        }
        // Re-read so the served MIME and the injection gate both see "text/html".
        val effectiveContentType = modifiedHeaders.getOrDefault("content-type", "application/octet-stream")

        // OkHttp has no errorStream; byteStream() yields the error page for 4xx/5xx
        // and an empty stream for no-body responses (204/304/HEAD). Read once for
        // all status codes.
        var bodyBytes = try {
            // Read the body through a bounded reader so a compromised/oversized
            // host cannot balloon heap here (mirrors HttpClient.readAsText's
            // cap). Seed the buffer from the declared content length (clamped)
            // so we don't pre-allocate a full 16 MB on every intercepted fetch.
            HttpClient.readAsBytes(
                response.body.byteStream(),
                initialSize = response.body.contentLength()
                    .coerceIn(8192L, HttpClient.MAX_READ_BYTES.toLong()).toInt()
            )
        } catch (_: Exception) {
            // Serving fewer bytes than Content-Length promises makes Chromium
            // wait for bytes that never arrive — drop the framing headers.
            modifiedHeaders.remove("content-length")
            modifiedHeaders.remove("content-encoding")
            ByteArray(0)
        }
        // The RAW body (pre-injection) is what gets cached / persisted, so a
        // tightened firewall applies at serve time (see MAIN_FRAME cache serve).
        val rawBodyBytes = bodyBytes

        // Persist the RAW main-frame HTML (before injection) for stale-while-
        // revalidate on a later cold start. Injection is applied at serve time
        // with the current firewall config, so only app-shell routes qualify.
        if (isDiscordDomain && isMainFrame && statusCode in 200..299 && bodyBytes.isNotEmpty()) {
            val ctLower = (modifiedHeaders.getOrDefault("content-type", "")).lowercase()
            if (ctLower.contains("text/html")) {
                // bodyBytes is still raw here; injection happens below. Persist
                // off the network thread with the sanitized headers so a stale
                // serve matches the in-memory cache (incl. security headers).
                val rawBytes = bodyBytes
                val sanitizedHeaders = HashMap(modifiedHeaders)
                diskCacheExecutor.execute {
                    MainFrameDiskCache.writeMainFrame(urlString, rawBytes, sanitizedHeaders)
                }
            }
        }

        // Inject the JS network firewall into every Discord HTML response. This
        // runs before any page scripts and wraps fetch/XHR/WebSocket for hosts
        // that slip past shouldInterceptRequest. The runtimes are embedded
        // alongside when both are in memory, so the renderer parses them at
        // document time instead of blocking the UI thread on a ~1 MB
        // evaluateJavascript round-trip.
        if (isDiscordDomain && isMainFrame && statusCode in 200..299 && bodyBytes.isNotEmpty()) {
            val ctLower = (modifiedHeaders.getOrDefault("content-type", "")).lowercase()
            if (ctLower.contains("text/html")) {
                try {
                    val text = bodyBytes.toString(Charsets.UTF_8)
                    val runtimeReady = isMainFrameRuntimeReady()
                    val patched = injectFirewallAndCss(text, urlString, isDiscordMainFrame = isDiscordDomain)
                    if (patched != null && patched !== text) {
                        bodyBytes = patched.toByteArray(Charsets.UTF_8)
                        modifiedHeaders["content-length"] = bodyBytes.size.toString()
                        modifiedHeaders.remove("content-encoding")
                        // Record the embedded URL so onPageStarted can skip
                        // re-injection. On overflow, clear so a new navigation
                        // is still tracked (a dropped entry only costs one
                        // idempotent re-injection).
                        if (firewallEmbeddedUrls.size >= MAX_FIREWALL_TRACKED) firewallEmbeddedUrls.clear()
                        firewallEmbeddedUrls.add(urlString)
                        VDELog.i("WV", "Embedded firewall JS: $host (${FirewallConfig.jsAllowedHosts().size} hosts)")
                        if (runtimeReady) {
                            if (runtimeEmbeddedUrls.size >= MAX_RUNTIME_TRACKED) runtimeEmbeddedUrls.clear()
                            runtimeEmbeddedUrls.add(urlString)
                            VDELog.i("WV", "Embedded Vencord runtime into main frame")
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (statusCode in 200..299 && cacheTarget != null) {
            // MAIN_FRAME caches the RAW (pre-injection) body; the firewall and
            // runtimes are injected at serve time so a config change applies on
            // the next serve. THEME_CSS has no injected content, so it caches
            // the final bytes.
            val bytesToCache = if (cacheTarget == CacheTarget.MAIN_FRAME) rawBodyBytes else bodyBytes
            // Set-Cookie never enters modifiedHeaders (diverted to setCookies),
            // so cached headers are cookie-free by construction. A cached
            // replay cannot resurrect an old token (zombie session) or cross
            // accounts on a shared device.
            val headersToCache = HashMap(modifiedHeaders).apply {
                remove("content-length")
                remove("content-encoding")
            }
            val entry = CachedResponse(statusCode, reasonPhrase, headersToCache, bytesToCache)
            when (cacheTarget) {
                CacheTarget.THEME_CSS -> themeCssCache.put(cacheKey, entry)
                CacheTarget.MAIN_FRAME -> {
                    mainFrameCache.put(cacheKey, entry)
                    // Refresh the in-memory preloaded shell so the preferred serve
                    // path doesn't fall back to a stale startup-time copy.
                    if (urlString == "https://discord.com/app") {
                        val refreshed = MainFrameDiskCache.CachedMainFrame(
                            rawBodyBytes, reasonPhrase, headersToCache, System.currentTimeMillis()
                        )
                        val shells = HashMap(preloadedShells)
                        shells[urlString] = refreshed
                        preloadedShells = shells
                    }
                }
            }
        }

        if (setCookies.isNotEmpty()) {
            val responseUrl = response.request.url.toString()
            if (isMultiCookieChannelSupported()) {
                // Chromium M138+ splits the androidx wrapper's NUL-joined value
                // into real Set-Cookie headers, so every cookie survives with
                // full attribute fidelity (HttpOnly, SameSite, Expires).
                try {
                    val compat = WebResourceResponseCompat(
                        effectiveContentType, "utf-8", statusCode, reasonPhrase,
                        modifiedHeaders, ByteArrayInputStream(bodyBytes)
                    )
                    compat.setCookies(setCookies)
                    return compat.toWebResourceResponse()
                } catch (_: Exception) {
                    // Glue failure; fall through to the CookieManager path.
                }
            }
            // Without the multivalue channel the flat map can carry only one
            // Set-Cookie, and comma-joining corrupts Expires dates. Replay
            // each into the cookie store, scoped to the final response URL.
            // setCookie is void, so a store that refuses a cookie logs nothing
            // here; the delivery log below still shows what was attempted.
            for (cookie in setCookies) {
                CookieManager.getInstance().setCookie(responseUrl, cookie)
            }
            VDELog.d("WV", "Delivered ${setCookies.size} Set-Cookie header(s) for ${UrlNormalizer.redactForLog(responseUrl)}")
        }

        return WebResourceResponse(effectiveContentType, "utf-8", statusCode, reasonPhrase, modifiedHeaders, ByteArrayInputStream(bodyBytes))
    }

    /**
     * The multivalue Set-Cookie channel needs Chromium M138+ glue. The
     * provider's COOKIE_INTERCEPT feature report is ground truth; OEM
     * WebViews vary and version strings are unreliable. Support is fixed for
     * the process lifetime once WebView is loaded, so the check is cached.
     */
    @Volatile
    private var multiCookieChannel: Boolean? = null

    private fun isMultiCookieChannelSupported(): Boolean {
        multiCookieChannel?.let { return it }
        val supported = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT)
        } catch (_: Exception) {
            false
        }
        multiCookieChannel = supported
        return supported
    }

    companion object {
        // Theme CSS is public, changes rarely, and is fetched from forge hosts.
        // A longer TTL avoids a blocking network-thread refetch on every
        // navigation while still picking up theme edits within a session.
        private const val THEME_CSS_TTL_MS = 300_000L
        private const val MAIN_FRAME_TTL_MS = 300_000L
        private val STRIPPED_CONDITIONAL_HEADERS = setOf(
            "if-none-match", "if-modified-since", "if-unmodified-since", "if-match"
        )
        // Headers that select a different response body, folded into the cache key.
        private val VARIANT_HEADERS = setOf(
            "if-none-match", "if-modified-since", "accept-encoding", "accept"
        )
        private val VENCORD_INCOMPATIBLE_CSP_DIRECTIVES = hashSetOf(
            "default-src", "script-src", "script-src-elem", "script-src-attr",
            "style-src", "style-src-elem", "style-src-attr",
            "connect-src", "img-src", "font-src", "media-src",
            "worker-src", "manifest-src", "child-src"
        )

        // Forge hosts are only trusted to serve user-theme CSS. The instance
        // method [isForgeHost] handles CSP-stripping; this companion copy lets the
        // shared network gate (in the companion object) enforce the CSS-only rule.
        private val COMPANION_FORGE_HOSTS_EXACT = hashSetOf(
            "github.com", "raw.githubusercontent.com", "codeberg.org",
            "githack.com", "raw.githack.com", "cdn.githack.com", "cbcdn.githack.com"
        )
        private val companionForgeHostCache = ConcurrentHashMap<String, Boolean>()
        private fun isForgeHostCompanion(host: String): Boolean =
            companionForgeHostCache.computeIfAbsent(host) { h ->
                h in COMPANION_FORGE_HOSTS_EXACT ||
                    h.endsWith(".github.io") ||
                    h.endsWith(".codeberg.page") ||
                    h.endsWith(".githack.com")
            } ?: false

        // Thread-safe LRU cache wrapping LruCache because
        // shouldInterceptRequest runs on Chromium network threads and can be
        // invoked concurrently.
        private class ThreadSafeLruCache(maxSize: Int) {
            private val cache = object : LruCache<String, CachedResponse>(maxSize) {
                override fun sizeOf(key: String, value: CachedResponse): Int = value.body.size
            }
            @Synchronized fun get(key: String): CachedResponse? = cache.get(key)
            @Synchronized fun put(key: String, value: CachedResponse): CachedResponse? = cache.put(key, value)
            @Synchronized fun remove(key: String): CachedResponse? = cache.remove(key)
        }

        private val themeCssCache = ThreadSafeLruCache(2 * 1024 * 1024)
        private val mainFrameCache = ThreadSafeLruCache(2 * 1024 * 1024)

        // Track main-frame URLs that already have the network firewall JS
        // embedded in the HTML (from shouldInterceptRequest / fetchAndProcessResponse).
        // When onPageStarted fires for one of these, the combined
        // firewall+animation evaluateJavascript can be skipped entirely,
        // saving one IPC round-trip.  Capped at 16 entries to bound memory.
        private val firewallEmbeddedUrls = ConcurrentHashMap.newKeySet<String>()
        private const val MAX_FIREWALL_TRACKED = 16

        // Track main-frame URLs that already have the Vencord runtimes embedded
        // in the HTML. Mirrors firewallEmbeddedUrls so onPageStarted can skip
        // the (up to ~1 MB) evaluateJavascript round-trip for those URLs.
        private val runtimeEmbeddedUrls = ConcurrentHashMap.newKeySet<String>()
        private const val MAX_RUNTIME_TRACKED = 16

        // Privacy filter — blocks Discord telemetry, Sentry, fingerprinting,
        // and (optionally) typing indicators at the path level. Shared by
        // VWebviewClient.shouldInterceptRequest and the Service Worker client
        // in MainActivity so SW-fetched requests can't bypass it.

        @Volatile
        private var blockTypingIndicator = false

        private val SENTRY_PATTERN = Regex("^/assets/sentry\\.[^/]+\\.js$")

        /** Update the typing indicator block. Called at startup and on toggle. */
        fun updateTypingBlock(block: Boolean) {
            blockTypingIndicator = block
        }

        /**
         * Returns a blocking [WebResourceResponse] if the request matches a
         * privacy filter, or null to allow. Always blocks telemetry (/science,
         * /track), Sentry SDK, and fingerprinting (/api.js, /cdn-cgi/). Blocks
         * typing indicators only if [blockTypingIndicator] is true.
         *
         * A fresh [WebResourceResponse] is allocated per call to match the
         * existing host-block pattern and avoid relying on Chromium
         * stream-reuse semantics.
         */
        fun shouldBlockForPrivacy(host: String?, path: String?, lowerHost: String? = host?.lowercase()): WebResourceResponse? {
            if (host == null || path.isNullOrEmpty()) return null
            if (!Constants.isDiscordDomainLower(lowerHost ?: return null)) return null
            return when {
                path.endsWith("/science") || path.endsWith("/track") -> {
                    VDELog.d("WV", "Blocked telemetry: $path")
                    blockedResponse()
                }
                path.endsWith("/api.js") || path.startsWith("/cdn-cgi/") -> {
                    VDELog.d("WV", "Blocked fingerprinting: $path")
                    blockedResponse()
                }
                SENTRY_PATTERN.matches(path) -> {
                    VDELog.d("WV", "Blocked Sentry SDK: $path")
                    blockedResponse()
                }
                blockTypingIndicator && path.endsWith("/typing") -> {
                    VDELog.d("WV", "Blocked typing indicator: $path")
                    blockedResponse()
                }
                else -> null
            }
        }
        /** Blocking response: 200 + empty text/plain body. A 204 can let
         *  Chromium serve a cached copy; a 200 with mismatched MIME makes the
         *  resource a no-op (no script execution, no CSS application). */
        private fun blockedResponse(): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

        /**
         * Shared scheme + host + privacy interception gate used by both the
         * WebView client's [shouldInterceptRequest] and the Service Worker
         * client (in MainActivity), so the two enforcement paths cannot drift.
         *
         * Restricts to browser/inline schemes (data:, blob: allowed for
         * embedded media/images), blocks `http` (MITM risk), blocks any host
         * outside the domain allowlist, and applies the privacy path filter.
         * Returns a blocking response if the request must be denied, or null
         * to allow it through to the fetch/CSP path.
         */
        @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.N)
        fun shouldBlockForRequest(request: android.webkit.WebResourceRequest): WebResourceResponse? {
            val scheme = request.url.scheme
            // data:/blob: have a null host and would bypass the domain
            // allowlist. Allow only inert media/font/CSS data: payloads; block
            // executable data: payloads that could run script in the page
            // origin. The bridge capability token neutralizes any data:
            // subframe that does load, so this is defense in depth.
            //
            // NOTE: data:image/ is kept broad (not restricted to png/jpeg/etc.)
            // because as a SUBRESOURCE (<img>), data:image/svg+xml does NOT
            // execute scripts — Discord relies on it for avatars/icons. The
            // SVG-script-execution risk applies only in navigation/subframe
            // contexts, handled by shouldOverrideUrlLoading.
            if (scheme == "data") {
                // Allocation-free prefix checks: data: payloads (avatars, inline
                // media, fonts) can be hundreds of KB, and this runs per request
                // on the Chromium network thread, so avoid lowercasing a copy.
                val url = request.url.toString()
                val isInert = url.startsWith("data:image/", ignoreCase = true) ||
                    url.startsWith("data:font/", ignoreCase = true) ||
                    url.startsWith("data:application/font", ignoreCase = true) ||
                    url.startsWith("data:text/css", ignoreCase = true) ||
                    url.startsWith("data:application/octet-stream", ignoreCase = true) // some fonts use this
                if (!isInert) {
                    return blockedResponse()
                }
                // Inert data: payloads are allowed; no host gate applies.
                return shouldBlockForPrivacy(null, null)
            }

            val host = request.url.host
            val lowerHost = host?.lowercase()
            if (shouldBlockUri(scheme, host, request.url.path, lowerHost)) {
                return blockedResponse()
            }
            // Privacy: block Discord telemetry, Sentry, and fingerprinting before
            // the CSP-stripping/fetch path so they never reach the network.
            return shouldBlockForPrivacy(host, request.url.path, lowerHost)
        }

        /**
         * The core scheme + host + forge-host-CSS-only gate, shared by both the
         * direct request path and the redirect re-validation path so they cannot
         * drift. Returns true when the URL must be blocked.
         *
         * Note: unlike [shouldBlockForRequest] this does NOT apply the privacy
         * path filter (callers with a host/path apply it separately), and does
         * NOT handle data: payloads (the direct path does; redirect Location
         * targets are never data:).
         */
        private fun shouldBlockUri(scheme: String?, host: String?, path: String?, lowerHost: String? = host?.lowercase()): Boolean {
            // data:, file:, intent:, and custom schemes have a null host and
            // would otherwise bypass the domain allowlist below. blob: is
            // needed for Discord media.
            if (scheme != "https" && scheme != "http" && scheme != "blob") {
                return true
            }

            if (host != null && (lowerHost == null || !Constants.isAllowedDomainLower(lowerHost))) {
                // Block non-whitelisted subresources (scripts, images, XHR, media, etc.)
                return true
            }
            if (scheme == "http") {
                return true
            }
            // Forge hosts are only trusted for user-theme CSS. Block all other
            // subresources so attacker content on those hosts can't exfiltrate.
            //
            // Note: the CSS itself isn't inert — attribute-selector + url()
            // exfiltration to a .css endpoint on an allowlisted forge host is
            // possible. Accepted trust trade-off of remote-theme support.
            if (lowerHost != null && isForgeHostCompanion(lowerHost)) {
                val isCss = path?.endsWith(".css") == true
                if (!isCss) return true
            }
            return false
        }

        // Bounded executor for SWR background refetches; per-URL dedup avoids
        // redundant refreshes during rapid navigation.
        private val revalidateExecutor: java.util.concurrent.Executor =
            java.util.concurrent.Executors.newSingleThreadExecutor()
        private val revalidatingUrls = ConcurrentHashMap.newKeySet<String>()

        // Dedicated single-thread executor for persisting raw main-frame HTML to
        // the disk cache off the network thread, avoiding a fresh Thread per write
        // and never competing with the SWR executor.
        private val diskCacheExecutor: java.util.concurrent.Executor =
            java.util.concurrent.Executors.newSingleThreadExecutor()

        // Preloaded raw shells for every URL persisted last session (keyed by URL).
        // Populated off-thread at cold start so shouldInterceptRequest never has
        // to touch disk on the Chromium network thread. Written off-thread, read
        // on the network thread → guarded by @Volatile (map replaced atomically).
        @Volatile
        private var preloadedShells: Map<String, MainFrameDiskCache.CachedMainFrame> = emptyMap()

        /** Preloads all cached shells into memory. Called off-thread at cold start. */
        fun preloadMainFrameCache() {
            val shells = HashMap<String, MainFrameDiskCache.CachedMainFrame>()
            for (url in MainFrameDiskCache.preloadableUrls()) {
                val cached = MainFrameDiskCache.readMainFrame(url) ?: continue
                shells[url] = cached
            }
            preloadedShells = shells
        }

        /** Looks up a shell from the in-memory preload, or null. */
        private fun inMemoryShell(urlString: String): MainFrameDiskCache.CachedMainFrame? {
            val shells = preloadedShells
            val entry = shells[urlString]
            if (entry == null || System.currentTimeMillis() - entry.fetchedAt > MainFrameDiskCache.MAX_AGE_MS) {
                return null
            }
            return entry
        }
    }
}
