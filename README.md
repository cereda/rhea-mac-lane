# Rhea

_The Island's process runner: one port, four platforms, typed verdicts._

## Highlights

- **Typed verdicts.** A run returns `ProcessResult.Completed` or
  `ProcessResult.Destroyed`. Any exit code is the tool's own answer; only
  the boundary failing throws `ProcessException`.
- **Never a shell.** You pass an executable and its arguments, typed and
  separate. Rhea never parses, never splits, and the argv reaches the
  kernel as you wrote it.
- **The capture survives every outcome.** A killed tool's output stays in
  your hands, and it is usually exactly the story you need. When a
  session's pipe breaks, the exception carries the output collected so
  far.
- **Honest attribution.** The separate capture tells you which stream
  said what. The merged capture keeps the true interleaving of both
  streams and refuses to guess the attribution.
- **A console mode.** Capture mode `NONE` is for interactive tools: no
  pipes at all, the child writes straight to your console, and the user
  types straight into the child.
- **Kotlin Multiplatform.** `jvm`, `linuxX64`, `macosArm64`, `mingwX64`.
  One API, no platform cages.
- **Dependency-free core.** The runner pulls no coroutines (the
  suspending face is its own opt-in module), no logger facades, nothing.
  Your logger adapts in one lambda.

## Overview

Rhea runs external commands for the Island of TeX's libraries. You give
it an executable and its arguments, and you get back a typed verdict and
whatever the tool printed. Shell quirks, exit code conventions and
string splitting all die at the boundary. The library is two artifacts:
the runner (`org.islandoftex:rhea`) and its optional coroutines face
(`org.islandoftex:rhea-coroutines`), versions in lockstep.

| Target                   | Machinery                                                                                      | Tests run           |
|--------------------------|------------------------------------------------------------------------------------------------|---------------------|
| `jvm`                    | the JVM's `ProcessBuilder`                                                                     | wherever a JVM runs |
| `linuxX64`, `macosArm64` | POSIX `fork` + `execvp`, pipes drained by concurrent threads, the wait status factored by hand | on a matching host  |
| `mingwX64`               | Win32 `CreateProcessW`, anonymous pipes, the Microsoft quoting rules                           | on Windows          |

## Installation

The packages ride the GitLab Maven registry; every tag lands there as a
release.

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://gitlab.com/api/v4/projects/88599570/packages/maven")
    }
}

