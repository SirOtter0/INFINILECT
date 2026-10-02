// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.oapen

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareHead
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.infinilect.app.archive.EXPERIMENT_USER_AGENT

internal const val OAPEN_OAI_RECORD = "https://library.oapen.org/oai/request?verb=GetRecord&identifier=oai:library.oapen.org:20.500.12657/25287&metadataPrefix=xoai"
internal data class OapenBitstream(val url: String, val size: Long, val rights: String?, val licenseUrl: String?)
internal data class OapenAlternateEvidence(val metadataStatus: Int, val metadataBytes: Int, val pdf: OapenBitstream?, val headStatus: Int?, val redirect: String?)

/** One documented record, then HEAD only. No bulk harvesting, retries, or challenge bypass. */
internal class OapenAlternateProbe(private val engine: HttpClientEngine = Java.create()) : AutoCloseable {
    var attemptedRequests = 0
        private set
    var consumedMetadataBytes = 0
        private set
    var lastMetadataStatus: Int? = null
        private set
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 15_000 }
    }

    suspend fun inspect(): OapenAlternateEvidence {
        var status = 0
        attemptedRequests++
        val bytes = client.prepareGet(OAPEN_OAI_RECORD) {
            header(HttpHeaders.UserAgent, EXPERIMENT_USER_AGENT)
            header(HttpHeaders.Accept, "application/xml, text/xml")
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            status = response.status.value
            lastMetadataStatus = status
            if (status != 200) return@execute null
            check(response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.trim()?.lowercase() in setOf("text/xml", "application/xml"))
            check(response.headers[HttpHeaders.ContentEncoding]?.let { it.lowercase() != "identity" } != true)
            response.headers[HttpHeaders.ContentLength]?.let { check(it.toLongOrNull()?.let { size -> size in 0..OAPEN_PROBE_MAX_BYTES } == true) }
            val channel = response.bodyAsChannel()
            try {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.readAvailable(buffer, 0, minOf(buffer.size, OAPEN_PROBE_MAX_BYTES - output.size() + 1))
                    if (count == -1) break
                    consumedMetadataBytes += count
                    check(count <= OAPEN_PROBE_MAX_BYTES - output.size())
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally { channel.cancel(null) }
        } ?: return OapenAlternateEvidence(status, 0, null, null, null)
        val context = currentCoroutineContext()
        val pdf = parseOapenRecord(bytes) { context.ensureActive() }
        attemptedRequests++
        return client.prepareHead(pdf.url) {
            header(HttpHeaders.UserAgent, EXPERIMENT_USER_AGENT)
            header(HttpHeaders.Accept, "application/pdf")
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            response.bodyAsChannel().cancel(null)
            OapenAlternateEvidence(status, bytes.size, pdf, response.status.value, response.headers[HttpHeaders.Location])
        }
    }

    override fun close() { client.close(); engine.close() }
}

/** Extracts just the announced PDF of the documented example; XML remains untrusted. */
internal fun parseOapenRecord(bytes: ByteArray, checkCancellation: () -> Unit = {}): OapenBitstream {
    require(bytes.size <= OAPEN_PROBE_MAX_BYTES)
    val factory = XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty("javax.xml.stream.isSupportingExternalEntities", false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
        xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ -> error("External XML entities are forbidden.") }
    }
    val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
    val streams = mutableListOf<Map<String, String>>()
    var depth = 0
    var events = 0
    var bitstreamDepth = -1
    var fields = mutableMapOf<String, String>()
    var field: String? = null
    var fieldDepth = -1
    var text = StringBuilder()
    var recordIdentifier = false
    var headerIdentifier = false
    try {
        while (reader.hasNext()) {
            checkCancellation()
            check(++events <= 20_000)
            when (reader.next()) {
                DTD, ENTITY_REFERENCE -> error("DTD/entities are forbidden.")
                START_ELEMENT -> {
                    check(++depth <= 32 && reader.attributeCount <= 16 && reader.namespaceCount <= 16)
                    for (index in 0 until reader.attributeCount) check(reader.getAttributeValue(index).length <= 16_384)
                    for (index in 0 until reader.namespaceCount) check((reader.getNamespaceURI(index)?.length ?: 0) <= 16_384)
                    if (reader.localName == "identifier" && reader.namespaceURI == "http://www.openarchives.org/OAI/2.0/") {
                        headerIdentifier = true; text = StringBuilder()
                    }
                    if (reader.namespaceURI == "http://www.lyncode.com/xoai") {
                        if (reader.localName == "element" && reader.getAttributeValue(null, "name") == "bitstream") {
                            check(streams.size < 32 && bitstreamDepth == -1)
                            bitstreamDepth = depth; fields = mutableMapOf()
                        }
                        if (reader.localName == "field" && bitstreamDepth != -1) {
                            field = reader.getAttributeValue(null, "name"); fieldDepth = depth; text = StringBuilder()
                        }
                    }
                }
                CHARACTERS, CDATA -> {
                    if (field != null || headerIdentifier) { check(text.length + reader.textLength <= 16_384); text.append(reader.text) }
                }
                END_ELEMENT -> {
                    if (headerIdentifier && reader.localName == "identifier") {
                        recordIdentifier = text.toString() == "oai:library.oapen.org:20.500.12657/25287"; headerIdentifier = false
                    }
                    if (field != null && fieldDepth == depth) { check(fields.put(field, text.toString()) == null); field = null }
                    if (depth == bitstreamDepth) { streams += fields; bitstreamDepth = -1 }
                    depth--
                }
            }
        }
    } finally { reader.close() }
    check(recordIdentifier)
    val pdf = streams.singleOrNull { it["format"] == "application/pdf" } ?: error("No unambiguous PDF in example record.")
    val url = pdf["url"] ?: error("Missing PDF URL.")
    val uri = URI(url)
    check(uri.scheme == "https" && uri.host == "library.oapen.org" && uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.query == null)
    check(Regex("/bitstream/20\\.500\\.12657/25287/[0-9]+/[A-Za-z0-9_.-]+\\.pdf").matches(uri.rawPath) && ".." !in uri.rawPath)
    val size = pdf["size"]?.toLongOrNull() ?: error("Missing PDF size.")
    check(size in 1..64L * 1024 * 1024)
    return OapenBitstream(url, size, pdf["rights"], pdf["rightsuri"])
}
