import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.security.MessageDigest

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
            fun coordinate(id: org.gradle.api.artifacts.component.ComponentIdentifier): String? =
                (id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" }
            // Size and SHA-256 of files Gradle already resolved: an observation, not authentication.
            val observed = hashMapOf<java.io.File, Pair<Long, String>>()
            fun observe(file: java.io.File): Pair<Long, String> = observed.getOrPut(file) {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        size += read
                    }
                }
                size to digest.digest().joinToString("") { "%02x".format(it) }
            }
            val configurations = scopes.map { (name, configuration) ->
                val result = configuration.incoming.resolutionResult
                val failures = result.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
                if (failures.isNotEmpty()) throw GradleException("Unresolved dependencies in $name", failures.first().failure)
                val modules = result.allComponents.mapNotNull { coordinate(it.id) }.distinct().sorted()
                if (modules.isEmpty()) throw GradleException("Empty dependency inventory for $name")
                // The configuration's selected artifacts: no extra variant/view requested.
                // Resolving them may download files or run already-configured artifact transforms.
                val artifacts = configuration.incoming.artifacts.artifacts.mapNotNull { artifact ->
                    val module = coordinate(artifact.id.componentIdentifier) ?: return@mapNotNull null
                    val file = artifact.file
                    if (!file.isFile) throw GradleException("Resolved artifact is not a file in $name")
                    val (size, sha256) = observe(file)
                    // File name only: no runner or cache paths.
                    mapOf("module" to module, "file" to file.name, "size" to size, "sha256" to sha256)
                }.sortedWith(compareBy<Map<String, Any>>({ it["module"] as String }, { it["file"] as String }))
                if (artifacts.isEmpty()) throw GradleException("No resolved module artifacts for $name")
                val edges = result.allDependencies.filterIsInstance<ResolvedDependencyResult>().mapNotNull { dependency ->
                    val from = coordinate(dependency.from.id)
                        ?: if (dependency.from.id == result.root.id) "<root>" else null
                    val to = coordinate(dependency.selected.id)
                    if (from == null || to == null) null else
                        Triple(from, to, dependency.isConstraint)
                }.distinct().sortedWith(compareBy<Triple<String, String, Boolean>> { it.first }
                    .thenBy { it.second }.thenBy { it.third }).map {
                    mapOf("from" to it.first, "to" to it.second, "constraint" to it.third)
                }
                mapOf("scope" to name, "modules" to modules, "edges" to edges, "artifacts" to artifacts)
            }
            val json = JsonOutput.toJson(mapOf("schema" to 1, "commit" to commit, "configurations" to configurations))
            val output = app.layout.buildDirectory.file("reports/dependency-inventory.json").get().asFile
            output.parentFile.mkdirs()
            output.writeText(json + "\n")
            // Public Maven coordinates, file names and hashes only. Logs remain usable when artifact storage is full.
            app.logger.lifecycle("MUON_DEPENDENCY_INVENTORY {}", json)
        }
    }
}
