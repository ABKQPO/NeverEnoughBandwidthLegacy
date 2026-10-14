plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

minecraft {
    extraRunJvmArguments.addAll("-Xmx4G")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val runConfigs = listOf(
    "runClient" to "run/client",
    "runClient17" to "run/client_new",
    "runClient21" to "run/client_new",
    "runClient25" to "run/client_new",
    "runServer" to "run/server",
    "runServer17" to "run/server_new",
    "runServer21" to "run/server_new",
    "runServer25" to "run/server_new"
)

runConfigs.forEach { (taskName, path) ->
    tasks.named<JavaExec>(taskName) {
        workingDir = file("${projectDir}/$path")
        doFirst {
            workingDir.mkdirs()
        }
    }
}

// Opt-in auto-connect so a dev client can be driven from the command line:
//   ./gradlew runClient25 -PnebConnect=127.0.0.1:25565
val nebConnect = providers.gradleProperty("nebConnect")
if (nebConnect.isPresent) {
    val host = nebConnect.get().substringBefore(':')
    val port = nebConnect.get().substringAfter(':', "25565")
    listOf("runClient", "runClient17", "runClient21", "runClient25").forEach { taskName ->
        tasks.named<JavaExec>(taskName) {
            args("--server", host, "--port", port)
        }
    }
}
