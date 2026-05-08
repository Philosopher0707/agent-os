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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Verify we are running on JDK 21+ (needed for records, pattern matching, etc.)
    tasks.withType<JavaCompile> {
        doFirst {
            if (JavaVersion.current() < JavaVersion.VERSION_21) {
                throw GradleException(
                    "JDK 21 is required but current JVM is ${JavaVersion.current()}.\n" +
                    "Set JAVA_HOME to a JDK 21 installation:\n" +
                    "  macOS (Homebrew): export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home\n" +
                    "  Ubuntu/CI:        export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64\n" +
                    "  Then re-run:       ./gradlew ..."
                )
            }
        }
        options.compilerArgs.add("-Xlint:-preview")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
