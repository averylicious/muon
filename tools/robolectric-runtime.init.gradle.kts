// Dispatch-only unsigned audit. Never imported by ordinary builds.
import org.gradle.api.tasks.testing.Test
val runtimeRepository = System.getenv("MUON_ROBO_RUNTIME_REPOSITORY")
    ?: error("An isolated runtime repository is required")
val runtimeDirectory = java.io.File(runtimeRepository)
require(runtimeDirectory.isAbsolute && runtimeDirectory.isDirectory) { "Runtime repository must exist" }
gradle.projectsEvaluated {
    gradle.rootProject.project(":app").tasks.withType<Test>().configureEach {
        systemProperty("maven.repo.local", runtimeRepository)
        systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2")
    }
}
