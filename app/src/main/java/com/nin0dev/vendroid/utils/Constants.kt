package com.nin0dev.vendroid.utils

import java.util.concurrent.ConcurrentHashMap

object Constants {
    /**
     * The Vencord/Equicord bundle and prefetched CSS are fetched over HTTPS
     * from vde-builds.nin0.dev and raw.githubusercontent.com/VendroidEnhanced/...
     * without SRI/signature verification, so updates need no per-build signing.
     * Compromise of either host injects arbitrary code into every Discord
     * session; the operator is the root of trust.
     */
    const val JS_BUNDLE_URL = "https://vde-builds.nin0.dev/vencord/browser.js"
    const val EQUICORD_BUNDLE_URL = "https://vde-builds.nin0.dev/equicord/browser.js"

    // The bundle is arbitrary code executed in the Discord origin; it may only
    // be fetched from the operator-controlled build host.
    // User/attacker-controlled hosts (github, gists, github.io, codeberg) are
    // never permitted, even for a custom bundle URL.
    private val VENCORD_ALLOWED_HOSTS = hashSetOf(
        "vde-builds.nin0.dev"
    )

    private val domainCache = BoundedHostCache()
    private val appOriginCache = BoundedHostCache()
    private val vencordHostCache = BoundedHostCache()
    private val allowedDomainCache = BoundedHostCache()
    private val navigationDomainCache = BoundedHostCache()

    /**
     * Thread-safe boolean cache with a size cap. On overflow the backing map
     * is swapped for a fresh one rather than evicting entries; the working set
     * is well under the cap, so wholesale replacement is cheaper than LRU.
     */
    private class BoundedHostCache(private val cap: Int = DEFAULT_CAP) {
        @Volatile private var map: ConcurrentHashMap<String, Boolean> = ConcurrentHashMap()

        fun computeIfAbsent(key: String, loader: (String) -> Boolean): Boolean {
            var m = map
            val existing = m[key]
            if (existing != null) return existing
            val v = loader(key)
            // Racy on overflow; a few extra entries may slip in before the swap.
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

    // Main-frame navigation allowlist: app origins plus hosts that navigate
    // in-app (discord.gg invites, Discord Activity hosts). Excludes raw-content
    // CDN hosts (cdn.discordapp.com, media.discordapp.net) — those render as a
    // bare media document with no in-app back path, so they route to the popup.
    // Subresource loads (e.g. <img>) are governed separately by the firewall.
    fun isNavigationAllowedDomain(host: String): Boolean {
        val h = host.lowercase()
        return navigationDomainCache.computeIfAbsent(h) { key ->
            // Discord app origins (incl. ptb/canary), apex discordapp.com only.
            key == "discord.com" || key == "ptb.discord.com" || key == "canary.discord.com" ||
                key == "discordapp.com" ||
                // Invite links → redirect into the app.
                key == "discord.gg" || key.endsWith(".discord.gg") ||
                // Discord Activities (embedded games).
                key == "discordsays.com" || key.endsWith(".discordsays.com") ||
                key == "watchanimeattheoffice.com" || key.endsWith(".watchanimeattheoffice.com")
        }
    }

    /**
     * WebView domain allowlist, backed by [FirewallConfig]. Entries use
     * leading-dot form (".example.com") so apex/subdomain matches do not
     * falsely match lookalikes such as "evildiscord.com".
     */
    fun isAllowedDomain(host: String): Boolean {
        // The allowlist reads FirewallConfig's snapshot, which exists only
        // after FirewallConfig.init() has run. Enforce the boot-order contract
        // in debug so a new call path that skips init fails loudly instead of
        // silently blocking every host (fail-closed).
        if (com.nin0dev.vendroid.BuildConfig.DEBUG && !FirewallConfig.isInitialized()) {
            android.util.Log.e("Vendroid", "isAllowedDomain() called before FirewallConfig.init(); returning false")
        }
        return isAllowedDomainLower(host.lowercase())
    }

    /** [isAllowedDomain] for callers that already hold a lowercased host. */
    fun isAllowedDomainLower(h: String): Boolean =
        allowedDomainCache.computeIfAbsent(h) { key ->
            for (entry in FirewallConfig.allowedHosts()) {
                // ".domain.tld": substring(1) matches the apex, endsWith matches subdomains.
                if (key == entry.substring(1) || key.endsWith(entry)) return@computeIfAbsent true
            }
            false
        }

    fun isDiscordDomain(host: String): Boolean = isDiscordDomainLower(host.lowercase())

    /** [isDiscordDomain] for callers that already hold a lowercased host. */
    fun isDiscordDomainLower(h: String): Boolean =
        domainCache.computeIfAbsent(h) { key ->
            key == "discord.com" || key.endsWith(".discord.com") ||
                    key == "discordapp.com" || key.endsWith(".discordapp.com")
        }

    /**
     * True only for the Discord web-app origins — the hosts that serve the web
     * client and legitimately receive the bridge runtimes and capability token.
     * Deliberately narrower than [isDiscordDomain]: subdomains such as
     * cdn.discordapp.com / media.discordapp.net are Discord-owned but must
     * never receive the injected runtimes/bridge token, as they serve
     * attacker-uploaded content.
     */
    fun isDiscordAppOrigin(host: String): Boolean = isDiscordAppOriginLower(host.lowercase())

    /** [isDiscordAppOrigin] for callers that already hold a lowercased host. */
    fun isDiscordAppOriginLower(h: String): Boolean =
        appOriginCache.computeIfAbsent(h) { key ->
            key == "discord.com" ||
                key == "ptb.discord.com" ||
                key == "canary.discord.com" ||
                key == "discordapp.com"
        }

    fun isAllowedVencordHost(host: String): Boolean {
        val h = host.lowercase()
        return vencordHostCache.computeIfAbsent(h) { key ->
            key in VENCORD_ALLOWED_HOSTS
        }
    }

    /** Clears all per-host caches to keep them bounded over long sessions, and
     *  invalidates the cached JS firewall string in [JsPatches]. */
    fun invalidateFirewallCaches() {
        allowedDomainCache.clear()
        domainCache.clear()
        appOriginCache.clear()
        vencordHostCache.clear()
        navigationDomainCache.clear()
        JsPatches.invalidateCache()
    }
}
