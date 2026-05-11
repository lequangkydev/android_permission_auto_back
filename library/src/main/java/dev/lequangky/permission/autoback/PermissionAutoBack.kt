package dev.lequangky.permission.autoback

import android.app.Activity
import android.content.Context
import androidx.annotation.MainThread
import dev.lequangky.permission.autoback.internal.PermissionChecker
import dev.lequangky.permission.autoback.internal.PermissionPoller
import dev.lequangky.permission.autoback.internal.SettingsNavigator
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Public entry point.
 *
 * Two flows are supported:
 *  - [openSettingsAndAwait] / [openSettings] — opens the right Settings page
 *    for the permission, then polls until granted or [Config.timeoutMs] elapses.
 *    On grant the host app is brought back to the foreground.
 *  - [pollAndAwait] / [poll] — only polls (no Settings trip). Use this when the
 *    caller has already routed the user somewhere — e.g. when requesting
 *    `ACCESS_BACKGROUND_LOCATION` on Android 11+, where the OS itself sends the
 *    user to the location-permission page.
 *
 * Construction:
 *  - [from] (recommended): pass an `Activity`. The library holds a `WeakReference`
 *    so it never prevents the activity from being garbage-collected.
 *  - [from] with a `Context`: pure-context variant; uses `FLAG_ACTIVITY_NEW_TASK`
 *    when launching Settings.
 *
 * Threading: all public methods must be called from the main thread.
 *
 * Lifecycle: a single instance maintains one outstanding poll at a time.
 * Starting a new poll cancels the previous one (invoking its callback / resuming
 * its coroutine with `false`). Call [cancel] explicitly to abort.
 */
public class PermissionAutoBack private constructor(
    private val appContext: Context,
    private val activityRef: WeakReference<Activity>?,
) {

    private val poller = PermissionPoller()

    /** Synchronous granted check. Safe to call from any thread. */
    public fun isGranted(permission: Permission): Boolean =
        PermissionChecker.isGranted(appContext, permission)

    /**
     * Best-effort granted/denied/permanently-denied status. The
     * permanently-denied verdict requires an [Activity] and only fires for
     * [Permission.Runtime] entries the user has previously been asked about
     * via [markPermissionAsked].
     */
    public fun status(permission: Permission): PermissionStatus =
        PermissionChecker.status(appContext, activityRef?.get(), permission)

    /**
     * Mark a runtime permission as "we have shown the OS dialog for this at
     * least once". Used by [status] to disambiguate first-launch from
     * permanently-denied. Call this from your `ActivityResultCallback` for
     * `RequestPermission`.
     */
    public fun markPermissionAsked(permission: Permission.Runtime) {
        PermissionChecker.markAsked(appContext, permission)
    }

    /**
     * Open the Settings page for [permission] and poll until granted.
     *
     * Returns `true` if the permission flipped to granted before [Config.timeoutMs]
     * elapsed; `false` otherwise (or if cancelled).
     */
    @MainThread
    public suspend fun openSettingsAndAwait(
        permission: Permission,
        config: Config = Config.Default,
    ): Boolean {
        if (isGranted(permission)) return true
        return suspendCancellableCoroutine { cont ->
            val handle = openSettings(permission, config) { granted ->
                if (cont.isActive) cont.resume(granted)
            }
            cont.invokeOnCancellation { handle.cancel() }
        }
    }

    /**
     * Callback-style variant of [openSettingsAndAwait]. The callback fires once,
     * on the main thread. Returns a [Cancellable] handle.
     */
    @MainThread
    public fun openSettings(
        permission: Permission,
        config: Config = Config.Default,
        onResult: (granted: Boolean) -> Unit,
    ): Cancellable {
        if (isGranted(permission)) {
            onResult(true)
            return Cancellable.NoOp
        }
        SettingsNavigator.open(appContext, activityRef?.get(), permission)
        return poller.start(appContext, permission, config, onResult)
    }

    /**
     * Poll [permission] without opening any Settings page. Use this when the
     * navigation has already been performed by something else.
     */
    @MainThread
    public suspend fun pollAndAwait(
        permission: Permission,
        config: Config = Config.Default,
    ): Boolean {
        if (isGranted(permission)) return true
        return suspendCancellableCoroutine { cont ->
            val handle = poll(permission, config) { granted ->
                if (cont.isActive) cont.resume(granted)
            }
            cont.invokeOnCancellation { handle.cancel() }
        }
    }

    @MainThread
    public fun poll(
        permission: Permission,
        config: Config = Config.Default,
        onResult: (granted: Boolean) -> Unit,
    ): Cancellable {
        if (isGranted(permission)) {
            onResult(true)
            return Cancellable.NoOp
        }
        return poller.start(appContext, permission, config, onResult)
    }

    /** Cancel any in-flight poll. Safe to call when nothing is running. */
    @MainThread
    public fun cancel() {
        poller.cancel()
    }

    public companion object {
        /**
         * Create an instance bound to an [Activity]. The activity is held via
         * [WeakReference], so the library never keeps it from being collected.
         */
        @JvmStatic
        public fun from(activity: Activity): PermissionAutoBack =
            PermissionAutoBack(activity.applicationContext, WeakReference(activity))

        /**
         * Create an instance with only a [Context]. Settings is launched with
         * `FLAG_ACTIVITY_NEW_TASK` (no task affinity preservation). Prefer
         * [from] with an Activity when you have one.
         */
        @JvmStatic
        public fun from(context: Context): PermissionAutoBack =
            PermissionAutoBack(context.applicationContext, null)
    }
}
