// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Host microbenchmark of the exact former getter, not a device scrolling/FPS claim.
 * Timing is diagnostic only; the regression assertion checks retained snapshot identity. */
class DiscoveryPerformanceTest {
    @Volatile private var observed: List<DiscoveryEntry>? = null
    @Test fun immutableResultSnapshotAvoidsRepeatedFlattenAndDedupAllocations() {
        val source=CatalogFixture()
        val state=DiscoveryState(catalogs=listOf(CatalogResults(source.id,List(100){source.entry("$it",listOf("Adventure"))})))
        val oldGetter={distinctEntries(state.catalogs.flatMap {it.entries})}
        val snapshot={state.entries}
        assertEquals(oldGetter(),snapshot());assertSame(snapshot(),snapshot())
        System.getenv("INFINILECT_DISCOVERY_BENCHMARK_DIRECTORY")?.let { directory ->
            val bean=ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            val allocations=bean?.takeIf {it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled}
            fun measure(read: () -> List<DiscoveryEntry>): Pair<Long,Long?> {
                repeat(5000){observed=read()}
                val id=Thread.currentThread().threadId()
                val bytes=allocations?.getThreadAllocatedBytes(id)
                val start=System.nanoTime()
                repeat(20_000){observed=read()}
                return (System.nanoTime()-start) to bytes?.let {allocations.getThreadAllocatedBytes(id)-it}
            }
            val before=List(5){measure(oldGetter)}.sortedBy {it.first}[2]
            val after=List(5){measure(snapshot)}.sortedBy {it.first}[2]
            val dir=Path.of(directory);Files.createDirectories(dir)
            Files.writeString(dir.resolve("discovery-snapshot-benchmark.txt"),
                "JDK=${System.getProperty("java.version")}; OS=${System.getProperty("os.name")}; arch=${System.getProperty("os.arch")}\n"+
                    "100 entries; 20000 reads; median of 5 warmed samples\n"+
                    "former getter: ns=${before.first}; thread allocated bytes=${before.second}\n"+
                    "snapshot: ns=${after.first}; thread allocated bytes=${after.second}\n"+
                    "Not Android measurements; no frame-time or network-latency inference.\n")
        }
    }
}
