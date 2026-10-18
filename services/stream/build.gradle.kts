plugins {
    java
    id("com.diffplug.spotless")
}

group = "com.beaconclimb"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// BC-0303: Flink job skeleton (ADR-0004: Flink 2.2.1 + flink-connector-kafka 5.0.0-2.2, the only
// connector line that supports Flink 2.2.x). Every coordinate below was checked on
// repo1.maven.org before pinning (docs/evidence/BC-0303/pinned-coordinates.txt lists the URLs).
val flinkVersion = "2.2.1"
val flinkKafkaConnectorVersion = "5.0.0-2.2"
// flink-connector-kafka-parent 5.0.0-2.2 declares kafka.version 4.2.0; 4.2.1 is the patch the
// api side already uses through the Boot 4.1.1 BOM, so both halves talk the same client line.
val kafkaClientsVersion = "4.2.1"
// Jackson 3 (tools.jackson), the same major the api uses; 3.2.1 is what networknt
// json-schema-validator 3.0.8 (the contract validator shared with the api tests) depends on.
val jacksonVersion = "3.2.1"

dependencies {
    // Provided by the flink:2.2.1-java21 image (/opt/flink/lib/flink-dist-2.2.1.jar bundles
    // flink-core, flink-streaming-java, flink-clients and flink-connector-base), so they stay out
    // of the fat jar and are only on the compile and test classpaths.
    compileOnly("org.apache.flink:flink-streaming-java:$flinkVersion")
    compileOnly("org.apache.flink:flink-clients:$flinkVersion")
    compileOnly("org.apache.flink:flink-connector-base:$flinkVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    // BC-0302: the shared validation library (schemas, EventValidator, DlqRecord); it brings
    // json-schema-validator 3.0.8 and jackson-databind 3.2.1 into the fat jar.
    implementation(project(":events"))
    implementation("org.apache.flink:flink-connector-kafka:$flinkKafkaConnectorVersion")
    implementation("org.apache.kafka:kafka-clients:$kafkaClientsVersion")
    implementation("tools.jackson.core:jackson-databind:$jacksonVersion")

    testImplementation("org.apache.flink:flink-streaming-java:$flinkVersion")
    testImplementation("org.apache.flink:flink-clients:$flinkVersion")
    testImplementation("org.apache.flink:flink-connector-base:$flinkVersion")
    testImplementation("org.apache.flink:flink-test-utils:$flinkVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.2")
}

spotless { java { googleJavaFormat("1.28.0") } }

// The committed example events are copied into the test jar at build time from contracts/events so
// the files are not duplicated in git (the schemas themselves come with the :events jar).
tasks.named<ProcessResources>("processTestResources") {
    from("../../contracts/events/examples") {
        include("valid-attempt-*.json", "invalid-attempt-*.json")
        into("contracts/events/examples")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "1g"
    // Flink 2.x on Java 17+ needs these opens (flink-dist ships the same list as
    // env.java.opts.all in its config.yaml); without them the MiniCluster serialisers fail.
    jvmArgs(
        "-XX:+IgnoreUnrecognizedVMOptions",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
    )
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

// Fat jar for `make stream-submit` (mounted read-only into the jobmanager like the api jar).
// A plain Jar task merging the runtime classpath avoids a second Gradle plugin; the Flink
// provided jars are compileOnly so they are never merged, and slf4j-api is skipped because
// /opt/flink/lib already carries it (a second copy on the user classpath only causes warnings).
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds build/libs/stream.jar with every runtime dependency merged in."
    archiveFileName.set("stream.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true
    manifest { attributes("Main-Class" to "com.beaconclimb.stream.StreamJob") }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") && !it.name.startsWith("slf4j-api") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}
