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

    /**
     * Environment prelude that must run BEFORE the Vencord bundle on every
     * injection path (HTML embed and evaluateJavascript).
     *
     * The bundle's ScreenShare plugin executes `navigator.mediaDevices
     * .getDisplayMedia.bind(...)` at init time; getDisplayMedia does not exist
     * on Android Chromium, so the TypeError aborts the bundle's single
     * synchronous IIFE before window.Vencord is assigned. A rejecting stub
     * satisfies the eager `.bind`; the plugin's start() replaces it with its
     * own modal-based implementation afterwards.
     *
     * Also installs an in-memory localStorage fallback when window.localStorage
     * is unusable (the bundle destructures it once at line 46, so a later
     * recovery cannot help). Engage state is logged so device reports show it.
     *
     * Idempotent (__vdeEnvShim guard). Must contain no "</script" / "<!--"
     * because callers concatenate it raw inside <script> tags.
     */
    const val VENCORD_PRELUDE_JS: String =
        "(function(){" +
            "'use strict';" +
            "if(window.__vdeEnvShim)return;" +
            "window.__vdeEnvShim=1;" +
            // Boot-probe snapshot (boot0): storage health at injection time,
            // before any page script ran.
            "try{window.__vdeLsBoot=typeof window.localStorage}catch(_){window.__vdeLsBoot='throws'}" +
            // Android Chromium lacks getDisplayMedia (desktop-only)
            "try{" +
                "if(!window.navigator.mediaDevices)" +
                    "Object.defineProperty(window.navigator,'mediaDevices',{value:{},configurable:true});" +
                "if(typeof window.navigator.mediaDevices.getDisplayMedia!=='function'){" +
                    "var rej=function(){return Promise.reject(new DOMException('Screen sharing is not supported in Vendroid','NotSupportedError'));};" +
                    "Object.defineProperty(window.navigator.mediaDevices,'getDisplayMedia',{value:rej,configurable:true,writable:true});" +
                "}" +
            "}catch(e){console.warn('[Vendroid] env shim mediaDevices failed:',e)}" +
            // In-memory fallback only when storage looks broken
            "try{" +
                "var ls=null;try{ls=window.localStorage}catch(_){ls=undefined}" +
                "if(!ls||typeof ls.getItem!=='function'||typeof ls.setItem!=='function'){" +
                    "window.__vdeLsShim=1;" +
                    "var mem={};" +
                    "var store={getItem:function(k){return Object.prototype.hasOwnProperty.call(mem,k)?mem[k]:null;}," +
                        "setItem:function(k,v){k=String(k);mem[k]=String(v);try{var n=0;for(var q in mem)n++;if(n>128)delete mem[Object.keys(mem)[0]]}catch(_){}}," +
                        "removeItem:function(k){delete mem[k];}," +
                        "key:function(i){var ks=Object.keys(mem);return i<ks.length?ks[i]:null;}," +
                        "clear:function(){mem={};}};" +
                    "Object.defineProperty(store,'length',{get:function(){return Object.keys(mem).length}});" +
                    "try{Object.defineProperty(window,'localStorage',{value:store,configurable:true,writable:true});" +
                        "console.warn('[Vendroid] window.localStorage unavailable; installed in-memory fallback')}catch(e2){" +
                        "console.warn('[Vendroid] window.localStorage unavailable and could not be shimmed:',e2)}" +
                "}" +
                // Storage healthy: re-publish localStorage as a
                // non-configurable own accessor so page JS can no longer
                // delete or redefine it. A later probe reporting
                // ls=undefined|own=n then points below the JS layer, i.e.
                // engine-side invalidation.
                "else{" +
                    "try{var real=ls;" +
                        "Object.defineProperty(window,'localStorage',{configurable:false,enumerable:true," +
                            "get:function(){return real;}," +
                            "set:function(v){console.warn('[Vendroid] localStorage overwritten by page code');}});" +
                        "window.__vdeLsWatch=1;" +
                    "}catch(e){window.__vdeLsWatch='failed:'+e.message}" +
                "}" +
            "}catch(e){}" +
        "})()"
}
