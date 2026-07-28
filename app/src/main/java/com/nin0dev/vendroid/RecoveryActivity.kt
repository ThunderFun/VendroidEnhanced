package com.nin0dev.vendroid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.webkit.CookieManager
import com.google.android.material.card.MaterialCardView
import java.io.File

class RecoveryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_recovery)

        findViewById<MaterialCardView>(R.id.start_normally).setOnClickListener {
            it.isClickable = false
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<MaterialCardView>(R.id.safe_mode).setOnClickListener {
            it.isClickable = false
            val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val e = sPrefs.edit()
            e.putBoolean("safeMode", true)
            e.apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<MaterialCardView>(R.id.force_update).setOnClickListener {
            it.isClickable = false
            val sPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val e = sPrefs.edit()
            e.putInt("lastMajorUpdateThatUserHasUpdatedVencord", 0)
            e.apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<MaterialCardView>(R.id.view_logs).setOnClickListener {
            val logFile = File(filesDir, "vde_logs.txt")
            val text = if (logFile.exists()) logFile.readText() else "No logs available."
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
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("VendroidEnhanced Logs")
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy") { _, _ ->
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("VDE Logs", text))
                }
                .setNegativeButton("Share") { _, _ ->
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "VendroidEnhanced Logs")
                        }
                        startActivity(android.content.Intent.createChooser(intent, "Share logs"))
                    } catch (_: android.content.ActivityNotFoundException) {
                        android.widget.Toast.makeText(this, "No app available to share logs", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }

    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
