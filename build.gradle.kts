plugins {
    id("java")
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.portswigger.burp.extensions:montoya-api:2026.2")

    // JSON parsing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    // Pure-Java jq implementation (no external binary needed)
    implementation("net.thisptr:jackson-jq:1.0.0")
    implementation("net.thisptr:jackson-jq-extra:1.0.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

// Fat JAR: BApp Store requires all dependencies to be bundled.
tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Pinned so the release workflow's hardcoded artifact path stays valid even if a
    // project version is introduced later.
    archiveFileName.set("JSON_Tree_JQ.jar")

    // Resolved lazily — resolving a configuration at configuration time is deprecated in Gradle 9
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    })

    // Bundled signatures no longer match the merged JAR, and module-info confuses the loader
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
}