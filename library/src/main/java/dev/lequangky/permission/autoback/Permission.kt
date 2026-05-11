package dev.lequangky.permission.autoback

import android.Manifest

/**
 * Type-safe enumeration of every Android permission the library can open a
 * Settings page for and poll until granted.
 *
 * Two categories:
 *  - [Runtime] — backed by a single `android.permission.*` string, requested
 *    via the standard runtime-permission dialog. When permanently denied, the
 *    user must toggle it from the app details page.
 *  - [Special] — gated by its own dedicated Settings screen (overlay, all-files
 *    access, exact alarms, notification listener, etc.). These do not have a
 *    runtime dialog at all.
 *
 * [Custom] is an escape hatch for permission strings not enumerated here
 * (vendor permissions, future Android additions, etc.).
 */
public sealed class Permission {

    /** A runtime (a.k.a. dangerous) Android permission. */
    public sealed class Runtime(public val manifestPermission: String) : Permission() {

        public object Camera : Runtime(Manifest.permission.CAMERA)
        public object RecordAudio : Runtime(Manifest.permission.RECORD_AUDIO)

        public object AccessFineLocation : Runtime(Manifest.permission.ACCESS_FINE_LOCATION)
        public object AccessCoarseLocation : Runtime(Manifest.permission.ACCESS_COARSE_LOCATION)

        /** Android 10+ (API 29). Below Q this is auto-granted with foreground location. */
        public object AccessBackgroundLocation : Runtime("android.permission.ACCESS_BACKGROUND_LOCATION")

        public object ReadContacts : Runtime(Manifest.permission.READ_CONTACTS)
        public object WriteContacts : Runtime(Manifest.permission.WRITE_CONTACTS)
        public object GetAccounts : Runtime(Manifest.permission.GET_ACCOUNTS)

        public object CallPhone : Runtime(Manifest.permission.CALL_PHONE)
        public object ReadPhoneState : Runtime(Manifest.permission.READ_PHONE_STATE)

        /** Android 8.0+ (API 26). */
        public object ReadPhoneNumbers : Runtime("android.permission.READ_PHONE_NUMBERS")

        /** Android 8.0+ (API 26). */
        public object AnswerPhoneCalls : Runtime("android.permission.ANSWER_PHONE_CALLS")

        public object AddVoicemail : Runtime("com.android.voicemail.permission.ADD_VOICEMAIL")
        public object UseSip : Runtime(Manifest.permission.USE_SIP)
        public object ReadCallLog : Runtime(Manifest.permission.READ_CALL_LOG)
        public object WriteCallLog : Runtime(Manifest.permission.WRITE_CALL_LOG)

        @Suppress("DEPRECATION")
        public object ProcessOutgoingCalls : Runtime(Manifest.permission.PROCESS_OUTGOING_CALLS)

        public object SendSms : Runtime(Manifest.permission.SEND_SMS)
        public object ReceiveSms : Runtime(Manifest.permission.RECEIVE_SMS)
        public object ReadSms : Runtime(Manifest.permission.READ_SMS)
        public object ReceiveWapPush : Runtime(Manifest.permission.RECEIVE_WAP_PUSH)
        public object ReceiveMms : Runtime(Manifest.permission.RECEIVE_MMS)

        /** Deprecated on API 33+ — use the granular media permissions below. */
        @Suppress("DEPRECATION")
        public object ReadExternalStorage : Runtime(Manifest.permission.READ_EXTERNAL_STORAGE)

