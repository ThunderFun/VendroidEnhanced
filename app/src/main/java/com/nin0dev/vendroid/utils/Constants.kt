package com.nin0dev.vendroid.utils

object Constants {
    const val JS_BUNDLE_URL = "https://github.com/VendroidEnhanced/plugin/releases/download/vencord/browser.js"
    const val EQUICORD_BUNDLE_URL = "https://github.com/VendroidEnhanced/plugin/releases/download/equicord/browser.js"

    fun isDiscordDomain(host: String): Boolean =
        host == "discord.com" || host.endsWith(".discord.com") ||
                host == "discordapp.com" || host.endsWith(".discordapp.com")
}
