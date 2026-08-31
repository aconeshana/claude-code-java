plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-permissions"))
    api(libs.commons.lang3)

    testImplementation(libs.logback.classic)
}
