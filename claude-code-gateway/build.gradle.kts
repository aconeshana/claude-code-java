plugins { `java-library` }

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-runtime"))
    implementation(project(":claude-code-api"))
    api(libs.commons.lang3)
    api(libs.jackson.databind)

    // Wire-format round-trip and interop tests drive the HTTP+SSE server with
    // the same OkHttp EventSource stack the product uses as an API client.
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.sse)
    // The shared permission gate is a composition-root type the gateway never
    // names; only the broadcast test builds a real one to prove the mirror
    // frame and the polled state are one projection of the same gate.
    testImplementation(project(":claude-code-permissions"))
}
