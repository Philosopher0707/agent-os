plugins {
    application
    id("java")
}

val slf4jVersion = "2.0.16"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation(project(":agent-os-directory"))
    implementation(project(":agent-os-messaging"))
    implementation(project(":agent-os-reasoning-bdi"))
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.15")
}

application {
    mainClass.set("com.agentos.samples.opsmonitor.OpsMonitorDemo")
}
