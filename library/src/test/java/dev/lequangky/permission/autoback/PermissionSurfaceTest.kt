package dev.lequangky.permission.autoback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSurfaceTest {

    @Test
    fun runtimePermissions_haveManifestStrings() {
        assertEquals("android.permission.CAMERA", Permission.Runtime.Camera.manifestPermission)
        assertEquals(
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            Permission.Runtime.AccessBackgroundLocation.manifestPermission,
        )
        assertEquals(
            "android.permission.POST_NOTIFICATIONS",
            Permission.Runtime.PostNotifications.manifestPermission,
        )
    }

    @Test
    fun customPermission_passesThrough() {
        val p = Permission.Custom("dev.example.permission.FOO")
        assertEquals("dev.example.permission.FOO", p.manifestPermission)
    }

    @Test
    fun configDefaults_matchReferenceCadence() {
        val c = Config.Default
        assertEquals(500L, c.pollIntervalMs)
        assertEquals(5L * 60L * 1000L, c.timeoutMs)
        assertTrue(c.bringAppToFrontOnGrant)
    }

    @Test(expected = IllegalArgumentException::class)
    fun config_rejectsTooSmallPollInterval() {
        Config(pollIntervalMs = 10L, timeoutMs = 5_000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun config_rejectsTimeoutSmallerThanInterval() {
        Config(pollIntervalMs = 1_000L, timeoutMs = 500L)
    }

    @Test
    fun specialPermissions_dataClasses_compareByValue() {
        val a = Permission.Special.NotificationListener("com.foo.MyListener")
        val b = Permission.Special.NotificationListener("com.foo.MyListener")
        val c = Permission.Special.NotificationListener("com.bar.MyListener")
        assertEquals(a, b)
        assertNotNull(a)
        assertTrue(a != c)
    }
}
