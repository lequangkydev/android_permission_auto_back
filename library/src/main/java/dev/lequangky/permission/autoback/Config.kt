package dev.lequangky.permission.autoback

import androidx.annotation.IntRange

/**
 * Tunables for polling behaviour.
 *
 * Defaults match the reference Flutter plugin: poll every 500 ms, give up after
 * 5 minutes. Callers can shorten the timeout for tight UX flows (e.g. 30 s) or
 * lengthen it for permissions known to take longer to grant.
 *
 * @property pollIntervalMs how often to re-check the permission state, in ms.
 * @property timeoutMs maximum time to spend polling before returning `false`, in ms.
 * @property bringAppToFrontOnGrant if `true`, launches the host app via its main
 *   launcher Intent once the permission flips to granted. Set to `false` for
 *   pure-polling use cases where the caller will navigate on their own.
 */
public data class Config(
    @param:IntRange(from = 50) public val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    @param:IntRange(from = 1_000) public val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    public val bringAppToFrontOnGrant: Boolean = true,
    public val debug: Boolean = false,
) {
    init {
        require(pollIntervalMs >= 50) { "pollIntervalMs must be >= 50 ms" }
        require(timeoutMs >= pollIntervalMs) { "timeoutMs must be >= pollIntervalMs" }
    }

    public companion object {
        public const val DEFAULT_POLL_INTERVAL_MS: Long = 500L
        public const val DEFAULT_TIMEOUT_MS: Long = 5L * 60L * 1000L

        public val Default: Config = Config()
    }
}
