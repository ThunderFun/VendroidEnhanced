package com.nin0dev.vendroid.utils

/**
 * JavaScript patches injected into the WebView at runtime.
 * Kept separate from [Constants] to preserve domain-rule cohesion.
 */
object JsPatches {
    /**
     * Compact JS wrapping the JavaScript network APIs (fetch, XHR, WebSocket,
     * EventSource, Worker, sendBeacon) and blocking calls to non-whitelisted
     * hosts. Service Workers are allowed so Discord can cache assets locally;
     * SW fetch events are still intercepted at the Android layer by
     * ServiceWorkerClientCompat.
     *
     * Best-effort defense-in-depth: it does not wrap RTCPeerConnection (WebRTC
     * bypasses it), and the idempotency markers (__vendroidFw etc.) are writable
     * window globals, so earlier script can neuter it. The native
     * shouldInterceptRequest / Service Worker gate is the real boundary.
     *
     * The allowlist is built from [FirewallConfig] at injection time, so config
     * edits apply on the next page load. The built string is cached and rebuilt
     * only when the firewall config changes via [invalidateCache].
     */
    @Volatile
    private var cachedNetworkFirewallJs: String? = null

    val NETWORK_FIREWALL_JS: String
        get() = cachedNetworkFirewallJs ?: buildNetworkFirewallJs().also { cachedNetworkFirewallJs = it }

    /** Invalidates the cached firewall JS string. Called from
     *  [Constants.invalidateFirewallCaches] when the firewall config changes. */
    fun invalidateCache() {
        cachedNetworkFirewallJs = null
    }

    /** Builds the JS firewall string from the current [FirewallConfig] snapshot. */
    fun buildNetworkFirewallJs(): String {
        val hosts = FirewallConfig.jsAllowedHosts()
        val sb = StringBuilder(hosts.size * 30)
        sb.append('[')
        var first = true
        for (h in hosts) {
            if (!first) sb.append(',')
            first = false
            sb.append('\'')
            // Escape backslash first, then single quote. normalizeDomain()
            // already restricts chars to [a-z0-9.-]; this is defense in depth.
            sb.append(h.replace("\\", "\\\\").replace("'", "\\'"))
            sb.append('\'')
        }
        sb.append(']')
        val arr = sb.toString()
        return "(function(){" +
            "'use strict';" +
            "if(window.__vendroidFw===1)return;" +
            "window.__vendroidFw=1;" +
            "var a=$arr;" +
            "function ok(h){for(var i=0;i<a.length;i++){var e=a[i];if(h===e||h.endsWith(e))return true;if(e.charCodeAt(0)===46&&h===e.slice(1))return true;}return false;}" +
            // Normalize string/Request/URL args so the allowlist can't be bypassed.
            "function g(u){if(typeof u==='string')return u;try{if(u&&u.url&&(u instanceof Request||u instanceof URL))return u.url;}catch(e){}return null;}" +
            "function bad(u){var s=g(u);if(s===null)return false;try{return !ok(new URL(s).host);}catch(e){return false;}}" +
            // Redact query/fragment so token-bearing URLs never reach the shareable log.
            "function redact(u){if(!u)return u;try{var q=u.indexOf('?'),f=u.indexOf('#'),e=q<0?f:(f<0?q:Math.min(q,f));return e<0?u:u.slice(0,e)+'[...]';}catch(e){return u;}}" +
            "var of=window.fetch;window.fetch=function(u,o){if(bad(u)){console.warn('[Vendroid] Blocked fetch: '+redact(g(u)));return Promise.reject(new TypeError('Blocked by Vendroid firewall'));}return of.apply(this,arguments);};" +
            "var oxo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){if(bad(u)){console.warn('[Vendroid] Blocked XHR: '+redact(g(u)));throw new TypeError('Blocked by Vendroid firewall');}return oxo.apply(this,arguments);};" +
            "var ow=window.WebSocket;window.WebSocket=function(u,p){if(bad(u)){console.warn('[Vendroid] Blocked WS: '+redact(g(u)));throw new TypeError('Blocked by Vendroid firewall');}return new ow(u,p);};Object.setPrototypeOf(window.WebSocket,ow);window.WebSocket.prototype=ow.prototype;" +
            "if(window.EventSource){var oes=window.EventSource;window.EventSource=function(u,o){if(bad(u)){console.warn('[Vendroid] Blocked ES: '+redact(g(u)));throw new TypeError('Blocked by Vendroid firewall');}return new oes(u,o);};}" +
            "if(window.Worker){var owr=window.Worker;window.Worker=function(u,o){if(bad(u)){console.warn('[Vendroid] Blocked Worker: '+redact(g(u)));throw new TypeError('Blocked by Vendroid firewall');}return new owr(u,o);};}" +
            // Wrap sendBeacon too (was silently unwrapped); bind() preserves `this`.
            "if(navigator&&navigator.sendBeacon){var osb=navigator.sendBeacon.bind(navigator);navigator.sendBeacon=function(u,d){if(bad(u)){console.warn('[Vendroid] Blocked Beacon: '+redact(g(u)));return false;}return osb(u,d);};}" +
            "})()"
    }

