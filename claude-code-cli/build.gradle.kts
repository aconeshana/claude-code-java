plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-http"))
    implementation(project(":claude-code-api"))
    implementation(project(":claude-code-permissions"))
    implementation(project(":claude-code-runtime"))
    implementation(project(":claude-code-session"))
    implementation(project(":claude-code-mcp"))
    implementation(project(":claude-code-commands"))
    implementation(project(":claude-code-ui"))
    implementation(project(":claude-code-tools"))
    implementation(project(":claude-code-services"))
    implementation(project(":claude-code-lsp"))
    api(libs.picocli)
    api(libs.commons.lang3)
    api(libs.logback.classic)
    annotationProcessor(libs.picocli.codegen)
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf(
        "-Aproject=${rootProject.name}/${project.name}",
    ))
}

// ClaudeCodeCliFlagMatrixTest reads the flag matrix and the smoke plan from the source tree
// rather than the classpath, so edits to either are invisible to Gradle's up-to-date check
// without this. The smoke plan is declared here — not only in claude-code-app, which runs it —
// because the test that catches drift between the two ledgers lives in this module.
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("gradle/cli-flag-matrix.json"))
        .withPropertyName("cliFlagMatrix")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("gradle/cli-flag-smoke.json"))
        .withPropertyName("cliFlagSmokePlan")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
