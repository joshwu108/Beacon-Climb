plugins {
    java
    id("org.springframework.boot")
    id("com.diffplug.spotless")
}

group = "com.beaconclimb"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// BC-0107: controller interfaces are hand-written. openapi-generator 7.26.0 (spring) cannot load
// contracts/openapi.yaml because the frozen 1.1.0 spec uses more than 50 YAML aliases, which the
// bundled swagger-parser rejects before validation. Contract conformance is proven instead by
// ContractSchemas (test) validating every observed response body against the component schemas.
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Argon2id password hashing (ADR-0005). spring-security-crypto needs Bouncy Castle at runtime.
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    // JSON Schema 2020-12 validator (Jackson 3 based, matching Boot 4.1): catalog line validation
    // in the importer (BC-0105) and contract conformance in tests.
    implementation("com.networknt:json-schema-validator:3.0.8")
    // BC-0302: the shared event validation library (attempts.v1 / climber-features.v1 /
    // stream-dlq.v1 schemas, EventValidator, DlqRecord, RedriveRecord). The attempt write path,
    // the DlqPublisher and the redrive CLI all judge conformance through it.
    implementation(project(":events"))
    // BC-0122: token-bucket rate limiting (ADR-0005). Core only; no Spring integration needed.
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    // BC-0301: the transactional-outbox publisher (com.beaconclimb.api.outbox). Only the core
    // spring-kafka library (BOM: spring-kafka 4.1.1, kafka-clients 4.2.1), deliberately not the
    // Boot starter/auto-configuration module, so no Kafka bean exists unless
    // beacon.stream.enabled=true wires them (OutboxConfig).
    implementation("org.springframework.kafka:spring-kafka")
    // BC-0301 (and BC-0308): the outbox gauges/counters scrape at /prometheus on 127.0.0.1:8080.
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // BC-0301: shared apache/kafka:4.3.1 KRaft container (KafkaTestContainer) and bounded waits.
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.awaitility:awaitility")
    testImplementation("net.jqwik:jqwik:1.9.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless { java { googleJavaFormat("1.28.0") } }

// BC-0105: the importer validates every catalog line against the versioned contract. The schema is
// copied into the jar at build time from contracts/catalog so the file is not duplicated in git.
tasks.named<ProcessResources>("processResources") {
    from("../../contracts/catalog") {
        include("catalog-v1.schema.json")
        into("contracts/catalog")
    }
    // BC-0109: every outbox payload is validated against the attempts.v1 event contract before
    // it is inserted (ADR-0002, TM-11); since BC-0302 that schema ships inside the :events jar.
    // BC-0111: the decision record and candidate snapshot are validated against the decision
    // contract before the ledger transaction commits (ADR-0003), so the schema ships inside the jar.
    from("../../contracts/decisions") {
        include("decision-record.v1.schema.json")
        into("contracts/decisions")
    }
}
tasks.withType<Test> {
    useJUnitPlatform()
    // BC-0106: `make bench-catalog` passes -Dbench=true through to the test JVM (CatalogBenchIT).
    systemProperty("bench", System.getProperty("bench", "false"))
}
