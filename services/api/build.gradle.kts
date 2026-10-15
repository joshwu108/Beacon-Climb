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
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
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
}
tasks.withType<Test> {
    useJUnitPlatform()
    // BC-0106: `make bench-catalog` passes -Dbench=true through to the test JVM (CatalogBenchIT).
    systemProperty("bench", System.getProperty("bench", "false"))
}
