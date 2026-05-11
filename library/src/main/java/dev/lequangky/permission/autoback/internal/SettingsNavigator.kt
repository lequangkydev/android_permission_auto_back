package dev.lequangky.permission.autoback.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.lequangky.permission.autoback.Permission

internal object SettingsNavigator {

    /**
     * Open the Settings page best suited for [permission]. Returns `true` if the
     * Settings activity was launched (specific or fallback), `false` otherwise.
     *
     * Strategy:
     *  1. Build the most specific Intent we know about for the permission.
     *  2. Try to start it from [activity] when available (preferred — preserves
     *     task affinity), otherwise from [context] with FLAG_ACTIVITY_NEW_TASK.
     *  3. If the specific Intent fails to resolve, fall back to the app details page.
     */
    fun open(context: Context, activity: Activity?, permission: Permission): Boolean {
        val intent = intentFor(context, permission)
        if (tryStart(context, activity, intent)) return true
        val fallback = appDetailsIntent(context)
        return tryStart(context, activity, fallback)
    }

    private fun tryStart(context: Context, activity: Activity?, intent: Intent): Boolean {
        val launcher = activity ?: context
        val launchIntent = if (launcher === context) {
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            intent
        }
        return try {
            launcher.startActivity(launchIntent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun intentFor(context: Context, permission: Permission): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        return when (permission) {
            is Permission.Runtime -> appDetailsIntent(context)
            is Permission.Custom -> appDetailsIntent(context)
            is Permission.Special -> specialIntent(context, permission, pkgUri)
        }
    }

    private fun specialIntent(
        context: Context,
        permission: Permission.Special,
        pkgUri: Uri,
    ): Intent = when (permission) {
        Permission.Special.SystemAlertWindow ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)

        Permission.Special.WriteSettings ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)

        Permission.Special.ManageExternalStorage ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)
            } else {
                appDetailsIntent(context)
            }

        Permission.Special.RequestInstallPackages ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri)
            } else {
                appDetailsIntent(context)
            }

        Permission.Special.ScheduleExactAlarm ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
            } else {
                appDetailsIntent(context)
            }

        Permission.Special.UseExactAlarm ->
            // Normal permission — no Settings page exists for it. App details is
            // the most reasonable destination if the caller invokes us anyway.
            appDetailsIntent(context)

        Permission.Special.IgnoreBatteryOptimizations ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)

        Permission.Special.AccessNotificationPolicy ->
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        Permission.Special.Notifications ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else {
                appDetailsIntent(context)
            }

        Permission.Special.PackageUsageStats ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        is Permission.Special.NotificationListener ->
            // ACTION_NOTIFICATION_LISTENER_SETTINGS deep-links to the listener
            // toggle list; on API 29+ we can pre-select the caller's listener.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                    putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        "${context.packageName}/${permission.serviceClass}",
                    )
                }
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }

        is Permission.Special.AccessibilityService ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
}
