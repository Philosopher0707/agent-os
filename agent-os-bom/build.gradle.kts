plugins {
    `java-platform`
}

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api("com.fasterxml.jackson.core:jackson-databind:2.18.2")
        api("com.fasterxml.jackson.core:jackson-core:2.18.2")
        api("org.slf4j:slf4j-api:2.0.16")
        api("ch.qos.logback:logback-classic:1.5.12")
        api("org.junit.jupiter:junit-jupiter:5.11.4")
        api("org.assertj:assertj-core:3.27.3")
        api("org.mockito:mockito-core:5.14.2")
        api("org.yaml:snakeyaml:2.3")
    }
}
