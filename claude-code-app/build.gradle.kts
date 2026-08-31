import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    `java-library`
    application
    alias(libs.plugins.shadow)
    alias(libs.plugins.graalvm.native)
}

application {
    mainClass = "com.claudecode.cli.ClaudeCodeCli"
    // Interactive sessions favor predictable first-use latency over peak C2
    // throughput. Tier 3 keeps fully optimized C1 code without the cold C2
    // compilation spikes seen in the PTY model/config/send cases; Serial GC
    // avoids concurrent collector noise in this small, latency-sensitive CLI.
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:TieredStopAtLevel=3",
        "-XX:+UseSerialGC",
    )
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "claude-code-app"
            mainClass = "com.claudecode.cli.ClaudeCodeCli"
            // Without this the plugin defaults to a shared library, likely because this module
            // also applies `java-library` alongside `application`.
            sharedLibrary = false
            // tasks.jar is disabled for this module (see below) and shadowJar writes its fat jar
            // to that same conventional path, so the plugin's default classpath resolution would
            // otherwise pick up the shaded jar as this project's own artifact. That fat jar merges
            // org.graalvm.sdk:word's classes without its module descriptor, which breaks native-image's
            // ForceOnModulePath handling for that module. Point the classpath at the raw sourceSet
            // output plus the normal per-dependency runtimeClasspath instead, bypassing the shadow jar.
            classpath.setFrom(sourceSets.main.get().output, configurations.runtimeClasspath)
        }
        create("quick") {
            imageName = "claude-code-app"
            mainClass = "com.claudecode.cli.ClaudeCodeCli"
            sharedLibrary = false
            classpath.setFrom(sourceSets.main.get().output, configurations.runtimeClasspath)
            buildArgs.add("-Ob")
        }
        create("release") {
            imageName = "claude-code-app"
            mainClass = "com.claudecode.cli.ClaudeCodeCli"
            sharedLibrary = false
            classpath.setFrom(sourceSets.main.get().output, configurations.runtimeClasspath)
            buildArgs.add("-Os")
        }
    }
}

// GraalVM Build Tools 0.11 currently retains Gradle Project/SourceSet objects
// in its native task graph. Gradle 9 cannot serialize those objects into the
// configuration cache, so explicitly opt only the native-image tasks out.
// Regular JVM builds continue to use the repository-wide configuration cache.
// The resources-config task is named after its binary — `generateResourcesConfigFile` for
// `main`, but `generateQuickResourcesConfigFile` and `generateReleaseResourcesConfigFile` for
// the two extra ones — so the predicate has to match the whole family. Matching only the
// `main` spelling left `nativeQuickCompile` and `nativeReleaseCompile` unbuildable with the
// repository's default configuration cache, which are the two the startup smoke asks for.
tasks.configureEach {
    val generatesResourcesConfig = name.startsWith("generate") && name.endsWith("ResourcesConfigFile")
    if (generatesResourcesConfig || name.startsWith("native")) {
        notCompatibleWithConfigurationCache(
            "GraalVM Build Tools retains non-serializable Gradle model objects"
        )
    }
}

val nativeAssetManifest = rootProject.layout.projectDirectory.file("gradle/native-assets.properties")
val generatedRipgrepResources = layout.buildDirectory.dir("generated/ripgrep-resources")
val generatedCcConnectResources = layout.buildDirectory.dir("generated/cc-connect-resources")
val detectedDistributionTarget = providers.provider {
    val platform = when {
        System.getProperty("os.name").lowercase().contains("mac") -> "darwin"
        System.getProperty("os.name").lowercase().contains("linux") -> "linux"
        System.getProperty("os.name").lowercase().contains("win") -> "windows"
        else -> error("unsupported distribution platform: ${System.getProperty("os.name")}")
    }
    val architecture = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "amd64"
        else -> error("unsupported distribution architecture: ${System.getProperty("os.arch")}")
    }
    "$platform-$architecture"
}
val distributionTarget = nativeDistributionTarget(
    providers.gradleProperty("distributionTarget")
        .orElse(detectedDistributionTarget)
        .get(),
)
val moduleArchitectureInputs = rootProject.fileTree(rootProject.projectDir) {
    include("claude-code-*/build.gradle.kts")
    include("claude-code-*/src/main/java/**/*.java")
    include("claude-code-*/src/test/java/**/*.java")
}

// NativeReachabilityMetadataTest reads this file from the source tree rather than the
// classpath, so edits to it are invisible to Gradle's up-to-date check without this.
val nativeReachabilityMetadata = layout.projectDirectory.file(
    "src/main/resources/META-INF/native-image/com.claudecode/claude-code-app/reachability-metadata.json"
)

val vendorRipgrep = tasks.register<VendorRipgrepTask>("vendorRipgrep") {
    group = "native assets"
    description = "Downloads and verifies ripgrep for ${distributionTarget.name}."
    manifestFile = nativeAssetManifest
    targets.set(listOf(distributionTarget.ripgrepTarget))
    outputDirectory = generatedRipgrepResources
    downloadCacheDirectory = file("${gradle.gradleUserHomeDir}/caches/ripgrep")
}

