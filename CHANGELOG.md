# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
