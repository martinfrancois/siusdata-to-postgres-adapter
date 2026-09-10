group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
    id("org.openrewrite.rewrite") version "7.39.0"
    id("com.gradleup.shadow") version "9.6.1"
    id("org.graalvm.buildtools.native") version "1.1.11"
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
    implementation("com.google.guava:guava:33.7.1-jre")

    // DB connection
    implementation("org.postgresql:postgresql:42.7.13")  // PostgreSQL JDBC Driver
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    // CSV Parsing
    implementation("de.siegmar:fastcsv:4.4.0")

    // JSON Handling
    implementation("org.json:json:20250517")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-toxiproxy:2.0.5")
    testImplementation("com.github.stefanbirkner:system-lambda:1.2.1")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testRuntimeOnly("net.jqwik:jqwik-engine:1.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // OpenRewrite
    rewrite("org.openrewrite.recipe:rewrite-migrate-java:3.42.1")
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    // fix for: "Unable to make field private final java.util.Map java.util.Collections$UnmodifiableMap.m accessible: module java.base does not "opens java.util" to unnamed module"
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )

    val skipIntegrationTests = providers.provider {
        val propertyValue = project.findProperty("skipIntegrationTests") as? String
        val envValue = System.getenv("SKIP_INTEGRATION_TESTS")

        fun String.isTruthy(): Boolean = equals("true", ignoreCase = true) || this == "1" || equals("yes", ignoreCase = true)

        sequenceOf(propertyValue, envValue)
            .filterNotNull()
            .firstOrNull { it.isNotBlank() }
            ?.let { it.isTruthy() }
            ?: false
    }

    filter {
        if (skipIntegrationTests.get()) {
            excludeTestsMatching("ch.fmartin.SiusDataToPostgresAdapterIntegrationTest")
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
