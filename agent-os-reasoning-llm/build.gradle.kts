val langchain4jVersion = "0.36.2"
val slf4jVersion = "2.0.16"
val junitVersion = "5.11.4"
val assertjVersion = "3.27.3"

dependencies {
    implementation(project(":agent-os-kernel"))
    implementation("dev.langchain4j:langchain4j:$langchain4jVersion")
    implementation("org.slf4j:slf4j-api:$slf4jVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
}
