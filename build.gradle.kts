group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
    id("org.openrewrite.rewrite") version "6.25.1"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.graalvm.buildtools.native") version "0.10.3"
    id("application")
}

application {
    mainClass.set("ch.fmartin.SiusDataToPostgresAdapter")
}

repositories {
    mavenCentral()
}

dependencies {
    // Utilities
    implementation("com.google.guava:guava:33.3.1-jre")

    // DB connection
    implementation("org.postgresql:postgresql:42.7.4")  // PostgreSQL JDBC Driver
    implementation("com.zaxxer:HikariCP:6.1.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // CSV Parsing
    implementation("de.siegmar:fastcsv:3.3.1")

    // JSON Handling
    implementation("org.json:json:20240303")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:toxiproxy:1.20.3")
    testImplementation("com.github.stefanbirkner:system-lambda:1.2.1")
    testImplementation("org.awaitility:awaitility:4.2.2")

    // OpenRewrite
    rewrite("org.openrewrite.recipe:rewrite-migrate-java:2.27.1")
}

tasks.test {
    useJUnitPlatform()
    // fix for: "Unable to make field private final java.util.Map java.util.Collections$UnmodifiableMap.m accessible: module java.base does not "opens java.util" to unnamed module"
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED"
    )
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
