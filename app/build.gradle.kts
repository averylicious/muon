plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.avery.muon"
    // Android 17. Its platform is published as API level 37.0 (platforms;android-37.0), hence the minor level.
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.avery.muon"
        minSdk = 28
        // At 37, reaching the LAN needs ACCESS_LOCAL_NETWORK, asked for before connecting (LocalNetwork.kt).
        targetSdk = 37
        versionCode = providers.environmentVariable("MUON_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.environmentVariable("MUON_VERSION_NAME").getOrElse("0.1.0")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") {
        storeFile = file(providers.environmentVariable("MUON_DEBUG_KEYSTORE")
            .getOrElse(rootProject.file(".local/debug.keystore").path))
    }
    val releaseKeystore = providers.environmentVariable("MUON_RELEASE_KEYSTORE").orNull
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("MUON_RELEASE_PASSWORD").get()
            keyAlias = "muon-release"
            keyPassword = storePassword
        }
    }
    // Opt-in diagnostic app (docs/ci.md): CI sets it only for a manual branch run that asked for
    // one (tools/diagnostic_mode.py). Exactly "true" or "false"; anything else stops the build.
    val diagnosticDebug = when (val value = providers.environmentVariable("MUON_DIAGNOSTIC_DEBUG").getOrElse("false")) {
        "true" -> true
        "false" -> false
        else -> throw GradleException("MUON_DIAGNOSTIC_DEBUG must be true or false, not \"$value\"")
    }
    buildTypes {
        getByName("debug") {
            // Canary is built like Stable in the one way that matters for QA: not debuggable, so
            // Compose runs at full speed and phone testing reflects real performance (#125). The
            // package, signing key and update path are unchanged. "Muon β" fits the launcher's
            // one-line label, where "Muon Canary" was cut to "Muon Can…" (#29). A diagnostic build
            // is the one exception: debuggable, and named so that it cannot pass for a normal one.
            isDebuggable = diagnosticDebug
            if (diagnosticDebug) applicationIdSuffix = ".diagnostic"
            resValue("string", "app_name", if (diagnosticDebug) "Muon diag" else "Muon β")
            versionNameSuffix = "-canary." + providers.environmentVariable("MUON_VERSION_CODE").getOrElse("local") +
                if (diagnosticDebug) "-diagnostic" else ""
        }
        getByName("release") {
            // Never debuggable, diagnostic build or not; the benchmark type inherits this.
            isDebuggable = false
            // Install beside the original milestone/debug app, preserving its settings.
            applicationIdSuffix = ".release"
            resValue("string", "app_name", "Muon")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        // Only for recording the Baseline Profile (#83; docs/baseline-profile.md): Stable's code,
        // unminified so the profile names real classes (R8 rewrites them for the release build),
        // profileable, and in a package of its own ("Muon Benchmark") so Canary and Stable are
        // never touched. Signed with the debug key. Never published.
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            resValue("string", "app_name", "Muon Benchmark")
            isMinifyEnabled = false
            isShrinkResources = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { compose = true }
    // The service lifecycle harness loads the real notification-channel resources/manifest.
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.0")
    // Installs the Baseline Profile shipped in the APK on phones that are not updated through Play.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    // Real Media3 cache/index and controller callback tests on the JVM; not packaged in either APK.
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
