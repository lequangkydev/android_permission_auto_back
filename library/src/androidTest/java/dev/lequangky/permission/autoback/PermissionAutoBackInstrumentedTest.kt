package dev.lequangky.permission.autoback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionAutoBackInstrumentedTest {
    @Test
    fun checkerHandlesEveryPermissionWithoutThrowing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val autoBack = PermissionAutoBack.from(context)
        // Exercise the checker against every enumerated permission; on a fresh
        // test app most return false, but none should throw.
        val sample = listOf(
            Permission.Runtime.Camera,
            Permission.Runtime.AccessFineLocation,
            Permission.Runtime.AccessBackgroundLocation,
            Permission.Runtime.PostNotifications,
            Permission.Runtime.ReadMediaImages,
            Permission.Special.SystemAlertWindow,
            Permission.Special.ManageExternalStorage,
            Permission.Special.IgnoreBatteryOptimizations,
            Permission.Special.AccessNotificationPolicy,
            Permission.Special.Notifications,
            Permission.Special.PackageUsageStats,
            Permission.Special.NotificationListener("com.example.MyListener"),
            Permission.Special.AccessibilityService("com.example.MyA11y"),
            Permission.Custom("dev.example.custom.PERMISSION"),
        )
        sample.forEach { p ->
            // Should not throw.
            autoBack.isGranted(p)
        }
        assertNotNull(autoBack)
    }
}
