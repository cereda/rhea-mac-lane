# Rhea

_The Island of TeX's process runner: one port, four platforms — the typed
verdicts of a spawn, the capture that survives every outcome._

## Overview

Rhea runs external commands for the IoT family's libraries: the process
boundary behind a small, dependency-free Kotlin Multiplatform API. The
tool's accent (blank lines, exit-code mysteries, string splitting) dies
at the boundary; the API speaks typed verdicts.

- the executable and its args, spoken separately and typed — the library
  never parses, never splits, never sees a shell
- the capture with the attribution: `stdout` and `stderr` fields by
  default (the lossless face), the pipe-level merge as the opt-in (the
  kernel's true interleaving, the LaTeX log's artifact)
- any exit code is an *answer* — non-zero exits are the tool's own
  vocabulary, not the boundary's failure
- the boundary's own failures (a missing command, a refused spawn) wear
  `ProcessException`, carrying the partial capture: the output printed
  before the failure is exactly what explains it

| Target | The runner's machinery | The tests |
|---|---|---|
| `jvm` | the JVM's `ProcessBuilder` | run everywhere a JVM runs |
| `linuxX64`, `macosArm64` | POSIX `fork` + `execvp` (argv straight to the kernel), the pipes drained through a single `poll()` loop, the wait-status factorization | run on a matching host |
| `mingwX64` | the Win32 `CreateProcessW` + the anonymous pipes + the Microsoft quoting rules | run on Windows |

## Installation

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://gitlab.com/api/v4/projects/PROJECT_ID/packages/maven")
    }
}

dependencies {
    implementation("org.islandoftex:rhea:<version>")
}
```

## The API

### `Rhea`, the facade

| Method | Answers |
|---|---|
| `run(executable, args, options): ProcessResult` | the run's verdict, awaited |
| `run(executable, vararg args, options): ProcessResult` | the same, the vararg face |

### `ProcessResult`, the verdict

| Member | Meaning |
|---|---|
| `output: String` | the capture: the combined view (the arrival order under the separate mode, the kernel's true order under the merged one) — the fact that explains the verdict |
| `Completed.exitCode: Int` | the tool's own answer (zero, the failure codes, the shell's 127 conventions — all vocabulary) |
| `Completed.stdout: String?` | the attributed stdout — populated under the separate capture, null under the merged one |
| `Completed.stderr: String?` | the attributed stderr — the same mode's terms |

### `RunOptions`, the modifiers

| Field | Type | Meaning |
|---|---|---|
| `capture` | `CaptureMode` | `SEPARATE` (the default: the attribution) or `MERGED` (the pipe-level join: the kernel's true order, the attribution's price) |
| `stdin` | `StdinPolicy` | `INHERIT` (the default: the child reads the parent's stdin — the interactive tools' floor) or `CLOSED` (the immediate end of stream) |

The tiers ahead (the roadmap, built on consumer demand): the working
directory, the environment, the line sink (a logger's seam), the pumped
stdin; then the timeouts and the destroy escalation.

## Usage

```kotlin
val result = Rhea.run("kpsewhich", "--version")

when (result) {
    is ProcessResult.Completed -> println(result.output)
}
```

The attribution: the separate capture answers which stream said what.

```kotlin
val result = Rhea.run(
    "/bin/sh",
    args = listOf("-c", "echo answered; echo complained >&2")
)
result.stdout?.let { println("the answer: $it") }
result.stderr?.let { println("the complaint: $it") }
```

The merged capture: the log's interleaving is the artifact.

```kotlin
val result = Rhea.run(
    "pdflatex",
    args = listOf("document.tex"),
    options = RunOptions(capture = CaptureMode.MERGED)
)
```

## License

This library is licensed under the
[New BSD License](https://opensource.org/licenses/BSD-3-Clause).
