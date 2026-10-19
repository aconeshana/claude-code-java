import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory

/**
 * The release tag is the single source of the project version. CI passes it in as
 * `-Pversion=` (derived from the pushed `v*` tag); every other build derives it from the same
 * tags through `git describe`, so a local build reads `0.2.5` on a tagged commit and
 * `0.2.5-5-gb9361840` five commits later — never a hand-maintained number that drifts behind.
 */
fun ProviderFactory.projectVersion(): Provider<String> =
    gradleProperty("version").orElse(gitDescribeVersion()).orElse(FALLBACK_VERSION)

/** Used outside a git checkout or before the first tag exists. */
const val FALLBACK_VERSION = "0.1.0-SNAPSHOT"

private val describedVersion = Regex("""v(\d+\.\d+\.\d+(?:-\d+-g[0-9a-f]+)?)""")

/** Strips the tag's leading `v`; returns null when the output is not a release tag. */
fun versionFromGitDescribe(output: String): String? =
    describedVersion.matchEntire(output.trim())?.groupValues?.get(1)

/**
 * `providers.exec` keeps the call compatible with the configuration cache, which re-runs it
 * to decide whether a cached configuration is still valid. A missing `git` or a checkout without
 * tags exits non-zero; that is a fallback case, not a build failure.
 *
 * `--exclude 'v*-*'` skips suffixed tags such as `v0.2.6-rc1`: describe picks the nearest
 * matching tag, so one of those on HEAD would otherwise hide the release it sits next to.
 */
private fun ProviderFactory.gitDescribeVersion(): Provider<String> {
    val describe = exec {
        commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--exclude", "v*-*")
        isIgnoreExitValue = true
    }
    return describe.result.zip(describe.standardOutput.asText) { result, output ->
        if (result.exitValue == 0) versionFromGitDescribe(output).orEmpty() else ""
    }.filter { it.isNotEmpty() }
}
