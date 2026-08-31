plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-http"))
    api(libs.commons.lang3)
    api(libs.jackson.databind)
    api(libs.jackson.core)
    api(libs.okhttp)
    api(libs.okhttp.sse)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(libs.logback.classic)
}
