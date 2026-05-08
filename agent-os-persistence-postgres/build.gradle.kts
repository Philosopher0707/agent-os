val hikariVersion = "6.0.0"
val postgresVersion = "42.7.4"
val junitVersion = "5.11.4"
val assertjVersion = "3.27.3"
val testcontainersVersion = "1.20.4"
val slf4jVersion = "2.0.16"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")
}
