group = "ch.fmartin"
version = "1.0-SNAPSHOT"

plugins {
    id("java")
    id("org.openrewrite.rewrite") version "7.39.0"
    id("com.gradleup.shadow") version "9.6.1"
    id("org.graalvm.buildtools.native") version "1.1.14"
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

// Every JQF module is released together under one version, and the agent must match the engine.
val jqfVersion = "3.0"

val jqfAgent = configurations.create("jqfAgent") {
    isTransitive = false
}

dependencies {
    // Utilities
    implementation("com.google.guava:guava:33.7.2-jre")

    // DB connection
    implementation("org.postgresql:postgresql:42.7.13")  // PostgreSQL JDBC Driver
    implementation("com.zaxxer:HikariCP:7.1.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("ch.qos.logback:logback-classic:1.6.5")

    // CSV Parsing
    implementation("de.siegmar:fastcsv:4.4.0")

    // JSON Handling
    implementation("org.json:json:20250517")

    // Testing
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-toxiproxy:2.0.5")
    testImplementation("com.github.stefanbirkner:system-lambda:1.2.1")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("net.jqwik:jqwik:1.10.1")
    testRuntimeOnly("net.jqwik:jqwik-engine:1.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Fuzz testing
    jqfAgent("edu.berkeley.cs.jqf:jqf-instrument:$jqfVersion")

    // OpenRewrite
    rewrite("org.openrewrite.recipe:rewrite-migrate-java:3.42.1")
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
    }
    // fix for: "Unable to make field private final java.util.Map java.util.Collections$UnmodifiableMap.m accessible: module java.base does not "opens java.util" to unnamed module"
    // --enable-native-access: Testcontainers reaches the container engine through docker-java, which
    // loads JNA, and the JVM allows that native call only when it is granted here.
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED"
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
            excludeTestsMatching("ch.fmartin.*IntegrationTest")
        }
    }


    // SiusDataToPostgresAdapterJarIntegrationTest starts the shadow jar in a separate JVM.
    val shadowJarFile = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(shadowJarFile)
    doFirst {
        systemProperty("siusdata.shadowJar", shadowJarFile.get().asFile.absolutePath)
    }
}

// CI runs the tests on every current Java LTS while the code stays compiled for 21.
// Without the property the toolchain JDK runs them.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("testJavaVersion").orNull?.let { version ->
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(version))
        })
    }
}

val fuzzResults = layout.buildDirectory.dir("fuzz-results")

// A campaign saves the inputs that reach new code under jqf.fuzz.out, and the regression run replays them from there.
fun Test.useFuzzTestSuite() {
    val fuzzTestSourceSet = sourceSets["fuzzTest"]
    testClassesDirs = fuzzTestSourceSet.output.classesDirs
    classpath = fuzzTestSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }
    systemProperty("jqf.fuzz.out", fuzzResults.get().asFile.absolutePath)
}

testing {
    suites {
        register<JvmTestSuite>("fuzzTest") {
            // The fuzz tests reuse the unit test dependencies, so both suites resolve JUnit from one BOM.
            configurations.named(sources.implementationConfigurationName) {
                extendsFrom(configurations.testImplementation.get())
            }
            dependencies {
                implementation(project())
                implementation("edu.berkeley.cs.jqf:jqf-junit5:$jqfVersion")
                implementation("edu.berkeley.cs.jqf:jqf-generator-quickcheck:$jqfVersion")
            }
            targets.all {
                testTask.configure {
                    useFuzzTestSuite()
                    // A new campaign or a new regression input changes what this task replays.
                    inputs.files(fileTree(fuzzResults) { include("**/corpus/**") })
                        .withPropertyName("fuzzCorpus")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    inputs.files(fileTree("src/fuzzTest/regression"))
                        .withPropertyName("fuzzRegressionInputs")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
    }
}

// Without jqf.fuzz, a @FuzzTest method runs once per saved input, which is quick enough for every build.
tasks.check {
    dependsOn(testing.suites.named("fuzzTest"))
}

tasks.register<Test>("fuzz") {
    description = "Runs a coverage-guided JQF campaign on every @FuzzTest method. " +
        "-PfuzzDuration=10m sets the time per method, -PfuzzRepro=<file> replays one saved input instead."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    // A campaign empties the corpus directory before it starts, so a replay of that corpus has to come first.
    mustRunAfter("fuzzTest")
    useFuzzTestSuite()
    systemProperty("jqf.fuzz", "true")
    systemProperty("jqf.fuzz.duration", providers.gradleProperty("fuzzDuration").getOrElse("60s"))
    providers.gradleProperty("fuzzRepro").orNull?.let { systemProperty("jqf.repro", file(it).absolutePath) }
    // Zest otherwise redraws a full-screen status view, which a Gradle log cannot show.
    systemProperty("jqf.ei.QUIET_MODE", "true")
    // The agent measures the branch coverage that guides Zest. Without it the campaign is random input only.
    // It instruments only the project's classes and FastCSV: an empty exclude prefix matches every
    // class, and the includes win over it. Instrumented JDK classes would fail to load, and those of
    // JUnit and Gradle only slow each trial down.
    systemProperty("janala.excludes", "")
    systemProperty("janala.includes", "ch/fmartin/,de/siegmar/fastcsv/")
    inputs.files(jqfAgent)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${jqfAgent.singleFile.absolutePath}") })
    // Each campaign explores new inputs, so an earlier run never makes it up to date.
    outputs.upToDateWhen { false }
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
