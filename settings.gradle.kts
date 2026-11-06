// SPDX-License-Identifier: BSD-3-Clause
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "rhea-build"

// the family: the runner itself and its coroutines face (the
// artifacts org.islandoftex:rhea and org.islandoftex:rhea-coroutines)
include("rhea")
include("rhea-coroutines")
