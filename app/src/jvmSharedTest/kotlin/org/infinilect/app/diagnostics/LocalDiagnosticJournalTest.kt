// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.diagnostics

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.*

class LocalDiagnosticJournalTest {
    private fun fixture(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("original-diagnostic-test").toFile()
        try { block(File(dir, "journal")) } finally { dir.deleteRecursively() }
    }
    @Test fun disabledByDefaultDoesNotCreateFilesOrRecordAnything() = fixture { dir ->
        val journal = LocalDiagnosticJournal(dir)
        journal.restore(); journal.record(TechnicalEvent.HOME_VISIBLE); journal.crashLine("at org.infinilect.Code.run:1"); journal.flush()
        assertFalse(journal.enabled); assertFalse(dir.exists()); assertFalse(journal.summary().contains("HOME_VISIBLE"))
    }
    @Test fun enabledEvidenceSurvivesNewOwnerAndExplicitDisableDeletesOnlyDiagnosticFiles() = fixture { dir ->
        val book = File(dir.parentFile, "untouched-book").apply { writeText("Original content") }
        val journal = LocalDiagnosticJournal(dir) { 42 }
        journal.enable(); journal.record(TechnicalEvent.HOME_LOADING, 7); journal.record(TechnicalEvent.NAV_LIBRARY)
        journal.exit(5, 9, 120, 200); journal.flush()
        val reopened = LocalDiagnosticJournal(dir); reopened.restore()
        assertTrue(reopened.enabled); assertTrue("code=HOME_LOADING g=7" in reopened.summary())
        assertTrue("exit reason=5 status=9" in reopened.summary())
        reopened.disable(); reopened.record(TechnicalEvent.HOME_VISIBLE); reopened.flush()
        assertFalse(reopened.enabled); assertTrue(dir.listFiles()!!.isEmpty()); assertEquals("Original content", book.readText())
        val again = LocalDiagnosticJournal(dir); again.restore(); assertFalse(again.enabled)
    }
    @Test fun ringAndAtomicSnapshotAreBoundedUnderConcurrentEvents() = fixture { dir ->
        val journal = LocalDiagnosticJournal(dir) { 123 }; journal.enable()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..3).map { worker -> pool.submit {
                repeat(500) { i -> journal.record(TechnicalEvent.REQUEST_COMPLETE, i.toLong(), worker); if (i % 100 == 0) journal.flush() }
            } }
            jobs.forEach { it.get() }
            journal.flush()
            val lines = journal.summary().lines().drop(1)
            assertEquals(LocalDiagnosticJournal.MAX_LINES, lines.size)
            assertTrue(File(dir, "events.txt").length() <= LocalDiagnosticJournal.MAX_BYTES)
            assertFalse(File(dir, "events.tmp").exists())
            val reopened = LocalDiagnosticJournal(dir); reopened.restore(); assertEquals(journal.summary(), reopened.summary())
        } finally { pool.shutdownNow() }
    }
    @Test fun incompleteOversizedAndCorruptEvidenceCannotExposeUntrustedText() = fixture { dir ->
        val journal = LocalDiagnosticJournal(dir); journal.enable()
        File(dir, "events.tmp").writeText("Private incomplete snapshot")
        File(dir, "events.txt").writeText("Private metadata\nuncaught depth=0 class=java.lang.IllegalStateException\nat org.infinilect.Code.run:7\nQuery: private\n")
        val restored = LocalDiagnosticJournal(dir); restored.restore()
        assertFalse(File(dir, "events.tmp").exists())
        assertTrue("IllegalStateException" in restored.summary()); assertFalse("Private" in restored.summary()); assertFalse("Query" in restored.summary())
        File(dir, "events.txt").writeText("X".repeat(41_000))
        val oversized = LocalDiagnosticJournal(dir); oversized.restore(); assertTrue(oversized.summary().lines().size <= 2)
    }
    @Test fun crashUsesMessageFreeBoundedFormatterAndAlwaysDelegatesOriginal() = fixture { dir ->
        val journal = LocalDiagnosticJournal(dir); journal.enable()
        val privateText = "Private book title query /private/path credential"
        val error = IllegalStateException(privateText, IOException(privateText))
        error.stackTrace = arrayOf(StackTraceElement("org.infinilect.Code", "run", privateText, 19))
        var delegated: Throwable? = null
        SanitizedCrashDiagnostic({ _, failure -> journal.flush(); delegated = failure }, journal::crashLine)
            .uncaughtException(Thread.currentThread(), error)
        assertSame(error, delegated); assertTrue("Code.run:19" in journal.summary())
        assertFalse(privateText in journal.summary()); assertFalse(privateText in File(dir, "events.txt").readText())
        journal.crashLine(privateText); assertFalse(privateText in journal.summary())
        repeat(1000) { journal.record(TechnicalEvent.COVER_READY) }
        assertTrue("Code.run:19" in journal.summary(), "Reopening/cover churn must not erase the last Java stack")
        assertEquals(LocalDiagnosticJournal.MAX_LINES + 1, journal.summary().lines().size)
        journal.flush()
        val reopened = LocalDiagnosticJournal(dir); reopened.restore(); assertTrue("Code.run:19" in reopened.summary())
        reopened.crashLine("uncaught depth=0 class=java.lang.IllegalArgumentException")
        assertFalse("Code.run:19" in reopened.summary(), "A newer crash replaces the previous stack")
    }
    @Test fun storageFailureLeavesPreviousAtomicSnapshotIntact() = fixture { dir ->
        val journal = LocalDiagnosticJournal(dir); journal.enable(); journal.record(TechnicalEvent.HOME_VISIBLE); journal.flush()
        val previous = File(dir, "events.txt").readText()
        assertTrue(File(dir, "events.tmp").mkdir())
        assertFailsWith<IOException> { journal.flush() }
        assertEquals(previous, File(dir, "events.txt").readText())
    }
    @Test fun unwritableStorageAndConcurrentDisableAreSafeAndExplicit() = fixture { dir ->
        dir.writeText("Synthetic storage obstacle")
        assertFailsWith<IOException> { LocalDiagnosticJournal(dir).enable() }
        assertTrue(dir.delete())
        val journal = LocalDiagnosticJournal(dir); journal.enable()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit { repeat(100) { journal.record(TechnicalEvent.COVER_STARTED); journal.flush() } }
            val disable = pool.submit { journal.disable() }
            writer.get(); disable.get()
            assertFalse(journal.enabled); assertTrue(dir.listFiles()!!.isEmpty())
            assertFalse("COVER_STARTED" in journal.summary())
        } finally { pool.shutdownNow() }
    }
    @Test fun deepestCauseSurvivesFormatFilterStorageAndRestartWithoutRaisingBudgets() = fixture { dir ->
        val journal = LocalDiagnosticJournal(dir); journal.enable()
        var error: Throwable = java.io.IOException("private leaf")
        repeat(10) { error = IllegalStateException("private wrapper", error) }
        SanitizedCrashDiagnostic({ _, _ -> journal.flush() }, journal::crashLine)
            .uncaughtException(Thread.currentThread(), error)
        val reopened = LocalDiagnosticJournal(dir); reopened.restore()
        assertTrue("depth=10 class=java.io.IOException" in reopened.summary())
        assertFalse("private" in reopened.summary())
        assertTrue(reopened.summary().lines().size <= 52)
        assertTrue(File(dir, "events.txt").length() <= LocalDiagnosticJournal.MAX_BYTES)
    }

}
