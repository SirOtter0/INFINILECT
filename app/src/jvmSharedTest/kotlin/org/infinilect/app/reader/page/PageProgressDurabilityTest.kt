// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.reader.page

import java.nio.file.*
import java.nio.ByteBuffer
import java.io.*
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import org.infinilect.core.*
import org.infinilect.app.progress.*

@OptIn(ExperimentalCoroutinesApi::class)
class PageProgressDurabilityTest {
    private lateinit var root:Path
    private val directory get()=root.resolve(PROGRESS_DIRECTORY_NAME)
    private val doc get()=TestPageDocument()
    @BeforeTest fun setup(){root=Files.createTempDirectory("page-progress")}
    @AfterTest fun cleanup(){root.toFile().deleteRecursively()}
    private fun records()=Files.list(directory).use { stream->stream.filter{it.fileName.toString().endsWith(".progress")}.toList() }
    @Test fun pageRecordUsesVersionThreeAndNewStoreRestoresExactly()=runBlocking {
        val value=ReadingProgress(doc.progressId,ReadingLocator.Page("page-45",45,.6),.456,1)
        assertTrue(FileReadingProgressStore(directory).save(value))
        assertEquals(3,ByteBuffer.wrap(Files.readAllBytes(records().single())).getInt(8))
        assertEquals(value,FileReadingProgressStore(directory).get(value.id))
    }
    @Test fun closeDiscardAllRamDeleteCacheAndNewReaderOwnerRestoresPageAndMode()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val first=ProgressPersistence(FileReadingProgressStore(directory,dispatcher=dispatcher),dispatcher){1}
        val settings=PageSettingsPersistence(FilePageReaderSettingsStore(root.resolve(PAGE_SETTINGS_DIRECTORY_NAME),dispatcher),dispatcher){1}
        val document=doc;val reader=PageReaderController(document,this,first,settings,TestRasterDecoder(),dispatcher)
        reader.initialize(null);reader.mode(PageReadingMode.WEBTOON);reader.report(reader.state.value.ticket,63,.7)
        runCurrent();reader.presented(reader.state.value.ticket,63,assertIs<PageFrame.Ready>(reader.state.value.frames[63]).stamp)
        reader.close();first.close();settings.close();first.awaitClosed();settings.awaitClosed()
        assertFalse(first.saveFailed.value);assertTrue(Files.isRegularFile(records().single()))
        Files.createDirectories(root.resolve("cache")).resolve("bytes").toFile().writeText("disposable")
        root.resolve("cache").toFile().deleteRecursively()
        // Completely new persistence, store, document, settings and reader: recent RAM cannot satisfy this.
        val second=ProgressPersistence(FileReadingProgressStore(directory,dispatcher=dispatcher),dispatcher){2}
        val preferences=PageSettingsPersistence(FilePageReaderSettingsStore(root.resolve(PAGE_SETTINGS_DIRECTORY_NAME),dispatcher),dispatcher){2}
        val fresh=doc;val reopened=PageReaderController(fresh,this,second,preferences,TestRasterDecoder(),dispatcher)
        try {reopened.initialize(second.get(fresh.progressId));assertEquals(PagePosition(63,.7),reopened.state.value.position)
            assertEquals(PageReadingMode.WEBTOON,reopened.state.value.settings.mode)}
        finally{reopened.close();second.close();preferences.close();second.awaitClosed();preferences.awaitClosed()}
    }
    @Test fun failedTargetNeverBecomesDurableAcrossCompletelyNewOwners()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler)
        val first=ProgressPersistence(FileReadingProgressStore(directory,dispatcher=dispatcher),dispatcher){1}
        val document=doc;val decoder=TestRasterDecoder()
        val reader=PageReaderController(document,this,first,decoder=decoder,decodeDispatcher=dispatcher)
        reader.initialize(null);reader.navigate(2);runCurrent()
        reader.presented(reader.state.value.ticket,2,assertIs<PageFrame.Ready>(reader.state.value.frames[2]).stamp)
        decoder.action={error("Unavailable page")};reader.navigate(50);runCurrent()
        assertIs<PageFrame.Unavailable>(reader.state.value.frames[50])
        reader.close();first.close();first.awaitClosed()
        val second=ProgressPersistence(FileReadingProgressStore(directory,dispatcher=dispatcher),dispatcher){2}
        val fresh=doc;val reopened=PageReaderController(fresh,this,second,decoder=TestRasterDecoder(),decodeDispatcher=dispatcher)
        try {reopened.initialize(second.get(fresh.progressId));assertEquals(2,reopened.state.value.position.index)}
        finally{reopened.close();second.close();second.awaitClosed()}
    }
    /** Independent historical serializer: verifies TEXT/EPUB bytes are unchanged, not merely
     * current-write/current-read agreement. The PR7/15 schemas retain their original tags. */
    private fun historical(value:ReadingProgress,version:Int):ByteArray {
        val body=ByteArrayOutputStream().also { b->DataOutputStream(b).use{out->
            fun field(text:String){val bytes=text.encodeToByteArray();out.writeInt(bytes.size);out.write(bytes)}
            with(value.id){listOf(publicationId.sourceId.value,publicationId.localId,resourceKey,format.name).forEach(::field)}
            out.writeInt(version)
            when(val locator=value.locator){
                is ReadingLocator.Text->{out.writeLong(locator.codePointOffset);out.writeLong(locator.documentCodePoints)}
                is ReadingLocator.Epub->{field(locator.spinePath.value);out.writeInt(locator.elementPath.size);locator.elementPath.forEach(out::writeInt);out.writeLong(locator.codePointOffset);out.writeDouble(locator.chapterProgression)}
                else->error("Historical schemas only")
            }
            out.writeDouble(value.progression);out.writeLong(value.updatedAtEpochMillis)
        }}.toByteArray()
        return ByteArrayOutputStream().also { b->DataOutputStream(b).use{out->out.writeLong(0x494e4650524f4752L);out.writeInt(version);out.writeInt(body.size);out.write(body);out.write(MessageDigest.getInstance("SHA-256").digest(body))}}.toByteArray()
    }
    @Test fun historicalTextAndEpubEncodingsRemainByteCompatibleAndReadable()=runBlocking {
        val id=doc.progressId
        val values=listOf(
            ReadingProgress(id.copy(resourceKey="text",format=PublicationFormat.TEXT),ReadingLocator.Text(4,10),.4,1),
            ReadingProgress(id.copy(resourceKey="epub",format=PublicationFormat.EPUB),ReadingLocator.Epub(EpubEntryPath("OPS/c.xhtml"),listOf(0,2),4,.4),.4,1))
        for((index,value) in values.withIndex()) {
            assertTrue(FileReadingProgressStore(directory).save(value))
            val matching=records().single { path->Files.readAllBytes(path).contentEquals(historical(value,index+1)) }
            Files.write(matching,historical(value,index+1))
            assertEquals(value,FileReadingProgressStore(directory).get(value.id))
        }
    }
    @Test fun corruptTruncatedAndFuturePageRecordFailsSafely()=runBlocking {
        val value=ReadingProgress(doc.progressId,ReadingLocator.Page("p",5,.5),.5,1)
        FileReadingProgressStore(directory).save(value);val record=records().single();val valid=Files.readAllBytes(record)
        for(bytes in listOf(valid.copyOf(20),valid.copyOf().also{it[it.lastIndex]=0},valid.copyOf().also{ByteBuffer.wrap(it).putInt(8,99)})) {
            Files.write(record,bytes);assertNull(FileReadingProgressStore(directory).get(value.id))
        }
    }
    @Test fun checksumValidMalformedPageLocatorIsRejected()=runBlocking {
        val value=ReadingProgress(doc.progressId,ReadingLocator.Page("p",5,.5),.5,1)
        FileReadingProgressStore(directory).save(value);val record=records().single();val bytes=Files.readAllBytes(record)
        val bodyEnd=bytes.size-32
        ByteBuffer.wrap(bytes).putDouble(bodyEnd-24,Double.NaN) // page fraction precedes overall fraction and time
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(16,bodyEnd)).copyInto(bytes,bodyEnd)
        Files.write(record,bytes);assertNull(FileReadingProgressStore(directory).get(value.id))
    }
}
