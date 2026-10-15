# Android responsiveness

PR #32 starts at `3ef7aac20bae5841b022f3bc66bab3a27897f784` (PR #31 squash).
The reference device is the user's realme C55. No physical Android device is
attached to the development environment. Host rendering, operation counts and
Android compilation are separate evidence; none establishes smooth C55 scrolling.

## Audit and hypotheses

Home is a lazy vertical list containing keyed horizontal lazy rows. Search and
Library use keyed lazy grids; History groups real timestamps. App owns vertical
scroll states independently of the active destination. Discovery already limits
network concurrency to two, retains eight 25-entry pages for five minutes and
rejects obsolete results. Home requests at most four pages in two batches.
No whole-publication download is used for discovery.

Confirmed avoidable work in the baseline:

* Navigating between unchanged local tabs queries Library and History again and
  emits loading states. In a controlled 100-change sequence, each repository is
  read **41 times**, including startup. SQLite already runs on an IO dispatcher;
  the unnecessary queries, snapshot allocation and loading UI are the issue.
* A new cover composition starts with an empty `CoverState`, even for a resident
  thumbnail. Its coroutine later acquires the existing image. That first
  composition lays out the typographic fallback before showing cached artwork.
* App collects collection progress while a reader owns the screen. A real shared
  text-reader scene with twenty valid `report`/flush operations produces twenty
  App-root side effects, although no collection is displayed. This is unnecessary
  ancestor composition, separate from the reader's necessary locator updates.
* Rounded cover surfaces have an outer clip plus Surface's own clip. Palette and
  title-gradient objects are constructed repeatedly. These are rendering/allocation
  candidates, not proof of the physical-device jank's main cause.

Important negative findings: thumbnail decoding is already asynchronous and
subsampled to at most 192×288 pixels. One worker owns loading/conversion; the LRU
has 24 slots, including active/pending/negative entries. Increasing this budget or
adding another image library is not justified. Cached Home scrolling in the host
fixture makes no network/decode calls. Resource ownership/security are unchanged.

SQL storage is dispatched off Main. Native translation is lazy, serialized and
performed off Main, with one native worker; Home does not initialize models or
start inference. Translation progress is collected by its own panel, not Home.
EPUB retains bounded semantic windows and prefetch/index caches. CBZ retains its
bounded decoder, presentation tickets and coalesced navigation. Neither reader
engine is rewritten without device profile evidence.

## Corrections and invariants

Tab visits now ensure a collection snapshot rather than force another read.
Concurrent ensure calls share the existing request; failed reads can retry. All
production commits already notify `ApplicationCollections.changed()` and still
force fresh repository snapshots. Import, removal, history recording and reader
return retain that path. Retry remains explicit. There is no persistent database
cache or schema change. Direct test-map edits must emit the same commit notice;
existing metadata/date assertions remain intact. A refresh with existing entries
does not insert a loading card above them and shift the grid.

