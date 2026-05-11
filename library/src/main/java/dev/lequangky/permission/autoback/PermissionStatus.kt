package dev.lequangky.permission.autoback

/**
 * The granted state of a permission at a moment in time.
 *
 * - [Granted] — currently granted.
 * - [Denied] — not granted; can still be requested (runtime dialog or Settings).
 * - [PermanentlyDenied] — runtime permission whose dialog will no longer be
 *   shown by the OS (user picked "Don't ask again" or denied twice on API 30+).
 *   Only reachable via [PermissionAutoBack.openSettingsAndAwait]; not surfaced
 *   for [Permission.Special] (special permissions cannot be permanently denied
 *   in the same sense).
 */
public enum class PermissionStatus {
    Granted,
    Denied,
    PermanentlyDenied,
}
