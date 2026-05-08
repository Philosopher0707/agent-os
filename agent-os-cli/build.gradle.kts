plugins {
    application
}

val picocliVersion = "4.7.6"
val slf4jVersion = "2.0.16"
val junitVersion = "5.11.4"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation("info.picocli:picocli:$picocliVersion")
    annotationProcessor("info.picocli:picocli-codegen:$picocliVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.15")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
}

application {
    mainClass.set("com.agentos.cli.AgentOsCtl")
}
