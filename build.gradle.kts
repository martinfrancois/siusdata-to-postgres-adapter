group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
    id("org.openrewrite.rewrite") version "7.17.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.graalvm.buildtools.native") version "0.10.6"
    id("application")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("ch.fmartin.SiusDataToPostgresAdapter")
}

repositories {
    mavenCentral()
}

dependencies {
    // Utilities
    implementation("com.google.guava:guava:33.5.0-jre")

    // DB connection
    implementation("org.postgresql:postgresql:42.7.8")  // PostgreSQL JDBC Driver
    implementation("com.zaxxer:HikariCP:7.0.2")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.19")

    // CSV Parsing
    implementation("de.siegmar:fastcsv:4.1.0")

    // JSON Handling
    implementation("org.json:json:20250517")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter:5.20.0")
    testImplementation("org.testcontainers:testcontainers:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("org.testcontainers:toxiproxy:1.21.3")
    testImplementation("com.github.stefanbirkner:system-lambda:1.2.1")
    testImplementation("org.awaitility:awaitility:4.3.0")

    // OpenRewrite
    rewrite("org.openrewrite.recipe:rewrite-migrate-java:3.19.0")
}

tasks.test {
    useJUnitPlatform()
    // fix for: "Unable to make field private final java.util.Map java.util.Collections$UnmodifiableMap.m accessible: module java.base does not "opens java.util" to unnamed module"
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )

    // Ensure Testcontainers connects to the active Docker context even if
    // ~/.testcontainers.properties specifies an outdated DOCKER_HOST.
    doFirst {
        val existing = System.getenv("DOCKER_HOST")
        if (existing.isNullOrBlank()) {
            try {
                val ctxProc = ProcessBuilder("docker", "context", "show")
                    .redirectErrorStream(true)
                    .start()
                val activeContext = ctxProc.inputStream.reader().readText().trim()
                ctxProc.waitFor()

                if (activeContext.isNotBlank()) {
                    val hostProc = ProcessBuilder(
                        "docker", "context", "inspect", "--format", "{{ .Endpoints.docker.Host }}", activeContext
                    ).redirectErrorStream(true).start()
                    val detectedHost = hostProc.inputStream.reader().readText().trim().trim('"')
                    hostProc.waitFor()

                    if (detectedHost.isNotBlank()) {
                        environment("DOCKER_HOST", detectedHost)
                    }
                }
            } catch (_: Exception) {
                // If detection fails, fall back to environment/default behavior
            }
        }
    }
}

graalvmNative {
    binaries.all {
        resources.autodetect()
        buildArgs.add("--enable-url-protocols=https") // Enable HTTPS protocol
    }
    metadataRepository {
        enabled.set(true)
    }
}

tasks.shadowJar {
    archiveFileName.set("siusdata-to-postgres-adapter.jar")
}

rewrite {
    activeRecipe("org.openrewrite.java.migrate.UpgradeToJava21")
}
