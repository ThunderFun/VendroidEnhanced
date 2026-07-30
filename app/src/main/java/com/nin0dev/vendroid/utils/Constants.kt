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

    private val domainCache = BoundedHostCache()
    private val vencordHostCache = BoundedHostCache()
    private val allowedDomainCache = BoundedHostCache()
    private val navigationDomainCache = BoundedHostCache()

    /**
     * Thread-safe boolean cache with a size cap. On overflow the backing map
     * is swapped for a fresh one rather than evicting individual entries;
     * the working set is well under the cap, so wholesale replacement is
     * cheaper than LRU bookkeeping.
     */
    private class BoundedHostCache(private val cap: Int = DEFAULT_CAP) {
        @Volatile private var map: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

        fun computeIfAbsent(key: String, loader: (String) -> Boolean): Boolean {
            var m = map
            val existing = m[key]
            if (existing != null) return existing
            val v = loader(key)
            // Racy on overflow; at worst a few extra entries slip in before
            // the swap, which is acceptable.
            if (m.size >= cap) {
                m = ConcurrentHashMap()
                map = m
            }
            m.putIfAbsent(key, v)
            return v
        }

        fun clear() { map = ConcurrentHashMap() }

        companion object {
            private const val DEFAULT_CAP = 2048
        }
    }

    // Hosts allowed to load as top-level pages in the WebView. A strict subset
    // of the firewall allowlist — only Discord-owned domains. Mirrors the
    // locked DISCORD category in FirewallConfig. Kept hardcoded (not driven
    // by FirewallConfig) so it cannot be widened by config edits.
    private val NAVIGATION_ALLOWED_HOSTS = hashSetOf(
        "discord.com", "discordapp.com", "discord.gg",
        "discord.media", "discordapp.net",
        "discordsays.com", "watchanimeattheoffice.com"
    )

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

    /** Returns true if [host] may load as a top-level page in the WebView.
     *  Only Discord-owned domains are permitted; all other hosts route to
     *  the link popup. This is independent of the firewall allowlist, which
     *  governs subresource fetches and remains broader. */
    fun isNavigationAllowedDomain(host: String): Boolean =
        navigationDomainCache.computeIfAbsent(host) { h ->
            NAVIGATION_ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
        } ?: false

    /** Clears all per-host caches, keeping them bounded over long sessions.
     *  Also invalidates the cached JS firewall string in [JsPatches]. */
    fun invalidateFirewallCaches() {
        allowedDomainCache.clear()
        domainCache.clear()
        vencordHostCache.clear()
        navigationDomainCache.clear()
        JsPatches.invalidateCache()
    }
}
