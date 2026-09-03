package com.nin0dev.vendroid.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the download-time prunes of the eq.js settings tree; see
 * HttpClient.vencordRuntimePatches for why the three keys are pruned.
 *
 * Each prune is an exact match on pristine upstream text. An upstream
 * re-bundle that shifts the text makes applyPatches log "Patch matched
 * nothing" and the dead rows reappear in the UI. The fixture tests pin the
 * mechanics; the snapshot test pins the patterns against the eq.js copy
 * vendored in the repo root.
 */
class HttpClientBundlePatchTest {

    // Verbatim upstream text for the three pruned entries. The trailing
    // comma is part of the match, so removal keeps the object literal valid.
    private val splashScreenEntry =
        "splashScreen:{label:\"Splash screen\",type:\"select\",description:\"Splash screen to show at app launch\",defaultValue:\"viggy\",options:[{key:\"viggy\",label:\"Viggy, by Shoritsu\"},{key:\"shiggy\",label:\"Shiggy, by naga_U\"},{key:\"oneko\",label:\"Oneko\"}]},"
    private val discordBranchEntry =
        "discordBranch:{type:\"select\",label:\"Discord branch\",description:\"The Discord branch to load\",options:[{key:\"stable\",label:\"Stable\"},{key:\"canary\",label:\"Canary\"},{key:\"ptb\",label:\"PTB\"}],defaultValue:\"stable\"},"
    private val allowRemoteDebuggingEntry =
        "allowRemoteDebugging:{label:\"Allow remote debugging\",type:\"toggle\",description:\"Expose WebView to remote Chrome DevTools. You will be able to inspect the WebView on a browser using chrome://inspect. This does not give any access outside of your local network\",defaultValue:!1},"

    private val prunedEntries =
        listOf(splashScreenEntry, discordBranchEntry, allowRemoteDebuggingEntry)

    @Test
    fun `prunes remove the dead tree entries and leave their markers`() {
        val patched = HttpClient.applyPatches(prunedEntries.joinToString(""))
        assertFalse(patched.contains("splashScreen:{"))
        assertFalse(patched.contains("discordBranch:{"))
        assertFalse(patched.contains("allowRemoteDebugging:{"))
        assertTrue(patched.contains("/*vde-prune-splash*/"))
        assertTrue(patched.contains("/*vde-prune-branch*/"))
        assertTrue(patched.contains("/*vde-prune-remdbg*/"))
    }

    @Test
    fun `prunes keep the surrounding object literal valid`() {
        // The replacement token lands in property position inside the tree
        // object; the surviving sibling must be untouched.
        val content = "Core:{$discordBranchEntry" +
            "checkVDEUpdates:{type:\"toggle\",defaultValue:!0}}"
        val patched = HttpClient.applyPatches(content)
        assertTrue(
            "pruned Core tree must keep checkVDEUpdates intact",
            patched.contains("Core:{/*vde-prune-branch*/checkVDEUpdates:{type:\"toggle\",defaultValue:!0}}")
        )
    }

    @Test
    fun `prunes are idempotent`() {
        val once = HttpClient.applyPatches(prunedEntries.joinToString(""))
        assertEquals(once, HttpClient.applyPatches(once))
    }

    @Test
    fun `prune patterns still match the vendored eqjs snapshot`() {
        val snapshot = File("../eq.js")
        if (!snapshot.exists()) return // snapshot not vendored in this checkout
        val patched = HttpClient.applyPatches(snapshot.readText())
        // Only absence is asserted, not marker presence: upstream may delete
        // these entries itself, or the snapshot may already be pruned. The
        // regression this test catches is the entries surviving patching,
        // which means the patterns no longer match upstream text.
        assertFalse("splashScreen still rendered", patched.contains("splashScreen:{"))
        assertFalse("discordBranch still rendered", patched.contains("discordBranch:{"))
        assertFalse("allowRemoteDebugging still rendered", patched.contains("allowRemoteDebugging:{"))
    }
}
