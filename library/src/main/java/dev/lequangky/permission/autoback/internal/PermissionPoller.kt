package dev.lequangky.permission.autoback.internal

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lequangky.permission.autoback.Cancellable
import dev.lequangky.permission.autoback.Config
import dev.lequangky.permission.autoback.Permission

/**
 * Polls a single permission on the main looper. One poll session is active at
 * a time per [PermissionPoller] instance — calling [start] while another poll
 * is in flight cancels the previous one (and invokes its callback with `false`).
 *
 * Identical cadence to the reference Flutter plugin: a single [Handler] running
 * a [Runnable] every [Config.pollIntervalMs] ms, with a hard [Config.timeoutMs]
 * stop and a final `bringAppToFront` step on success.
 */
internal class PermissionPoller {

    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var pendingCallback: ((Boolean) -> Unit)? = null

    fun start(
        context: Context,
        permission: Permission,
        config: Config,
        onResult: (Boolean) -> Unit,
    ): Cancellable {
        cancel()
        pendingCallback = onResult

        val appContext = context.applicationContext
        val startedAt = System.currentTimeMillis()
        var tick = 0

        log(config, "start: permission=$permission interval=${config.pollIntervalMs}ms timeout=${config.timeoutMs}ms")

        val r = object : Runnable {
            override fun run() {
                tick++
                val granted = PermissionChecker.isGranted(appContext, permission)
                log(config, "tick #$tick granted=$granted elapsed=${System.currentTimeMillis() - startedAt}ms")
                if (granted) {
                    if (config.bringAppToFrontOnGrant) {
                        log(config, "bringAppToFront -> launching")
                        bringAppToFront(appContext, config)
                    }
                    finish(true)
                    return
                }
                if (System.currentTimeMillis() - startedAt > config.timeoutMs) {
                    log(config, "timed out after $tick ticks")
                    finish(false)
                    return
                }
                handler.postDelayed(this, config.pollIntervalMs)
            }
        }
        runnable = r
        handler.postDelayed(r, config.pollIntervalMs)
        return Cancellable { cancel() }
    }

    fun cancel() {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        finish(false)
    }

    private fun finish(granted: Boolean) {
        val cb = pendingCallback ?: return
        pendingCallback = null
        runnable = null
        cb(granted)
    }

    private fun bringAppToFront(context: Context, config: Config) {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName) ?: run {
                log(config, "bringAppToFront: no launcher intent for ${context.packageName}")
                return
            }
        // Flag combo verified against MIUI/HyperOS, ColorOS and stock Android:
        //   NEW_TASK            — required when calling from app context
        //   CLEAR_TOP|SINGLE_TOP — reuse the existing activity instance
        //   EXCLUDE_FROM_RECENTS — avoid creating a duplicate Recents entry
        // The HOST APP'S launcher activity MUST declare `android:taskAffinity=""`
        // for this to reliably bring the existing task back on MIUI — without
        // that, MIUI treats the call as a fresh launch and silently blocks it.
        // See README "MIUI / HyperOS notes" section.
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        try {
            context.startActivity(launchIntent)
            log(config, "bringAppToFront: startActivity dispatched")
        } catch (e: SecurityException) {
            // Some OEMs (locked-down Xiaomi/Huawei profiles) throw on background
            // activity starts. The permission grant itself stands; the user can
            // come back via the back gesture. onResume() in the host Activity
            // will refresh state.
            log(config, "bringAppToFront blocked: ${e.message}")
        }
    }

    private fun log(config: Config, message: String) {
        if (config.debug) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "PermissionAutoBack"
    }
}
