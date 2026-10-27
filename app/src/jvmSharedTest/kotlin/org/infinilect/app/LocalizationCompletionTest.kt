// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.infinilect.app.ui.*
import org.infinilect.app.imports.ImportFailure
import org.infinilect.app.reader.*
import org.infinilect.app.reader.epub.*
import org.infinilect.app.reader.page.*
import org.infinilect.app.discovery.*
import org.infinilect.app.collections.LibraryActionState
import org.infinilect.core.PdfFailure
import kotlin.test.*

class LocalizationCompletionTest {
    private fun xml(language: String, file: String) = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(Path.of("src/commonMain/composeResources", if(language=="en") "values" else "values-$language", file).toFile())
    private fun values(language: String): Map<String,String> {
        val nodes = xml(language,"strings.xml").getElementsByTagName("string")
        val result = mutableMapOf<String,String>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            assertNull(result.put(e.getAttribute("name"),e.textContent)); assertTrue(e.textContent.isNotBlank())
        }
        return result
    }
    private val tables by lazy { interfaceLanguages.associateWith(::readTable) }
    private fun table(language: String) = tables.getValue(language)
    private fun readTable(language: String): LocalizedStrings {
        val values=values(language)
        val doc=xml(language,"plurals.xml"); val forms=mutableMapOf<UiPlural,List<String>>()
        val plurals=doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val e=plurals.item(i) as Element; val items=e.getElementsByTagName("item")
            val entries=(0 until items.length).associate { j -> val item=items.item(j) as Element; item.getAttribute("quantity") to item.textContent }
            assertEquals(setOf("one","other"),entries.keys)
            val key=when(e.getAttribute("name")) { "selected_count" -> UiPlural.SELECTED; "source_count" -> UiPlural.SOURCES; "publication_count" -> UiPlural.PUBLICATIONS; else -> error("Unregistered plural") }
            forms[key]=listOf(entries.getValue("one"),entries.getValue("other"))
        }
        assertEquals(UiPlural.entries.toSet(),forms.keys)
        return LocalizedStrings(UiText.entries.associateWith { values.getValue(it.name.lowercase()) },localeTag=language,plurals=forms)
    }
    @Test fun everyShippedKeyHasMatchingArgumentsAndNoImplicitEnglishEntries() {
        val english=values("en"); val parameter=Regex("%([1-9][0-9]*)\\\$s")
        assertEquals(UiText.entries.map { it.name.lowercase() }.toSet(),english.keys)
        for (key in UiText.entries) assertEquals(key.fallback,english.getValue(key.name.lowercase()),key.name)
        for (language in interfaceLanguages) {
            val translated=values(language); assertEquals(english.keys,translated.keys)
            for ((key,value) in english) {
                assertEquals(parameter.findAll(value).map { it.value }.sorted().toList(),parameter.findAll(translated.getValue(key)).map { it.value }.sorted().toList(),"$language:$key")
                if(language!="en" && value==translated[key]) assertTrue(key in setOf("original","romance","ui_categories","ui_format","ui_vertical","ui_webtoon","ui_project_gutenberg_experimental"),"Unreviewed English: $language/$key")
            }
            assertEquals(language,translated.getValue("interface_locale_tag"))
        }
    }
    @Test fun parametersAreSinglePassAndNeverInterpretNamesAsNewPlaceholders() {
        val name="Nom %2\$s — 李 📚"
        for (language in interfaceLanguages) {
            val strings=table(language)
            assertTrue(strings.format(UiText.HELLO_NAME,name).contains(name))
            assertEquals("second / first / second",substituteArguments("%2\$s / %1\$s / %2\$s",listOf("first","second")))
            assertFailsWith<IllegalStateException> { substituteArguments("%2\$s",listOf("one")) }
        }
    }
    @Test fun realPluralFormsCoverZeroOneAndManyWithoutChangingTheCount() {
        for (language in interfaceLanguages) {
            val strings=table(language)
            for (quantity in listOf(0,1,2,5,1000)) {
                assertTrue(strings.selectedCount(quantity).startsWith(quantity.toString()))
                assertTrue(strings.sourceCount(quantity).startsWith(quantity.toString()))
                assertTrue(strings.quantity(UiPlural.PUBLICATIONS,quantity).startsWith(quantity.toString()))
            }
            assertFailsWith<IllegalArgumentException> { strings.sourceCount(-1) }
        }
        assertEquals("1 publicación",table("es").quantity(UiPlural.PUBLICATIONS,1));assertEquals("2 publicaciones",table("es").quantity(UiPlural.PUBLICATIONS,2))
        assertEquals("1 publicació",table("ca").quantity(UiPlural.PUBLICATIONS,1));assertEquals("0 publicacions",table("ca").quantity(UiPlural.PUBLICATIONS,0))
        assertEquals("1 publication",table("en").quantity(UiPlural.PUBLICATIONS,1));assertEquals("5 publications",table("en").quantity(UiPlural.PUBLICATIONS,5))
        assertEquals("1 fuente seleccionada",table("es").sourceCount(1));assertEquals("2 fuentes seleccionadas",table("es").sourceCount(2))
        assertEquals("1 seleccionat",table("ca").selectedCount(1));assertEquals("2 seleccionats",table("ca").selectedCount(2))
        assertEquals("1,5",table("es").number(1.5,1));assertEquals("1,5",table("ca").number(1.5,1));assertEquals("1.5",table("en").number(1.5,1))
    }
    @Test fun appOwnedErrorAndReaderLabelContractsHaveExplicitMappings() {
        val messages=ImportFailure.entries.map { it.userMessage } + EpubFailure.entries.map { it.userMessage } +
            TextFailure.entries.map { it.userMessage } + PdfFailure.entries.map { it.userMessage } +
            CatalogErrorKind.entries.map { catalogFailureMessage("Original source",it) } + catalogFailureMessage("Original source",null) +
            Genre.entries.map { it.label } + DISCOVERY_LANGUAGES.values + PageFit.entries.map(::fitLabel) + PageReadingMode.entries.map(::modeLabel) +
            listOf(LibraryActionState(true,false,false),LibraryActionState(false,false,false),LibraryActionState(null,true,false),LibraryActionState(null,false,true),LibraryActionState(null,false,false)).map { it.label }
        for (value in messages) {
            assertNotNull(statusKey(value),"Unmapped contract: $value")
            for(language in interfaceLanguages) assertTrue(table(language).status(value).isNotBlank())
        }
        assertEquals("Original source ha devuelto JSON no válido.",table("es").status(catalogFailureMessage("Original source",CatalogErrorKind.INVALID_JSON)))
        assertNull(statusKey("Unexpected private exception /do/not/display"))
        assertEquals(table("ca")[UiText.UI_THE_ACTION_COULD_NOT_COMPLETE_PLEASE_TRY_AGAIN],table("ca").status("Unexpected private exception /do/not/display"))
    }
    @Test fun languageResolutionDoesNotChangeReadingLanguageOrPublicationMetadata() {
        for (language in interfaceLanguages) {
            val profile=LocalProfile("Nom original", "ES",language,true)
            val preferences=ApplicationPreferences(profile=profile,readingLanguage="en")
            assertEquals(language,resolveInterfaceLanguage(profile.interfaceLanguage,"fr"))
            assertEquals("en",preferences.readingLanguage);assertEquals("Nom original",preferences.profile.displayName)
        }
        assertEquals("ca",resolveInterfaceLanguage(null,"ca-ES"));assertEquals("en",resolveInterfaceLanguage(null,"fr-FR"))
    }
    @Test fun generatedImageLabelsUseOriginalOffsetMappingWithoutChangingBookRuns() {
        val runs=listOf(EpubRun("Before ",strong=true),EpubRun("[Image: alt 李]",translatable=false),EpubRun(" after 🌍",emphasis=true))
        val block=EpubBlock(listOf(0,1),0,EpubBlockKind.PARAGRAPH,runs,0)
        assertNull(localizedEpubImages(listOf(block),null,table("en")))
        for(language in listOf("es","ca")) {
            val displayed=assertNotNull(localizedEpubImages(listOf(block),null,table(language))).single()
            assertSame(block,displayed.source);assertEquals(runs,block.runs)
            assertSame(runs[0],displayed.runs[0]);assertSame(runs[2],displayed.runs[2])
            assertTrue(displayed.runs[1].text.contains("alt 李"));assertFalse(displayed.runs[1].translatable)
            val originalStart=runs.take(2).sumOf { it.text.epubCodePoints() }
            val displayStart=displayed.runs.take(2).sumOf { it.text.epubCodePoints() }
            assertEquals(originalStart,displayed.displayToSource(displayStart))
            assertEquals(displayStart,displayed.sourceToDisplay(originalStart))
            assertEquals(originalStart+2,displayed.displayToSource(displayStart+2))
        }
        val publisher=block.copy(runs=listOf(EpubRun("[Image: original book text]")))
        assertNull(localizedEpubImages(listOf(publisher),null,table("es")))
    }

}
