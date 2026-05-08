val kafkaVersion = "3.8.1"
val jacksonVersion = "2.18.2"
val slf4jVersion = "2.0.16"
val junitVersion = "5.11.4"
val assertjVersion = "3.27.3"
val testcontainersVersion = "1.20.4"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation(project(":agent-os-messaging"))
    implementation("org.apache.kafka:kafka-clients:$kafkaVersion")
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.testcontainers:kafka:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
}