    const val ANIMATION_PATCH_JS: String =
        "(function(){" +
        "'use strict';" +
        "if(window.__vendroidAnimCtrl)return;" +
        "window.__vendroidAnimCtrl=1;" +
        "window.__vendroidPauseAnimations=function(){" +
        "var a=document.getAnimations;a&&a.call(document).forEach(function(x){if(x.playState==='running'){x.pause();x.__vendroidPaused=1;}});" +
        "};" +
        "window.__vendroidResumeAnimations=function(){" +
        "var a=document.getAnimations;a&&a.call(document).forEach(function(x){if(x.__vendroidPaused){x.play();delete x.__vendroidPaused;}});" +
        "};" +
        "window.__vendroidSetVisibility=function(v){" +
        "var h=v==='hidden';" +
        "try{if(document.hidden===h)return;}catch(e){}" +
        "try{Object.defineProperty(document,'visibilityState',{get:function(){return v;},configurable:true});}catch(e){try{document.visibilityState=v;}catch(e2){}}" +
        "try{Object.defineProperty(document,'hidden',{get:function(){return h;},configurable:true});}catch(e){try{document.hidden=h;}catch(e2){}}" +
        "document.dispatchEvent(new Event('visibilitychange'));" +
        "};" +
        "})()"

    /**
     * Report-only CSP violation logger. Emits a [Vendroid] console line (routed
     * to VDELog by VChromeClient) for each SecurityPolicyViolationEvent, so the
     * report-only policy's allowlist can be validated before enforcement.
     */
    const val CSP_VIOLATION_REPORTER_JS: String =
        "(function(){" +
        "'use strict';" +
        "if(window.__vendroidCspReporter)return;" +
        "window.__vendroidCspReporter=1;" +
        // Truncate + strip query strings so blockedURI/sourceFile never leak
        // token-bearing URLs into the persistent, shareable VDELog.
        "var r=function(s){return String(s).replace(/[?].*$/,'').slice(0,120);};" +
        "var l=function(e){console.warn('[Vendroid] CSP violation directive='+(e.effectiveDirective||'')+" +
        " ' blocked='+r(e.blockedURI||'')+' source='+r(e.sourceFile||'')+' line='+(e.lineNumber||0));};" +
        "document.addEventListener('securitypolicyviolation',l);" +
        "})()"

    /**
     * Combined firewall + animation patches for onPageStarted injection. Uses a
     * single evaluateJavascript call instead of two, saving one IPC round-trip
     * per navigation. Each patch has its own idempotency guard
     * (__vendroidFw, __vendroidAnimCtrl, __vendroidCspReporter), so re-running
     * is safe.
     */
    val STARTUP_PATCHES_JS: String
        get() = NETWORK_FIREWALL_JS + ";" + ANIMATION_PATCH_JS + ";" + CSP_VIOLATION_REPORTER_JS
}
