package com.nin0dev.vendroid.utils

/**
 * JavaScript patches injected into the WebView at runtime.
 * Kept separate from [Constants] to preserve domain-rule cohesion.
 */
object JsPatches {
    /**
     * Compact JS that wraps all JavaScript network APIs and blocks calls to
     * non-whitelisted hosts. Service Workers are **allowed** to register so
     * Discord can cache assets locally; SW fetch events are still intercepted
     * at the Android layer by ServiceWorkerClientCompat.
     *
     * The allowlist array is built from [FirewallConfig] at injection time,
     * so config edits take effect on the next page load without a restart.
     * The built string is cached and only rebuilt when the firewall config
     * changes via [invalidateCache].
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
            // already restricts chars to [a-z0-9.-], so this is defense in depth.
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
            "function ok(u){try{var h=new URL(u).host;}catch(e){return false;}for(var i=0;i<a.length;i++){var e=a[i];if(h===e||h.endsWith(e))return true;if(e.charCodeAt(0)===46&&h===e.slice(1))return true;}return false;}" +
            "var of=window.fetch;window.fetch=function(u,o){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked fetch: '+u);return Promise.reject(new TypeError('Blocked by Vendroid firewall'));}return of.apply(this,arguments);};" +
            "var oxo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked XHR: '+u);throw new TypeError('Blocked by Vendroid firewall');}return oxo.apply(this,arguments);};" +
            "var ow=window.WebSocket;window.WebSocket=function(u,p){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked WS: '+u);throw new TypeError('Blocked by Vendroid firewall');}return new ow(u,p);};Object.setPrototypeOf(window.WebSocket,ow);window.WebSocket.prototype=ow.prototype;" +
            "if(window.EventSource){var oes=window.EventSource;window.EventSource=function(u,o){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked ES: '+u);throw new TypeError('Blocked by Vendroid firewall');}return new oes(u,o);};}" +
            "if(window.Worker){var owr=window.Worker;window.Worker=function(u,o){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked Worker: '+u);throw new TypeError('Blocked by Vendroid firewall');}return new owr(u,o);};}" +
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
     * Combined firewall + animation patches for onPageStarted injection.
     * Single evaluateJavascript call instead of two, saving one IPC
     * round-trip per navigation. Both patches have their own idempotency
     * guards (__vendroidFw, __vendroidAnimCtrl) so re-running is safe.
     */
    val STARTUP_PATCHES_JS: String
        get() = NETWORK_FIREWALL_JS + ";" + ANIMATION_PATCH_JS
}
