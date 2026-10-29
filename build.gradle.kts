buildscript {
    dependencies {
        constraints {
            add("classpath", "com.google.guava:guava:33.7.2-jre") {
                because("GHSA-xxph-c9ww-hj94: bound native deserialization allocation in build tools")
            }
        }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.test") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
