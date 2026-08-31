package com.nin0dev.vendroid.webview

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Pins the disk-cache contract of [HttpClient.readBundleFromDisk]:
 *
 *  - An oversized (corrupt) file fails closed; readText() has no cap and an
 *    uncaught OOM on a background thread still kills the process.
 *  - A well-formed file round-trips unchanged when the persisted patch flag
 *    is absent and no patch markers are missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpClientBundleCacheTest {

    private lateinit var sPrefs: SharedPreferences
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        sPrefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        sPrefs.edit().clear().commit()
        filesDir = appContext.filesDir
    }

    @Test
    fun oversizedCacheFile_failsClosed() {
        val file = File(filesDir, "vencord.js")
        file.writeText("a".repeat(HttpClient.MAX_READ_BYTES + 1))
        try {
            try {
                HttpClient.readBundleFromDisk(sPrefs, file)
                fail("Expected IOException for a cache file over MAX_READ_BYTES")
            } catch (expected: IOException) {
                // The message names the limit.
                assert(expected.message!!.contains("byte limit"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun wellFormedCacheFile_roundTripsUnchanged() {
        val file = File(filesDir, "vencord.js")
        val content = "// Vencord testbuild\n" + "x".repeat(4096)
        file.writeText(content)
        try {
            assertEquals(content, HttpClient.readBundleFromDisk(sPrefs, file))
        } finally {
            file.delete()
        }
    }
}
