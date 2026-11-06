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
    "The coroutines face of Rhea: the await pair — the run's verdict " +
    "and the session's own as suspension points, the cancellation " +
    "pulling the session's plug."

// the shared faces (the version, the compiler options, the toolchain's
// pins, the analysis, the docs, the publication) live in the root's
// own build; this script speaks only what is the coroutines face's
// own: the targets, the hierarchy, the dependencies

kotlin {
    // the same four targets as the runner itself: the await faces
    // work everywhere rhea works
    jvm()
    linuxX64()
    macosArm64()
    mingwX64()

    // the hierarchy: no posix split here — the blocking wait's
    // dispatcher is the native middle layer's own (the default pool
    // on every native, the IO pool on the JVM)
    applyHierarchyTemplate {
        common {
            group("jvm") {
                withJvm()
            }
            group("native") {
                withLinuxX64()
                withMacosArm64()
                withMingwX64()
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // the one-way dependency: the coroutines face knows
                // the runner, never the reverse; `api` because the
                // await signatures wear the runner's own types
                api(project(":rhea"))
                implementation(libs.kotlinx.coroutines)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines)
            }
        }
    }
}
