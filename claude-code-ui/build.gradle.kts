plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-commands"))
    implementation(project(":claude-code-permissions"))
    implementation(project(":claude-code-tools"))
    implementation(project(":claude-code-lsp"))
    implementation(project(":claude-code-runtime"))
    api(libs.commons.lang3)
    api(libs.commonmark)
    api(libs.commonmark.gfm.tables)
    api(libs.caffeine)
    api(libs.lanterna)
    runtimeOnly(libs.jna)
    runtimeOnly(libs.jna.platform)
    api(libs.tm4e) {
        exclude(group = "org.assertj", module = "assertj-core")
    }
    api(libs.joni)
    api(libs.icu4j)

    testImplementation(project(":claude-code-services"))
    testImplementation(project(":claude-code-mcp"))
}
