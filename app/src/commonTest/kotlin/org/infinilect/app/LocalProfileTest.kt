// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import kotlin.test.*
import org.infinilect.app.ui.*
import org.infinilect.core.*

class LocalProfileTest {
    @Test fun optionalNameHasGenericGreetingAndNoFabricatedResidence() {
        val profile=LocalProfile()
        assertEquals("Hello!",welcomeGreeting(profile));assertFalse(profile.hasDeclaredResidence)
        assertFalse(profile.setupHandled);assertNull(profile.interfaceLanguage)
        assertEquals("Hello, Ana!",welcomeGreeting(profile.copy(displayName="Ana")))
        assertFalse(profile.copy(setupHandled=true).hasDeclaredResidence)
    }
    @Test fun residenceRequiresKnownUppercaseIsoCodeRatherThanFreeText() {
        val countries=residenceCountries()
        assertTrue(countries.size>200);assertEquals(countries.size,countries.map{it.code}.distinct().size)
        for(code in listOf("ES","US","BR","JP")) assertTrue(LocalProfile(countryCode=code).hasDeclaredResidence)
        for(code in listOf("es","Spain","ZZ","","../ES","ESP")) assertFailsWith<IllegalArgumentException>{LocalProfile(countryCode=code)}
    }
    @Test fun namesAndLanguageRemainBoundedAndReflectImplementedInterface() {
        assertEquals("en",LocalProfile(interfaceLanguage="en").interfaceLanguage)
        for(name in listOf(""," "," Ana ","x".repeat(81),"a\nb","a\u2028b")) assertFailsWith<IllegalArgumentException>{LocalProfile(displayName=name)}
        assertFailsWith<IllegalArgumentException>{LocalProfile(interfaceLanguage="es")}
        assertEquals(80,LocalProfile(displayName="a".repeat(80)).displayName!!.length)
    }
    private fun publication(i:Int,source:String="local")=PublicationSnapshot(PublicationId(SourceId(source),i.toString()),"Original $i",PublicationType.BOOK)
    @Test fun recentlyAddedUsesActualAddedTimeWithIdentityDeduplicationAndEightItemBound() {
        val entries=(0..19).map {LibraryEntry(publication(it),it.toLong(),lastOpenedAtEpochMillis=(100-it).toLong())}
        assertEquals((19 downTo 12).map{publication(it)},homeRecentLibrary(entries+entries.take(2)))
        assertTrue(homeRecentLibrary(emptyList()).isEmpty())
    }
    @Test fun continueUsesRealSavedLocatorsAndDoesNotAliasSourcesOrInventPages() {
        val a=publication(1);val b=publication(1,"other");val c=publication(2)
        val history=listOf(HistoryEntry(a,2),HistoryEntry(b,5),HistoryEntry(c,3),HistoryEntry(a,1))
        val progress=ReadingProgress(ReadingProgressId(a.id,"text",PublicationFormat.TEXT),ReadingLocator.Text(5,10),.5,10)
        assertEquals(listOf(a),homeContinue(history,listOf(progress)))
        assertTrue(homeContinue(history,emptyList()).isEmpty())
        assertEquals(progress.locator,ReadingLocator.Text(5,10))
    }
}
