// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationModelsTest {
    private val forward = TranslationModel(TranslationPair("en", "es"), "test-a", 10)
    private val reverse = TranslationModel(TranslationPair("es", "en"), "test-b", 11)
    private class Records : TranslationModelRecords {
        var data = emptyMap<TranslationPair, ModelRetention>(); var writes = 0; var writable = true
        override suspend fun load() = data
        override suspend fun save(records: Map<TranslationPair, ModelRetention>): Boolean {
            writes++; if (!writable) return false; data = records; return true
        }
    }
    private inner class Engine : EpubTranslationEngine {
        override val identity = "test"; override val unavailable: String? = null
        override val models = listOf(forward, reverse)
        val installed = mutableSetOf<TranslationPair>(); val deleted = mutableListOf<TranslationPair>()
        var downloads = 0; var inspections = 0; var gate: CompletableDeferred<Unit>? = null; var failures = 0
        val callbacks = mutableListOf<(Long, Long) -> Unit>()
        override suspend fun installed(model: TranslationModel): Boolean { inspections++; return model.pair in installed }
        override suspend fun install(model: TranslationModel, progress: (Long, Long) -> Unit) {
            downloads++; callbacks.add(progress); progress(1, model.bytes); gate?.await()
            if (failures-- > 0) throw EpubTranslationException("Model download failed")
            installed.add(model.pair); progress(model.bytes, model.bytes)
        }
        override suspend fun remove(model: TranslationModel) { deleted.add(model.pair); installed.remove(model.pair) }
        override suspend fun storageBytes() = models.filter { it.pair in installed }.sumOf { it.bytes }
        override suspend fun translate(model: TranslationModel, text: String) = text
        override fun close() = Unit
    }
    @Test fun startupDoesNotInspectDownloadOrLoadModels() = runTest {
        val engine = Engine(); val manager = TranslationModels(engine, dispatcher = StandardTestDispatcher(testScheduler))
        runCurrent(); assertEquals(0, engine.inspections); assertEquals(0, engine.downloads)
        assertFalse(manager.state.value.loaded); manager.close(); manager.awaitClosed()
    }
    @Test fun cancelledDownloadCallbacksCannotReplaceNewDownloadProgress() = runTest {
        val engine = Engine(); engine.gate = CompletableDeferred()
        val manager = TranslationModels(engine, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val first = manager.install(forward, false); runCurrent()
            val old = engine.callbacks.single(); manager.cancel(); runCurrent(); assertFalse(first.await())
            engine.gate = CompletableDeferred()
            manager.install(reverse, false); runCurrent()
            assertEquals(1L, manager.state.value.downloaded); assertEquals(11L, manager.state.value.total)
            old(10, 10)
            assertEquals(1L, manager.state.value.downloaded); assertEquals(11L, manager.state.value.total)
        } finally { manager.close(); runCurrent(); manager.awaitClosed() }
    }
    @Test fun duplicateDownloadsShareOneOwnedOperationAndOtherCommandsDoNotQueue() = runTest {
        val engine = Engine(); engine.gate = CompletableDeferred()
        val manager = TranslationModels(engine, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val first = manager.install(forward, false); val duplicate = manager.install(forward, false)
            assertSame(first, duplicate); assertFalse(manager.install(reverse, false).await())
            assertFalse(manager.pin(forward, true).await()); runCurrent(); assertEquals(1, engine.downloads)
            assertTrue(manager.state.value.working); engine.gate!!.complete(Unit); runCurrent()
            assertTrue(first.await()); assertFalse(manager.state.value.working); assertEquals(10L, manager.state.value.storageBytes)
        } finally { manager.close(); manager.awaitClosed() }
    }
    @Test fun cancellationBeforeWorkerStartsDoesNotLeaveTheManagerLocked() = runTest {
        val engine = Engine(); val manager = TranslationModels(engine, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val cancelled = manager.install(forward, false); manager.cancel(); runCurrent()
            assertFalse(cancelled.await()); assertFalse(manager.state.value.working); assertEquals(0, engine.downloads)
            val retry = manager.install(forward, true); runCurrent(); assertTrue(retry.await())
        } finally { manager.close(); manager.awaitClosed() }
    }
    @Test fun retryRepeatsTheFailedDownloadAndPinnedInstallationCannotBeUnpinnedByReuse() = runTest {
        val engine = Engine(); engine.failures = 1; val records = Records()
        val manager = TranslationModels(engine, records, StandardTestDispatcher(testScheduler)) { 100 }
        try {
            val first = manager.install(forward, true); runCurrent(); assertFalse(first.await())
            assertNotNull(manager.state.value.error); manager.retry(); runCurrent(); assertEquals(2, engine.downloads)
            assertTrue(records.data.getValue(forward.pair).pinned)
            val reused = manager.install(forward, false); runCurrent(); assertTrue(reused.await())
            assertTrue(records.data.getValue(forward.pair).pinned)
        } finally { manager.close(); manager.awaitClosed() }
    }
    @Test fun legacyInstallationsAreProtectedAndOnlyOldUnpinnedModelsCanBeCleaned() = runTest {
        val engine = Engine(); engine.installed.addAll(listOf(forward.pair, reverse.pair))
        var now = TranslationModels.TEMPORARY_AGE + TranslationModels.USAGE_WRITE_INTERVAL + 100
        val records = Records(); records.data = mapOf(reverse.pair to ModelRetention(false, 1))
        val manager = TranslationModels(engine, records, StandardTestDispatcher(testScheduler)) { now }
        try {
            val refresh = manager.refresh(); runCurrent(); assertTrue(refresh.await())
            assertTrue(manager.state.value.models.first().retention.pinned)
            val cleanup = manager.cleanTemporary(); runCurrent(); assertTrue(cleanup.await())
            assertEquals(listOf(reverse.pair), engine.deleted); assertTrue(forward.pair in engine.installed)
            val unpin = manager.pin(forward, false); runCurrent(); assertTrue(unpin.await())
            // An unknown last-use timestamp is conservatively protected.
            val unknown = manager.cleanTemporary(); runCurrent(); assertTrue(unknown.await()); assertEquals(1, engine.deleted.size)
        } finally { manager.close(); manager.awaitClosed() }
    }
    @Test fun activeModelsAreProtectedAndUsageWritesAreThrottled() = runTest {
        val engine = Engine(); val records = Records(); var now = TranslationModels.TEMPORARY_AGE + TranslationModels.USAGE_WRITE_INTERVAL + 100
        records.data = mapOf(forward.pair to ModelRetention(false, 1))
        val manager = TranslationModels(engine, records, StandardTestDispatcher(testScheduler)) { now }
        val gate = CompletableDeferred<Unit>()
        try {
            val usage = launch { manager.use(forward) { gate.await() } }; runCurrent()
            assertTrue(manager.state.value.models.first().inUse)
            val cleanup = manager.cleanTemporary(); runCurrent(); assertTrue(cleanup.await()); assertTrue(engine.deleted.isEmpty())
            val remove = manager.remove(forward); runCurrent(); assertFalse(remove.await()); assertTrue(engine.deleted.isEmpty())
            val other = manager.remove(reverse); runCurrent(); assertFalse(other.await()); assertTrue(engine.deleted.isEmpty())
            gate.complete(Unit); runCurrent(); usage.join(); assertFalse(manager.state.value.models.first().inUse)
            val count = records.writes
            repeat(20) { manager.use(forward) { "cached text" } }; assertEquals(count, records.writes)
            now += 3_600_000; manager.use(forward) { Unit }; assertEquals(count + 1, records.writes)
        } finally { gate.complete(Unit); manager.close(); manager.awaitClosed() }
    }
    @Test fun storageFailureCannotMakeUnknownModelsEligibleForDeletion() = runTest {
        val engine = Engine(); val records = Records(); records.writable = false
        val manager = TranslationModels(engine, records, StandardTestDispatcher(testScheduler)) { TranslationModels.TEMPORARY_AGE + 100 }
        try {
            val install = manager.install(forward, false); runCurrent(); assertFalse(install.await())
            assertNotNull(manager.state.value.error); assertTrue(manager.state.value.models.first().retention.pinned)
            assertTrue(manager.state.value.models.first().installed)
            val cleanup = manager.cleanTemporary(); runCurrent(); assertTrue(cleanup.await()); assertTrue(engine.deleted.isEmpty())
        } finally { manager.close(); manager.awaitClosed() }
    }
    @Test fun readingUsageDoesNotPretendThatInstalledInventoryHasBeenInspected() = runTest {
        val engine = Engine(); engine.installed.add(forward.pair)
        val manager = TranslationModels(engine, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            manager.use(forward) { Unit }; assertFalse(manager.state.value.loaded); assertEquals(0, engine.inspections)
            val checked = manager.refresh(); runCurrent(); assertTrue(checked.await())
            assertTrue(manager.state.value.loaded); assertTrue(manager.state.value.models.first().installed)
        } finally { manager.close(); manager.awaitClosed() }
    }
    @Test fun readerDisposalDoesNotCancelAnExplicitApplicationOwnedDownload() = runTest {
        val engine = Engine(); engine.gate = CompletableDeferred()
        val service = EpubTranslationService(engine, modelDispatcher = StandardTestDispatcher(testScheduler))
        val controller = EpubTranslationController(service, backgroundScope, null, "en") {}
        try {
            controller.selection("Hello"); runCurrent(); assertEquals(forward, controller.state.value.requiredModel)
            assertEquals(0, engine.downloads); controller.install(); runCurrent(); assertEquals(1, engine.downloads)
            controller.close(); runCurrent(); assertTrue(service.models.state.value.working)
            engine.gate!!.complete(Unit); runCurrent(); assertTrue(service.models.state.value.models.first().installed)
            assertNull(controller.state.value.selectionResult); assertNull(controller.state.value.model)
        } finally { service.close(); runCurrent(); service.awaitClosed() }
    }
}
