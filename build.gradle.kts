group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
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
    implementation("com.zaxxer:HikariCP:6.0.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.10")

    // CSV Parsing
    implementation("org.apache.commons:commons-csv:1.12.0")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.11.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

graalvmNative {
    binaries.all {
        resources.autodetect()
    }
}

tasks.shadowJar {
    archiveFileName.set("siusdata-to-postgres-adapter.jar")
}
