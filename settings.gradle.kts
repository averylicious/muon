pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Muon"
include(":app")
// Records the Baseline Profile on a phone (#83); not part of the ordinary build.
include(":baselineprofile")

// Disposable foreign-UID QA helper: configured only in validated manual diagnostic builds.
if (providers.environmentVariable("MUON_DIAGNOSTIC_DEBUG").orNull == "true") include(":mediaentryprobe")
