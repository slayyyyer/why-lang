plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "why"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(project(":"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("why") {
            id = "why"
            implementationClass = "why.gradle.WhyPlugin"
            displayName = "Why"
            description = "Packages src/why pages into the jar and adds whyCheck and whyRun tasks."
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
