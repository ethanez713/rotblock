// Top-level build file. Plugin versions are declared here and applied in :app.
// AGP 8.6.0 on Gradle 8.7 is the pairing already proven on this machine.
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
