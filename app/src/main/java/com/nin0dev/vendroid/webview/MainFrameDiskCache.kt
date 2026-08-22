package com.nin0dev.vendroid.webview

import android.content.Context
import android.net.Uri
import com.nin0dev.vendroid.utils.Constants
import com.nin0dev.vendroid.utils.VDELog
import java.io.File
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistent disk cache for the Discord main-frame HTML shell.
 *
 * Serves the same purpose as the in-memory [VWebviewClient] main-frame cache
 * but survives process death, so a cold start doesn't block on a network fetch
 * of the (mostly static) Discord app shell.
 *
 * Security model:
 *  - Only **raw** (un-patched) HTML is persisted. The JS network firewall is
 *    injected at serve time using the *current* firewall config, so a user who
 *    tightens their firewall between sessions is never served a stale, wider
 *    allowlist. (The embedded firewall's `__vendroidFw` guard would otherwise
 *    block the new, tighter firewall from being applied.)
 *  - Only app-shell Discord routes are cacheable. Login / authorize / track and
 *    other routes that may carry OAuth `state`/`nonce`/CSRF parameters are
 *    **never** cached, so a stale copy cannot be re-served into a sensitive flow.
 *  - Stale entries are served only within [MAX_AGE_MS]; beyond that the cache is
 *    treated as a miss so ancient markup is never shown.
 *  - Writes are atomic (`.tmp` + `renameTo`) and the file is size-capped.
 *  - Reads are defensive (any failure is a cache miss → network fetch).
 */object MainFrameDiskCache {

    /** Max staleness for a stale-while-revalidate serve. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours

    /** Upper bound on a persisted body so a malformed huge response can't fill disk. */
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** Cap on the number of URLs recorded in the preload index. */
    private const val MAX_INDEXED_URLS = 64

    /** Serializes the index.txt read-modify-write. */
    private val indexAddLock = ReentrantLock()

    // App-shell routes safe to cache & re-serve. Mirrors the resume allowlist
    // in MainActivity.isAppResumeUrl; deliberately excludes
    // login/authorize/track, which can carry OAuth state/nonce/CSRF params.
    private val CACHEABLE_PATHS = arrayOf(
        "/app",
        "/channels", // "/channels" and "/channels/..."
        "/library",
        "/store",
        "/friends"
    )

    private var cacheDir: File? = null
    private val initLock = Any()

    fun init(context: Context) {
        synchronized(initLock) {
            if (cacheDir != null) return
            cacheDir = File(context.applicationContext.cacheDir, "main_frame_cache").apply {
                // Cache dir is a throwaway; recreate if it was cleared.
                if (!exists()) mkdirs()
            }
        }
    }

    /**
     * True if the URL may be persisted and re-served as a stale HTML shell.
     * Requires a Discord host, HTTPS, and an app-shell path.
     */
    fun isCacheableRoute(url: Uri): Boolean {
        if (url.scheme != "https") return false
        val host = url.host ?: return false
        if (!Constants.isDiscordDomain(host)) return false
        val path = url.path ?: "/"
        // "/channels" must match "/channels" and "/channels/..." but not
        // "/channelssomething".
        if (path == "/channels" || path.startsWith("/channels/")) return true
        return CACHEABLE_PATHS.any { path == it }
    }

    /**
     * Persists a raw HTML main-frame response. No-op unless [isCacheableRoute]
     * passes and the body is within the size cap. [headers] must be the
     * fetch-path's sanitized headers so a stale serve preserves security headers.
     */
    fun writeMainFrame(
        urlString: String,
        rawBody: ByteArray,
        headers: Map<String, String>,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val dir = cacheDir ?: return false
        if (rawBody.isEmpty() || rawBody.size > MAX_BODY_BYTES) return false
        val uri = try { Uri.parse(urlString) } catch (_: Exception) { return false }
        if (!isCacheableRoute(uri)) return false

        return try {
            val base = entryBaseName(urlString)
            // Matched-pair scheme: the body is named by the SAME timestamp
            // stored in the meta ("$base.<ts>.html"). The meta (renamed last)
            // is the commit point, so a reader always resolves the body from
            // the timestamp it just read from meta — a torn {new body, old
            // meta} combination is structurally impossible. (With a fixed
            // "base.html" name, renaming the body into place before the meta
            // could expose a new body paired with an old meta.)
            val bodyTmp = File(dir, "$base.tmp")
            val bodyFile = File(dir, "$base.$nowMs.html")
            val metaTmp = File(dir, "$base.meta.tmp")
            val meta = File(dir, "$base.meta")

            bodyTmp.writeBytes(rawBody)
            if (!bodyTmp.renameTo(bodyFile)) {
                bodyTmp.delete()
                return false
            }

            // Meta lines:
            //   0: fetch timestamp (== the body filename timestamp)
            //   1: base64 reason phrase
            //   2..: "key\tbase64value" header pairs (bounded, no newlines in values)
            val sb = StringBuilder()
            sb.append(nowMs).append('\n')
            sb.append(Base64.getEncoder().encodeToString("OK".toByteArray(Charsets.UTF_8))).append('\n')
            val enc = Base64.getEncoder()
            for ((k, v) in headers) {
                // Keep only headers that matter to re-serve (security/content-type).
                val lk = k.lowercase()
                if (lk == "content-length" || lk == "content-encoding" || lk == "transfer-encoding") continue
                if (lk == "content-type" || lk == "content-security-policy" ||
                    lk == "content-security-policy-report-only" || lk == "strict-transport-security") {
                    val kv = "$k\t${enc.encodeToString(v.toByteArray(Charsets.UTF_8))}"
                    sb.append(kv).append('\n')
                }
            }
            // Write meta atomically LAST — it is the commit point.
            metaTmp.writeText(sb.toString())
            if (!metaTmp.renameTo(meta)) {
                metaTmp.delete()
                // Clean up the now-orphaned body file; no meta references it.
                bodyFile.delete()
                return false
            }

            // Best-effort: drop any older body file for this URL now that the new
            // meta is committed. Safe even if a concurrent reader holds the old
            // file open (deleting an open file just orphans the fd; a read that
            // races the delete lands in the catch below and becomes a cache
            // miss).
            dir.listFiles { _, n -> n.startsWith("$base.") && n.endsWith(".html") && n != "$base.$nowMs.html" }
                ?.forEach { it.delete() }

            // Record the URL in the index so cold-start preload can enumerate it.
            indexAddLock.withLock {
                val idx = File(dir, "index.txt")
                val lines = try {
                    idx.readLines().toMutableList()
                } catch (_: Exception) { mutableListOf() }
                // Keep the list bounded; drop the oldest entries beyond the cap.
                if (!lines.contains(urlString)) {
                    lines.add(urlString)
                    if (lines.size > MAX_INDEXED_URLS) {
                        lines.removeAt(0)
                    }
                    try { idx.writeText(lines.joinToString("\n") + "\n") } catch (_: Exception) {}
                }
            }
            true
        } catch (e: Exception) {
            VDELog.d("MainFrameDiskCache", "write failed: ${e.message}")
            false
        }
    }

    /** A valid stale HTML shell read from disk, or null. */
    data class CachedMainFrame(
        val body: ByteArray,
        val reasonPhrase: String,
        val headers: Map<String, String>,
        val fetchedAt: Long
    )

    /**
     * Reads a stale main-frame from disk if present and within [MAX_AGE_MS].
     * Any failure (missing, corrupt, too old, wrong route) is a cache miss.
     */
    fun readMainFrame(urlString: String, nowMs: Long = System.currentTimeMillis()): CachedMainFrame? {
        val dir = cacheDir ?: return null
        val uri = try { Uri.parse(urlString) } catch (_: Exception) { return null }
        if (!isCacheableRoute(uri)) return null

        return try {
            val base = entryBaseName(urlString)
            val metaFile = File(dir, "$base.meta")
            // The body is named by the timestamp stored in the meta, so a
            // committed meta always references a body that was already renamed
            // into place. Re-resolve the meta's timestamp after reading the
            // body so a commit that lands mid-read becomes a miss, never a
            // torn pair.
            val fetchedAt = readFetchedAt(metaFile) ?: return null
            if (nowMs - fetchedAt > MAX_AGE_MS) {
                // Expired — drop so a later read doesn't re-serve a dead entry.
                metaFile.delete()
                File(dir, "$base.$fetchedAt.html").delete()
                return null
            }
            val bodyFile = File(dir, "$base.$fetchedAt.html")
            if (!bodyFile.exists()) return null
            val body = bodyFile.readBytes()
            if (body.isEmpty() || body.size > MAX_BODY_BYTES) return null
            // Commit landed mid-read → treat as a miss rather than a torn pair.
            if (readFetchedAt(metaFile) != fetchedAt) return null
            val metaLines = metaFile.readText().split('\n')
            if (metaLines.size < 2) return null
            val reason = try {
                String(Base64.getDecoder().decode(metaLines[1]), Charsets.UTF_8)
            } catch (_: Exception) {
                "OK"
            }
            // Rebuild headers from any "key\tvalue" pairs in lines 2+.
            val headers = HashMap<String, String>()
            val dec = Base64.getDecoder()
            for (line in metaLines.drop(2)) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val k = line.substring(0, tab)
                val vEnc = line.substring(tab + 1)
                try {
                    headers[k] = String(dec.decode(vEnc), Charsets.UTF_8)
                } catch (_: Exception) {}
            }
            if (headers.isEmpty()) headers["Content-Type"] = "text/html"
            CachedMainFrame(body, reason, headers, fetchedAt)
        } catch (e: Exception) {
            VDELog.d("MainFrameDiskCache", "read failed: ${e.message}")
            null
        }
    }

    /** Reads just the fetchedAt (first line) of a meta file, or null on failure. */
    private fun readFetchedAt(metaFile: File): Long? =
        try { metaFile.readText().substringBefore('\n').toLongOrNull() } catch (_: Exception) { null }

    /** The cacheable URLs recorded during writes, for cold-start preload. */
    fun preloadableUrls(): List<String> {
        val dir = cacheDir ?: return emptyList()
        return try {
            File(dir, "index.txt").readLines().filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Best-effort cleanup for tests / cache management. Not called on the hot path. */
    fun clear() {
        val dir = cacheDir ?: return
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    private fun entryBaseName(urlString: String): String {
        // SHA-256 (truncated) instead of hashCode(): hashCode() is not
        // collision-free, so two URLs could silently share a cache entry and
        // one page would be served the other's stale HTML. Truncating to 32
        // hex chars keeps collisions astronomically unlikely while staying
        // compact.
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder(32)
        for (b in md.digest(urlString.toByteArray(Charsets.UTF_8))) {
            sb.append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1))
        }
        return sb.toString().take(32)
    }
}
