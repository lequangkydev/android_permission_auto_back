package dev.lequangky.permission.autoback.sample

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.lequangky.permission.autoback.Cancellable
import dev.lequangky.permission.autoback.Config
import dev.lequangky.permission.autoback.Permission
import dev.lequangky.permission.autoback.PermissionAutoBack
import kotlinx.coroutines.launch

/**
 * Manual test harness for the Permission Auto Back library.
 *
 * For each enumerated permission below:
 *  - "Status" shows the current `isGranted()` verdict (refreshed in `onResume`).
 *  - "Request" calls `requestAndAwait()` — the recommended full-flow API. For
 *    runtime permissions it shows the OS dialog first, and only falls back to
 *    Settings if the user has permanently denied the permission.
 *  - "Settings" calls `openSettings()` (callback variant) — skips the runtime
 *    dialog entirely and opens the matching Settings page right away. Useful
 *    for special permissions or for forcing the Settings path.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var autoBack: PermissionAutoBack
    private val rows = mutableListOf<Row>()
    private var pendingCallback: Cancellable? = null

    private data class Entry(
        val label: String,
        val permission: Permission,
        val note: String? = null,
    )

    private data class Row(
        val entry: Entry,
        val statusView: TextView,
    )

    private val entries = listOf(
        Entry("Camera", Permission.Runtime.Camera),
        Entry("Microphone", Permission.Runtime.RecordAudio),
        Entry("Location (fine)", Permission.Runtime.AccessFineLocation),
        Entry(
            "Background location",
            Permission.Runtime.AccessBackgroundLocation,
            "API 29+ — system redirects via foreground-location grant.",
        ),
        Entry("Contacts", Permission.Runtime.ReadContacts),
        Entry(
            "Post notifications",
            Permission.Runtime.PostNotifications,
            "API 33+ runtime; older devices use channel toggle.",
        ),
        Entry("Photos (READ_MEDIA_IMAGES)", Permission.Runtime.ReadMediaImages),

        Entry(
            "Display over other apps",
            Permission.Special.SystemAlertWindow,
            "Special — opens overlay settings.",
        ),
        Entry(
            "All files access",
            Permission.Special.ManageExternalStorage,
            "Special, API 30+.",
        ),
        Entry(
            "Schedule exact alarm",
            Permission.Special.ScheduleExactAlarm,
            "Special, API 31+.",
        ),
        Entry("Write system settings", Permission.Special.WriteSettings),
        Entry("Install unknown apps", Permission.Special.RequestInstallPackages),
        Entry("Ignore battery optimizations", Permission.Special.IgnoreBatteryOptimizations),
        Entry("DND access", Permission.Special.AccessNotificationPolicy),
        Entry("Notifications (channel)", Permission.Special.Notifications),
        Entry("Usage stats", Permission.Special.PackageUsageStats),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoBack = PermissionAutoBack.from(this)
        setContentView(buildUi())
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // Re-read status when the user returns from Settings via the back gesture.
        refreshAll()
    }

    override fun onDestroy() {
        pendingCallback?.cancel()
        pendingCallback = null
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val title = TextView(this).apply {
            text = "Permission Auto Back — sample"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, 0, 0, dp(12))
        }
        column.addView(title)

        entries.forEach { entry ->
            column.addView(buildCard(entry))
            column.addView(spacer(dp(8)))
        }

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return scroll
    }

    private fun buildCard(entry: Entry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.argb(20, 0, 0, 0))
        }

        val label = TextView(this).apply {
            text = entry.label
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        card.addView(label)

        val status = TextView(this).apply {
            text = "Status: …"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(status)
        rows.add(Row(entry, status))

        entry.note?.let { noteText ->
            val note = TextView(this).apply {
                text = noteText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.65f
                setPadding(0, dp(2), 0, 0)
            }
            card.addView(note)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            gravity = Gravity.END
        }

        val settingsButton = Button(this).apply {
            text = "Settings"
            setOnClickListener { runOpenSettings(entry) }
        }
        buttonRow.addView(settingsButton)
        buttonRow.addView(spacer(dp(8)).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 0)
        })

        val requestButton = Button(this).apply {
            text = "Request"
            setOnClickListener { runRequest(entry) }
        }
        buttonRow.addView(requestButton)

        card.addView(buttonRow)
        return card
    }

    private fun runRequest(entry: Entry) {
        lifecycleScope.launch {
            val granted = autoBack.requestAndAwait(
                this@MainActivity,
                entry.permission,
                Config(timeoutMs = 60_000L, debug = true),
            )
            updateStatus(entry, granted)
            Toast.makeText(
                this@MainActivity,
                "${entry.label}: ${if (granted) "granted" else "not granted"}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun runOpenSettings(entry: Entry) {
        pendingCallback?.cancel()
        pendingCallback = autoBack.openSettings(
            entry.permission,
            Config(timeoutMs = 60_000L, debug = true),
        ) { granted ->
            pendingCallback = null
            updateStatus(entry, granted)
            Toast.makeText(
                this,
                "${entry.label} (settings): ${if (granted) "granted" else "not granted"}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun refreshAll() {
        rows.forEach { row ->
            row.statusView.text = "Status: ${labelFor(autoBack.isGranted(row.entry.permission))}"
        }
    }

    private fun updateStatus(entry: Entry, granted: Boolean) {
        rows.firstOrNull { it.entry === entry }
            ?.statusView
            ?.text = "Status: ${labelFor(granted)}"
    }

    private fun labelFor(granted: Boolean) = if (granted) "Granted" else "Denied"

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
