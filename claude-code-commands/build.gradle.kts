plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-runtime"))
    api(libs.commons.lang3)
    api(libs.commonmark)
}
