import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
}

group = "io.github.slayyyyer"
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

java {
    withSourcesJar()
    withJavadocJar()
}

gradlePlugin {
    plugins {
        create("why") {
            id = "io.github.slayyyyer.why"
            implementationClass = "why.gradle.WhyPlugin"
            displayName = "Why"
            description = "Packages src/why pages into the jar and adds whyCheck and whyRun tasks."
        }
    }
}

val signingKey = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("why-gradle-plugin")
            description.set("Gradle plugin for packaging Why pages and running whyCheck and whyRun.")
            url.set("https://slayyyyer.github.io/why-lang/")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("slayyyyer")
                    name.set("slayyyyer")
                    email.set("slayyyyer@users.noreply.github.com")
                    url.set("https://github.com/slayyyyer")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/slayyyyer/why-lang.git")
                developerConnection.set("scm:git:ssh://git@github.com/slayyyyer/why-lang.git")
                url.set("https://github.com/slayyyyer/why-lang")
            }
        }
    }
    repositories {
        maven {
            name = "test"
            url = rootProject.layout.buildDirectory.dir("test-repo").get().asFile.toURI()
        }
    }
}

signing {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publishing.publications)
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":publishAllPublicationsToTestRepository")
    dependsOn("publishAllPublicationsToTestRepository")
    systemProperty("why.test.repo", rootProject.layout.buildDirectory.dir("test-repo").get().asFile.absolutePath)
    systemProperty("why.test.gradleHome", gradle.gradleHomeDir?.absolutePath ?: "")
}
