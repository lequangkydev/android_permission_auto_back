package dev.lequangky.permission.autoback

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import dev.lequangky.permission.autoback.internal.PermissionChecker
import dev.lequangky.permission.autoback.internal.PermissionPoller
import dev.lequangky.permission.autoback.internal.SettingsNavigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    /**
     * Full-flow permission request — the recommended entry point.
     *
     * Behavior:
     *  - Already granted → returns `true` immediately.
     *  - [Permission.Special] → no OS dialog exists for special permissions;
     *    equivalent to [openSettingsAndAwait] (jumps straight to Settings + polls).
     *  - [Permission.Runtime] / [Permission.Custom]:
     *      1. Show the OS runtime-permission dialog.
     *      2. User taps **Allow** → return `true`.
     *      3. User taps **Deny** for the first time → return `false` (caller can
     *         re-ask later; the system will show the dialog again).
     *      4. User has permanently denied (two denies on API 30+, "Don't ask
     *         again" on older Android) → automatically fall through to
     *         [openSettingsAndAwait]: opens the app details page, polls, and
     *         brings the host app back to the foreground once toggled on.
     *
     * Requires the host activity to extend [ComponentActivity] (which
     * [androidx.appcompat.app.AppCompatActivity] and [androidx.fragment.app.FragmentActivity]
     * already do). The Activity Result launcher is registered on the activity's
     * [androidx.activity.result.ActivityResultRegistry] for the duration of the
     * call and unregistered afterwards — safe to call from any lifecycle state.
     */
    @MainThread
    public suspend fun requestAndAwait(
        activity: ComponentActivity,
        permission: Permission,
        config: Config = Config.Default,
    ): Boolean {
        if (isGranted(permission)) return true

        val manifestPerm = when (permission) {
            is Permission.Runtime -> permission.manifestPermission
            is Permission.Custom -> permission.manifestPermission
            is Permission.Special -> return openSettingsAndAwait(permission, config)
        }

        // ACCESS_BACKGROUND_LOCATION on Android 11+ silently redirects to the
        // app's location-permission Settings page instead of showing a dialog,
        // and ActivityResultLauncher only delivers a result when the user
        // manually navigates back. Run polling in parallel so we can auto-return
        // the moment "Allow all the time" is selected.
        //
        // Foreground location is a prerequisite — without it the background
        // request is denied silently by the OS, leaving the user confused.
        val isBackgroundLocation = manifestPerm == BACKGROUND_LOCATION
        val needsParallelPoll = isBackgroundLocation &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        if (needsParallelPoll) {
            val hasForeground = isGranted(Permission.Runtime.AccessFineLocation) ||
                isGranted(Permission.Runtime.AccessCoarseLocation)
            if (!hasForeground) {
                val fineGranted = requestRuntimeDialog(
                    activity,
                    Permission.Runtime.AccessFineLocation.manifestPermission,
                )
                markPermissionAsked(Permission.Runtime.AccessFineLocation)
                if (!fineGranted) return false
            }
        }

        val grantedFromDialog = if (needsParallelPoll) {
            requestRuntimeDialogWithParallelPoll(activity, permission, manifestPerm, config)
        } else {
            requestRuntimeDialog(activity, manifestPerm)
        }
        if (permission is Permission.Runtime) markPermissionAsked(permission)
        if (grantedFromDialog) return true

        val canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            manifestPerm,
        )
        if (canAskAgain) return false

        return openSettingsAndAwait(permission, config)
    }

    private suspend fun requestRuntimeDialog(
        activity: ComponentActivity,
        manifestPerm: String,
    ): Boolean {
        val key = "permission_auto_back_${manifestPerm.replace('.', '_')}_${System.nanoTime()}"
        val deferred = CompletableDeferred<Boolean>()
        val launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            deferred.complete(granted)
        }
        return try {
            launcher.launch(manifestPerm)
            deferred.await()
        } finally {
            launcher.unregister()
        }
    }

    /**
     * Hybrid flow for permissions where the OS redirects to Settings instead of
     * showing a dialog (currently: `ACCESS_BACKGROUND_LOCATION` on Android 11+).
     *
     * Launches the OS request *and* polls in parallel — whichever resolves
     * first wins. The poller is the one that brings the app back to the
     * foreground; the launcher only delivers a result when the user manually
     * navigates back, which defeats the "auto" in auto-back.
     */
    private suspend fun requestRuntimeDialogWithParallelPoll(
        activity: ComponentActivity,
        permission: Permission,
        manifestPerm: String,
        config: Config,
    ): Boolean = coroutineScope {
        val result = CompletableDeferred<Boolean>()

        val key = "permission_auto_back_${manifestPerm.replace('.', '_')}_${System.nanoTime()}"
        val launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            result.complete(granted)
        }

        val pollJob = launch {
            runCatching {
                val granted = pollAndAwait(permission, config)
                if (granted) result.complete(true)
            }
        }

        launcher.launch(manifestPerm)

        try {
            result.await()
        } finally {
            pollJob.cancel()
            launcher.unregister()
        }
    }

    /** Cancel any in-flight poll. Safe to call when nothing is running. */
    @MainThread
    public fun cancel() {
        poller.cancel()
    }

    public companion object {
        private const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

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
