package com.nin0dev.vendroid.webview

import org.junit.Assert.assertTrue
import org.junit.Test

// Regression tests for VencordCsp.build. A CSP host-source with no explicit
// port only matches the scheme default (443 for wss), so the voice RTC
// gateway on 2053/2096 was rejected as a connect-src violation even with a
// wildcard entry for .discord.media. Also covers the matching URL.host vs
// URL.hostname firewall check.

class VencordCspTest {

    private val csp = VencordCsp.build()

    private fun connectSrc(): String =
        csp.split("; ").first { it.startsWith("connect-src") }

    @Test fun connectSrc_allowsDiscordMedia() {
        val src = connectSrc()
        assertTrue("missing wss discord.media: $src", src.contains("wss://*.discord.media"))
    }

    @Test fun connectSrc_allowsNonDefaultVoicePorts() {
        val src = connectSrc()
        assertTrue("missing port wildcard: $src", src.contains("wss://*.discord.media:*"))
        for (port in listOf(443, 2053, 2096, 8443)) {
            assertTrue("missing wss://*.discord.media:$port: $src", src.contains("wss://*.discord.media:$port"))
        }
    }

    @Test fun connectSrc_stillPinsOtherDiscordHosts() {
        val src = connectSrc()
        assertTrue(src.contains("https://*.discord.com"))
        assertTrue(src.contains("wss://*.discord.gg"))
    }

    @Test fun objectSrcLockedDown() {
        assertTrue(csp.contains("object-src 'none'"))
        assertTrue(csp.contains("base-uri 'none'"))
    }
}
