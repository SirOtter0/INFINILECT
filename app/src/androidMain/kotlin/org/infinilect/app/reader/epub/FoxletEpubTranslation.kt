// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import android.content.Context
import android.os.Build
import io.github.yinvoker.foxlet.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No Activity, remote text requests, automatic model installation or pivot models. */
internal class FoxletEpubTranslation(context: Context) : EpubTranslationEngine {
    private val application = context.applicationContext
    override val identity = "foxlet/0.5.0/plain/worker1/batch512/prefixes-v1"
    override val unavailable: String? = when {
        Build.VERSION.SDK_INT < 28 -> "Local translation requires Android 9 or newer. Original reading remains available."
        !android.os.Process.is64Bit() || "arm64-v8a" !in Build.SUPPORTED_ABIS -> "Local translation requires a 64-bit ARM device. Original reading remains available."
        (application.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.isLowRamDevice == true -> "Local translation is unavailable on low-memory devices. Original reading remains available."
        else -> null
    }
    override val models = listOf<TranslationModel>(
        TranslationModel(TranslationPair("en", "es"), "2.1:7d51237c0a07027dcd61643cfbbb0f8c48597d19907ef53d2cae9d6bec2cf25c,3b1c399511c01c84c36fae5c0524df44096288efdc8236e182b5c97d7ad2244c,5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad", 36576277L),
        TranslationModel(TranslationPair("es", "en"), "2.0:e2610211d3b9577d012638fe7e7e74ed7b4b708ce96b9e792e67c282a6492daa,4aed7734152ae0045d1a69ae49c86cfda18f53c61f90e95e1d1de1c7c7c3b033,5ae254fa9b15aa182e70fd2a6186b1333c63a29a48043a9224c6aa4fcac058ad", 37014089L),
    )
    private val root = File(application.noBackupFilesDir, "epub-translation-models-v1")
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: Foxlet? = null
    private val installedModels = mutableMapOf<TranslationPair, InstalledModel>()
    private var active: TranslationPair? = null
    @Volatile private var closed = false
    private var closing: Job? = null
    private suspend fun client(): Foxlet {
        check(!closed && unavailable == null)
        return client ?: Foxlet.create(application, FoxletConfig(
            models = ModelsConfig(directory = root,
                downloadOptions = DownloadOptions(maxRetries = 0, resume = false)),
            translation = TranslationConfig(threading = Threading.Fixed(1), cacheSize = 0),
        )).also { client = it }
    }
    private suspend fun descriptor(sdk: Foxlet, model: TranslationModel): ModelDescriptor {
        require(model in models)
        val value = sdk.models.findBundled(LanguagePair(model.pair.source, model.pair.target)) ?: error("Unsupported model")
        val fingerprint = value.version + ":" + value.assets.joinToString(",") { it.sha256 }
        check(fingerprint == model.revision && value.sizeBytes == model.bytes)
        return value
    }
    override suspend fun installed(model: TranslationModel): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val sdk = client(); val exact = descriptor(sdk, model)
            sdk.models.findUsable(exact.pair)?.takeIf { it.descriptor == exact }?.also { installedModels[model.pair] = it } != null
        }
    }
    override suspend fun install(model: TranslationModel, progress: (Long, Long) -> Unit) = safe { mutex.withLock {
        withContext(Dispatchers.IO) {
            val sdk = client(); val exact = descriptor(sdk, model)
            sdk.models.cleanup(CleanupOptions(staleTempAge = kotlin.time.Duration.ZERO))
            if (!translationModelStorageFits(root.toPath())) throw EpubTranslationException("Model storage exceeds its 120 MiB budget. Delete an unused model and retry.")
            if (sdk.models.findUsable(exact.pair)?.descriptor != exact) {
                unload(sdk); active = null; installedModels.remove(model.pair)
                checkDeleted(sdk.models.deleteAll(exact.pair))
                if (!translationModelStorageFits(root.toPath(), model.bytes)) throw EpubTranslationException("Insufficient model storage budget. Delete an unused model and retry.")
            }
            try { installedModels[model.pair] = sdk.models.download(exact) { value -> progress(value.downloadedBytes, value.totalBytes) }; Unit }
            finally { withContext(NonCancellable) { sdk.models.cleanup(CleanupOptions(staleTempAge = kotlin.time.Duration.ZERO)) } }
        }
    }
    }
    override suspend fun remove(model: TranslationModel) = safe { mutex.withLock {
        withContext(Dispatchers.IO) {
            val sdk = client(); descriptor(sdk, model)
            unload(sdk); active = null
            installedModels.remove(model.pair)
            checkDeleted(sdk.models.deleteAll(LanguagePair(model.pair.source, model.pair.target))); Unit
        }
    }
    }
    override suspend fun translate(model: TranslationModel, text: String): String = safe { mutex.withLock {
        require(text.epubCodePoints() <= EpubTranslationPolicy.INPUT_POINTS)
        withContext(Dispatchers.IO) {
            val sdk = client(); val exact = descriptor(sdk, model)
            val installed = installedModels[model.pair] ?: sdk.models.findUsable(exact.pair)?.takeIf { it.descriptor == exact }?.also { installedModels[model.pair] = it } ?: error("Model not installed")
            if (active != model.pair) { unload(sdk); active = model.pair }
            // SDK drains a submitted native batch even when its coroutine is cancelled.
            val result = sdk.translator.translate(text, installed, TextFormat.Plain)
            currentCoroutineContext().ensureActive(); check(!closed)
            require(result.length <= EpubTranslationPolicy.OUTPUT_UNITS)
            result
        }
    }
    }
    private suspend fun <T> safe(action: suspend () -> T): T = try { action() }
    catch (error: CancellationException) { throw error }
    catch (error: ModelStorageException) { throw EpubTranslationException("Model storage is unavailable or full. Free space or delete an unused model, then retry.", error) }
    catch (error: NetworkException) { throw EpubTranslationException("Model download failed. Check your connection and retry. Translation needs no connection after installation.", error) }
    catch (error: ModelIntegrityException) { throw EpubTranslationException("Model verification failed. Delete and download the model again.", error) }
    catch (error: LinkageError) { throw EpubTranslationException("The local translation engine could not load on this device. Use Original.", error) }
    private fun checkDeleted(report: DeleteReport) {
        check(report.results.all { it.status == DeleteStatus.Deleted || it.status == DeleteStatus.AlreadyAbsent }) { "Model deletion could not complete" }
    }
    private suspend fun unload(sdk: Foxlet) {
        if (!sdk.translator.unloadModels().allReleased) {
            sdk.shutdown(); client = null; active = null; installedModels.clear()
            error("Native model release could not be confirmed")
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        closing = cleanup.launch { mutex.withLock { client?.shutdown(); client = null; active = null } }
    }
    override suspend fun awaitClosed() { if (closed) { closing?.join(); cleanup.cancel() } }
}
