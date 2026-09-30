import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

// Read-only audit task: no signing, compilation, dependency updates or verification bootstrap.
gradle.projectsEvaluated {
    val app = gradle.rootProject.project(":app")
    app.tasks.register("auditDependencyInventory") {
        doLast {
            val scopes = linkedMapOf<String, org.gradle.api.artifacts.Configuration>()
            listOf("debugRuntimeClasspath", "releaseRuntimeClasspath",
                "debugUnitTestRuntimeClasspath", "releaseUnitTestRuntimeClasspath").forEach {
                scopes[":app:$it"] = app.configurations.getByName(it)
            }
            scopes[":build:classpath"] = gradle.rootProject.buildscript.configurations.getByName("classpath")
            val commit = System.getenv("GITHUB_SHA") ?: throw GradleException("GITHUB_SHA is required for audit provenance")
            if (!commit.matches(Regex("[0-9a-f]{40}"))) throw GradleException("Invalid audit commit")
            val configurations = scopes.map { (name, configuration) ->
                val result = configuration.incoming.resolutionResult
                val failures = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
                if (failures.isNotEmpty()) throw GradleException("Unresolved dependencies in $name", failures.first().failure)
                val modules = result.allComponents.mapNotNull { component ->
                    (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" }
                }.distinct().sorted()
                if (modules.isEmpty()) throw GradleException("Empty dependency inventory for $name")
                mapOf("scope" to name, "modules" to modules)
            }
            val json = JsonOutput.toJson(mapOf("schema" to 1, "commit" to commit, "configurations" to configurations))
            val output = app.layout.buildDirectory.file("reports/dependency-inventory.json").get().asFile
            output.parentFile.mkdirs()
            output.writeText(json + "\n")
            // Public Maven coordinates only. Logs remain usable when artifact storage is full.
            app.logger.lifecycle("MUON_DEPENDENCY_INVENTORY {}", json)
        }
    }
}
