// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.epub

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun epubTranslationLanguage(value: String?): String? = when (value?.substringBefore('-')?.lowercase()) {
    "en", "eng" -> "en"
    "es", "spa" -> "es"
    else -> null
}
internal data class TranslationPair(val source: String, val target: String) {
    init { require(source in setOf("en", "es") && target in setOf("en", "es") && source != target) }
}
internal class EpubTranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)
internal data class TranslationModel(val pair: TranslationPair, val revision: String, val bytes: Long)
internal interface EpubTranslationEngine {
    /** Stable provider/configuration version; never a publication title or user secret. */
    val identity: String
    val unavailable: String?
    val models: List<TranslationModel>
    suspend fun installed(model: TranslationModel): Boolean
    suspend fun install(model: TranslationModel, progress: (Long, Long) -> Unit)
    suspend fun remove(model: TranslationModel)
    /** Plain text only. Cancellation must drain native work before another request owns it. */
    suspend fun translate(model: TranslationModel, text: String): String
    fun close()
    suspend fun awaitClosed() {}
}
internal interface EpubTranslationCache {
    suspend fun get(key: List<String>): String?
    suspend fun put(key: List<String>, value: String)
    suspend fun clear()
}
internal object UnavailableTranslation : EpubTranslationEngine {
    override val identity = "unavailable-v1"
    override val unavailable = "Local translation is not available on this platform. Original reading remains available."
    override val models = emptyList<TranslationModel>()
    override suspend fun installed(model: TranslationModel) = false
    override suspend fun install(model: TranslationModel, progress: (Long, Long) -> Unit): Unit = error(unavailable)
    override suspend fun remove(model: TranslationModel) = Unit
    override suspend fun translate(model: TranslationModel, text: String): String = error(unavailable)
    override fun close() = Unit
}

internal object EpubTranslationPolicy {
    const val INPUT_POINTS = 2048
    const val OUTPUT_UNITS = 8192
    const val PRESENTATION_UNITS = 262_144
    const val CACHE_BYTES = 4L * 1024 * 1024
    const val CACHE_RECORDS = 256
}

/** The source chapter is never rewritten. Offsets remain original Unicode positions. */
internal data class EpubTranslatedBlock(val source: EpubBlock, val runs: List<EpubRun>) {
    val text = runs.joinToString("") { it.text }
    fun sourceToDisplay(points: Int) = mapOffset(source.runs, runs, points)
    fun displayToSource(points: Int) = mapOffset(runs, source.runs, points)
    private fun mapOffset(from: List<EpubRun>, to: List<EpubRun>, points: Int): Int {
        var left = points.coerceAtLeast(0); var result = 0
        for (i in from.indices) {
            val count = from[i].text.epubCodePoints(); val destination = to[i].text.epubCodePoints()
            if (left <= count) return result + if (count == 0) 0 else (left.toLong() * destination / count).toInt()
            left -= count; result += destination
        }
        return result
    }
}

