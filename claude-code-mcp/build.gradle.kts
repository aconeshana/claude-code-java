plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-http"))
    api(libs.commons.lang3)
    api(libs.jackson.databind)
    api(libs.okhttp)
}
