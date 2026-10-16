plugins {
    `java-library`
}

group = "breaketernity"
version = "2.1.3"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    withSourcesJar()
}

// Java 17 language/API level, compiled with whatever JDK runs Gradle (>= 17).
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform {
        // Differential tests shell out to Node and the original repo; run them
        // explicitly through the differentialTest task instead.
        excludeTags("differential")
    }
}

tasks.register<Test>("differentialTest") {
    group = "verification"
    description = "Differential fuzz tests comparing this port against the original JS library via Node."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("differential")
    }
    systemProperty(
        "original.repo",
        providers.gradleProperty("originalRepo").getOrElse("../break_eternity.js"),
    )
    shouldRunAfter(tasks.test)
}