A read-only StateFlow index exposes the same 24 resident cover entries for the
first composition, followed by the usual leased flow. It allocates no additional
bitmap and forgets eviction/invalidation/close. Cache misses still use the original
fallback. Composition is keyed by owning cache and publication identity. Surface
owns the single rounded clip, including the child ripple. Each card retains its
own gradient brush (its shader can depend on that card's dimensions); the fallback
palette is shared. Cover dimensions, quality, aspect ratio and touch semantics are
unchanged.

App's five destinations use Compose's saveable state holder, retaining nested
carousel offsets when a tab leaves composition. This stores UI state, not
bitmaps/readers/native jobs. Vertical states keep their existing owner. Collection
progress is indexed once per immutable snapshot and snapshot-to-display conversion
is retained per lazy item. Newest-record and first-tie semantics are unchanged.

App subscribes to collection progress only while the shell is displayed. Readers
continue collecting their own locator/controller flows and writing through the
same application-owned persistence worker. Returning collects the latest snapshot
immediately; no save interval, locator schema, translation mapping or reader
lifetime changes.

### Budgets retained

| Resource | Limit / ownership |
| --- | --- |
| Cover cache | 24 entries including pending/negative/pinned; one load/decode/conversion; each bitmap <=192×288. Resident pixels at the maximum dimensions total 5,308,416 bytes (5.0625 MiB ARGB), excluding temporary decode/GPU/framework costs. The read index references these entries, not another cache. No disk thumbnails. |
| Collection presentation | Library repository limit 1000; History UI reads 50; progress source retains 64 records plus <=8 Home summaries. The read-only index contains <=72 publication IDs. Lazy item conversions do not duplicate an entire publication. |
| Discovery | Two active metadata requests, eight cached 25-entry pages, five-minute TTL; <=100 loaded Search entries per source; no new background prefetch. |
| EPUB | 128 blocks/65,536 text units per semantic window, two retained windows. Existing indexes, append limits and translated-text budgets are unchanged. |
| CBZ | Three single-page/vertical raster slots or four spread slots; existing decode resolution/security and coalescing unchanged. |
| Translation | One native worker; no Home/startup inference. Existing 120 MiB model storage cap, explicit consent, pinned/temporary ownership and translation cache limits unchanged. |

These are payload/entry budgets, not a measured total app RAM ceiling. Skia/GPU,
fonts, UI nodes, archive indexes and native model overhead require device profiling.
The twelve-ID warm fixture fits within the LRU; an entire catalog does not. Distant
artwork can be evicted/reloaded beyond 24 IDs. No claim of zero redecodes for an
arbitrary full catalog is made.

## Reproducible host measurements

`HomeResponsivenessTest` uses the real Home composables at 390×720, density 1,
three discovery rows and twelve original synthetic publications, all artwork
already cached. Five samples each execute 60 scroll/render steps, 30 down and 30
up, after a warm traversal. The load counter must remain twelve. Software Skia,
JDK 21 on Linux amd64; no network, model or third-party books. Timing has no flaky
pass threshold. Allocation counts cover the calling thread only, not total heap.

```sh
INFINILECT_PERFORMANCE_DIRECTORY=/absolute/private/output \
  gradle :app:desktopTest --rerun-tasks --tests '*HomeResponsivenessTest' \
    --tests '*NavigationResponsivenessTest'
```

Use the repository's documented JDK 21/toolchain configuration. Optional output
must stay outside the repository; it is diagnostic material, not a tracked fixture.
Compare identical environments and fixtures. Host values are not Android FPS,
frame deadlines, startup or battery measurements.

### Recorded comparison (2026-10-15)

JDK 21.0.12.1, Linux amd64, 2-CPU cgroup quota, 8 GiB container limit. Both
render measurements ran the same scrolling method alone in a fresh test JVM,
without another build/test running. Five warmed samples per version; synthetic
192×288 resident bitmaps, not real book artwork or Android JPEG decoding. Baseline
is the unoptimized `3ef7aac` implementation with the harness added. Values from
broader focused runs are intentionally excluded (different font/JIT warmup).

| Measurement | Before | After | Interpretation |
| --- | ---: | ---: | --- |
| 100 tab changes, Library reads including startup | 41 | 1 | Unchanged data; committed changes still trigger another read. |
| Same sequence, History reads | 41 | 1 | No repeated loading snapshots solely for tab visits. |
| 20 valid reader report/flushes, App-root side effects | 20 | 0 | Actual shared TEXT reader scene; last code-point locator remains 20 after Back. Reader-local updates are still expected. |
| Resident cover's first composition | Empty image | Existing image | Deterministic failing-before/passing-after regression, no new decode. |
| Cached Home, median per 60 scroll/render steps | 363.20 ms | 366.22 ms | Overlapping ranges, **no demonstrated rendering-time improvement**. |
| Cached Home, sample ranges (60 steps) | 331.63–405.32 ms | 341.19–377.96 ms | Host/software-rendering diagnostics; not Android frame deadlines. |
| Same workload, median calling-thread JVM allocated bytes | 1,375,936 | 1,159,736 | About 15.7% fewer allocated bytes; excludes native/other-thread allocations. |
| Cover loads during warmed measurement | 0 | 0 | Twelve initial loads retained throughout; no catalog refresh. |

This does not establish the dominant cause of the reported C55 jank or a perceptible
phone improvement. Startup, CPU scheduling, GPU frames, heap peaks and translation
contention still need the physical scenarios below. Correcting measured repeated
work and supplying diagnostics is the verified scope of this Draft.

Compiler diagnostics are opt-in via `-Pinfinilect.composeReports=true`, written to
`app/build/compose-diagnostics`. In this build HomeScreen, DiscoveryTile, HomeCover
and CollectionScreen are skippable; StrongSkipping is enabled. Reports describe
compiler contracts, **not runtime recomposition counts**. No stability annotations
were added to models with mutable/list contracts. Generated reports are not tracked.

## Optional physical-device frame diagnostics

The existing debug APK accepts an explicit `infinilect.performance` intent extra.
Only a debuggable app enables this. Normal launches create no listener, histogram
or diagnostic worker. No remote telemetry, book text, query, history, identity or
private path is recorded. A fixed 1001-bucket histogram is collected on a dedicated
diagnostic callback thread, released at Activity stop. There is no per-frame log.
PSS/Java heap and aggregate ART GC counters are sampled on that thread at stop.
Rotations/restarts create separate sessions. First-draw frames are counted apart.

```sh
adb shell am force-stop org.infinilect.app
adb logcat -c
adb shell am start -W -n org.infinilect.app/.MainActivity \
  --ez infinilect.performance true
# Perform ONE scenario below. Press Home to finish that interval and report it.
adb logcat -d -s InfinilectPerf:I '*:S' > frames-scenario-A.txt
adb shell dumpsys meminfo org.infinilect.app > memory-scenario-A.txt
```

FrameMetrics is available on all supported Android versions (minSdk 26). API 31+
uses the supplied per-frame deadline; older versions use the display refresh
period. Percentiles are upper histogram bucket edges at 1ms resolution; 1001
means >=1000ms and must be read alongside the overflow count. `callbackDrops`
means missing callbacks, **not** dropped display frames. `missedDeadline` and
`over700ms` describe observed frame durations, not a full Android vitals score.
PSS is a single sample, not a peak; GC counters are cumulative process counters.
Repeat scenarios with diagnostics disabled to check their own overhead.
If the platform cannot register FrameMetrics or supply its summary, the app logs
the exception class and continues normally; use the independent system trace.

For startup use `am start -W` (Activity launch measurement) and Android Studio
System Trace/Perfetto for full startup and scheduling. The debug frame histogram
does not measure cold startup. Use System Trace with FrameTimeline, main/render
threads, CPU scheduling and GC to attribute slow frames before further tuning.
`adb shell dumpsys gfxinfo org.infinilect.app reset` before a scenario and
`adb shell dumpsys gfxinfo org.infinilect.app framestats` afterwards provide an
independent system measurement. Do not record sensitive screen contents.

Macrobenchmark is not introduced here: no connected Android target/emulator is
available to execute and validate it. The deterministic host scenario plus public
platform FrameMetrics/Perfetto avoids an unexecuted benchmark dependency. Real
scroll frame distributions, GPU cost, RSS/PSS peaks and device inference contention
remain physical acceptance work.

## Physical comparison checklist (realme C55)

Keep the same APK signing identity, app data, screen refresh rate, font scale,
appearance, battery/thermal conditions and catalog contents. Do not uninstall or
clear app data. Record Android version, build SHA and each scenario's duration.
Compare the same build type; debug results are not release-performance evidence.
Run at least three times per build, alternate baseline/candidate order where an
authorized compatible APK is available, and separate cache-cold from cache-warm.

| Scenario | Procedure / checks |
| --- | --- |
| A — warm Home (priority) | Let every request and thumbnail finish. Scroll the same vertical route and horizontal rows for 30s, without refreshing. Capture frame p50/p95/p99, missed deadlines, CPU/GC and PSS. Repeat offline with warm metadata to separate network work. |
| B — progressive Home | Refresh with explicit online consent, scroll while rows arrive. Check stable positions/card dimensions and no blank local Home. |
| C — five destinations | Home → Library → History → Search → Settings → Home, twenty cycles. Keep Search query/results and each viewport; do not trigger new collection reads merely by switching. |
| D — small Library | Scroll 3–10 owned publications; open details, resume, Back. Check covers and original progress. |
| E — large Library | Import/generated original fixtures up to the existing 1000-entry limit; scroll repeatedly. No copyrighted diagnostic corpus, unbounded cache or growing decode queue. |
| F — EPUB | Long chapter, large font, mixed images/links; cross windows both ways, change typography, reopen deep position. Select, resize handles, translate, cancel selection with one outside tap/Back. |
| G — CBZ | Large original archive, rapid turns, RTL/LTR, spreads, vertical mode, pinch/pan/double tap and rotation. Verify displayed page and saved locator agree. |
| H — translation | Explicitly prepare a verified model; compare EPUB scrolling during pending translation and after. Navigate away, return, cancel/retry; no stale text or progress. Repeat a model download while browsing local tabs. |
| I — reader return | Open/resume from Home, advance, Back, repeat. Home viewport must remain stable and Continue progress update. |
| J — startup | `am force-stop` + `am start -W` for process-cold starts; Home/background/foreground for warm starts. Models must not load during general startup. |

For every scenario check memory after repeated navigation/return, low-memory kills,
ANRs/crashes, original Library/History and durable progress. Keep en/es/ca resources,
model retention and explicit download semantics unchanged. A perceptible,
repeatable warm-Home improvement on this phone is the acceptance criterion;
automated tests alone do not meet it.

## Regression verification

New cases cover initial cached presentation, repeated warm Home scrolling,
cache index eviction/invalidation/close, one in-flight collection read, failed-read
retry/committed-history refresh, repeated five-tab visits, newest/tied progress
indexing, nested carousel restoration, lazy 1000-entry Library and reader progress
isolation. Two frame-histogram/invalid-metric cases complete twelve new tests.
Existing assertions remain; three
UI fixture edits now emit real commit notifications instead of relying on tab visits
to rediscover unannounced map edits. No tests were disabled.

Full matrix, JDK 21, offline dependency resolution, one Gradle worker:

```sh
gradle -Pinfinilect.composeReports=true \
  :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:verifyDebugLanguageResources :desktopApp:compileKotlin
```

| Suite / build | Result |
| --- | --- |
| App Desktop full suite | 1,533 passed, 0 failures/errors/skips |
| App Android-host full suite | 1,291 passed, 0 failures/errors/skips |
| Core JVM | 59 passed, 0 failures/errors/skips |
| Core Android-host | 59 passed, 0 failures/errors/skips |
| Android debug APK | Built; `verifyDebugLanguageResources` checks packaged en/es/ca bytes against generated resources |
| Desktop application compilation | Passed |
| Final focused suite | 87 passed, 0 failures/errors/skips |
| Isolated post-change Home benchmark | Passed; values above |
| Repository hygiene | `git diff --check`, source/header, generated-artifact and credential-pattern checks passed (438 files inspected) |

The full matrix succeeded in 10m 2s. Existing warnings in PDF and test fixtures
remain; no dependencies, signing, manifest permissions, release settings or data
schemas changed. All EPUB, CBZ, PDF, TEXT, import/security, selection/localization,
translation, collection/history and discovery tests in these suites remain enabled.

The Android target was compiled and packaged, not executed here. No usable physical
or emulated Android target was configured; local adb also cannot initialize its
read-only home preferences directory. No native Desktop window acceptance, device
frame traces, device memory profile, real-model inference benchmark or C55 subjective
comparison was performed. The headless Compose scenes and JVM counters must not be
reported as those checks. Normal debug signing is unchanged; this verification
does not establish whether an APK shares the certificate of the user's installed
development build.