dependencies {
    implementation("org.islandoftex:rhea:<version>")
}
```

## The API

### `Rhea`, the facade

| Method                                                 | What it does                                                    |
|--------------------------------------------------------|-----------------------------------------------------------------|
| `run(executable, args, options): ProcessResult`        | runs the command and waits for the verdict                      |
| `run(executable, vararg args, options): ProcessResult` | the same, with the arguments spelled out                        |
| `start(executable, args, options): Session`            | starts a conversation instead: the child runs while you feed it |
| `start(executable, vararg args, options): Session`     | the same, the vararg form                                       |

### `ProcessResult`, the verdict

The run's outcome. A tool that exits non-zero still gives you a
`Completed`, carrying that code; only the boundary itself failing throws
`ProcessException`.

| Member                            | Meaning                                                                                                                                                            |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `output: String`                  | everything the tool printed. Under `SEPARATE` this is the arrival order of the two drains; under `MERGED` it is the kernel's true order; under `NONE` it is empty. |
| `Completed.exitCode: Int`         | the tool's own answer. Zero, failure codes, the shell's 127 convention: it is all vocabulary.                                                                      |
| `Completed.stdout: String?`       | the stdout alone. Populated under `SEPARATE`, null under `MERGED` and `NONE`.                                                                                      |
| `Completed.stderr: String?`       | the stderr alone, on the same terms.                                                                                                                               |
| `Destroyed.reason: DestroyReason` | why the process was killed: `TIMEOUT`, or `DESTROYED` (you called `Session.destroy`). The output collected so far is in `output`.                                  |

### `ProcessException`, the boundary's failure

The boundary's own failure: the command is missing, the platform refuses
the spawn, or a pipe breaks mid-conversation. Every tier tells this
story the same way (the POSIX tier detects a failed exec through an
internal exit code and hands you the exception instead of the number).
The `output` property holds whatever the tool printed before the
failure: a broken session pipe carries the conversation so far, while a
refused spawn has nothing to carry and answers `""`.

### `Session`, the conversation

`Rhea.start` returns a `Session`: the child runs with its stdin
connected to a pipe that you drive. This is the mode for conversational
tools. You write the answer to a prompt while the child lives, you say
when your input ends, and you can pull the plug early. The verdict
arrives at `waitFor`, shaped exactly like `run`'s, and the capture and
the sinks run live the whole time.

| Member | Meaning |
|---|---|
| `write(text)` / `write(bytes)` | writes to the child's stdin and flushes. Writing to a dead child throws `ProcessException`. Writing after `endOfInput` throws `IllegalStateException`: the child may well be alive, but your side of the pipe is done. |
| `endOfInput()` | closes your side, so the child's stdin reads its EOF. |
| `destroy()` | runs the kill ladder on demand; the verdict reports `DESTROYED`. On an already-exited child it does nothing. On POSIX the call blocks for the grace period; on the JVM and on Windows the waiting happens elsewhere and the call returns at once. |
| `pid: Long` | the child's process id: the JVM's `Process.pid()`, the fork's pid, or the Win32 process id. |
| `waitFor(): ProcessResult` | the verdict. Asking again, even from two threads at once, answers the same object. This is also where the leftovers go: the drained streams close, and so does a stdin you never spoke the end on, so a late `write` answers `ProcessException`. |

One rule above all: **the session's stdin is always a pipe**. The
`stdin` option of `RunOptions` is ignored here, because the pipe's other
end is your hand. Everything else composes as in `run`: the capture
mode, the sinks, the timeout ladder.

### `Stdin`, the sealed policy

| Value                         | Meaning                                                                                                                                                  |
|-------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Stdin.Inherit` (the default) | the child reads your stdin: a prompt reaches the user, and the user's answer reaches the child                                                           |
| `Stdin.Closed`                | the child reads an immediate end of stream. On Windows this means the child gets no stdin handle at all, and the tool's own read decides what that means |
| `Stdin.From(bytes)`           | the child reads the given bytes, then an end of stream                                                                                                   |

### `Sink`, the logger's seam

A consumer for the tool's lines while they stream: a logger, the
console, a file. The runner depends on nothing, so your logger adapts
in one lambda.

Lines arrive with their stream attached, which is the floor for level
routing (stderr to the warnings, say). Under the merged capture lines
arrive as `Stream.UNKNOWN`, because the pipe erased the attribution and
Rhea refuses to guess. Under `NONE` no pipes exist and the sink hears
nothing. The calls are serialized: even with two drains running, your
sink sees one line at a time and needs no lock of its own.

| Member                         | Meaning                                                       |
|--------------------------------|---------------------------------------------------------------|
| `Sink { line, stream -> ... }` | the live consumer of the child's lines, alongside the capture |
| `Stream.OUT` / `Stream.ERR`    | the attribution under the separate capture                    |
| `Stream.UNKNOWN`               | the attribution under the merged capture: the pipe erased it  |

### `ByteSink`, the byte level

The line sink waits for a newline: a prompt that never ends in one (the
interactive tools' habit, `pdflatex`'s `?` among them) stays invisible
until the child exits. `ByteSink` drops that wait. You get every chunk
exactly as the pipe handed it over, the moment it arrived, one chunk at
a time, serialized like the line sink.

A chunk may split a UTF-8 sequence at its edge, so append the bytes
somewhere and decode once at the end instead of decoding per chunk.
Attribution follows the pipe's law: `OUT`/`ERR` under the separate
capture, `UNKNOWN` under the merged one, nothing under `NONE`. The sinks
compose: set both, and the same drain feeds each.

| Member                              | Meaning                                                           |
|-------------------------------------|-------------------------------------------------------------------|
| `ByteSink { chunk, stream -> ... }` | the live consumer of the child's raw bytes, alongside the capture |

### `RunOptions`, the modifiers

Every field is optional; the unset ones stay out of the machinery.

