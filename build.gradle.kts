import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()

    maven("https://jitpack.io")
}

group   = "one.wabbit"
version = "0.1.0"

plugins {
    kotlin("jvm")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")

    id("one.wabbit.acyclic")

    id("maven-publish")

    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    coordinates("one.wabbit", "kotlin-inspect", "0.1.0")
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("kotlin-inspect")
        description.set("kotlin-inspect")
        url.set("https://github.com/wabbit-corp/kotlin-inspect")
        licenses {
            license {
                name.set("GNU Affero General Public License v3.0 or later")
                url.set("https://spdx.org/licenses/AGPL-3.0-or-later.html")
            }
        }
        developers {
            developer {
                id.set("wabbit-corp")
                name.set("Wabbit Consulting Corporation")

                email.set("wabbit@wabbit.one")

            }
        }
        scm {
            url.set("https://github.com/wabbit-corp/kotlin-inspect")
            connection.set("scm:git:git://github.com/wabbit-corp/kotlin-inspect.git")
            developerConnection.set("scm:git:ssh://git@github.com/wabbit-corp/kotlin-inspect.git")
        }
    }
}

val localPublishRequested =
    gradle.startParameter.taskNames.any { taskName -> "MavenLocal" in taskName }

if (localPublishRequested) {
    tasks.withType<org.gradle.plugins.signing.Sign>().configureEach {
        enabled = false
    }
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.10")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    implementation("org.ow2.asm:asm-util:9.8")
    implementation("org.ow2.asm:asm-analysis:9.8")
}

java {
    targetCompatibility = JavaVersion.toVersion(21)
    sourceCompatibility = JavaVersion.toVersion(21)
}

val configuredVersionString = version.toString()

tasks.register("printVersion") {
    inputs.property("configuredVersion", configuredVersionString)
    doLast {
        println(inputs.properties["configuredVersion"])
    }
}

tasks.register("assertReleaseVersion") {
    inputs.property("configuredVersion", configuredVersionString)
    doLast {
        val versionString = inputs.properties["configuredVersion"].toString()
        require(!versionString.endsWith("+dev-SNAPSHOT")) {
            "Release publishing requires a non-snapshot version, got $versionString"
        }
        val refType = System.getenv("GITHUB_REF_TYPE") ?: ""
        val refName = System.getenv("GITHUB_REF_NAME") ?: ""
        if (refType == "tag" && refName.isNotBlank()) {
            val expectedTag = "v$versionString"
            require(refName == expectedTag) {
                "Git tag $refName does not match project version $versionString"
            }
        }
    }
}

tasks.register("assertSnapshotVersion") {
    inputs.property("configuredVersion", configuredVersionString)
    doLast {
        val versionString = inputs.properties["configuredVersion"].toString()
        require(versionString.endsWith("+dev-SNAPSHOT")) {
            "Snapshot publishing requires a +dev-SNAPSHOT version, got $versionString"
        }
        require((System.getenv("GITHUB_REF_TYPE") ?: "") != "tag") {
            "Snapshot publishing must not run from a tag ref"
        }
    }
}

tasks {
    withType<Test> {
        jvmArgs("-ea")

    }
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }
    withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xcontext-parameters")

            freeCompilerArgs.addAll(
                "-P",
                "plugin:one.wabbit.acyclic:compilationUnits=enabled",
            )

            freeCompilerArgs.addAll(
                "-P",
                "plugin:one.wabbit.acyclic:declarations=enabled",
            )

        }
    }

    jar {
        setProperty("zip64", true)

    }
}

// Kover Configuration
kover {
    // useJacoco() // This is the default, can be specified if you want to be explicit
    // reports {
    //     // Configure reports for the default test task.
    //     // Kover tries to infer the variant for simple JVM projects.
    //     // If you have specific build types/flavors, you'd configure them here as variants.
    //     variant() { // Or remove "debug" for a default JVM setup unless you have variants
    //         html {
    //             // reportDir.set(layout.buildDirectory.dir("reports/kover/html")) // Uncomment to customize output
    //             // title.set("kotlin-inspect Code Coverage") // Uncomment to customize title
    //         }
    //         xml {
    //             // reportFile.set(layout.buildDirectory.file("reports/kover/coverage.xml")) // Uncomment to customize output
    //         }
    //     }
    // }
}

dokka {
    moduleName.set("kotlin-inspect")
    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        failOnWarning.set(true)
    }
    dokkaSourceSets.main {
        val dokkaModuleFile = file("docs/dokka-module.md")
        if (dokkaModuleFile.exists()) {
            includes.from(dokkaModuleFile)
        }

        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/wabbit-corp/kotlin-inspect/tree/master/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }

    }
    pluginsConfiguration.html {
        // customStyleSheets.from("styles.css")
        // customAssets.from("logo.png")
        footerMessage.set("(c) Wabbit Consulting Corporation")
    }
}
