plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    api(libs.commons.lang3)
    api(libs.lsp4j)
}
