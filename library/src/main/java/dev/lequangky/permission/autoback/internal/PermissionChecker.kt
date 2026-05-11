package dev.lequangky.permission.autoback.internal

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.lequangky.permission.autoback.Permission
import dev.lequangky.permission.autoback.PermissionStatus

internal object PermissionChecker {

    fun isGranted(context: Context, permission: Permission): Boolean = when (permission) {
        is Permission.Runtime -> isRuntimeGranted(context, permission)
        is Permission.Special -> isSpecialGranted(context, permission)
        is Permission.Custom -> hasRuntime(context, permission.manifestPermission)
    }

    fun status(context: Context, activity: Activity?, permission: Permission): PermissionStatus {
        if (isGranted(context, permission)) return PermissionStatus.Granted
        if (permission is Permission.Runtime && activity != null) {
            val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                permission.manifestPermission,
            )
            if (!showRationale && wasEverAsked(context, permission)) {
                return PermissionStatus.PermanentlyDenied
            }
        }
        return PermissionStatus.Denied
    }

    private fun isRuntimeGranted(context: Context, permission: Permission.Runtime): Boolean {
        // OS-version short-circuits: some permission strings exist only on newer APIs and
        // are auto-granted (or simply not enforced) on older devices.
        when (permission) {
            Permission.Runtime.AccessBackgroundLocation -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return hasRuntime(context, "android.permission.ACCESS_FINE_LOCATION") ||
                        hasRuntime(context, "android.permission.ACCESS_COARSE_LOCATION")
                }
            }
            Permission.Runtime.ReadMediaImages,
            Permission.Runtime.ReadMediaVideo,
            Permission.Runtime.ReadMediaAudio -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    return hasRuntime(context, "android.permission.READ_EXTERNAL_STORAGE")
                }
            }
            Permission.Runtime.ReadMediaVisualUserSelected -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    return false
                }
            }
            Permission.Runtime.PostNotifications -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    return NotificationManagerCompat.from(context).areNotificationsEnabled()
                }
            }
            Permission.Runtime.BluetoothScan,
            Permission.Runtime.BluetoothConnect,
            Permission.Runtime.BluetoothAdvertise -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            }
            Permission.Runtime.NearbyWifiDevices -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            }
            Permission.Runtime.ActivityRecognition -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            }
            Permission.Runtime.BodySensorsBackground -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    return hasRuntime(context, "android.permission.BODY_SENSORS")
                }
            }
            Permission.Runtime.ReadPhoneNumbers,
            Permission.Runtime.AnswerPhoneCalls -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
            }
            else -> Unit
        }
        return hasRuntime(context, permission.manifestPermission)
    }

    private fun isSpecialGranted(context: Context, permission: Permission.Special): Boolean =
        when (permission) {
            Permission.Special.SystemAlertWindow ->
                Settings.canDrawOverlays(context)

            Permission.Special.WriteSettings ->
                Settings.System.canWrite(context)

            Permission.Special.ManageExternalStorage ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                    Environment.isExternalStorageManager()

            Permission.Special.RequestInstallPackages ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                    context.packageManager.canRequestPackageInstalls()

            Permission.Special.ScheduleExactAlarm -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    true
                } else {
                    (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                        ?.canScheduleExactAlarms() == true
                }
            }

            Permission.Special.UseExactAlarm ->
                // Normal permission, auto-granted at install on API 33+.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

            Permission.Special.IgnoreBatteryOptimizations -> {
                (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(context.packageName) == true
            }

            Permission.Special.AccessNotificationPolicy -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    true
                } else {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                        ?.isNotificationPolicyAccessGranted == true
                }
            }

            Permission.Special.Notifications ->
                NotificationManagerCompat.from(context).areNotificationsEnabled()

            Permission.Special.PackageUsageStats -> usageStatsGranted(context)

            is Permission.Special.NotificationListener ->
                NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)

            is Permission.Special.AccessibilityService ->
                isAccessibilityServiceEnabled(context, permission.serviceClass)
        }

    private fun hasRuntime(context: Context, manifestPermission: String): Boolean =
        ContextCompat.checkSelfPermission(context, manifestPermission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Persistent "have we ever shown this dialog" bit, used to disambiguate
     * "first run" (rationale=false, never asked) from "permanently denied"
     * (rationale=false, asked before). We store one boolean per permission in
     * a private SharedPreferences file the first time the caller invokes
     * [markAsked].
     */
    private fun wasEverAsked(context: Context, permission: Permission.Runtime): Boolean =
        prefs(context).getBoolean(permission.manifestPermission, false)

    internal fun markAsked(context: Context, permission: Permission.Runtime) {
        prefs(context).edit().putBoolean(permission.manifestPermission, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "dev.lequangky.permission.autoback.prefs"

    @Suppress("DEPRECATION")
    private fun usageStatsGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            as? android.app.AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: String): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = "${context.packageName}/$serviceClass"
        // Stored as a colon-separated list of ComponentName flattenings.
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
