val jettyVersion = "11.0.24"
val slf4jVersion = "2.0.16"
val junitVersion = "5.11.4"
val assertjVersion = "3.27.3"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation(project(":agent-os-messaging"))
    implementation("org.eclipse.jetty.websocket:websocket-jetty-server:$jettyVersion")
    implementation("org.eclipse.jetty.websocket:websocket-jetty-client:$jettyVersion")
    implementation("jakarta.servlet:jakarta.servlet-api:5.0.0")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
}
