# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

* The mingw tier's full parity: the SEPARATE capture's concurrent
  drain (the Win32 threads — the deadlock's documented caveat
  dies), the pumped stdin's real pipe (the child's stdin was the
  nothing's face before!), and the timeout's budget: the
  process-wait's expiry speaks TerminateProcess (the Windows'
  kill's only face — no graceful signal exists) and the verdict
  speaks Destroyed.

* The seed tier: the one-shot synchronous run (`Rhea.run`), the
  typed verdict (`ProcessResult.Completed` with the exit code and
  the capture — any exit code is an answer, not an exception),
  the SEPARATE capture as the default (the attribution:
  `stdout`/`stderr` fields, the `output` composing them in the
  arrival order) and MERGED as the opt-in (the pipe-level join:
  the kernel's true interleaving, the LaTeX log's artifact), the
  stdin policy (the parent's stream inherited, or the closed
  isolation), the direct argv (no shell, no quoting — the words
  go to the kernel: `execvp`/`CreateProcessW`), and the boundary's
  own failure carrying the partial capture (`ProcessException`).
  Four platforms from one port: the JVM's `ProcessBuilder`, POSIX
  `fork`/`execvp` with the `poll()`-drained pipes, and the Win32
  `CreateProcessW` with the Microsoft quoting rules.
* The tiers: the working directory (the child's `chdir`, the
  failure's story riding the captured stderr), the environment's
  overrides (`putenv` over the inherited), the line sink (the
  attributed lines: `OUT`/`ERR` — and `UNKNOWN` under the merged
  capture, the typed absence's face; the logger's seam with zero
  dependencies — the consumer's logger adapts in one lambda), the
  pumped stdin (`Stdin.From` — the bytes feed the child, the
  pipe's EOF closes the feed; **the fork's hygiene: the child
  closes its inherited copy of the write end**, or the child's
  read never sees EOF — the deadlock's root, the forensics' cat
  graveyard's verdict), and the timeout's destroy escalation (the
  watchdog's detached pthread: the graceful SIGTERM, the grace,
  the forceful SIGKILL — the killed child's partial capture
  survives in the `Destroyed` verdict).
