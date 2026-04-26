package com.nin0dev.vendroid.webview

import android.app.Activity
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.nin0dev.vendroid.utils.DomainWhitelist
import com.nin0dev.vendroid.utils.Logger
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

class VWebviewClient(
    context: Context,
    private val onPageLoadedCallback: (() -> Unit)? = null
) : WebViewClient() {
    private val appContext: Context = context.applicationContext
    private val activityRef: WeakReference<Activity> = if (context is Activity) WeakReference(context) else WeakReference(null)

    @Volatile
    var vencordInjected = false
        internal set

    @Volatile
    var pageFinished = false
        private set

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if ("about:blank" == url.toString()) return false
        if (DomainWhitelist.isAllowed(url.host)) return false
        val intent = Intent(Intent.ACTION_VIEW, url)
        try {
            view.context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(appContext, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        if (req.isForMainFrame) {
            try {
                return doFetchMainFrame(req)
            } catch (e: Exception) {
                Logger.e("[VWebviewClient] doFetchMainFrame failed for ${req.url}", e)
            }
        } else if (req.url.path?.endsWith(".css") == true) {
            try {
                return doFetchCss(req)
            } catch (e: Exception) {
                Logger.e("[VWebviewClient] doFetchCss failed for ${req.url}", e)
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doFetchMainFrame(req: WebResourceRequest): WebResourceResponse {
        val url = req.url.toString()
        Logger.d("[VWebviewClient] doFetchMainFrame: intercepting $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = req.method
        conn.setRequestProperty("Accept-Encoding", "identity")
        for ((key, value) in req.requestHeaders) {
            if (key.equals("Accept-Encoding", ignoreCase = true)) continue
            conn.setRequestProperty(key, value)
        }
        val code = conn.responseCode
        val msg = conn.responseMessage
        Logger.d("[VWebviewClient] HTTP $code for $url")
        val htmlBytes = conn.inputStream.readBytes()
        var html = String(htmlBytes, Charsets.UTF_8)
        Logger.d("[VWebviewClient] Downloaded HTML: ${html.length} chars")

        val vencord = HttpClient.VencordRuntime
        val mobile = HttpClient.VencordMobileRuntime
        if (vencord != null) {
            val scriptTag = StringBuilder("<script>")
            scriptTag.append(vencord.replace("</script>", "<\\/script>"))
            if (mobile != null) {
                scriptTag.append(mobile.replace("</script>", "<\\/script>"))
            }
            scriptTag.append("</script>")
            val injectPoint = html.indexOf("<head>")
            if (injectPoint >= 0) {
                html = html.substring(0, injectPoint + 6) + scriptTag + html.substring(injectPoint + 6)
                Logger.i("[VWebviewClient] Injected Vencord after <head> (${scriptTag.length} chars)")
            } else {
                html = scriptTag.toString() + html
                Logger.i("[VWebviewClient] No <head> found, prepended Vencord (${scriptTag.length} chars)")
            }
            vencordInjected = true
            Logger.i("[VWebviewClient] vencordInjected = true, modified HTML: ${html.length} chars")
        } else {
            Logger.w("[VWebviewClient] VencordRuntime is null, skipping injection!")
        }

        val modifiedHeaders = HashMap<String, String>()
        for ((key, valueList) in conn.headerFields) {
            if (key == null) continue
            if ("Content-Security-Policy".equals(key, ignoreCase = true)) continue
            if ("Content-Length".equals(key, ignoreCase = true)) continue
            if ("Content-Encoding".equals(key, ignoreCase = true)) continue
            if ("Transfer-Encoding".equals(key, ignoreCase = true)) continue
            if (valueList.isNotEmpty()) {
                modifiedHeaders[key] = valueList[0]
            }
        }
        modifiedHeaders["Content-Length"] = html.toByteArray(Charsets.UTF_8).size.toString()
        val contentType = modifiedHeaders.getOrDefault("Content-Type", "text/html")
        val bodyStream: InputStream = ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        return WebResourceResponse(contentType, "utf-8", code, msg, modifiedHeaders, bodyStream)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun doFetchCss(req: WebResourceRequest): WebResourceResponse {
        val url = req.url.toString()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = req.method
        conn.setRequestProperty("Accept-Encoding", "identity")
        for ((key, value) in req.requestHeaders) {
            if (key.equals("Accept-Encoding", ignoreCase = true)) continue
            conn.setRequestProperty(key, value)
        }
        val code = conn.responseCode
        val msg = conn.responseMessage
        val cssBytes = conn.inputStream.readBytes()
        val modifiedHeaders = HashMap<String, String>()
        for ((key, valueList) in conn.headerFields) {
            if (key == null) continue
            if ("Content-Security-Policy".equals(key, ignoreCase = true)) continue
            if ("Content-Length".equals(key, ignoreCase = true)) continue
            if ("Content-Encoding".equals(key, ignoreCase = true)) continue
            if ("Transfer-Encoding".equals(key, ignoreCase = true)) continue
            if (valueList.isNotEmpty()) {
                modifiedHeaders[key] = valueList[0]
            }
        }
        modifiedHeaders["Content-Type"] = "text/css"
        modifiedHeaders["Content-Length"] = cssBytes.size.toString()
        return WebResourceResponse("text/css", "utf-8", code, msg, modifiedHeaders, ByteArrayInputStream(cssBytes))
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageFinished = false
        Logger.d("[VWebviewClient] onPageStarted: $url, vencordInjected=$vencordInjected")
        if (vencordInjected) return
        Logger.i("[VWebviewClient] Fallback injection: vencordInjected was false")
        try {
            val vencord = HttpClient.VencordRuntime
            val mobile = HttpClient.VencordMobileRuntime
            if (vencord != null) {
                view.evaluateJavascript(vencord, null)
                if (mobile != null) {
                    view.evaluateJavascript(mobile, null)
                }
                vencordInjected = true
                Logger.i("[VWebviewClient] Fallback injection succeeded")
            } else {
                Logger.w("[VWebviewClient] Fallback injection: VencordRuntime is null!")
            }
        } catch (e: Exception) {
            Logger.e("[VWebviewClient] Fallback injection failed", e)
            Toast.makeText(appContext, "Couldn't load Vencord, try restarting the app.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        pageFinished = true
        Logger.d("[VWebviewClient] onPageFinished: $url, vencordInjected=$vencordInjected")
        view.settings.cacheMode = WebSettings.LOAD_DEFAULT

        if (vencordInjected) {
            view.evaluateJavascript("""
                (function() {
                    var results = [];
                    results.push('typeof Vencord: ' + typeof Vencord);
                    if (typeof Vencord !== 'undefined') {
                        results.push('Vencord.Webpack: ' + typeof Vencord.Webpack);
                        results.push('Vencord.Plugins: ' + typeof Vencord.Plugins);
                        if (Vencord.Webpack) {
                            var c = Vencord.Webpack.cache;
                            results.push('Webpack.cache type: ' + typeof c + ' keys: ' + (c ? Object.keys(c).length : 'null'));
                            results.push('Webpack.search type: ' + typeof Vencord.Webpack.search);
                            results.push('Webpack.findLazy type: ' + typeof Vencord.Webpack.findLazy);
                            var wpKeys = Object.keys(Vencord.Webpack).slice(0, 20);
                            results.push('Webpack keys: ' + wpKeys.join(','));
                            results.push('Common keys: ' + Object.keys(Vencord.Webpack.Common || {}).slice(0, 15).join(','));
                        }
                    }
                    var chunkArr = window.webpackChunkdiscord_app;
                    results.push('webpackChunkdiscord_app: ' + (chunkArr ? 'exists len=' + chunkArr.length : 'MISSING'));
                    var globals = Object.keys(window).filter(function(k) { return k.indexOf('webpack') !== -1 || k.indexOf('Webpack') !== -1 || k.indexOf('chunk') !== -1; });
                    results.push('Webpack-related globals: ' + globals.join(','));
                    results.push('typeof VencordMobile: ' + typeof VencordMobile);
                    results.push('typeof VencordMobileNative: ' + typeof VencordMobileNative);
                    return results.join(' | ');
                })()
            """.trimIndent()) { result ->
                Logger.i("[VWebviewClient] Vencord immediate diag: $result")
            }

            view.postDelayed({
                view.evaluateJavascript("""
                    (function() {
                        try {
                            var results = [];
                            results.push('chunk arr: ' + (window.webpackChunkdiscord_app ? 'len=' + window.webpackChunkdiscord_app.length : 'MISSING'));
                            var wpRelated = Object.keys(window).filter(function(k) { return k.indexOf('webpack') !== -1 || k.indexOf('chunk') !== -1 || k.indexOf('Chunk') !== -1; });
                            results.push('wp globals: ' + wpRelated.join(','));
                            if (typeof Vencord !== 'undefined') {
                                results.push('cache keys: ' + Object.keys(Vencord.Webpack?.cache || {}).length);
                                results.push('Common FD: ' + !!(Vencord.Webpack?.Common?.FluxDispatcher));
                                results.push('wreq: ' + typeof Vencord.Webpack?.wreq);
                            }
                            return results.join(' | ');
                        } catch(e) { return 'ERR: ' + e.message; }
                    })()
                """.trimIndent()) { result ->
                    Logger.i("[VWebviewClient] Quick wp probe (1s): $result")
                }
            }, 1000)

            view.postDelayed({
                view.evaluateJavascript("""
                    (function() {
                        try {
                            var results = [];
                            if (typeof Vencord !== 'undefined') {
                                var cache = Vencord.Webpack?.cache || {};
                                results.push('Webpack.cache: ' + Object.keys(cache).length + ' modules');
                                results.push('Webpack.Common.FD: ' + !!(Vencord.Webpack?.Common?.FluxDispatcher));
                                results.push('Common keys: ' + Object.keys(Vencord.Webpack?.Common || {}).join(','));

                                var chunkArr = window.webpackChunkdiscord_app;
                                if (chunkArr) {
                                    results.push('Chunk arr len: ' + chunkArr.length);
                                    var origPush = chunkArr.push;
                                    var isPatched = origPush.toString().substring(0, 200);
                                    results.push('push fn: ' + isPatched.substring(0, 120));
                                } else {
                                    results.push('Chunk arr MISSING');
                                }

                                try {
                                    results.push('wreq type: ' + typeof Vencord.Webpack?.wreq);
                                    if (Vencord.Webpack?.wreq) {
                                        var m = Vencord.Webpack.wreq.m || Vencord.Webpack.wreq.__webpack_modules__;
                                        results.push('wreq.m keys: ' + (m ? Object.keys(m).length : 'null'));
                                    }
                                } catch(e) { results.push('wreq err: ' + e.message); }

                                try {
                                    var fd2 = Vencord.Webpack.findLazy ? Vencord.Webpack.findLazy(function(m) { return m?.dispatch && m?.subscribe; }) : null;
                                    results.push('findLazy FD: ' + (fd2 ? 'FOUND' : 'not found'));
                                } catch(e) { results.push('findLazy err: ' + e.message); }
                            }
                            return results.join('\\n');
                        } catch(e) { return 'ERROR: ' + e.message; }
                    })()
                """.trimIndent()) { result ->
                    Logger.i("[VWebviewClient] Vencord webpack diag (3s):\n$result")
                }
            }, 3000)

            view.postDelayed({
                view.evaluateJavascript("""
                    (function() {
                        try {
                            var results = [];
                            if (typeof Vencord !== 'undefined' && Vencord.Plugins) {
                                var pluginErrors = [];
                                Object.values(Vencord.Plugins.plugins).forEach(function(p) {
                                    if (p.started && p.startOutcome === 'error') {
                                        pluginErrors.push(p.name);
                                    }
                                });
                                results.push('Plugins with start errors: ' + (pluginErrors.length ? pluginErrors.join(', ') : 'none'));

                                var patchStats = [];
                                Object.values(Vencord.Plugins.plugins).forEach(function(p) {
                                    (p.patches || []).forEach(function(patch) {
                                        if (patch.all?.length) {
                                            var failed = patch.all.filter(function(r) { return r.error; });
                                            if (failed.length) {
                                                patchStats.push(p.name + ': ' + failed.length + '/' + patch.all.length + ' failed');
                                            }
                                        }
                                    });
                                });
                                results.push('Patch failures: ' + (patchStats.length ? patchStats.join('; ') : 'none'));
                            }
                            return results.join('\\n');
                        } catch(e) { return 'ERROR: ' + e.message; }
                    })()
                """.trimIndent()) { result ->
                    Logger.i("[VWebviewClient] Vencord patch diag (6s):\n$result")
                }
            }, 6000)

            view.postDelayed({
                view.evaluateJavascript("""
                    (function() {
                        try {
                            if (typeof Vencord === 'undefined' || !Vencord.Plugins || !Vencord.Plugins.plugins) {
                                return 'SKIP: Vencord.Plugins not available';
                            }
                            var plugins = Vencord.Plugins.plugins;
                            var total = Object.keys(plugins).length;
                            var enabled = Object.values(plugins).filter(function(p) { return Vencord.Plugins.isPluginEnabled(p.name); }).length;
                            var disabled = Object.values(plugins).filter(function(p) {
                                return (p.required || p.enabledByDefault) && !Vencord.Plugins.isPluginEnabled(p.name);
                            });
                            var log = 'Native recovery: ' + enabled + '/' + total + ' enabled, ' + disabled.length + ' required/default disabled';
                            if (disabled.length > 0) {
                                var enabledNames = [];
                                disabled.forEach(function(p) {
                                    try {
                                        if (!Vencord.Settings.plugins[p.name]) {
                                            Vencord.Settings.plugins[p.name] = { enabled: true };
                                        } else {
                                            Vencord.Settings.plugins[p.name].enabled = true;
                                        }
                                        if (!Vencord.Plugins.pluginRequiresRestart(p) && !p.started) {
                                            try { Vencord.Plugins.startDependenciesRecursive(p); } catch(e) {}
                                            Vencord.Plugins.startPlugin(p);
                                        }
                                        enabledNames.push(p.name);
                                    } catch(e) {
                                        log += ' | FAIL:' + p.name + ':' + e.message;
                                    }
                                });
                                var newEnabled = Object.values(plugins).filter(function(p) { return Vencord.Plugins.isPluginEnabled(p.name); }).length;
                                log += ' | After: ' + newEnabled + '/' + total + ' enabled';
                                log += ' | Re-enabled: ' + enabledNames.join(', ');
                            }
                            return log;
                        } catch(e) { return 'Native recovery ERROR: ' + e.message; }
                    })()
                """.trimIndent()) { result ->
                    Logger.i("[VWebviewClient] Native plugin recovery (8s):\n$result")
                }
            }, 8000)

            view.postDelayed({
                view.evaluateJavascript("""
                    (function() {
                        try {
                            var results = [];
                            results.push('URL: ' + window.location.pathname);
                            var settingsPlugin = Vencord.Plugins?.plugins?.Settings;
                            if (settingsPlugin) {
                                results.push('Settings: enabled=' + Vencord.Plugins.isPluginEnabled('Settings') + ' started=' + settingsPlugin.started + ' startAt=' + settingsPlugin.startAt);
                                var patchDetails = (settingsPlugin.patches || []).map(function(p, i) {
                                    var hasAll = !!(p.all && p.all.length);
                                    var findStr = p.find || 'none';
                                    var matchStr = p.match ? p.match.toString().substring(0, 60) : 'no-match';
                                    return i + ':applied=' + hasAll + ':find=' + findStr + ':match=' + matchStr;
                                });
                                results.push('Patches: ' + patchDetails.join('; '));
                            }
                            var vencordEls = document.querySelectorAll('[class*="vencord"], [class*="Vencord"], [class*="vcd"]');
                            results.push('Vencord DOM els: ' + vencordEls.length);
                            var bodyText = document.body?.innerText?.substring(0, 2000) || '';
                            var hasVencord = bodyText.indexOf('Vencord') !== -1;
                            results.push('Vencord in body: ' + hasVencord);

                            var c = Vencord.Webpack?.cache;
                            results.push('Webpack.cache: ' + (c === undefined ? 'undefined' : (c === null ? 'null' : Object.keys(c).length + ' keys')));
                            results.push('Common.FD: ' + !!(Vencord.Webpack?.Common?.FluxDispatcher));
                            var chunkArr = window.webpackChunkdiscord_app;
                            results.push('Chunk arr: ' + (chunkArr ? 'len=' + chunkArr.length : 'MISSING'));

                            try {
                                results.push('wreq: ' + typeof Vencord.Webpack?.wreq);
                                results.push('_initWebpack src: ' + (Vencord.Webpack?._initWebpack?.toString()?.substring(0, 200) || 'N/A'));
                                results.push('factoryListeners: ' + (Vencord.Webpack?.factoryListeners?.length || 0));
                                results.push('filters: ' + (Vencord.Webpack?.filters?.length || 0));
                                results.push('_blacklistBadModules: ' + typeof Vencord.Webpack?._blacklistBadModules);
                                var appliedCount = 0;
                                var totalPatchCount = 0;
                                Object.values(Vencord.Plugins?.plugins || {}).forEach(function(p) {
                                    (p.patches || []).forEach(function(patch) {
                                        totalPatchCount++;
                                        if (patch.all?.length) appliedCount++;
                                    });
                                });
                                results.push('Total patches: ' + totalPatchCount + ' applied: ' + appliedCount);
                            } catch(e) { results.push('detail err: ' + e.message); }

                            try {
                                var fd = Vencord.Webpack.findLazy(function(m) { return m?.dispatch && m?.subscribe; });
                                results.push('findLazy FD: ' + (fd ? 'FOUND' : 'not found'));
                            } catch(e) { results.push('findLazy err: ' + e.message); }

                            return results.join('\\n');
                        } catch(e) { return 'DOM diag ERROR: ' + e.message; }
                    })()
                """.trimIndent()) { result ->
                    Logger.i("[VWebviewClient] Full diag (10s):\n$result")
                }
            }, 10000)
        }

        val activity = activityRef.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            (activity as? com.nin0dev.vendroid.MainActivity)?.scheduleLoadingScreenDismiss(3000)
        }
        super.onPageFinished(view, url)
        onPageLoadedCallback?.invoke()
    }
}
