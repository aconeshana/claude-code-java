plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // 1.28.0 pulls in commons-lang3:3.18.0, which fixes CVE-2025-48924
    // (uncontrolled recursion in commons-lang3 < 3.18.0 shipped by earlier compress releases).
    implementation("org.apache.commons:commons-compress:1.28.0")
    constraints {
        implementation("commons-codec:commons-codec:1.23.0") {
            because("Remediate vulnerable commons-codec 1.19.0 pulled transitively by commons-compress")
        }
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
