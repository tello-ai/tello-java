import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    signing
    // 0.36.0 and later require Gradle 9; the wrapper is on Gradle 8.14.
    id("com.vanniktech.maven.publish") version "0.35.0"
}

group = "io.telloai"
version = "0.1.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

base { archivesName.set("tello-sdk") }

repositories { mavenCentral() }

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("tello.gradleVersion", project.version.toString())
}

// The client reports its own version in the upgrade URL; it reads it from this
// resource so the Gradle version above stays the only copy.
tasks.processResources {
    val sdkVersion = project.version.toString()
    inputs.property("sdkVersion", sdkVersion)
    filesMatching("io/telloai/sdk-version.properties") { expand("version" to sdkVersion) }
}

// Sources carry Korean text; never fall back to the platform default charset.
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        docEncoding = "UTF-8"
        charSet = "UTF-8"
    }
}

mavenPublishing {
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    coordinates(group.toString(), "tello-sdk", version.toString())
    // Through the Central Portal. The publish workflow runs publishAndReleaseToMavenCentral.
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("Tello SDK for Java")
        description.set(
            "A thin WebSocket realtime client for the Tello turn-provider-gateway: place phone " +
                "calls and answer each caller turn from your own code."
        )
        url.set("https://github.com/tello-ai/tello-java")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("tello-ai")
                name.set("Tello")
                email.set("tello@telloai.io")
                url.set("https://telloai.io")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/tello-ai/tello-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/tello-ai/tello-java.git")
            url.set("https://github.com/tello-ai/tello-java")
        }
    }
}

// Maven Central only takes signed artifacts, and the publish workflow always passes
// the key. Require signing only when a key is set, so publishToMavenLocal works
// without one.
signing {
    isRequired = providers.gradleProperty("signingInMemoryKey").isPresent
}
