plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.test") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no module applies this plugin. Declaring it
    // here only pins the Kotlin Gradle Plugin that AGP uses, instead of AGP's own older default.
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
