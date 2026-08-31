# Build and Testing Guide

## Build and run

~~~bash
# Full build and regression suite
./gradlew build

# Single module
./gradlew :claude-code-core:build
./gradlew :claude-code-cli:build

# Runnable fat JAR
./gradlew :claude-code-app:shadowJar

# Native images
./gradlew :claude-code-app:nativeQuickCompile
./gradlew :claude-code-app:nativeReleaseCompile

# Run the application
java -jar claude-code-app/build/libs/claude-code-app-0.1.0-SNAPSHOT.jar
~~~

The Gradle wrapper pins Gradle 9.7.0 and its distribution checksum. Dependency
versions live in `gradle/libs.versions.toml`. The 15 product modules compile
with the Java 25 toolchain and `--release 25`; `build-recipes` is a separate
build-time helper project. Gradle itself must also run on Java 25 or newer.
Build caching, configuration caching, and parallel project execution are
enabled in `gradle.properties`.

### Lanterna fork

The terminal UI runs on the project fork of Lanterna rather than the upstream
release. The fork contains input and terminal-protocol support required by this
project, including Escape+Enter and horizontal-wheel decoding and OSC 8
hyperlink handling, together with CJK and screen-refresh optimizations.

It resolves from JitPack, so a fresh clone needs no extra setup:

~~~text
com.github.aconeshana:lanterna:3.2.0-cc3
~~~

The Maven coordinate differs from upstream, but the Java package remains
com.googlecode.lanterna. The fork targets Java 8 even though this project
uses Java 25.

To iterate on the fork itself:

~~~bash
# In the Lanterna fork checkout
./mvnw install -DskipTests

# In this repository
./gradlew build -PlanternaLocal
~~~

### Bundled native assets

The JAR and native executable are platform-specific distributions. By default,
the build detects the current macOS, Linux, or Windows architecture and packages
ripgrep 15.2.0. macOS and Linux targets also package the matching cc-connect
Session Host. The archives are verified against the metadata and SHA-256 pins in
`gradle/native-assets.properties`.

Supported distribution targets are `darwin-arm64`, `darwin-amd64`,
`linux-arm64`, `linux-amd64`, `windows-arm64`, and `windows-amd64`. Windows
targets package ripgrep but omit cc-connect. Select any target explicitly with:

~~~bash
./gradlew :claude-code-app:shadowJar -PdistributionTarget=windows-amd64
~~~

Session Host currently requires macOS or Linux Unix-domain sockets. The Windows
JVM distribution can still package and run without that sidecar.

To package a locally built or unpublished cc-connect release, provide a
directory containing the selected archive named in the manifest:

~~~bash
./gradlew :claude-code-app:shadowJar \
  -PccConnectAssetDir=/path/to/cc-connect-release-assets
~~~

The application JAR remains self-contained at runtime and does not contact
GitHub to obtain these bundled assets.

`nativeQuickCompile` uses GraalVM `-Ob` and writes to
`claude-code-app/build/native/nativeQuickCompile/`. `nativeReleaseCompile` uses
`-Os` and writes to `claude-code-app/build/native/nativeReleaseCompile/`. Both
variants use the same application classpath and generated platform resources.

## Run tests

~~~bash
# Full regression
./gradlew test

# Single module
./gradlew :claude-code-core:test

# Rebuild all test outputs when stale artifacts are suspected
./gradlew clean test
~~~

Ordinary incremental runs should use `./gradlew test`; the build is cache-aware.

## Startup flag coverage

CLI startup-flag coverage is tracked at two levels by JSON ledgers in
`gradle/`.

L1, the parse level, is defined by `gradle/cli-flag-matrix.json`. It records the
supported root options and their expected Java launch-request field deltas.
`ClaudeCodeCliFlagMatrixTest` runs as part of `test` and fails when the option
set and matrix diverge. This proves that each executable flag reaches the
expected field.

L2, the process level, is defined by `gradle/cli-flag-smoke.json`. It turns
matrix entries into real process runs against a real socket and a fake
Anthropic server. This layer catches reflection metadata gaps, missing
resources, and class-initialization order problems that an in-process JVM test
cannot see.

