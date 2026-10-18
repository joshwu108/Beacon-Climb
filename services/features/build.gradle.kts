plugins {
    `java-library`
    `java-test-fixtures`
    id("com.diffplug.spotless")
}

group = "com.beaconclimb"
version = "0.1.0"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

// BC-0304: the ONE implementation of contracts/features/climber-features.v1.md shared by the api
// (synchronous SQL recompute, the parity oracle) and the Flink job (event-time aggregation). Plain
// Java: no Spring, no Flink, no Kafka. FeatureComputer, FeatureAggregates, GradeBandBuckets and
// AttemptFeatureRow are verbatim ports of com.beaconclimb.api.features (only the package changed);
// Jcs is the api's RFC 8785 canonicaliser, which FeatureAggregates.toJson() and the provenance
// checksum need. jackson-databind is `api` because FeatureAggregates.toJsonNode()/fromJson() expose
// Jackson trees; 3.2.1 is the Jackson 3 line the api (Boot 4.1.1 BOM) and the stream module pin.
dependencies {
    api("tools.jackson.core:jackson-databind:3.2.1")

    // The golden fixture reader (src/testFixtures) is shared with the :stream tests, which replay
    // the same files through the Flink operator.
    testFixturesApi("tools.jackson.core:jackson-databind:3.2.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.2")
}

spotless { java { googleJavaFormat("1.28.0") } }

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// Regenerates the committed golden fixtures (src/test/resources/golden/climber-features-v1) by
// running the computer over the hand-built cases in GoldenFixtureGenerator. Run it only after a
// deliberate contract change; GoldenFixturesTest fails whenever the committed files and the code
// disagree, and the api-side follow-up replays the same files against its own copy.
tasks.register<JavaExec>("generateGoldenFixtures") {
    group = "verification"
    description = "Rewrites the golden climber-features.v1 fixtures from GoldenFixtureGenerator."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.beaconclimb.features.GoldenFixtureGenerator")
    args(layout.projectDirectory.dir("src/test/resources/golden/climber-features-v1").asFile.path)
}
