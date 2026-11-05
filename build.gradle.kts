// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.tapmoc)
    alias(libs.plugins.dokka)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotless.changelog)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.versions)
    alias(libs.plugins.maven.publish)
}

group = "org.islandoftex"

// ----------------------------------------------------------------------
// JVM and Kotlin API versions, defined in gradle.properties (see keys)
// ----------------------------------------------------------------------
val profiles =
    VersionProfile(
        forJava = "rhea.target.jvm",
        forKotlin = "rhea.target.kotlin",
    )
// ----------------------------------------------------------------------

kotlin {
    // the library discipline: public declarations must spell out
    // their types and visibility
    explicitApi()

    // the opt-in gate: run the build with -PwarningsAsErrors=true
    // to prove that the tree compiles without a single warning —
    // every target, one switch
    compilerOptions {
        allWarningsAsErrors.set(
            providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.getOrElse(false),
        )
    }

    // the JVM and the natives: one POSIX actual serves the unix-likes,
    // the Win32 API earns its own (the tests for the non-host targets
    // ride the machines that can run them; the CI keeps them
    // compile-only) — the JVM versioning is tapmoc's business, never a
    // direct jvmTarget
    jvm()
    linuxX64()
    macosArm64()
    mingwX64()

    // the hierarchy, spelled out: nativeMain shares the whole native
    // family, and posixMain is the unix-likes' own middle layer (the
    // fork machinery the Win32 side cannot see)
    applyHierarchyTemplate {
        common {
            group("jvm") {
                withJvm()
            }
            group("native") {
                withLinuxX64()
                withMacosArm64()
                withMingwX64()
                group("posix") {
                    withLinuxX64()
                    withMacosArm64()
                }
            }
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// the IoT discipline: no JVM toolchains — tapmoc pins the JVM and
// the Kotlin API versions the compiler flags speak, and checks the
// dependencies against them
tapmoc {
    java(profiles.java)
    kotlin(profiles.kotlin)
    checkDependencies()
}

repositories {
    mavenCentral()
}

spotlessChangelog {
    changelogFile("CHANGELOG.md")
    setAppendDashSnapshotUnless_dashPrelease(true)
    ifFoundBumpBreaking("breaking change")
    tagPrefix("v")
    commitMessage("Release v{{version}}")
    remote("origin")
}

version = spotlessChangelog.versionNext

// the license header both Kotlin formatting steps enforce, with the
// delimiter bounding the replaceable header region
private val spdxHeader = "// SPDX-License-Identifier: BSD-3-Clause"
private val spdxDelimiter =
    "(@file:|package |import |plugins |dependencyResolutionManagement )"

spotless {
    kotlinGradle {
        // the settings file keeps its header by hand: a one-liner
        // with no delimiter-matching line (no imports, no package)
        target("build.gradle.kts")
        licenseHeader(spdxHeader, spdxDelimiter)
        ktlint()
        trimTrailingWhitespace()
        leadingTabsToSpaces()
        endWithNewline()
    }
    kotlin {
        target("src/**/*.kt")
        licenseHeader(spdxHeader, spdxDelimiter)
        ktlint()
        trimTrailingWhitespace()
        leadingTabsToSpaces()
        endWithNewline()
    }
}

detekt {
    ignoreFailures = false
    buildUponDefaultConfig = true
    config.from(files("detekt-config.yml"))
    // the KMP tree: every source set speaks to the analysis
    source.from(files("src"))
}

// the documentation: the KDoc's real gate — reportUndocumented
// marks the undocumented public declarations and failOnWarning
// fails the generation on them (and on broken links)
dokka {
    moduleName.set(project.name)

    dokkaPublications.html {
        suppressInheritedMembers.set(true)
        suppressObviousFunctions.set(true)
        failOnWarning.set(true)
    }

    pluginsConfiguration.html {
        footerMessage.set("Island of TeX")
    }

    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
        skipDeprecated.set(false)
        skipEmptyPackages.set(true)

        sourceLink {
            localDirectory.set(file("./"))
            remoteLineSuffix.set("#L")
            remoteUrl("https://gitlab.com/islandoftex/libraries/rhea")
        }

        enableKotlinStdLibDocumentationLink.set(true)
        enableJdkDocumentationLink.set(true)
    }
}

// the coverage gate: `./gradlew koverVerify` enforces the floors below
// for the line coverage of the JVM test suite; lower the thresholds
// only with good reason, otherwise add tests for the uncovered path
// (the thin per-platform runners ride the smoke tests instead)
kover {
    reports {
        verify {
            rule {
                minBound(65)
            }
        }
    }
}

tasks {
    named<Test>("jvmTest") {
        useJUnitPlatform()

        testLogging {
            events(
                TestLogEvent.FAILED,
                TestLogEvent.PASSED,
                TestLogEvent.SKIPPED,
            )
        }
    }
    withType<DependencyUpdatesTask> {
        resolutionStrategy {
            componentSelection {
                all {
                    fun isNonStable(version: String) =
                        listOf("alpha", "beta", "rc", "cr", "m", "preview", "b", "ea")
                            .any { qualifier ->
                                version.matches(Regex("(?i).*[.-]$qualifier[.\\d-+]*"))
                            }
                    if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
                        reject("Release candidate")
                    }
                }
            }
        }
        checkForGradleUpdate = false
    }
}

// the publish flow: the GitLab package registry rides the CI job
// token; the KMP plugin publishes one artifact per target, the pom
// rides every publication
publishing {
    publications {
        withType<MavenPublication> {
            pom {
                name.set("Rhea")
                description.set(
                    "The Island of TeX's process runner: one port, four " +
                        "platforms — the typed verdicts of a spawn, the " +
                        "capture that survives every outcome.",
                )
                inceptionYear.set("2026")
                url.set("https://gitlab.com/islandoftex/libraries/rhea")
                organization {
                    name.set("Island of TeX")
                    url.set("https://gitlab.com/islandoftex")
                }
                licenses {
                    license {
                        name.set("""BSD 3-clause "New" or "Revised" License""")
                        url.set("https://gitlab.com/islandoftex/libraries/rhea/blob/main/LICENSE")
                    }
                }
                scm {
                    connection.set("scm:git:git://gitlab.com/islandoftex/libraries/rhea.git")
                    developerConnection.set("scm:git:ssh://git@gitlab.com:islandoftex/libraries/rhea.git")
                    url.set("https://gitlab.com/islandoftex/libraries/rhea")
                }
                ciManagement {
                    system.set("GitLab")
                    url.set("https://gitlab.com/islandoftex/libraries/rhea/pipelines")
                }
                issueManagement {
                    system.set("GitLab")
                    url.set("https://gitlab.com/islandoftex/libraries/rhea/-/issues")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitLab"
            // TODO: set the project id once the GitLab project exists
            url = uri("https://gitlab.com/api/v4/projects/0/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                if (project.hasProperty("jobToken")) {
                    name = "Job-Token"
                    value = project.property("jobToken").toString()
                }
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
}

// the publish flow needs the CI job token: a loud warning beats a
// silent, doomed upload
if (
    !project.hasProperty("jobToken") &&
    gradle.startParameter.taskNames.any { requested ->
        val name = requested.substringAfterLast(':')
        (
            name.contains("publish", ignoreCase = true) &&
                !name.startsWith("publishToMavenLocal", ignoreCase = true)
        ) ||
            name.lowercase().startsWith("generatepomfile")
    }
) {
    logger.warn(
        """
        Island of TeX ----------------------------------------------
        Will be unable to publish (jobToken missing)
        Pass -PjobToken=... (CI: -PjobToken=${'$'}CI_JOB_TOKEN).
        ------------------------------------------------------------
        """.trimIndent(),
    )
}

class VersionProfile(
    private val forJava: String,
    private val forKotlin: String,
) {
    val java: Int by lazy {
        run { extra[forJava] as String }.toInt()
    }

    val kotlin: String by lazy {
        run { extra[forKotlin] as String }
    }
}