~~~bash
./gradlew :claude-code-app:flagStartupSmoke
./gradlew :claude-code-app:flagStartupSmoke -PsmokeTargets=jar
./gradlew :claude-code-app:flagStartupSmoke -PsmokeRequireNative=true
~~~

The smoke task is intentionally not wired into `check`: each case starts a
process, and native targets are not on the default build path. Native binaries
are discovered rather than built by the task. Missing native binaries are
reported and skipped unless `-PsmokeRequireNative=true` is set; the report also
includes each discovered target's build time so stale binaries are visible.

The harness workspace lives outside any Git repository by default at
`java.io.tmpdir/claude-code-flag-smoke`, overridable with `-Dsmoke.root`. This
is required so resume, continue, and worktree cases exercise real repository
boundaries without mutating the checkout. The matrix and smoke ledgers are
joined by ID; missing cases, unresolved names, and unrecorded exclusions are
test failures.

## Commit-time Java quality checks

Install the repository-managed hook once per clone:

~~~bash
./scripts/install-git-hooks.sh
~~~

To run the same checks manually:

~~~bash
./gradlew --no-configuration-cache --no-parallel preCommitQuality
~~~

The aggregate task runs the repository PMD rules over main and test sources,
resolves runtime and test classpaths with version-conflict failure enabled, and
runs the configured OpenRewrite recipes in dry-run/fail-on-change mode. The PMD
rules include checks for locale-sensitive case conversion, `printStackTrace`,
ignored file-permission results, and string-comparison APIs.

## Property-based testing

The default test filter includes classes ending in `Properties`. The current
jqwik suites are:

| Property | Test class |
|----------|------------|
| Message and content-block serialization compatibility | `MessageSerializationProperties` |
| Tool execution determinism | `ToolExecutionDeterminismProperties` |
| Permission decision consistency and immutable updates | `PermissionDecisionProperties` |
| Task lifecycle correctness | `TaskLifecycleProperties` |
| Session compaction safety | `CompactSafetyProperties` |

## Current validation boundaries

The default test task covers the module and contract suites. The following
checks remain separate because they require an external process, an independent
harness, or a platform-specific runtime:

- `flagStartupSmoke` starts packaged processes and is opt-in;
- wire-level request comparison uses an independent harness outside the Gradle
  build;
- cross-platform terminal behavior must be exercised on each distribution
  target;
- native-image builds are available, but are not part of the ordinary JVM
  test path.

These are validation boundaries, not a product roadmap.

## Configuration

- API key resolution order: `--api-key`, `ANTHROPIC_API_KEY`, then the
  `primaryApiKey` field in the global config (legacy field: `apiKey`)
- Bearer auth for an independent gateway channel: `ANTHROPIC_AUTH_TOKEN`
- Settings sources: user `~/.claude/settings.json`, project
  `<project>/.claude/settings.json`, local `<project>/.claude/settings.local.json`,
  OS-specific administrator-managed policy settings and `managed-settings.d`
  drop-ins, and a file or inline JSON object supplied with `--settings`;
  file-backed settings use strict JSON after an optional UTF-8 BOM, so comments
  and trailing commas are rejected
- Instruction discovery: user-level `~/.claude/CLAUDE.md` and
  `~/.claude/rules/**/*.md`; at each directory from the filesystem root to the
  working directory, `CLAUDE.md`, `.claude/CLAUDE.md`,
  `.claude/rules/**/*.md`, and `CLAUDE.local.md`
- Transcript storage: `~/.claude/projects/<sanitized-cwd>/<sessionId>.jsonl`
- Configuration-directory override: `CLAUDE_CONFIG_DIR`; without it, the
  configuration home is `~/.claude` and the global config is `~/.claude.json`.
  With it, the configuration home is `$CLAUDE_CONFIG_DIR` and the global config
  is `$CLAUDE_CONFIG_DIR/.claude.json`, unless the legacy
  `$CLAUDE_CONFIG_DIR/.config.json` exists

## Wire-level request validation

Wire-level request comparison is maintained in a separate harness rather than
in the Gradle multi-project build. It is not included in `./gradlew test` or
`./gradlew build`; follow that harness's own documentation when validating
request assembly.
