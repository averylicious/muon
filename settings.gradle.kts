pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Muon"
include(":app")
// Records the Baseline Profile on a phone (#83); not part of the ordinary build.
include(":baselineprofile")
