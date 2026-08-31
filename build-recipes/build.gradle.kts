plugins {
    `java-library`
}

// Self-contained tooling module. Intentionally NOT wired into the root subprojects{}
// block: it pulls rewrite-java / rewrite-templating at versions that clash with the
// strict runtime convergence, and it must not be scanned by PMD or the rewrite plugin.
// It produces the OpenRewrite recipe jar consumed via the `rewrite(...)` configuration.

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(libs.rewrite.bom))
    implementation(libs.rewrite.java)
    implementation(libs.rewrite.migrate.java)
    implementation(libs.rewrite.static.analysis)
    implementation(libs.commons.lang3)
    implementation(libs.lanterna)

    // Refaster template support: the processor generates the Recipe class from the
    // @BeforeTemplate/@AfterTemplate methods; error_prone_core supplies those annotations.
    annotationProcessor(libs.rewrite.templating)
    implementation(libs.rewrite.templating)
    // The generated recipes reference javax.annotation.Generated (removed from the JDK in SE 11).
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
    compileOnly("${libs.error.prone.core.get()}:with-dependencies") {
        exclude(group = "com.google.auto.service", module = "auto-service-annotations")
    }

    testImplementation(platform(libs.rewrite.bom))
    testImplementation("org.openrewrite:rewrite-test")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Error Prone's Refaster machinery reaches into javac internals; the templating
    // processor needs these exports to run under a modern JDK.
    options.isFork = true
    options.forkOptions.jvmArgs = (options.forkOptions.jvmArgs ?: mutableListOf()).apply {
        addAll(
            listOf(
                "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
            )
        )
    }
}
