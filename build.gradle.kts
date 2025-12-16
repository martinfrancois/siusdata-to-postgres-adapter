group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
    id("org.openrewrite.rewrite") version "7.22.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("org.graalvm.buildtools.native") version "0.11.3"
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
    implementation("ch.qos.logback:logback-classic:1.5.22")

    // CSV Parsing
    implementation("de.siegmar:fastcsv:4.1.0")

    // JSON Handling
    implementation("org.json:json:20250517")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter:5.21.0")
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:toxiproxy:1.21.4")
    testImplementation("com.github.stefanbirkner:system-lambda:1.2.1")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testRuntimeOnly("net.jqwik:jqwik-engine:1.9.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // OpenRewrite
    rewrite("org.openrewrite.recipe:rewrite-migrate-java:3.23.0")
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
