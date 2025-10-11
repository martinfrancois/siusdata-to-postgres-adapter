import org.gradle.api.tasks.JavaExec
import java.util.Locale

group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
    id("org.openrewrite.rewrite") version "7.18.0"
    id("com.gradleup.shadow") version "9.2.2"
    id("org.graalvm.buildtools.native") version "0.11.1"
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

val jqfVersion = "2.1"

sourceSets {
    val fuzzTest by creating {
        java.setSrcDirs(listOf("src/fuzzTest/java"))
        resources.setSrcDirs(listOf("src/fuzzTest/resources"))
        compileClasspath += sourceSets.main.get().output
        compileClasspath += sourceSets.main.get().compileClasspath
        runtimeClasspath += output
        runtimeClasspath += compileClasspath
        runtimeClasspath += sourceSets.main.get().runtimeClasspath
    }
}

configurations {
    named("fuzzTestImplementation") {
        extendsFrom(getByName("testImplementation"))
    }
    named("fuzzTestRuntimeOnly") {
        extendsFrom(getByName("testRuntimeOnly"))
    }
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Fuzz testing
    add("fuzzTestImplementation", "edu.berkeley.cs.jqf:jqf-fuzz:$jqfVersion")
    add("fuzzTestImplementation", "edu.berkeley.cs.jqf:jqf-instrument:$jqfVersion")
    add("fuzzTestImplementation", "edu.berkeley.cs.jqf:jqf-zest:$jqfVersion")
    add("fuzzTestImplementation", "junit:junit:4.13.2")
    add("fuzzTestImplementation", "com.pholser:junit-quickcheck-core:1.0")
    add("fuzzTestImplementation", "com.pholser:junit-quickcheck-generators:1.0")

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

configurations.all {
    resolutionStrategy.dependencySubstitution {
        // JQF 2.1 does not publish a dedicated jqf-zest artifact; reuse jqf-fuzz until upstream ships one.
        substitute(module("edu.berkeley.cs.jqf:jqf-zest")).using(module("edu.berkeley.cs.jqf:jqf-fuzz:$jqfVersion"))
    }
}

val jqfTargets = listOf(
    "fuzzIsValidCsvFile",
    "fuzzCalculateTimestamp",
    "fuzzSetIntegerField",
    "fuzzSetLongField",
    "fuzzSetBooleanField",
    "fuzzSetTextField"
)

val jqfFuzzTasks = jqfTargets.map { method ->
    val taskName = "jqf" + method.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Run JQF Zest on $method"
        val fuzz = sourceSets.named("fuzzTest").get()
        classpath = fuzz.runtimeClasspath
        mainClass.set("edu.berkeley.cs.jqf.fuzz.Launch")
        val outputDir = layout.buildDirectory.dir("jqf/$method")
        args("zest", "ch.fmartin.SiusDataToPostgresAdapterFuzzTest", method, outputDir.get().asFile.absolutePath)
        outputs.dir(outputDir)
        jvmArgs(
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED"
        )
        doFirst {
            if (System.getenv("JQF_ZEST_MAX_TIME").isNullOrBlank()) {
                environment("JQF_ZEST_MAX_TIME", "300s")
            }
            val agent = (configurations.named("fuzzTestRuntimeOnly").get().resolve() +
                configurations.named("fuzzTestImplementation").get().resolve())
                .firstOrNull { it.name.startsWith("jqf-instrument") && it.extension == "jar" }
            if (agent != null) {
                jvmArgs("-javaagent:${agent.absolutePath}")
            }
        }
    }
}

tasks.register("jqfFuzz") {
    group = "verification"
    description = "Run all JQF fuzz targets"
    dependsOn(jqfFuzzTasks)
}