val vendorCcConnect = distributionTarget.ccConnectTarget?.let { ccConnectTarget ->
    tasks.register<VendorCcConnectTask>("vendorCcConnect") {
        group = "native assets"
        description = "Downloads and verifies cc-connect for ${distributionTarget.name}."
        manifestFile = nativeAssetManifest
        targets.set(listOf(ccConnectTarget))
        outputDirectory = generatedCcConnectResources
        downloadCacheDirectory = file("${gradle.gradleUserHomeDir}/caches/cc-connect")
        providers.gradleProperty("ccConnectAssetDir").orNull?.let {
            localAssetDirectory = file(it)
        }
    }
}

sourceSets.main {
    resources.exclude("native/README.md")
    resources.srcDir(vendorRipgrep)
    vendorCcConnect?.let { resources.srcDir(it) }
}

dependencies {
    implementation(project(":claude-code-cli"))
    runtimeOnly(libs.logback.classic)
    // NativeReachabilityMetadataTest reflects over the Jackson binding closure and
    // parses the committed reachability metadata; jackson reaches this module only
    // transitively at runtime, so the test needs it declared directly.
    testImplementation(libs.jackson.databind)
    // The repository's PMD strict gate requires commons-lang3's null-safe Strings for every
    // string comparison, which the smoke harness does plenty of. It arrives here transitively
    // through claude-code-cli's api dependency; declared directly so the test source does not
    // depend on that staying an api one.
    testImplementation(libs.commons.lang3)
}

tasks.jar {
    enabled = false
}

tasks.shadowJar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    eachFile {
        if (!path.startsWith("META-INF/services/") && !path.endsWith(".kotlin_module")) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }
    }
    archiveBaseName = "claude-code-app"
    archiveClassifier = ""
    archiveVersion = project.version.toString()
    mergeServiceFiles()
    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/*.EC",
        "META-INF/sig-*",
    )
    manifest {
        attributes["Main-Class"] = "com.claudecode.cli.ClaudeCodeCli"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        attributes["Claude-Code-Distribution-Target"] = distributionTarget.name
    }
}

tasks.check {
    vendorCcConnect?.let { dependsOn(it) }
}

tasks.named<Test>("test") {
    inputs.files(moduleArchitectureInputs)
        .withPropertyName("moduleArchitectureInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(nativeReachabilityMetadata)
        .withPropertyName("nativeReachabilityMetadata")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// `tasks.jar` is disabled for this module and shadowJar writes to the conventional
// jar path, so generated launchers consume shadowJar's output without a declared edge. Make the
// ordering explicit and keep the Windows console aligned with Java's UTF-8 output.
tasks.withType<CreateStartScripts>().configureEach {
    dependsOn(tasks.shadowJar)
    doLast {
        val current = windowsScript.readText(Charsets.UTF_8)
        windowsScript.writeText(configureWindowsUtf8StartScript(current), Charsets.UTF_8)
    }
}

val jarContentManifest = tasks.register<JarContentManifestTask>("jarContentManifest") {
    group = "verification"
    description = "Writes a normalized path/size/SHA-256 manifest for the Gradle fat JAR."
    jarFile = tasks.shadowJar.flatMap { it.archiveFile }
    manifestFile = layout.buildDirectory.file("reports/jar-contents.sha256")
}

// Process-level startup smoke (FlagStartupSmoke). Deliberately NOT wired into `check`: every case
// spawns a real process against a real socket, and the native targets it covers are neither on the
// default build path nor buildable without GRAALVM_HOME. `test` never picks the class up either,
// because the repository-wide filter includes only *Test/*Tests/*Properties.
val flagStartupSmokeCases = listOf(
    rootProject.layout.projectDirectory.file("gradle/cli-flag-matrix.json"),
    rootProject.layout.projectDirectory.file("gradle/cli-flag-smoke.json"),
)

tasks.register<Test>("flagStartupSmoke") {
    group = "verification"
    description = "Runs every planned startup flag as a real process, against the fat JAR and " +
        "any native image already built."
    val testSourceSet = sourceSets.named("test").get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    include("**/FlagStartupSmoke.class")
    filter.isFailOnNoMatchingTests = true
    // Cases of one target share a home directory and a recorded session, so they run in sequence.
    maxParallelForks = 1
    val smokeJar = tasks.shadowJar.flatMap { it.archiveFile }
    val smokeBuildDirectory = layout.buildDirectory
    val requireNative = providers.gradleProperty("smokeRequireNative")
    val smokeTargets = providers.gradleProperty("smokeTargets")
    // Resolved when the fork is about to start rather than while configuring, so the
    // configuration cache never has to serialize a resolved artifact path.
    doFirst {
        systemProperty("smoke.jar", smokeJar.get().asFile.absolutePath)
        systemProperty("smoke.buildDir", smokeBuildDirectory.get().asFile.absolutePath)
        requireNative.orNull?.let { systemProperty("smoke.requireNative", it) }
        smokeTargets.orNull?.let { systemProperty("smoke.targets", it) }
    }
    dependsOn(tasks.shadowJar)
    inputs.files(flagStartupSmokeCases)
        .withPropertyName("flagStartupSmokeCases")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // A native image is discovered, not built: it lives outside this task's declared inputs, so a
    // rebuilt binary must not be masked by an up-to-date result from the previous one.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
