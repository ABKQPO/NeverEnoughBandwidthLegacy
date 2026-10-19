import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

val compressionProbeClasses = layout.buildDirectory.dir("tmp/compression-probe/classes")
val compileCompressionProbe = tasks.register<JavaCompile>("compileCompressionProbe") {
    dependsOn(tasks.named("classes"))
    source = fileTree("tools") {
        include("CompressionProbe.java")
    }
    classpath = sourceSets.main.get().runtimeClasspath
    destinationDirectory.set(compressionProbeClasses)
    options.encoding = "UTF-8"
}

tasks.register<JavaExec>("compressionProbe") {
    dependsOn(compileCompressionProbe)
    classpath = files(compressionProbeClasses, sourceSets.main.get().runtimeClasspath)
    mainClass.set("CompressionProbe")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

minecraft {
    extraRunJvmArguments.addAll("-Xmx8G", "-Xms8G", "-Dgtnhlib.dumpkeys=true")
}

tasks.withType<JavaCompile>().configureEach {
    options.annotationProcessorPath = configurations.annotationProcessor.get()
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

val devClientMods = configurations.named("devClientMods")
listOf("runClient", "runClient17", "runClient21", "runClient25").forEach { taskName ->
    tasks.named<JavaExec>(taskName) {
        classpath(devClientMods)
    }
}
