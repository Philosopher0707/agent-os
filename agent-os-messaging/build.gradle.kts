val junitVersion = "5.11.4"
val assertjVersion = "3.27.3"
val jacksonVersion = "2.18.2"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
}
