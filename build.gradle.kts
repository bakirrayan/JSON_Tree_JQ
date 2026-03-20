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
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "21"
    targetCompatibility = "21"
    options.encoding = "UTF-8"
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().filter { it.isDirectory })
    from(configurations.runtimeClasspath.get().filterNot { it.isDirectory }.map { zipTree(it) })
}