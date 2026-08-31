plugins { `java-library` }

sourceSets.main {
    resources.exclude("tool-text/README.md")
}

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-http"))
    implementation(project(":claude-code-permissions"))
    implementation(project(":claude-code-session"))
    implementation(project(":claude-code-lsp"))
    implementation(project(":claude-code-mcp"))
    implementation(project(":claude-code-runtime"))
    api(libs.jackson.databind)
    api(libs.commons.lang3)
    api(libs.okhttp)
    api(libs.graal.polyglot)
    api(libs.graal.js.language)
}
