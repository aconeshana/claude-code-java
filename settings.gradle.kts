// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` and the centralized `repositories {}`
// block are @Incubating but the documented, long-stable way to declare
// dependency repositories centrally; suppress the IDE "unstable API" lint.
@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            // Lanterna 3.2.0 never shipped to Maven Central; upstream itself points users at
            // JitPack. `com.github.aconeshana:lanterna` is our fork of it — the Maven coordinate
            // differs but the Java package is still com.googlecode.lanterna, so no import changes.
            name = "jitpack"
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.aconeshana") }
        }
        maven {
            name = "tm4eSnapshots"
            url = uri("https://repo.eclipse.org/content/repositories/tm4e-snapshots/")
            mavenContent { snapshotsOnly() }
        }
    }
}

rootProject.name = "claude-code-java"

include(
    "build-recipes",
    "claude-code-core",
    "claude-code-http",
    "claude-code-api",
    "claude-code-permissions",
    "claude-code-runtime",
    "claude-code-tools",
    "claude-code-commands",
    "claude-code-mcp",
    "claude-code-session",
    "claude-code-services",
    "claude-code-ui",
    "claude-code-lsp",
    "claude-code-cli",
    "claude-code-sdk",
    "claude-code-app",
)
