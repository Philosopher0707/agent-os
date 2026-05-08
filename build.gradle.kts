plugins {
    id("java")
}

allprojects {
    group = "com.agentos"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }
}

configure(subprojects.filter { it.name != "agent-os-bom" }) {
    apply(plugin = "java-library")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:-preview")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
