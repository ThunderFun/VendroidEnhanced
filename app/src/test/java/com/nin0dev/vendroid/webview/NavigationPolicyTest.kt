package com.nin0dev.vendroid.webview

import com.nin0dev.vendroid.utils.Constants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationPolicyTest {

    // ------------------------------------------------------------------
    //  isNavigationAllowedDomain
    // ------------------------------------------------------------------

    @Test fun navAllowed_discordApex() {
        assertTrue(Constants.isNavigationAllowedDomain("discord.com"))
    }

    @Test fun navAllowed_discordSubdomain() {
        assertTrue(Constants.isNavigationAllowedDomain("canary.discord.com"))
        assertTrue(Constants.isNavigationAllowedDomain("ptb.discordapp.com"))
    }

    @Test fun navAllowed_discordapp() {
        assertTrue(Constants.isNavigationAllowedDomain("discordapp.com"))
    }

    @Test fun navAllowed_discordGg() {
        assertTrue(Constants.isNavigationAllowedDomain("discord.gg"))
    }

    @Test fun navAllowed_discordMedia() {
        assertTrue(Constants.isNavigationAllowedDomain("discord.media"))
    }

    @Test fun navAllowed_discordappNet() {
        assertTrue(Constants.isNavigationAllowedDomain("discordapp.net"))
        assertTrue(Constants.isNavigationAllowedDomain("cdn.discordapp.net"))
    }

    @Test fun navAllowed_discordsays() {
        assertTrue(Constants.isNavigationAllowedDomain("discordsays.com"))
    }

    @Test fun navAllowed_watchanimeattheoffice() {
        assertTrue(Constants.isNavigationAllowedDomain("watchanimeattheoffice.com"))
    }

    @Test fun navAllowed_lookalikeRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("evildiscord.com"))
        assertFalse(Constants.isNavigationAllowedDomain("discord.com.evil.com"))
    }

    @Test fun navAllowed_githubRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("github.com"))
    }

    @Test fun navAllowed_rawGithubRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("raw.githubusercontent.com"))
    }

    @Test fun navAllowed_hcaptchaRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("hcaptcha.com"))
    }

    @Test fun navAllowed_googleStorageRejected() {
        assertFalse(Constants.isNavigationAllowedDomain("storage.googleapis.com"))
    }

    @Test fun navAllowed_emptyRejected() {
        assertFalse(Constants.isNavigationAllowedDomain(""))
    }

    // ------------------------------------------------------------------
    //  NavigationPolicy.decide — main frame
    // ------------------------------------------------------------------

    @Test fun decide_mainFrameDiscord_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("https", "discord.com", isForMainFrame = true)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_mainFrameDiscordSubdomain_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("https", "canary.discord.com", isForMainFrame = true)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_mainFrameGithub_showsPopup() {
        assertTrue(
            NavigationPolicy.decide("https", "github.com", isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    @Test fun decide_mainFrameUnknownHost_showsPopup() {
        assertTrue(
            NavigationPolicy.decide("https", "evil.com", isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    @Test fun decide_mainFrameNullHost_showsPopup() {
        assertTrue(
            NavigationPolicy.decide("https", null, isForMainFrame = true)
                == NavigationPolicy.Action.SHOW_POPUP
        )
    }

    @Test fun decide_aboutScheme_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("about", null, isForMainFrame = true)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    // ------------------------------------------------------------------
    //  NavigationPolicy.decide — subframe (iframe)
    // ------------------------------------------------------------------

    @Test fun decide_subframeGithub_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("https", "github.com", isForMainFrame = false)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_subframeYoutube_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("https", "youtube.com", isForMainFrame = false)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_subframeHcaptcha_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("https", "hcaptcha.com", isForMainFrame = false)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_subframeUnknownHost_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("https", "evil.com", isForMainFrame = false)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }

    @Test fun decide_subframeNullHost_loadsInWebview() {
        assertTrue(
            NavigationPolicy.decide("https", null, isForMainFrame = false)
                == NavigationPolicy.Action.LOAD_IN_WEBVIEW
        )
    }
}