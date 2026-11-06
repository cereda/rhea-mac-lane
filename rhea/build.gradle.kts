// SPDX-License-Identifier: BSD-3-Clause
@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.tapmoc)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
    alias(libs.plugins.maven.publish)
}

description =
    "The Island of TeX's process runner: one port, four platforms — " +
    "the typed verdicts of a spawn, the capture that survives every outcome."

// the shared faces (the version, the compiler options, the toolchain's
// pins, the analysis, the docs, the publication) live in the root's
// own build; this script speaks only what is the runner's own: the
// targets, the hierarchy, the dependencies

kotlin {
    // the JVM and the natives: one POSIX actual serves the unix-likes,
    // the Win32 API earns its own (the tests for the non-host targets
    // ride the machines that can run them; the CI keeps them
    // compile-only)
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

tasks {
    // the POSIX layer's metadata compilation: the K/N platform
    // libraries never reach the metadata compilations (the KGP's own
    // limitation — they ride the per-target binary compilations
    // alone), so the layer's platform.* imports cannot resolve there.
    // The disable is load-bearing: the coroutines module's metadata
    // compilations pull this task conservatively (the project
    // dependency's full metadata graph), and they compile fine
    // without its output (proven — the expects' common metadata is
    // all the callers' analysis and compilation need). The layer's
    // real face is the per-target binaries; when a KGP ships the
    // platform libs to the metadata compilations, this dies.
    // (configureEach, not named: the KMP registers its compilation
    // tasks after this script's own evaluation.)
    configureEach {
        if (name == "compilePosixMainKotlinMetadata") {
            enabled = false
        }
    }
}
