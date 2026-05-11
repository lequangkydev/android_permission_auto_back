// Top-level build file. Sub-projects apply the plugins they need.
// AGP 9 bundles Kotlin support, so no separate kotlin.android plugin is needed.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