        /** Has no effect on API 30+ — kept for legacy SDK targets. */
        @Suppress("DEPRECATION")
        public object WriteExternalStorage : Runtime(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        /** Android 13+ (API 33). Falls back to READ_EXTERNAL_STORAGE on older devices. */
        public object ReadMediaImages : Runtime("android.permission.READ_MEDIA_IMAGES")

        /** Android 13+ (API 33). */
        public object ReadMediaVideo : Runtime("android.permission.READ_MEDIA_VIDEO")

        /** Android 13+ (API 33). */
        public object ReadMediaAudio : Runtime("android.permission.READ_MEDIA_AUDIO")

        /** Android 14+ (API 34). Granted when user picks Selected Photos. */
        public object ReadMediaVisualUserSelected :
            Runtime("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

        public object AccessMediaLocation : Runtime("android.permission.ACCESS_MEDIA_LOCATION")

        public object ReadCalendar : Runtime(Manifest.permission.READ_CALENDAR)
        public object WriteCalendar : Runtime(Manifest.permission.WRITE_CALENDAR)

        public object BodySensors : Runtime(Manifest.permission.BODY_SENSORS)

        /** Android 13+ (API 33). */
        public object BodySensorsBackground : Runtime("android.permission.BODY_SENSORS_BACKGROUND")

        /** Android 12+ (API 31). */
        public object BluetoothScan : Runtime("android.permission.BLUETOOTH_SCAN")

        /** Android 12+ (API 31). */
        public object BluetoothConnect : Runtime("android.permission.BLUETOOTH_CONNECT")

        /** Android 12+ (API 31). */
        public object BluetoothAdvertise : Runtime("android.permission.BLUETOOTH_ADVERTISE")

        /** Android 13+ (API 33). */
        public object NearbyWifiDevices : Runtime("android.permission.NEARBY_WIFI_DEVICES")

        /** Android 10+ (API 29). */
        public object ActivityRecognition : Runtime("android.permission.ACTIVITY_RECOGNITION")

        /** Android 13+ (API 33). */
        public object PostNotifications : Runtime("android.permission.POST_NOTIFICATIONS")
    }

    /**
     * A "special" permission whose state is gated by its own Settings page —
     * there is no runtime dialog. The library opens the right page and polls
     * the matching getter (e.g. [android.os.Environment.isExternalStorageManager]).
     */
    public sealed class Special : Permission() {

        /** Overlay / draw-on-top. Settings → `ACTION_MANAGE_OVERLAY_PERMISSION`. */
        public object SystemAlertWindow : Special()

        /** Modify system settings. Settings → `ACTION_MANAGE_WRITE_SETTINGS`. */
        public object WriteSettings : Special()

        /** Android 11+ (API 30). All-files access. */
        public object ManageExternalStorage : Special()

        /** Android 8+ (API 26). Install unknown apps. */
        public object RequestInstallPackages : Special()

        /** Android 12+ (API 31). */
        public object ScheduleExactAlarm : Special()

        /** Android 13+ (API 33) — auto-granted at install time; status is read-only. */
        public object UseExactAlarm : Special()

        /** Battery whitelist. */
        public object IgnoreBatteryOptimizations : Special()

        /** Do Not Disturb access. */
        public object AccessNotificationPolicy : Special()

        /**
         * Notification posting permission, generic. On Android 13+ prefer
         * [Runtime.PostNotifications] — that gives a real runtime dialog.
         * On older versions the only path is the app notification settings.
         */
        public object Notifications : Special()

        /** Settings → `ACTION_USAGE_ACCESS_SETTINGS`. */
        public object PackageUsageStats : Special()

        /**
         * Notification listener access. Pollable via
         * [androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages].
         * Caller must declare a `NotificationListenerService` in their manifest;
         * pass its fully-qualified class name.
         */
        public data class NotificationListener(public val serviceClass: String) : Special()

        /**
         * Accessibility service. Pollable via [android.provider.Settings.Secure]
         * `enabled_accessibility_services`. Caller must declare an
         * `AccessibilityService` in their manifest.
         */
        public data class AccessibilityService(public val serviceClass: String) : Special()
    }

    /**
     * Escape hatch for permission strings not enumerated above (vendor permissions,
     * upcoming Android additions, internal-use system permissions, etc.).
     *
     * Use it sparingly — prefer adding a proper [Runtime] constant when possible.
     */
    public data class Custom(public val manifestPermission: String) : Permission()
}
