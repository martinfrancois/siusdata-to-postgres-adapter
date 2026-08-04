import org.gradle.api.tasks.JavaExec
import java.util.Locale

group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
    id("org.openrewrite.rewrite") version "7.38.0"
    id("com.gradleup.shadow") version "9.6.1"
    id("org.graalvm.buildtools.native") version "1.1.6"
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
    implementation("com.google.guava:guava:33.6.0-jre")

    // DB connection
    implementation("org.postgresql:postgresql:42.7.13")  // PostgreSQL JDBC Driver
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.18")
    implementation("ch.qos.logback:logback-classic:1.6.1")

    // CSV Parsing
    implementation("de.siegmar:fastcsv:4.4.0")

    // JSON Handling
    implementation("org.json:json:20250517")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:toxiproxy:1.21.4")
    testImplementation("com.github.stefanbirkner:system-lambda:1.2.1")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testRuntimeOnly("net.jqwik:jqwik-engine:1.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Fuzz testing
    add("fuzzTestImplementation", "edu.berkeley.cs.jqf:jqf-fuzz:$jqfVersion")
    add("fuzzTestImplementation", "edu.berkeley.cs.jqf:jqf-instrument:$jqfVersion")
    add("fuzzTestImplementation", "edu.berkeley.cs.jqf:jqf-zest:$jqfVersion")
    add("fuzzTestImplementation", "junit:junit:4.13.2")
    add("fuzzTestImplementation", "com.pholser:junit-quickcheck-core:1.0")
    add("fuzzTestImplementation", "com.pholser:junit-quickcheck-generators:1.0")

    // OpenRewrite
    rewrite("org.openrewrite.recipe:rewrite-migrate-java:3.41.0")
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
        args(
            "--output",
            outputDir.get().asFile.absolutePath,
            "ch.fmartin",
            "ch.fmartin.SiusDataToPostgresAdapterFuzzTest",
            method
        )
        outputs.dir(outputDir)
        jvmArgs(
            "--add-opens", "java.base/java.lang=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED"
        )
        doFirst {
            if (System.getenv("JQF_ZEST_MAX_TIME").isNullOrBlank()) {
                environment("JQF_ZEST_MAX_TIME", "300s")
            }
            val agent = configurations.named("fuzzTestRuntimeClasspath").get().resolve()
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
