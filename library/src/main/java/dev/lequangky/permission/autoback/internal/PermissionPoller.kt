package dev.lequangky.permission.autoback.internal

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

        val r = object : Runnable {
            override fun run() {
                if (PermissionChecker.isGranted(appContext, permission)) {
                    if (config.bringAppToFrontOnGrant) {
                        bringAppToFront(appContext)
                    }
                    finish(true)
                    return
                }
                if (System.currentTimeMillis() - startedAt > config.timeoutMs) {
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

    private fun bringAppToFront(context: Context) {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName) ?: return
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        try {
            context.startActivity(launchIntent)
        } catch (_: SecurityException) {
            // Some launcher intents (e.g. on locked-down OEMs) can throw. Treat
            // bring-to-front as best-effort; the permission grant itself stands.
        }
    }
}
