plugins {
    application
}

application {
    mainClass.set("com.agentos.demo.Main")
}

val junitVersion = "5.11.4"
val assertjVersion = "3.27.3"
val slf4jVersion = "2.0.16"
val logbackVersion = "1.5.12"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation(project(":agent-os-directory"))
    implementation(project(":agent-os-messaging"))
    implementation(project(":agent-os-reasoning-reactive"))
    implementation(project(":agent-os-reasoning-bdi"))
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
}