/** One native permit, shared by readers and selection requests. No background book queue. */
internal class EpubTranslationService(val engine: EpubTranslationEngine, private val cache: EpubTranslationCache? = null) {
    init { require(engine.identity.length in 1..128) }
    private val permit = Mutex()
    private val literalUrl = Regex("""(?:https?://|www\.)\S+""")
    private val closedState = MutableStateFlow(false)
    private val closed get() = closedState.value
    private var cacheEpoch = 0L
    suspend fun text(model: TranslationModel, identity: List<String>?, source: String): String = permit.withLock {
        check(!closed); require(source.epubCodePoints() <= 8192); currentCoroutineContext().ensureActive()
        val epoch = cacheEpoch
        val result = StringBuilder()
        var start = 0
        while (start < source.length) {
            currentCoroutineContext().ensureActive()
            val limit = start + source.substring(start).epubUtf16(EpubTranslationPolicy.INPUT_POINTS)
            // Prefer a real word boundary; never omit the separating whitespace.
            var end = limit
            if (limit < source.length) {
                val boundary = source.lastIndexOf(' ', limit - 1)
                if (boundary >= start + (limit - start) / 2) end = boundary + 1
            }
            val segment = source.substring(start, end)
            val key = identity?.plus(listOf("epub-runs-word-boundaries-v1", engine.identity, model.revision, model.pair.source, model.pair.target, segment))
            val leading = segment.takeWhile(Char::isWhitespace)
            val trailing = segment.drop(leading.length).takeLastWhile(Char::isWhitespace)
            val body = segment.substring(leading.length, segment.length - trailing.length)
            val translated = if (body.isEmpty()) segment else cache?.let { if (key != null) it.get(key) else null }
                ?: (leading + engine.translate(model, body).also { require(it.isNotBlank()) { "Translation produced no readable text." } } + trailing).also {
                    require(it.length <= EpubTranslationPolicy.OUTPUT_UNITS) { "Translation exceeds the supported segment size." }
                    currentCoroutineContext().ensureActive()
                    if (!closed && epoch == cacheEpoch && key != null) cache?.put(key, it)
                }
            currentCoroutineContext().ensureActive(); check(!closed)
            require(result.length + translated.length <= EpubTranslationPolicy.PRESENTATION_UNITS) { "Translation exceeds the supported passage size." }
            result.append(translated); start = end
        }
        result.toString()
    }
    suspend fun passage(model: TranslationModel, identity: List<String>?, chapter: EpubChapter): List<EpubTranslatedBlock> {
        require(chapter.blocks.size <= EpubWindowPolicy.BLOCKS * EpubWindowPolicy.RETAINED &&
            chapter.blocks.sumOf { it.text.length } <= EpubWindowPolicy.TEXT_UNITS * EpubWindowPolicy.RETAINED &&
            chapter.blocks.sumOf { it.runs.size } <= EpubWindowPolicy.APPEND_EVENTS * EpubWindowPolicy.RETAINED)
        var units = 0
        return chapter.blocks.map { block ->
            currentCoroutineContext().ensureActive()
            val runs = block.runs.map { run ->
                if (!run.translatable || block.kind == EpubBlockKind.PREFORMATTED || block.image != null || block.kind == EpubBlockKind.IMAGE ||
                    run.text.isBlank() || run.text.contains(literalUrl)) run
                else run.copy(text = text(model, identity, run.text))
            }
            units += runs.sumOf { it.text.length }
            require(units <= EpubTranslationPolicy.PRESENTATION_UNITS) { "Translation exceeds the supported passage size." }
            EpubTranslatedBlock(block, runs)
        }
    }
    suspend fun clear() = permit.withLock { cacheEpoch++; cache?.clear() }
    fun close() { if (closedState.compareAndSet(false, true)) { cacheEpoch++; engine.close() } }
    suspend fun awaitClosed() = engine.awaitClosed()
}

internal data class EpubTranslationState(
    val model: TranslationModel? = null, val installed: Boolean = false, val busy: Boolean = false,
    val downloaded: Long = 0, val total: Long = 0, val message: String? = null,
    val translated: Boolean = false, val blocks: List<EpubTranslatedBlock>? = null,
    val selection: String? = null, val selectionResult: String? = null,
)

