// Records Muon's Baseline Profile (#83) on a real phone: this test APK drives the "benchmark" build of
// the app ("Muon Benchmark", its own package, so Canary and Stable are untouched) through the journeys
// in BaselineProfileGenerator, and BaselineProfileRule writes the profile to the device. See
// docs/baseline-profile.md for how it is run and where the result goes.
plugins {
    id("com.android.test")
}
android {
    namespace = "dev.avery.muon.baselineprofile"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"
    defaultConfig {
        // Recording without root needs Android 13; the app's own floor is lower.
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    // The test drives another app, so it instruments itself rather than the target.
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// Only the benchmark variant exists: debug would target the Canary package.
androidComponents { beforeVariants { it.enable = it.buildType == "benchmark" } }
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
}