| Field | Type | Meaning |
|---|---|---|
| `capture` | `CaptureMode` | `SEPARATE` (the default: attributed streams), `MERGED` (both streams into one pipe, the kernel's true order, the attribution gone), or `NONE` (no pipes: the child writes to your console, nothing is captured) |
| `stdin` | `Stdin` | `Inherit` (the default), `Closed`, or `From(bytes)` |
| `directory` | `String?` | the child's working directory; null keeps yours |
| `environment` | `Map<String, String>` | variables the child's inherited environment gains or overrides |
| `sink` | `Sink?` | the live consumer of the child's lines alongside the capture; null is the silent capture |
| `byteSink` | `ByteSink?` | the raw-byte version of the same idea; null is the byte level unheard |
| `timeout` | `Duration?` | how long the child may run before the boundary kills it; null lets it run as long as it wants |
| `killOnParentDeath` | `Boolean`, default true | the orphan fence: when rhea's own process dies, the child dies with it. `false` is the daemon's door: the child may outlive the run. A no-op on macOS, where the kernel offers no such mechanism |

### `rhea-coroutines`, the suspending face

The `rhea-coroutines` artifact adds the suspending face of the same API:
two `await` functions, nothing else. The dependency is opt-in and one-way
(the coroutines face knows the runner, never the reverse), and the
versions ride in lockstep with the runner's own.

```kotlin
dependencies {
    implementation("org.islandoftex:rhea-coroutines:<version>")
}
```

The names are `await` everywhere, so the blocking and the suspending
faces never blur into an overload pair. The pair wears one import of
its own (the extensions are top-level, so they wear it even though the
package is the runner's):

```kotlin
import org.islandoftex.rhea.await
```

| Member | Meaning |
|---|---|
| `Rhea.await(executable, args, options): ProcessResult` | the everyday run as a suspension point. A cancelled await returns control at once, and the child's life stays with its own options (the timeout's ladder, the orphan fence) |
| `Session.await(): ProcessResult` | the conversation's verdict as a suspension point. A cancelled await pulls the plug: the child dies by the ladder's hand. The session's own timeout answers `Destroyed(TIMEOUT)` through the same face |

The blocking wait parks on a worker thread that can afford it: the
JVM's IO pool, the natives' default pool. The pool is the ceiling: that
many awaits park that many threads.

The run's await: the everyday run, suspended.

```kotlin
val result = Rhea.await("kpsewhich", args = listOf("--version"))
```

The session's await: the verdict as a suspension point.

```kotlin
val session = Rhea.start("pdflatex", args = listOf("paper.tex"))
val result = session.await()
```

The cancellation's law is the session's own: if you change your mind,
the child does not outlive the decision.

```kotlin
val job = launch { session.await() }
// the caller's own change of mind:
job.cancel()   // the child is destroyed
```

The concurrency is the point: the runs the blocking API would
serialize, the suspending face races. Each await parks on its own
worker thread; the verdicts meet at the end.

```kotlin
coroutineScope {
    // the two tools run at once, each parked on its own worker thread
    val kpsewhich = async { Rhea.await("kpsewhich", args = listOf("--version")) }
    val pdflatex = async { Rhea.await("pdflatex", args = listOf("--version")) }
    val verdicts = listOf(kpsewhich, pdflatex).awaitAll()
}
```

And the structured face: a scope's cancellation reaches every session
its children await. One pull of the plug, every session's own hook
fires, every still-alive child destroyed.

```kotlin
coroutineScope {
    // the conversions race; the scope's cancellation destroys
    // every session still awaited
    val conversions =
        listOf("pdf", "dvi").map { format ->
            async {
                Rhea.start("pdflatex", "-$format", "paper.tex").await()
            }
        }
    conversions.awaitAll()
}
```

The failures keep the runner's vocabulary: the boundary's refusals
throw `ProcessException` through the await; the exit codes stay
answers.

### The timeout ladder

When the timeout expires the boundary acts in stages: the graceful kill
first (SIGTERM on the POSIX tier, `destroy()` on the JVM), a grace
period for the child's own cleanup, then the forceful escalation
(SIGKILL; on Windows, `TerminateProcess`, since Windows knows no
gentler face). The verdict is `Destroyed` and the partial capture
survives. A child that finishes inside the timeout is never touched.

### Orphan processes

By default the child does not outlive you. When rhea's own process
dies, the child is killed through each tier's mechanism: the JVM's
shutdown hook, Linux's `PR_SET_PDEATHSIG` (instant and unconditional),
and on Windows the job object's kill-on-close, which takes the child's
whole tree along. macOS is the documented silence: the kernel offers no
parent-death answer, so the fence does nothing there.

The `killOnParentDeath = false` option is the daemon's door: launchers
that spawn a child meant to outlive them open it explicitly.

## Usage

The everyday run. You get a verdict, and the exit code is an answer,
not a failure.

```kotlin
val result = Rhea.run("kpsewhich", "--version")

when (result) {
    is ProcessResult.Completed -> println(result.output)
    is ProcessResult.Destroyed -> error("the tool hung: ${result.output}")
}
```

A missing command is never a verdict, it is the exception. Every tier
throws it, and a blank executable throws the same way.

```kotlin
try {
    Rhea.run("no-such-tool-anywhere")
} catch (failure: ProcessException) {
    // the boundary's own failure: the child never ran
    println("the boundary refused: ${failure.message}")
}
```

Want to know which stream said what? That is the separate capture, the
default.

```kotlin
val result = Rhea.run(
    "/bin/sh",
    args = listOf("-c", "echo answered; echo complained >&2"),
)
result.stdout?.let { println("the answer: $it") }
result.stderr?.let { println("the complaint: $it") }
```

Sometimes the interleaving itself is the artifact (a LaTeX log, say).
The merged capture joins the streams at the pipe level and keeps the
kernel's true order.

```kotlin
val result = Rhea.run(
    "pdflatex",
    args = listOf("document.tex"),
    options = RunOptions(capture = CaptureMode.MERGED),
)
```

Interactive tools want a real terminal, not a capture. `CaptureMode.NONE`
gives them exactly that: whatever the tool writes, prompt or progress or
question, your console shows it the instant it is written, and whatever
you type reaches the tool. The verdict still arrives with the exit code;
the capture fields stay empty.

```kotlin
// pdflatex's question marks reach the user, the user's typed answer
// reaches pdflatex, and the run still returns typed
val result = Rhea.run(
    "pdflatex",
    args = listOf("document.tex"),
    options = RunOptions(capture = CaptureMode.NONE),
)
```

The modes compose: a pumped stdin still rides its own pipe while stdout
and stderr stay raw.

```kotlin
val result = Rhea.run(
    "bc",
    options = RunOptions(
        capture = CaptureMode.NONE,
        stdin = Stdin.From("2 + 2\n".encodeToByteArray()),
    ),
)
```

The child's surroundings: a working directory and an environment
override.

```kotlin
val result = Rhea.run(
    "latexmk",
    args = listOf("-pdf", "paper.tex"),
    options = RunOptions(
        directory = "/tmp/build",
        environment = mapOf("TEXMFOUTPUT" to "/tmp/build"),
    ),
)
```

The sink sees every line as it streams, with its attribution.

```kotlin
val result =
    Rhea.run(
        "pdflatex",
        args = listOf("document.tex"),
        options =
            RunOptions(
                sink = { line, stream ->
                    if (stream == Stream.ERR) System.err.println(line) else println(line)
                },
            ),
    )
```

When the output matters below the line (a prompt that never ends in a
newline, a progress bar rewriting itself), the byte sink gets each chunk
as the pipe hands it over. A prompt wakes this sink while the child
still runs; the line sink above would have waited for the exit.

```kotlin
val heard = StringBuilder()

val result =
    Rhea.run(
        "pdflatex",
        args = listOf("-interaction=nonstopmode", "document.tex"),
        options =
            RunOptions(
                byteSink = { chunk, _ ->
                    heard.append(chunk.decodeToString()) // append, decode at the end
                },
            ),
    )
```

A chunk may split a UTF-8 sequence at its edge, so the honest decoder
appends the bytes and decodes once at the end. The example shows the
shape; the law above is the full story.

The conversation: `Rhea.start` returns a session whose stdin is a pipe
you drive. You write the answer while the child lives, you speak the
end, you read the verdict. The real case: pdflatex's errorstop mode
stops at the `?` prompt, a prompt that never ends in a newline, so the
byte sink smells it the moment it is written, and your hand stands in
for the keyboard (`x` means exit; a line on a pipe ends in `\n` alone,
the Enter key's console accent).

```kotlin
val heard = StringBuilder()

val session =
    Rhea.start(
        "pdflatex",
        args = listOf("-errorstopmode", "paper.tex"),
        options =
            RunOptions(
                byteSink = { chunk, _ ->
                    heard.append(chunk.decodeToString()) // the prompt's ASCII survives the chunk's edges
                    if (heard.contains("? ")) println("the tool asks")
                },
            ),
    )

session.write("x\n")   // the answer to the prompt the byte sink smelled
session.endOfInput()

val result = session.waitFor()

when (result) {
    is ProcessResult.Completed -> println("the tool answered: ${result.exitCode}")
    is ProcessResult.Destroyed -> error("the tool was destroyed: ${result.reason}")
}
```

The child's identity: the pid answers for logs and ledgers while the
conversation runs.

```kotlin
val session = Rhea.start("pdflatex", args = listOf("paper.tex"))
println("the tool runs as pid ${session.pid}")   // the platform's own number

val result = session.waitFor()
```

Feeding stdin: the child gets the bytes, then the pipe closes. If the
child dies mid-feed, the verdict still arrives (the pump's failure never
swallows it).

```kotlin
val result =
    Rhea.run(
        "bc",
        options =
            RunOptions(
                stdin = Stdin.From("2 + 2\n".encodeToByteArray()),
            ),
    )
```

The disabled stdin: `Stdin.Closed` hands the child an immediate end of
stream, which converts every hang into an ending. A tool that waits for
input reads the EOF and answers on its own terms: pdflatex's errorstop
mode, probed on a real tree, stops at the `?` prompt, reads the end of
stream, prints `! Emergency stop.` and exits with code 1 (the tool's own
vocabulary; the verdict is a clean `Completed`). Nothing hangs, the log
explains, and you decide.

```kotlin
val result =
    Rhea.run(
        "pdflatex",
        args = listOf("-errorstopmode", "paper.tex"),
        options = RunOptions(stdin = Stdin.Closed),
    )
```

The timeout: a hung child is destroyed, the ladder runs, the partial
capture survives.

```kotlin
val result =
    Rhea.run(
        "pdflatex",
        args = listOf("interactive.tex"),
        options = RunOptions(timeout = 30.seconds),
    )

when (result) {
    is ProcessResult.Completed -> println(result.output)
    is ProcessResult.Destroyed -> println("killed after 30s: ${result.output}")
}
```

The daemon's door: the orphan fence is on by default, but a launcher
that spawns a child meant to outlive it opens the door explicitly.

```kotlin
val result =
    Rhea.run(
        "my-daemon",
        args = listOf("--serve"),
        options = RunOptions(killOnParentDeath = false),
    )
```

## The proof

Every change rides one gate:
`./gradlew allTests spotlessCheck detekt koverVerify changelogCheck
dokkaGenerateHtml -PwarningsAsErrors=true`. That is all four targets'
tests, the formatting, static analysis with zero issues tolerated, the
coverage floors, the changelog's consistency, and a dokka pass that
refuses undocumented public declarations, all with warnings treated as
errors.

Coverage over the JVM-visible surface (both modules), straight from the
kover report:

| Unit | Score | Floor | The uncovered faces |
|---|---|---|---|
| Lines | 99.6% (268 of 269) | 95% | the Windows' null-device sniff; the forceful escalation's line, proven by its own test in isolation (the full suite's report loses the daemon ladder thread's data) |
| Branches | 92.9% (78 of 84) | 90% | the same faces' edges, plus a few compiler-generated exhaustiveness edges |

Both floors hold; the gate's `koverVerify` speaks these numbers every
build.

| Suite | Runs on | Tests | What it proves |
|---|---|---|---|
| `commonTest` | every target | 4 | the caller's own failures (the blank executable), the await pair's blank faces |
| `jvmTest` | any JVM host | 46 | the `ProcessBuilder` tier, the session's edge cases, and the await pair: the conversation, the verdict's cache, the timeout's ladder, the cancellation's plug-pull |
| `posixTest` | `linuxX64` and `macosArm64` | 34 | the fork tier: the conversation, the timeout ladder, the orphan fence, the broken pipe, the await faces |
| `mingwX64Test` | `mingwX64` | 30 | the Win32 tier: quoting, the environment block, the job fence, the handle ledger, the await faces |
| `linuxX64Test` | `linuxX64` | 2 | the `PR_SET_PDEATHSIG` fence for real, and the fd ledger: a captured run leaves no file descriptor behind |

The counts span both modules: the runner's own suites and the
coroutines face's await proofs. Four CI lanes (JVM, Linux, macOS,
Windows) run the matrix on every push of the proof mirror. Native
suites run on the hosts that can execute them; the other targets
compile as part of the build.

## License

This library is licensed under the
[New BSD License](https://opensource.org/licenses/BSD-3-Clause).
