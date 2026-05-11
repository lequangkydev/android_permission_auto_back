# Permission Auto Back

A small Kotlin/Android library that handles the awkward dance of asking for a
"special" Android permission:

1. Open the right Settings page for the permission.
2. Poll on the main thread until the user toggles it on.
3. Bring your app back to the foreground automatically.

It also works for runtime permissions that have been permanently denied — the
only path back from there is the app details page anyway.

- **Min SDK:** 24
- **Compile / target SDK:** 36
- **Language:** Kotlin (explicit-API mode)
- **Deps:** `androidx.core`, `androidx.annotation`, `kotlinx-coroutines-android`

## Install

Add the Maven repo you publish to, then:

```kotlin
dependencies {
    implementation("dev.lequangky:permission-auto-back:0.1.0")
}
```

(See `library/build.gradle.kts` for the `maven-publish` setup. Once published to
Maven Central or your internal repo, that one line is enough.)

## Quick start

```kotlin
class MyActivity : AppCompatActivity() {

    private val autoBack by lazy { PermissionAutoBack.from(this) }

    fun ensureOverlay() = lifecycleScope.launch {
        val granted = autoBack.openSettingsAndAwait(Permission.Special.SystemAlertWindow)
        if (granted) startOverlay() else showRetryUi()
    }
}
```

That's it. The library opens `Settings → Display over other apps → <your app>`,
polls `Settings.canDrawOverlays(context)` every 500 ms, and re-launches your
activity the moment the user flips the toggle.

## API surface

```kotlin
val autoBack = PermissionAutoBack.from(activity)   // recommended
// or: PermissionAutoBack.from(context)            // no Activity — uses NEW_TASK

autoBack.isGranted(permission): Boolean
autoBack.status(permission): PermissionStatus      // Granted / Denied / PermanentlyDenied

// coroutines
autoBack.openSettingsAndAwait(permission, config): Boolean   // suspend
autoBack.pollAndAwait(permission, config): Boolean           // suspend

// callbacks
autoBack.openSettings(permission, config) { granted -> ... }: Cancellable
autoBack.poll(permission, config) { granted -> ... }: Cancellable

autoBack.cancel()                  // abort any in-flight poll
```

### `Config`

```kotlin
Config(
    pollIntervalMs = 500L,        // how often to re-check
    timeoutMs = 5L * 60 * 1000,   // give up after 5 min
    bringAppToFrontOnGrant = true // launch the app on success
)
```

### `Permission`

```kotlin
sealed class Permission {
    sealed class Runtime(val manifestPermission: String) : Permission()
    sealed class Special : Permission()
    data class Custom(val manifestPermission: String) : Permission()
}
```

All ~40 standard runtime permissions are enumerated as objects under
`Permission.Runtime` (camera, mic, location, contacts, phone, SMS, calendar,
sensors, bluetooth, nearby-wifi, activity recognition, post-notifications,
the Tiramisu media split, background-location, etc.).

Special permissions covered:

| Special                                   | Settings action                                       |
| ----------------------------------------- | ----------------------------------------------------- |
| `SystemAlertWindow` (overlay)             | `ACTION_MANAGE_OVERLAY_PERMISSION`                    |
| `WriteSettings`                           | `ACTION_MANAGE_WRITE_SETTINGS`                        |
| `ManageExternalStorage` (R+)              | `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`       |
| `RequestInstallPackages` (O+)             | `ACTION_MANAGE_UNKNOWN_APP_SOURCES`                   |
| `ScheduleExactAlarm` (S+)                 | `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`                 |
| `UseExactAlarm` (T+, normal)              | App details                                           |
| `IgnoreBatteryOptimizations`              | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`         |
| `AccessNotificationPolicy` (DND)          | `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`          |
| `Notifications`                           | `ACTION_APP_NOTIFICATION_SETTINGS` (O+) / app details |
| `PackageUsageStats`                       | `ACTION_USAGE_ACCESS_SETTINGS`                        |
| `NotificationListener(serviceClass)`      | `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` (Q+)   |
| `AccessibilityService(serviceClass)`      | `ACTION_ACCESSIBILITY_SETTINGS`                       |

For anything not in the list (vendor permissions, future Android additions),
use `Permission.Custom("android.permission.SOMETHING")`.

## Examples

### Runtime permission that's been permanently denied

```kotlin
when (autoBack.status(Permission.Runtime.Camera)) {
    PermissionStatus.Granted -> openCamera()
    PermissionStatus.Denied -> requestRuntimeDialog()
    PermissionStatus.PermanentlyDenied -> {
        // The OS dialog won't show again. Take the user to app details.
        lifecycleScope.launch {
            if (autoBack.openSettingsAndAwait(Permission.Runtime.Camera)) {
                openCamera()
            }
        }
    }
}
```

Combine with the standard ActivityResult API for the dialog itself:

```kotlin
private val requestCamera = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    autoBack.markPermissionAsked(Permission.Runtime.Camera)
    if (granted) openCamera() else maybeOfferSettings()
}
```

`markPermissionAsked` is what lets `status()` later distinguish "first launch,
never asked" from "permanently denied".

### Background location (Android 11+ split flow)

On Android 11+ asking for `ACCESS_BACKGROUND_LOCATION` cannot be done with a
single dialog — the OS kicks the user to the location-permission page. Use
`pollAndAwait` to wait for the toggle without opening anything yourself:

```kotlin
// You (or permission_handler / Activity Result) trigger the OS flow first…
requestBackgroundLocation.launch(Permission.Runtime.AccessBackgroundLocation.manifestPermission)
// …then poll without opening another page:
val granted = autoBack.pollAndAwait(
    Permission.Runtime.AccessBackgroundLocation,
    Config(timeoutMs = 60_000),
)
```

### Notification listener service

```kotlin
val listener = Permission.Special.NotificationListener(
    serviceClass = "com.example.MyListenerService",
)
lifecycleScope.launch {
    if (autoBack.openSettingsAndAwait(listener)) bindListener()
}
```

### Callback-style with manual cancel

```kotlin
private var pending: Cancellable? = null

fun start() {
    pending = autoBack.openSettings(Permission.Special.IgnoreBatteryOptimizations) { granted ->
        // …
    }
}

override fun onStop() {
    super.onStop()
    pending?.cancel()
    pending = null
}
```

## Host-app manifest requirement (important)

For the auto-return step to work reliably **on MIUI / HyperOS / ColorOS** and
other OEMs with aggressive background-activity-start restrictions, the host
app's launcher Activity **must** declare `android:taskAffinity=""`:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:taskAffinity="">                            <!-- ← required -->
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

Without an empty task affinity, those OEMs treat the bring-to-front call as a
fresh launch from background and silently block it (no exception, no log) — the
permission gets granted but the user is left stranded on the Settings page.
On stock Android the polling still works either way; this attribute only
matters for OEM compatibility.

Pair this with `android:launchMode="singleTop"` so the existing instance is
reused instead of re-created.

## Threading

- All public methods on `PermissionAutoBack` must be invoked from the main
  thread (annotated `@MainThread`).
- `isGranted()` is safe to call from any thread.
- The internal poller runs on `Handler(Looper.getMainLooper())`.

## What this library is *not*

- Not a replacement for the standard runtime-permission request dialog. Use
  `ActivityResultContracts.RequestPermission` (or
  `RequestMultiplePermissions`) for that. This library handles the *fallback*
  when the dialog can't be shown anymore.
- Not a manifest-permission DSL. Declare permissions in your `AndroidManifest.xml`
  as usual — the library only reads/observes their granted state.

## License

Apache-2.0.
