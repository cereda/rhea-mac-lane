// SPDX-License-Identifier: BSD-3-Clause
import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.gradleup.tapmoc.TapmocExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.credentials.HttpHeaderCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.authentication.http.HttpHeaderAuthentication
import org.jetbrains.dokka.gradle.DokkaExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// resolves plugin ids from the version catalog, so ids appear only in
// gradle/libs.versions.toml; plugins.withId(pluginId(alias)) never drifts
// apart from the id the catalog declares
fun pluginId(alias: String): String =
    the<org.gradle.api.artifacts.VersionCatalogsExtension>()
        .named("libs")
        .findPlugin(alias)
        .get()
        .get()
        .pluginId

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.tapmoc) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotless.changelog)
    alias(libs.plugins.kover)
    alias(libs.plugins.versions)
}

// the lockstep: the root's changelog drives the version for every
// module of the family
spotlessChangelog {
    changelogFile("CHANGELOG.md")
    setAppendDashSnapshotUnless_dashPrelease(true)
    ifFoundBumpBreaking("breaking change")
    tagPrefix("v")
    commitMessage("Release v{{version}}")
    remote("origin")
}
val rheaVersion = spotlessChangelog.versionNext

allprojects {
    group = "org.islandoftex"
    version = rheaVersion
}

// Compatibility profiles (see gradle.properties).
val targetJvm = (property("rhea.target.jvm") as String).toInt()
val targetKotlin = property("rhea.target.kotlin") as String

subprojects {
    plugins.withId(pluginId("kotlin-multiplatform")) {
        extensions.configure<KotlinMultiplatformExtension> {
            // the opt-in gate: run the build with -PwarningsAsErrors=true
            // to prove that the tree compiles without a single warning —
            // every target, one switch
            compilerOptions {
                allWarningsAsErrors.set(
                    providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.getOrElse(false),
                )
            }
        }

        // the JVM's suite: the JUnit platform + the logging's face
        tasks.withType<Test>().configureEach {
            if (name == "jvmTest") {
                useJUnitPlatform()
                testLogging {
                    events(
                        TestLogEvent.FAILED,
                        TestLogEvent.PASSED,
                        TestLogEvent.SKIPPED,
                    )
                }
            }
        }
    }

    plugins.withId(pluginId("tapmoc")) {
        extensions.configure<TapmocExtension> {
            java(targetJvm)
            kotlin(targetKotlin)
            checkDependencies()
        }
    }

    plugins.withId(pluginId("spotless")) {
        configure<SpotlessExtension> {
            // the module's own tree; the Gradle scripts live in the
            // root's own kotlinGradle block below
            kotlin {
                target("src/**/*.kt")
                targetExclude("build/**/*.kt")
                licenseHeader(
                    "// SPDX-License-Identifier: BSD-3-Clause",
                    "(@file:|package |import |plugins )",
                )
                ktlint()
                trimTrailingWhitespace()
                leadingTabsToSpaces()
                endWithNewline()
            }
        }
    }

    plugins.withId(pluginId("detekt")) {
        configure<DetektExtension> {
            ignoreFailures = false
            buildUponDefaultConfig = true
            // the config lives at the root: one analysis voice for the
            // family
            config.from(files(rootProject.file("detekt-config.yml")))
            source.setFrom(files("src"))
        }
    }

    plugins.withId(pluginId("dokka")) {
        configure<DokkaExtension> {
            // the moduleName defaults to the project's name; the
            // named() faces, not the shorthand accessors: the
            // plugin's own accessors don't exist at the root (the
            // plugin rides apply false here)
            dokkaPublications.named("html") {
                suppressInheritedMembers.set(true)
                suppressObviousFunctions.set(true)
                failOnWarning.set(true)
            }

            // the IoT brand's own face: the html plugin's parameters,
            // by type (the shorthand accessor doesn't exist at the
            // root — the plugin rides apply false here)
            pluginsConfiguration
                .withType<org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters>()
                .configureEach {
                    footerMessage.set("Island of TeX")
                }

            dokkaSourceSets.configureEach {
                // emit (and fail on) the undocumented public members
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
    }

    // the publish flow: the GitLab package registry rides the CI job
    // token; the KMP plugin publishes one artifact per target, the pom
    // rides every publication. Every module of the family publishes.
    plugins.withId(pluginId("kotlin-multiplatform")) {
        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(project.description)
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

            repositories {
                maven {
                    name = "GitLab"
                    url = uri("https://gitlab.com/api/v4/projects/88599570/packages/maven")
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
    }
}

// the publish flow needs the CI job token: a loud warning beats a
// silent, doomed upload — the graph's own face (the moonlight's): the
// warning only when a publish task was actually requested
if (!project.hasProperty("jobToken")) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.name.startsWith("publish") }) {
            logger.warn(
                """
                Island of TeX ----------------------------------------------
                Will be unable to publish (jobToken missing)
                Pass -PjobToken=... (CI: -PjobToken=${'$'}CI_JOB_TOKEN).
                ------------------------------------------------------------
                """.trimIndent(),
            )
        }
    }
}

spotless {
    kotlinGradle {
        // every Gradle script of the build, root and modules alike
        target("build.gradle.kts", "settings.gradle.kts", "rhea/build.gradle.kts", "rhea-coroutines/build.gradle.kts")
        licenseHeader(
            "// SPDX-License-Identifier: BSD-3-Clause",
            "(@file:|package |import |plugins |dependencyResolutionManagement |rootProject |include )",
        )
        ktlint()
        trimTrailingWhitespace()
        leadingTabsToSpaces()
        endWithNewline()
    }
}

// the coverage gate: `./gradlew koverVerify` enforces the floors below
// over the family's aggregate (the modules' JVM suites feed it); lower
// the thresholds only with good reason, otherwise add tests for the
// uncovered path (the thin per-platform runners ride the smoke tests
// instead)
kover {
    reports {
        verify {
            rule {
                minBound(95)
            }
            // the branches' floor: the faces the coverage lane cannot
            // reach sit below, forever uncoverable
            rule {
                bound {
                    coverageUnits = CoverageUnit.BRANCH
                    minValue = 90
                }
            }
        }
    }
}

// the family's aggregate: each module's JVM suite feeds the root's
// reports and the verify's floors
dependencies {
    kover(project(":rhea"))
    kover(project(":rhea-coroutines"))
}

tasks {
    withType<DependencyUpdatesTask>().configureEach {
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
