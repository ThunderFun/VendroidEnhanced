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
     * WebView domain allowlist. Backed by [FirewallConfig]. Entries use
     * leading-dot form (".example.com") so apex and subdomain matches do
     * not falsely match lookalikes such as "evildiscord.com".
     */
    fun isAllowedDomain(host: String): Boolean =
        allowedDomainCache.computeIfAbsent(host) { h ->
            for (entry in FirewallConfig.allowedHosts()) {
                // ".domain.tld": substring(1) matches the apex, endsWith matches subdomains.
                if (h == entry.substring(1) || h.endsWith(entry)) return@computeIfAbsent true
            }
            false
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

    /** Clears the per-host allowlist cache after a config change. Discord
     *  core and Vencord update host caches are not user-editable, so they
     *  are preserved. */
    fun invalidateFirewallCaches() {
        allowedDomainCache.clear()
    }
}
