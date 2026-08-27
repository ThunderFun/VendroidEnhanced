package com.nin0dev.vendroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.webkit.CookieManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.nin0dev.vendroid.utils.ShareHelper
import com.nin0dev.vendroid.utils.VDELog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecoveryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_recovery)

        // Show last-boot state from the boot-verify probe, if available.
        findViewById<android.widget.TextView>(R.id.last_boot_state).apply {
            val state = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(MainActivity.PREF_LAST_BOOT_STATE, null)
            if (state.isNullOrEmpty()) {
                visibility = android.view.View.GONE
            } else {
                visibility = android.view.View.VISIBLE
                text = "Last boot: $state"
            }
        }

        findViewById<MaterialCardView>(R.id.start_normally).setOnClickListener {
            it.isClickable = false
            // Kill before commit (see killWebProcess), then clear a stale
            // safeMode flag so "Start normally" boots normally.
            killWebProcess()
            val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            sPrefs.edit().putBoolean("safeMode", false).commit()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<MaterialCardView>(R.id.safe_mode).setOnClickListener {
            it.isClickable = false
            // Kill before commit (see killWebProcess).
            killWebProcess()
            val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            sPrefs.edit().putBoolean("safeMode", true).commit()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<MaterialCardView>(R.id.force_update).setOnClickListener {
            it.isClickable = false
            // Kill before commit (see killWebProcess).
            killWebProcess()
            val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            sPrefs.edit().putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0).commit()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<MaterialCardView>(R.id.view_logs).setOnClickListener {
            it.isClickable = false
            val logCard = it
            val logFile = File(filesDir, "vde_logs.txt")
            val prevLogFile = File(filesDir, "vde_logs.prev.txt")
            // Read the logs off the main thread to avoid jank/ANR on large
            // files. Defensive read: a corrupt/unreadable log file must not
            // crash the recovery screen. Prefer VDELog's own sink so the same
            // content source is used as the in-app viewer, falling back to a
            // message. Show the previous (crashed) session first, then the
            // current one, since VDELog rotates (not truncates) the file on
            // startup now.
            lifecycleScope.launch(Dispatchers.IO) {
                val text = try {
                    buildString {
                        if (prevLogFile.exists()) {
                            append("=== Previous session ===\n")
                            append(prevLogFile.readText())
                            append('\n')
                        }
                        if (logFile.exists()) {
                            append("=== Current session ===\n")
                            append(VDELog.getLogFileContents())
                        }
                    }.ifEmpty { "No logs available." }
                } catch (_: Exception) {
                    "Failed to read logs."
                }
                withContext(Dispatchers.Main) {
                    showLogDialog(text).setOnDismissListener { logCard.isClickable = true }
                }
            }
        }

    }

    private fun showLogDialog(text: String): androidx.appcompat.app.AlertDialog {
        val scrollView = android.widget.ScrollView(this).apply {
            setPadding(48, 32, 48, 32)
        }
        val textView = android.widget.TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        scrollView.addView(textView)
        return androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("VendroidEnhanced Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VDE Logs", text))
            }
            .setNegativeButton("Share") { _, _ ->
                ShareHelper.shareLogs(this, text)
            }
            .show()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * Terminates the `:web` process so MainActivity cold-starts and re-reads
     * the settings prefs from disk. Without this, a warm :web process keeps
     * its stale in-memory SharedPreferences and never honors recovery changes
     * (the singleTask activity also gets onNewIntent, not onCreate).
     *
     * Callers must invoke this BEFORE committing any pref write. A queued
     * apply() flush from :web rewrites the full XML from its stale in-memory
     * map and would clobber a preceding commit; killing first closes that
     * race, and commit() being synchronous makes the write durable before
     * MainActivity restarts.
     */
    private fun killWebProcess() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            // On API 22+ runningAppProcesses() returns only this app's own
            // processes, which is exactly what we need here.
            am.runningAppProcesses?.forEach { proc ->
                if (proc.processName == "$packageName:web") {
                    android.os.Process.killProcess(proc.pid)
                }
            }
        } catch (t: Throwable) {
            VDELog.e("Recovery", "killWebProcess failed", t)
        }
    }
}
