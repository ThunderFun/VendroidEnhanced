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
            h.endsWith(".github.io") || h.endsWith(".codeberg.page") ||
            h == "cdn.jsdelivr.net" || h.endsWith(".cdn.jsdelivr.net") ||
            h == "jsdelivr.net" || h.endsWith(".jsdelivr.net")
        } ?: false

    fun isDiscordDomain(host: String): Boolean =
        domainCache.computeIfAbsent(host) { h ->
            h == "discord.com" || h.endsWith(".discord.com") ||
                    h == "discordapp.com" || h.endsWith(".discordapp.com")
        } ?: false

    fun isAllowedVencordHost(host: String): Boolean =
        vencordHostCache.computeIfAbsent(host) { h ->
            h in VENCORD_ALLOWED_HOSTS || h.endsWith(".githubusercontent.com")
                    || h.endsWith(".github.io") || h.endsWith(".codeberg.page")
        } ?: false
}
