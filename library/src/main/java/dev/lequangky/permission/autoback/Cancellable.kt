package dev.lequangky.permission.autoback

/**
 * Handle returned by callback-style polling methods. Call [cancel] to stop
 * polling and invoke the callback with `false`. Safe to call multiple times.
 */
public fun interface Cancellable {
    public fun cancel()

    public companion object {
        public val NoOp: Cancellable = Cancellable { /* no-op */ }
    }
}
