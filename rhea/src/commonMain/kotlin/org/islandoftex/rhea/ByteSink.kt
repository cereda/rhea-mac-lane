// SPDX-License-Identifier: BSD-3-Clause
package org.islandoftex.rhea

/**
 * The raw chunk's face: the bytes exactly as the boundary read
 * them, the caller's decoder owning the faces.
 *
 * This port is the byte-level answer to the line-based [Sink]:
 * a prompt riding no newline (the interactive tools' habit)
 * reaches the caller's hand as its own chunk the moment the child
 * writes it, no line split waits for it. The [Stream]'s
 * attribution is the pipe's own law: `OUT`/`ERR` under the
 * separate capture, `UNKNOWN` under the merged one, nothing under
 * the raw capture (`NONE` — no pipes exist to hear). A chunk's
 * edge may split a UTF-8 multi-byte sequence: the bytes are for
 * appending and looking, never for decoding per chunk. The ears
 * serialize: the chunks arrive one at a time — the concurrent
 * drains ride the tiers' own locks, the caller's decoder needs
 * no lock of its own.
 *
 * The appending law, in the small — the chunks append, the
 * decoder owns the end:
 *
 * ```
 * val heard = StringBuilder()
 * val result = Rhea.run(
 *     "pdflatex", "-errorstopmode", "doc.tex",
 *     options = RunOptions(byteSink = { chunk, _ -> heard.append(chunk) }),
 * )
 * ```
 */
public fun interface ByteSink {
    /**
     * Accepts one raw [chunk] from [stream].
     */
    public fun accept(
        chunk: ByteArray,
        stream: Stream,
    )
}
