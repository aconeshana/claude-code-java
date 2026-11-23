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
        ivy {
            // Eclipse TM4E does not publish releases to Maven Central, and its snapshot
            // Nexus (`repo.eclipse.org`) suffers periodic 503 outages while forcing 24h
            // metadata revalidation. Resolve the immutable release bundle directly from
            // Eclipse's static release mirror instead.
            name = "tm4eReleases"
            url = uri("https://download.eclipse.org/tm4e/releases/0.18.0/plugins/")
            patternLayout {
                artifact("[artifact]_[revision].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("org.eclipse", "org.eclipse.tm4e.core")
            }
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
    "claude-code-gateway",
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
