// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.covers

import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.util.zip.*
import kotlinx.coroutines.*
import org.infinilect.app.epub.*
import org.infinilect.core.*
import kotlin.test.*

class LocalArchiveCoversTest {
    private fun fixture(epub2:Boolean=false, href:String="Images/cover%20é.png") = (if(epub2) epub2Fixture() else EpubFixture()).apply {
        entries["OPS/Images/cover é.png"]=developmentPng()
        opf { it.replace("</manifest>", "<item id=\"cover-art\" href=\"$href\" media-type=\"image/png\" ${if(epub2) "" else "properties=\"cover-image\""}/></manifest>")
            .replace("</metadata>", if(epub2) "<meta name=\"cover\" content=\"cover-art\"/></metadata>" else "</metadata>") }
    }
    private suspend fun extract(bytes:ByteArray, format:PublicationFormat=PublicationFormat.EPUB):EncodedCover? {
        val path=Files.createTempFile("original-cover-fixture", ".zip")
        try {Files.write(path,bytes);return localArchiveCover(path,format)} finally {Files.delete(path)}
    }
    @Test fun epub3CoverImageDeclarationUsesOwnedEncodedUnicodePath()=runBlocking<Unit> {
        val image=assertNotNull(extract(fixture().zip()));assertEquals("image/png",image.mediaType);assertContentEquals(developmentPng(),image.bytes)
    }
    @Test fun epub2LegacyCoverMetadataResolvesManifestIdentity()=runBlocking<Unit> {
        assertContentEquals(developmentPng(),assertNotNull(extract(fixture(true).zip())).bytes)
    }
    @Test fun epubCoverDoesNotParseOrMaterializeLongChapters()=runBlocking<Unit> {
        val f=fixture();f.entries["OPS/chapter.xhtml"]="not a chapter parser input".repeat(5000).encodeToByteArray()
        // Import validation is the caller's prerequisite; thumbnail extraction touches metadata/image only.
        assertNotNull(extract(f.zip()))
    }
    @Test fun noDeclaredCoverAndUnsupportedSvgUseFallback()=runBlocking<Unit> {
        assertNull(extract(EpubFixture().zip()))
        val f=fixture();f.opf { it.replace("media-type=\"image/png\"", "media-type=\"image/svg+xml\"") };assertNull(extract(f.zip()))
    }
    @Test fun externalTraversalAndDoubleEncodedCoverPathsFailClosed()=runBlocking<Unit> {
        for(href in listOf("https://example.test/art.png","/Images/art.png","../outside.png","Images/%2e%2e/%2e%2e/art.png","Images/%252e%252e/art.png"))
            assertFails {extract(fixture(href=href).zip())}
    }
    @Test fun duplicateAmbiguousCoverDeclarationsAreRejected()=runBlocking<Unit> {
        val f=fixture();f.opf { it.replace("</manifest>","<item id=\"other\" href=\"Images/cover%20é.png\" media-type=\"image/png\" properties=\"cover-image\"/></manifest>") }
        assertFails {extract(f.zip())}
    }
    @Test fun missingImageAndDuplicateManifestIdentityAreRejected()=runBlocking<Unit> {
        val f=fixture();f.entries.remove("OPS/Images/cover é.png");assertFails {extract(f.zip())}
        val duplicate=fixture();duplicate.opf { it.replace("id=\"chapter\"","id=\"cover-art\"") };assertFails {extract(duplicate.zip())}
    }
    @Test fun arbitraryZipOrInvalidMimetypeIsNeverAnEpubCover()=runBlocking<Unit> {
        val f=fixture();f.entries["mimetype"]="application/not-epub!".encodeToByteArray();assertFails {extract(f.zip())}
        assertFails {extract(zip(linkedMapOf("001.png" to developmentPng())))}
    }
    @Test fun encryptionAndExternalXmlEntitiesAreRejected()=runBlocking<Unit> {
        val f=fixture();f.entries["META-INF/encryption.xml"]="<encryption/>".encodeToByteArray();assertFails {extract(f.zip())}
        val dtd=fixture();dtd.opf {"<!DOCTYPE package [<!ENTITY x SYSTEM 'https://example.test/entity'>]>$it"};assertFails {extract(dtd.zip())}
    }
    @Test fun oversizedSelectedResourceIsRejectedBeforeDecode()=runBlocking<Unit> {
        val f=fixture();f.entries["OPS/Images/cover é.png"]=ByteArray(2*1024*1024+1);assertFails {extract(f.zip())}
    }
    @Test fun archiveTraversalAndNormalizedCollisionsAreRejected()=runBlocking<Unit> {
        for(name in listOf("../bad.png", "OPS/Images/%2e%2e/bad.png")) {
            val f=fixture();f.entries[name]=developmentPng();assertFails {extract(f.zip())}
        }
        val f=fixture();f.entries["OPS/Images/cover e\u0301.png"]=developmentPng();assertFails {extract(f.zip())}
    }
    @Test fun cbzUsesReaderNaturalOrderRatherThanInsertionOrLexicalOrder()=runBlocking<Unit> {
        val chosen=developmentPng();val bytes=zip(linkedMapOf("10.png" to byteArrayOf(10),"2.png" to chosen))
        assertContentEquals(chosen,assertNotNull(extract(bytes,PublicationFormat.CBZ)).bytes)
    }
    @Test fun cbzDirectoriesNonImagesAndTraversalRemainRejected()=runBlocking<Unit> {
        for(name in listOf("folder/","readme.txt","../1.png")) assertFails {extract(zip(linkedMapOf("1.png" to developmentPng(),name to byteArrayOf(1))),PublicationFormat.CBZ)}
    }
    @Test fun corruptSelectedCrcIsRejected()=runBlocking<Unit> {
        val bytes=zip(linkedMapOf("1.png" to developmentPng()));val needle=developmentPng()
        val at=(0..bytes.size-needle.size).first {bytes.copyOfRange(it,it+needle.size).contentEquals(needle)}
        bytes[at+needle.size/2]=(bytes[at+needle.size/2].toInt() xor 1).toByte()
        assertFails {extract(bytes,PublicationFormat.CBZ)}
    }
    private fun zip(entries:LinkedHashMap<String,ByteArray>)=ByteArrayOutputStream().use {out->
        ZipOutputStream(out).use {z->entries.forEach {(name,bytes)-> val e=ZipEntry(name).apply {method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply{update(bytes)}.value};z.putNextEntry(e);z.write(bytes);z.closeEntry()}};out.toByteArray()
    }
}
