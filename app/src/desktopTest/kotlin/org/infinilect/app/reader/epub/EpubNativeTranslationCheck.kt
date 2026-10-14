// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in real-engine proof, never part of deterministic test/check/build.
 * Linux ARM smoke tool may run under QEMU. This is NOT Android JNI/device acceptance.
 * Args: qemu-aarch64, ARM sysroot, Foxlet smoke executable, verified en-es config.
 */
object EpubNativeTranslationCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4 && args.all { File(it).isAbsolute })
        val error = File.createTempFile("native-translation", ".diagnostic")
        try {
            val process = ProcessBuilder(args[0], "-L", args[1], args[2], args[3]).redirectError(error).start()
            process.outputStream.bufferedWriter().use {
                it.write("The reader remembers your place in the book.\n")
                it.write("I enjoy reading stories about history and adventure.\n")
                it.write("A small library can open a large world.\n")
            }
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "Native inference timed out" }
                check(process.exitValue() == 0) { "Native inference failed" }
                val result = process.inputStream.bufferedReader().use { it.readText() }
                check(result.lines().filter(String::isNotBlank).size == 3)
                check(result.contains("El lector recuerda tu lugar en el libro."))
                check(result.contains("biblioteca"))
                println("PASS: Kotlin → local ARM native engine → real en-es model; three synthetic sentences, no network calls.")
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { error.delete() }
    }
}
