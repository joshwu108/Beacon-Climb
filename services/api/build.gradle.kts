plugins {
    java
    id("org.springframework.boot")
    id("com.diffplug.spotless")
}

group = "com.beaconclimb"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
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
tasks.withType<Test> { useJUnitPlatform() }
