val snakeyamlVersion = "2.3"
val junitVersion = "5.11.4"
val assertjVersion = "3.27.3"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
}
