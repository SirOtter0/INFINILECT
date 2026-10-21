// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.collections

import org.infinilect.core.*
import java.nio.file.*
import kotlin.test.*

class LibraryQueryPerformanceTest {
    @Test fun boundedIndexIsReusedAcrossQueriesFiltersAndSortsAtRequestedSizes() {
        val report=StringBuilder("Host JVM wall time, seven repetitions per size (includes possible JIT/GC effects); milliseconds. Not Android frames, scroll or native-memory measurements.\n")
        for(size in listOf(10,100,1000,5000)) {
            val rows=List(size) { index -> LibraryEntry(PublicationSnapshot(PublicationId(SourceId("original-benchmark"),"$index"),"Ciència original $index",PublicationType.BOOK,listOf("Author $index")),index.toLong(),favorite=index%3==0,tags=listOf(LibraryTag(1,"Estudis"))) }
            var index:List<LibrarySearchRow> = emptyList()
            val builds=List(7) { val start=System.nanoTime();index=libraryIndex(rows);(System.nanoTime()-start)/1_000_000.0 }
            val queries=List(7) { val start=System.nanoTime();val result=libraryMatches(index,LibraryQuery(text="ciencia",favorites=true,tags=setOf(1),sort=LibrarySort.TITLE,descending=false));assertEquals((size+2)/3,result.size);(System.nanoTime()-start)/1_000_000.0 }
            val sorts=List(7) { val start=System.nanoTime();for(sort in LibrarySort.entries) assertEquals(size,libraryMatches(index,LibraryQuery(sort=sort)).size);(System.nanoTime()-start)/1_000_000.0 }
            fun stats(values:List<Double>)=values.sorted().let {"min=${it.first()},median=${it[3]},max=${it.last()}"}
            report.appendLine("n=$size index_ms=${stats(builds)} search_and_filters_ms=${stats(queries)} five_sorts_ms=${stats(sorts)}")
            assertEquals(size,index.size)
        }
        println(report)
        System.getenv("INFINILECT_LIBRARY_METRICS")?.let {directory -> val path=Path.of(directory);require(path.isAbsolute);Files.createDirectories(path);Files.writeString(path.resolve("library-query-host-${System.getProperty("infinilect.test.platform","jvm")}.txt"),report)}
    }
}
