import com.android.build.api.dsl.ApplicationExtension

plugins { id("com.android.application") }
// Use the app's selected platform; the root plugin declaration also supplies the same pinned AGP.
evaluationDependsOn(":app")
val muon = project(":app").extensions.getByType<ApplicationExtension>()
android {
    namespace = "dev.avery.muon.entryprobe"
    compileSdk = muon.compileSdk
    compileSdkMinor = muon.compileSdkMinor
    buildToolsVersion = muon.buildToolsVersion
    defaultConfig {
        applicationId = "dev.avery.muon.entryprobe"
        minSdk = muon.defaultConfig.minSdk
        targetSdk = muon.defaultConfig.targetSdk
        versionCode = providers.environmentVariable("MUON_VERSION_CODE").get().toInt()
        versionName = "qa." + providers.environmentVariable("MUON_VERSION_CODE").get()
    }
    // Default ephemeral Android debug key, never Muon's signing configuration. No release task in CI.
    buildTypes.getByName("debug") { isDebuggable = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
