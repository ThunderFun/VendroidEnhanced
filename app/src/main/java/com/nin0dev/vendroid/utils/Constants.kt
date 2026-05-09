package com.nin0dev.vendroid.utils

import java.util.concurrent.ConcurrentHashMap

object Constants {
    const val JS_BUNDLE_URL = "https://vde-builds.nin0.dev/vencord/browser.js"
    const val EQUICORD_BUNDLE_URL = "https://vde-builds.nin0.dev/equicord/browser.js"

    private val VENCORD_ALLOWED_HOSTS = hashSetOf(
        "github.com", "raw.githubusercontent.com", "gist.githubusercontent.com",
        "codeload.github.com", "codeberg.org",
        "git.nin0.dev", "vde-builds.nin0.dev"
    )

    private val domainCache = ConcurrentHashMap<String, Boolean>()
    private val vencordHostCache = ConcurrentHashMap<String, Boolean>()
    private val allowedDomainCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Regex-based whitelist of domains the WebView is permitted to load.
     *
     * Covers Discord's own domains, Vencord/Equicord update sources,
     * captcha providers, and CSS/asset CDNs.
     */
    fun isAllowedDomain(host: String): Boolean =
        allowedDomainCache.computeIfAbsent(host) { h ->
            h == "discord.com" || h.endsWith(".discord.com") ||
            h == "discordapp.com" || h.endsWith(".discordapp.com") ||
            h == "discord.gg" || h.endsWith(".discord.gg") ||
            h == "discord.media" || h.endsWith(".discord.media") ||
            h == "discordapp.net" || h.endsWith(".discordapp.net") ||
            h == "storage.googleapis.com" || h.endsWith(".storage.googleapis.com") ||
            h == "github.com" || h.endsWith(".github.com") ||
            h == "githubusercontent.com" || h.endsWith(".githubusercontent.com") ||
            h == "hcaptcha.com" || h.endsWith(".hcaptcha.com") ||
            h == "discordsays.com" || h.endsWith(".discordsays.com") ||
            h == "vencord.dev" || h.endsWith(".vencord.dev") ||
            h == "codeberg.org" || h.endsWith(".codeberg.org") ||
            h == "git.nin0.dev" || h.endsWith(".git.nin0.dev") ||
            h == "vde-builds.nin0.dev" || h.endsWith(".vde-builds.nin0.dev") ||
            h.endsWith(".github.io") || h.endsWith(".codeberg.page")
        } ?: false

    fun isDiscordDomain(host: String): Boolean =
        domainCache.computeIfAbsent(host) { h ->
            h == "discord.com" || h.endsWith(".discord.com") ||
                    h == "discordapp.com" || h.endsWith(".discordapp.com")
        } ?: false

    /**
     * Compact JS that wraps all JavaScript network APIs and blocks calls to
     * non-whitelisted hosts.  Also blocks navigator.serviceWorker.register
     * and unregisters any existing SWs.
     */
    val NETWORK_FIREWALL_JS: String =
        "(function(){" +
        "'use strict';" +
        "if(window.__vendroidFw===1)return;" +
        "window.__vendroidFw=1;" +
        "var a=['discord.com','.discord.com','discordapp.com','.discordapp.com','discord.gg','.discord.gg','discord.media','.discord.media','discordapp.net','.discordapp.net','storage.googleapis.com','.storage.googleapis.com','github.com','.github.com','githubusercontent.com','.githubusercontent.com','hcaptcha.com','.hcaptcha.com','discordsays.com','.discordsays.com','vencord.dev','.vencord.dev','codeberg.org','.codeberg.org','git.nin0.dev','.git.nin0.dev','vde-builds.nin0.dev','.vde-builds.nin0.dev'];" +
        "function ok(u){try{var h=new URL(u).host;}catch(e){return false;}for(var i=0;i<a.length;i++)if(h===a[i]||h.endsWith(a[i]))return true;return false;}" +
        "var of=window.fetch;window.fetch=function(u,o){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked fetch: '+u);return Promise.reject(new TypeError('Blocked by Vendroid firewall'));}return of.apply(this,arguments);};" +
        "var oxo=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked XHR: '+u);throw new TypeError('Blocked by Vendroid firewall');}return oxo.apply(this,arguments);};" +
        "var ow=window.WebSocket;window.WebSocket=function(u,p){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked WS: '+u);throw new TypeError('Blocked by Vendroid firewall');}return new ow(u,p);};Object.setPrototypeOf(window.WebSocket,ow);window.WebSocket.prototype=ow.prototype;" +
        "if('serviceWorker'in navigator){navigator.serviceWorker.register=function(u,o){console.warn('[Vendroid] Blocked SW: '+u);return Promise.reject(new TypeError('Blocked by Vendroid firewall'));};navigator.serviceWorker.getRegistrations&&navigator.serviceWorker.getRegistrations().then(function(r){r.forEach(function(s){s.unregister();});}).catch(function(){});}" +
        "if(window.EventSource){var oes=window.EventSource;window.EventSource=function(u,o){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked ES: '+u);throw new TypeError('Blocked by Vendroid firewall');}return new oes(u,o);};}" +
        "if(window.Worker){var owr=window.Worker;window.Worker=function(u,o){if(typeof u==='string'&&!ok(u)){console.warn('[Vendroid] Blocked Worker: '+u);throw new TypeError('Blocked by Vendroid firewall');}return new owr(u,o);};}" +
        "})()"

    fun isAllowedVencordHost(host: String): Boolean =
        vencordHostCache.computeIfAbsent(host) { h ->
            h in VENCORD_ALLOWED_HOSTS || h.endsWith(".githubusercontent.com")
                    || h.endsWith(".github.io") || h.endsWith(".codeberg.page")
        } ?: false

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
}
