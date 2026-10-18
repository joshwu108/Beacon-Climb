plugins {
    `java-library`
    id("com.diffplug.spotless")
}

group = "com.beaconclimb"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// BC-0302: the consumer-side event validation library shared by the api (outbox payloads, the
// materializer's DLQ writes, the redrive CLI) and the Flink job (ParseAttempts). Plain Java: no
// Spring, no Flink, no Kafka. The two dependencies are `api` because callers see the validator's
// JsonNode payloads and, in the api, the networknt Error list the attempt write path reports.
// 3.2.1 is the Jackson 3 line both services already resolve (the Boot 4.1.1 BOM and the stream
// module pin the same version).
dependencies {
    api("com.networknt:json-schema-validator:3.0.8")
    api("tools.jackson.core:jackson-databind:3.2.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.2")
}

spotless { java { googleJavaFormat("1.28.0") } }

// The three event schemas ship inside the jar, copied at build time from contracts/events so the
// files are not duplicated in git (same pattern as services/api). The tests read the committed
// examples straight from contracts/events/examples (Gradle runs them with the module as cwd).
tasks.named<ProcessResources>("processResources") {
    from("../../contracts/events") {
        include("attempts.v1.schema.json", "climber-features.v1.schema.json", "stream-dlq.v1.schema.json")
        into("contracts/events")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
