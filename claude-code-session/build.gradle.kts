plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    api(libs.commons.lang3)
    api(libs.jackson.databind)
    api(libs.jackson.jdk8)
    api(libs.jackson.jsr310)

    testImplementation(libs.logback.classic)
}
