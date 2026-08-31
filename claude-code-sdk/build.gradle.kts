plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":claude-code-core"))
    implementation(project(":claude-code-session"))
    runtimeOnly(project(":claude-code-cli"))
    api(libs.jackson.databind)
    api(libs.commons.lang3)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}