/** Session-owned projection; one replaceable Kotlin job, stale generations cannot publish. */
internal class EpubTranslationController(
    val service: EpubTranslationService?, private val scope: CoroutineScope, private val identity: List<String>?,
    language: String?, private val changed: () -> Unit,
) {
    private val mutable = MutableStateFlow(EpubTranslationState(model = service?.engine?.models?.firstOrNull {
        it.pair.source == epubTranslationLanguage(language) && it.pair.target == "es"
    }))
    val state = mutable.asStateFlow()
    val unavailable: String? get() = if (service == null) UnavailableTranslation.unavailable else service.engine.unavailable
    private var chapter: EpubChapter? = null
    private var job: Job? = null
    private var modelWork = false
    private var retryRequest: (() -> Unit)? = null
    private val mutableEpoch = MutableStateFlow(0L)
    private var epoch: Long
        get() = mutableEpoch.value
        set(value) { mutableEpoch.value = value }
    private val mutableClosed = MutableStateFlow(false)
    private var closed: Boolean
        get() = mutableClosed.value
        set(value) { mutableClosed.value = value }
    fun chapter(value: EpubChapter) {
        if (chapter === value) return
        val reflowing = state.value.blocks != null
        chapter = value
        if (!modelWork) { epoch++; job?.cancel(); retryRequest = null }
        mutable.value = mutable.value.copy(blocks = null, selection = null, selectionResult = null, busy = if (modelWork) state.value.busy else false)
        if (reflowing) changed()
        if (state.value.translated && !modelWork) reading(true)
    }
    fun model(value: TranslationModel?, checkInstalled: Boolean = true) {
        original(); mutable.value = mutable.value.copy(model = value, installed = false, selection = null, selectionResult = null)
        if (checkInstalled && value != null) refresh()
    }
    fun refresh() {
        if (job?.isActive == true || state.value.installed) return
        action(true, retry = ::refresh) { model -> val installed = service!!.engine.installed(model); update { it.copy(installed = installed) } }
    }
    fun install() {
        val ticket = epoch + 1
        action(true, retry = ::install) { model ->
        service!!.engine.install(model) { done, total -> mutable.update { value -> if (!closed && epoch == ticket) value.copy(downloaded = done, total = total) else value } }
        update { it.copy(installed = true) }
        }
    }
    fun remove() { original(); action(retry = ::remove) { model -> service!!.engine.remove(model); update { it.copy(installed = false) } } }
    fun clear() {
        if (service == null) return
        operation(isModelWork = true, needsEngine = false, retry = ::clear) {
            service.clear(); update { it.copy(message = "Saved translations cleared. The current passage and reading position are unchanged.") }
        }
    }
    fun original() {
        epoch++; job?.cancel(); modelWork = false; modelWork = false; mutable.value = mutable.value.copy(translated = false, blocks = null, busy = false, message = null)
        changed()
    }
    fun reading(enabled: Boolean) {
        if (!enabled) { original(); return }
        val value = chapter ?: return
        mutable.value = mutable.value.copy(translated = true)
        action(retry = { reading(true) }) { model ->
            if (!service!!.engine.installed(model)) throw EpubTranslationException("Download the selected model before translating. Original reading remains available.")
            val blocks = service.passage(model, identity, value)
            update { it.copy(blocks = blocks, installed = true) }
            changed()
        }
    }
    fun selection(text: String, source: EpubChapter? = null) {
        if (closed || source != null && source !== chapter) return
        if (text.isBlank() || text.epubCodePoints() > EpubTranslationPolicy.INPUT_POINTS) {
            mutable.value = mutable.value.copy(selection = "", selectionResult = null, message = "Select up to 2,048 characters from this passage."); return
        }
        mutable.value = mutable.value.copy(selection = text, selectionResult = null)
        if (modelWork && job?.isActive == true) return
        action(retry = ::retrySelection) { model ->
            check(!state.value.translated) { "Return to Original to translate a selection from the book's source language." }
            if (!service!!.engine.installed(model)) throw EpubTranslationException("Download the selected model before translating. Original reading remains available.")
            val result = service.text(model, identity, text)
            update { it.copy(selectionResult = result, installed = true) }
        }
    }
    fun retry() { if (!closed) retryRequest?.invoke() }
    fun retrySelection() { state.value.selection?.let { selection(it, chapter) } }
    fun dismissSelection() {
        if (modelWork) { mutable.value = mutable.value.copy(selection = null, selectionResult = null); return }
        epoch++; job?.cancel(); modelWork = false; mutable.value = mutable.value.copy(selection = null, selectionResult = null, busy = false) }
    fun cancel() { epoch++; job?.cancel(); modelWork = false; mutable.value = mutable.value.copy(busy = false, message = "Translation cancelled. Original text remains available.") }
    private suspend fun update(transform: (EpubTranslationState) -> EpubTranslationState) {
        currentCoroutineContext().ensureActive(); check(!closed)
        mutable.value = transform(mutable.value)
    }
    private fun action(isModelWork: Boolean = false, retry: () -> Unit = ::refresh, block: suspend (TranslationModel) -> Unit) {
        val model = state.value.model ?: return
        operation(isModelWork, retry = retry) { block(model) }
    }
    private fun operation(isModelWork: Boolean, needsEngine: Boolean = true, retry: () -> Unit = ::refresh, block: suspend () -> Unit) {
        if (closed) return
        if (needsEngine && unavailable != null) { mutable.value = mutable.value.copy(message = unavailable); return }
        val ticket = ++epoch; job?.cancel(); modelWork = isModelWork; retryRequest = retry; mutable.value = mutable.value.copy(busy = true, message = null, downloaded = 0, total = 0)
        job = scope.launch {
            try {
                block(); currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) { if (!closed && epoch == ticket) mutable.value = mutable.value.copy(message = when(error) {
                is EpubTranslationException -> error.message
                is IllegalArgumentException -> "The translated passage exceeds supported text limits. Use Original or a shorter selection."
                else -> "Local translation could not complete. Check model storage or retry; Original is always available."
            }) }
            finally { if (!closed && epoch == ticket) {
                modelWork = false; mutable.value = mutable.value.copy(busy = false)
                if (isModelWork && state.value.installed) {
                    if (state.value.selection?.isNotBlank() == true && state.value.selectionResult == null) retrySelection()
                    else if (state.value.translated && state.value.blocks == null) reading(true)
                }
            } }
        }
    }
    fun close() { closed = true; epoch++; job?.cancel(); chapter = null; retryRequest = null; mutable.value = EpubTranslationState() }
}
