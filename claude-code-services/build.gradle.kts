plugins { `java-library` }

dependencies {
    api(enforcedPlatform(libs.opentelemetry.bom))

    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-runtime"))
    implementation(project(":claude-code-http"))
    implementation(project(":claude-code-api"))
    implementation(project(":claude-code-mcp"))
    implementation(project(":claude-code-permissions"))
    implementation(project(":claude-code-session"))
    implementation(project(":claude-code-tools"))
    api(libs.commons.lang3)
    api(libs.jackson.databind)
    api(libs.jackson.jdk8)
    api(libs.jackson.jsr310)
    api(libs.jackson.yaml)
    api(libs.directory.watcher)
    api(libs.commonmark)
    api(libs.okhttp)
    api(libs.opentelemetry.api)
    api(libs.opentelemetry.sdk)
    api(libs.opentelemetry.sdk.logs)
    api(libs.opentelemetry.sdk.metrics)
    api(libs.opentelemetry.sdk.trace)
    api(libs.opentelemetry.exporter.otlp)
    api(libs.opentelemetry.exporter.logging)
}
