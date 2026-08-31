// PMD's `ignoreFailures` lives on Gradle's @Incubating AbstractCodeQualityTask.
// It is a long-stable, documented property; suppress the IDE "unstable API" lint.
@file:Suppress("UnstableApiUsage")

import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.jvm.tasks.Jar
import org.openrewrite.gradle.RewriteExtension

plugins {
    base
    alias(libs.plugins.rewrite) apply false
    alias(libs.plugins.shadow) apply false
}

require(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_25)) {
    "Gradle must run on Java 25 or newer; current JVM is ${JavaVersion.current()}"
}

group = "com.claudecode"
// 版本号可用 `-Pversion=` 在命令行覆盖（release CI 从 tag 推导），
// 日常开发默认回退到 SNAPSHOT。
version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")

subprojects {
    group = rootProject.group
    version = rootProject.version

    // build-recipes is a build-time OpenRewrite tooling module: it must pull rewrite-java /
    // rewrite-templating at versions that conflict with the strict runtime convergence below,
    // and it must not itself be scanned by PMD or the rewrite plugin. It carries its own
    // self-contained build script instead.
    if (name == "build-recipes") return@subprojects

    apply(plugin = "java-library")
    apply(plugin = "pmd")
    apply(plugin = "org.openrewrite.rewrite")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        add("api", enforcedPlatform(rootProject.libs.jackson.bom))
        add("api", rootProject.libs.slf4j.api)
        add("annotationProcessor", rootProject.libs.picocli.codegen)

        add("testImplementation", enforcedPlatform(rootProject.libs.junit.bom))
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testImplementation", rootProject.libs.jqwik)
        add("testImplementation", rootProject.libs.assertj.core)
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
        add("rewrite", rootProject.libs.rewrite.testing.frameworks)
        add("rewrite", rootProject.libs.rewrite.migrate.java)
        add("rewrite", rootProject.libs.rewrite.static.analysis)
        add("rewrite", project(":build-recipes"))
    }

    configurations.configureEach {
        resolutionStrategy.failOnVersionConflict()
        resolutionStrategy.force(
            "org.slf4j:slf4j-api:${rootProject.libs.versions.slf4j.get()}",
            "ch.qos.logback:logback-classic:${rootProject.libs.versions.logback.get()}",
            "ch.qos.logback:logback-core:${rootProject.libs.versions.logback.get()}",
            "org.apache.commons:commons-lang3:${rootProject.libs.versions.commons.lang3.get()}",
            "commons-io:commons-io:${rootProject.libs.versions.commons.io.get()}",
            "org.commonmark:commonmark:${rootProject.libs.versions.commonmark.get()}",
            "org.commonmark:commonmark-ext-gfm-tables:${rootProject.libs.versions.commonmark.get()}",
            "com.github.aconeshana:lanterna:${rootProject.libs.versions.lanterna.get()}",
            "org.eclipse:org.eclipse.tm4e.core:${rootProject.libs.versions.tm4e.get()}",
            "org.jruby.joni:joni:${rootProject.libs.versions.joni.get()}",
            "io.methvin:directory-watcher:${rootProject.libs.versions.directory.watcher.get()}",
            "net.java.dev.jna:jna:${rootProject.libs.versions.jna.get()}",
            "com.squareup.okhttp3:okhttp-jvm:${rootProject.libs.versions.okhttp.get()}",
            "com.squareup.okhttp3:okhttp-sse:${rootProject.libs.versions.okhttp.get()}",
            "com.squareup.okhttp3:mockwebserver3:${rootProject.libs.versions.okhttp.get()}",
            "org.graalvm.polyglot:polyglot:${rootProject.libs.versions.graaljs.get()}",
            "org.graalvm.js:js-language:${rootProject.libs.versions.graaljs.get()}",
            "info.picocli:picocli:${rootProject.libs.versions.picocli.get()}",
            "info.picocli:picocli-codegen:${rootProject.libs.versions.picocli.get()}",
            "com.google.errorprone:error_prone_annotations:${rootProject.libs.versions.error.prone.annotations.get()}",
            "org.assertj:assertj-core:${rootProject.libs.versions.assertj.get()}",
            "net.bytebuddy:byte-buddy:${rootProject.libs.versions.byte.buddy.get()}",
            // okhttp-jvm 5.4.0 自带 kotlin-stdlib 2.1.21，而 okio-jvm 3.17.0 需要 2.2.21；
            // 二者在官方 central 解析下会出现版本冲突，靠 failOnVersionConflict 严格暴露。
            // 统一钉到较高版本 2.2.21（okio 用它编译，okhttp 的下限 2.1.21 向上兼容）。
            "org.jetbrains.kotlin:kotlin-stdlib:2.2.21",
        )

        // `-PlanternaLocal` swaps the published JitPack fork for whatever `mvn install`
        // last put in the local Maven repository, so iterating on a local checkout of the
        // lanterna fork does not require cutting a tag per debug cycle. Default builds stay
        // self-contained: a fresh clone resolves from JitPack alone.
        if (providers.gradleProperty("lanternaLocal").isPresent) {
            resolutionStrategy.dependencySubstitution {
                substitute(module("com.github.aconeshana:lanterna"))
                    .using(module("com.googlecode.lanterna:lanterna:3.2.0-SNAPSHOT"))
                    .because("-PlanternaLocal: resolve the fork from mavenLocal")
            }
        }
    }

    extensions.configure<PmdExtension> {
        toolVersion = rootProject.libs.versions.pmd.get()
        ruleSets = emptyList()
        ruleSetConfig = resources.text.fromFile(rootProject.file("config/pmd/ruleset.xml"))
        isConsoleOutput = true
        isIgnoreFailures = true
        threads = 1
    }
    extensions.configure<RewriteExtension> {
        activeRecipe(
            "org.openrewrite.java.testing.junit5.CleanupAssertions",
            "org.openrewrite.java.testing.junit5.AssertTrueInstanceofToAssertInstanceOf",
            "org.openrewrite.java.RemoveUnusedImports",
            "com.claudecode.recipes.ShortenFullyQualifiedTypeReferencesSafely",
            "org.openrewrite.java.migrate.lang.ReplaceUnusedVariablesWithUnderscore",
            "org.openrewrite.staticanalysis.MissingOverrideAnnotation",
            "com.claudecode.recipes.AvoidBoxedBooleanExpressionsSafely",
            "org.openrewrite.staticanalysis.AtomicPrimitiveEqualsUsesGet",
            "org.openrewrite.staticanalysis.BigDecimalDoubleConstructorRecipe",
            "org.openrewrite.staticanalysis.BigDecimalRoundingConstantsToEnums",
            "org.openrewrite.staticanalysis.CollectionToArrayShouldHaveProperType",
            "org.openrewrite.staticanalysis.IndexOfShouldNotCompareGreaterThanZero",
            "org.openrewrite.staticanalysis.RemoveRedundantNullCheckBeforeInstanceof",
            "org.openrewrite.staticanalysis.RemoveRedundantNullCheckBeforeLiteralEquals",
            "com.claudecode.recipes.ReplaceAnonymousClassWithLambdaSafely",
            "com.claudecode.recipes.ReplaceLambdaWithMethodReferenceSafely",
            "org.openrewrite.staticanalysis.UnnecessaryCloseInTryWithResources",
            "org.openrewrite.staticanalysis.UnnecessaryPrimitiveAnnotations",
            "org.openrewrite.staticanalysis.UnnecessaryReturnAsLastStatement",
            "org.openrewrite.staticanalysis.UseDiamondOperator",
            "com.claudecode.recipes.UseDiamondOperatorInTypeCastsRecipes\$LinkedHashMapStringObjectRecipe",
            "com.claudecode.recipes.UseBulkCollectionOperations",
            "com.claudecode.recipes.UseCollectionCopyConstructorsSafely",
            "com.claudecode.recipes.UseRecordPatterns",
            "com.claudecode.recipes.UseUnnamedTypePatterns",
            "org.openrewrite.staticanalysis.UseJavaStyleArrayDeclarations",
            "org.openrewrite.staticanalysis.UsePortableNewlines",
            "org.openrewrite.staticanalysis.UseStandardCharset",
            "org.openrewrite.staticanalysis.UseSystemLineSeparator",
            "org.openrewrite.staticanalysis.UseListSort",
            "org.openrewrite.staticanalysis.EqualsToContentEquals",
            "org.openrewrite.staticanalysis.UpperCaseLiteralSuffixes",
            "com.claudecode.recipes.UseStringUtilsBlankRecipes\$NullOrBlankRecipe",
            "com.claudecode.recipes.UseStringUtilsBlankRecipes\$NotNullAndNotBlankRecipe",
            "com.claudecode.recipes.UseStringUtilsBlankRecipes\$IsBlankRecipe",
            "com.claudecode.recipes.UseStringUtilsBlankRecipes\$IsNotBlankRecipe",
            "com.claudecode.recipes.UseStringUtilsEmptyRecipes\$NullOrEmptyRecipe",
            "com.claudecode.recipes.UseStringUtilsEmptyRecipes\$NotNullAndNotEmptyRecipe",
            "com.claudecode.recipes.UseNullSafeStringEquality",
            "com.claudecode.recipes.UseContainsAllRecipes\$StreamAllMatchContainsRecipe",
            "com.claudecode.recipes.UseStringContainsRecipes\$IndexOfLessThanZeroRecipe",
            "com.claudecode.recipes.UseStringContainsRecipes\$IndexOfGreaterThanOrEqualToZeroRecipe",
            "com.claudecode.recipes.UseMultilineTextBlocks",
            "com.claudecode.recipes.UseOptionalMapOrElseGet",
            "com.claudecode.recipes.UseOptionalIfPresent",
            "com.claudecode.recipes.UseStreamOfRecipes\$StringSplitRecipe",
            "com.claudecode.recipes.UseLanternaOfFactoriesRecipes\$TerminalPositionTopLeftCornerRecipe",
            "com.claudecode.recipes.UseLanternaOfFactoriesRecipes\$TerminalSizeZeroRecipe",
            "com.claudecode.recipes.UseLanternaOfFactoriesRecipes\$TextColorToColorRecipe",
        )
        failOnDryRunResults = true
        failOnInvalidActiveRecipes = true
        isEnableExperimentalGradleBuildScriptParsing = false
        throwOnParseFailures = true
        exclusion("**/coverage.yml", "**/build/**", "**/target/**")
    }
    tasks.named("rewriteDryRun") {
        notCompatibleWithConfigurationCache("OpenRewrite 7.39 uses the Project model at execution time")
    }

    val qualitySourceSets = extensions.getByType<SourceSetContainer>()
    tasks.register<Pmd>("pmdStrictMain") {
        group = "verification"
        description = "Runs the blocking PMD gate over production sources."
        setSource(qualitySourceSets.named("main").get().allJava)
        classpath = qualitySourceSets.named("main").get().output +
            qualitySourceSets.named("main").get().compileClasspath
        ignoreFailures = false
    }
    tasks.register<Pmd>("pmdStrictTest") {
        group = "verification"
        description = "Runs the blocking PMD gate over test sources."
        setSource(qualitySourceSets.named("test").get().allJava)
        classpath = qualitySourceSets.named("test").get().output +
            qualitySourceSets.named("test").get().compileClasspath
        ignoreFailures = false
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        systemProperty("claudecode.no.browser", "true")
        systemProperty("java.awt.headless", "true")
        providers.systemProperty("regen.snapshots").orNull?.let {
            systemProperty("regen.snapshots", it)
        }
        environment("ANTHROPIC_BASE_URL", "")
        environment("ENABLE_TOOL_SEARCH", "")
        environment("CLAUDE_CODE_USE_BEDROCK", "")
        environment("CLAUDE_CODE_USE_VERTEX", "")
        environment("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "")
        // The default-model env overrides shift ModelPicker's standard rows
        // (resolveOption takes the env branch), so a developer machine that sets
        // them would flip ConfigPanel's model-submenu row count. Blank them so
        // tests observe the built-in Opus/Sonnet/Haiku defaults deterministically.
        environment("ANTHROPIC_DEFAULT_OPUS_MODEL", "")
        environment("ANTHROPIC_DEFAULT_SONNET_MODEL", "")
        environment("ANTHROPIC_DEFAULT_HAIKU_MODEL", "")
    }

    val isolatedTestClasses = when (name) {
        "claude-code-runtime" -> listOf("com.claudecode.runtime.query.QueryEngineGitStatus197Test")
        "claude-code-cli" -> listOf(
            "com.claudecode.cli.SdkControlBrokerTest",
            "com.claudecode.commands.impl.context.CompactCommandTest",
        )
        "claude-code-tools" -> listOf(
            "com.claudecode.tools.tasks.teammate.TeammateLeaderCoordinatorTest",
        )
        else -> emptyList()
    }
    val mainTest = tasks.named<Test>("test") {
        include("**/*Test.class", "**/*Tests.class", "**/*Properties.class")
        isolatedTestClasses.forEach { exclude("${it.replace('.', '/')}.class") }
    }
    if (isolatedTestClasses.isNotEmpty()) {
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val isolatedStatefulTest = tasks.register<Test>("isolatedStatefulTest") {
            description = "Runs tests that own process-wide mutable state in a fresh JVM."
            group = "verification"
            testClassesDirs = sourceSets.named("test").get().output.classesDirs
            classpath = sourceSets.named("test").get().runtimeClasspath
            isolatedTestClasses.forEach { include("${it.replace('.', '/')}.class") }
            filter.isFailOnNoMatchingTests = false
            shouldRunAfter(mainTest)
        }
        mainTest.configure { finalizedBy(isolatedStatefulTest) }
    }

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version,
            )
        }
    }

    tasks.register<VerifyDependencyResolutionTask>("verifyDependencyConvergence") {
        group = "verification"
        description = "Resolves this module's runtime/test classpaths with conflict failure enabled."
        resolvedArtifacts.from(
            configurations.getByName("runtimeClasspath"),
            configurations.getByName("testRuntimeClasspath"),
        )
    }
}

val qualitySubprojects = subprojects.filter { it.name != "build-recipes" }

val verifyDependencyConvergence = tasks.register("verifyDependencyConvergence") {
    group = "verification"
    description = "Resolves production and test classpaths with version-conflict failure enabled."
    dependsOn(qualitySubprojects.map { "${it.path}:verifyDependencyConvergence" })
}

val rewriteDryRun = tasks.register("rewriteDryRun") {
    group = "verification"
    description = "Runs OpenRewrite assertion recipes across all modules without modifying sources."
    dependsOn(qualitySubprojects.map { "${it.path}:rewriteDryRun" })
    notCompatibleWithConfigurationCache("OpenRewrite 7.39 uses the Project model at execution time")
}

tasks.register("preCommitQuality") {
    group = "verification"
    description = "Runs the repository PMD, dependency, and OpenRewrite commit gates."
    dependsOn(verifyDependencyConvergence, rewriteDryRun)
    dependsOn(qualitySubprojects.flatMap { module ->
        listOf("${module.path}:pmdStrictMain", "${module.path}:pmdStrictTest")
    })
}
