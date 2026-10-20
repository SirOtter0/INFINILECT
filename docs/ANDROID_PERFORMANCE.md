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
private path is recorded. Five fixed 1001-bucket histograms (total, layout/measure, draw, sync and GPU where
available) are collected on a dedicated diagnostic callback thread, released at
Activity stop. There is no per-frame log. Summaries and PSS/Java heap/aggregate ART
GC samples are emitted every five seconds and at stop, so a hard kill does not
necessarily discard the entire interval. Histograms are cumulative per session;
GC counters are cumulative per process. Rotations/restarts create separate sessions.
First-draw frames are counted apart. Unsupported GPU metrics have zero samples,
which must not be interpreted as zero GPU work.

```sh
adb shell am force-stop org.infinilect.app
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

## Phase 1 regression verification

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

## Phase 2 — rapid-loading Home closure and frame-cost investigation

Starting HEAD: `ab0db6add527f3b824a180ed999161e46c9be376`. The user's physical
C55 test reports a small improvement, persistent warm-Home jank and a process
closure while rapidly scrolling during loading. **No device crash trace, exit
reason, memory profile or frame trace was supplied. There is no attached Android
target. The physical closure is not reproduced or established as fixed.** This
Draft remains blocked on that evidence and repeat physical acceptance.

### Findings and narrow correction

The resident cover index publishes immutable map snapshots under the same mutex
as its 24-entry LRU. It references the existing entries/images, not copied bitmaps.
Borrowers collect immutable `CoverState` values. Invalidation/eviction/close drop
references; they do not recycle images already handed to Compose. Android recycles
only the temporary decoder bitmap after copying its pixels into the bounded
raster. The converter creates a separate presentation bitmap. No unsynchronized
snapshot iteration or use-after-recycle was found in these paths. This is an audit
and regression finding, not proof against a device-specific image/driver failure.

A non-cooperative cancelled load is awaited before another starts. Ticket/entry
identity prevents its late result from filling an evicted/recreated entry. Tests
exercise that ordering, simultaneous viewport churn/invalidation, conversion
failures, oversized results and an old image still drawn after cache eviction.
There is still one worker, no additional prefetch or cache, and no catch for
`OutOfMemoryError`/other VM errors. Recoverable load/conversion exceptions continue
to produce the existing typographic fallback. No broad recovery catch was added.

**Reproduced layout defect:** `DiscoveryTile` conditionally omitted author and
language lines and allowed unbounded source-label wrapping. LazyRow measures its
currently composed children; a different metadata shape can change its height
and displace later Home sections even when every image is cached. A real Compose
layout at density 1/font scale 1.5 measured **292 vs 348 px** for otherwise identical
cards without/with author and language. The stable-height assertion failed before
the correction. It now measures **300 vs 300 px**. This is a layout measurement,
not an Android frame-time speedup or the crash's cause.

Each tile now reserves one line each for author, source attribution and language. Missing fields remain empty; no metadata is invented. Font-scaled line slots
keep the row height stable, including long source names. Visual source attribution
can ellipsize at one line; its full text remains in semantics/details. This spends
some blank metadata space to avoid vertical rebasing; artwork dimensions and image
quality are unchanged. No reader engine, progress schema, discovery algorithm,
network limit, signing configuration or dependency changed.

### Stress coverage and limits

`HomeLoadingStressTest` drives the actual shared App at 360×640 with two controlled
sources, 120 original synthetic entries per source, duplicates, missing metadata,
100 persisted Library/History entries, gated thumbnail loading, conversion failures
and a recoverable source failure. Touch drags reverse both axes while responses
arrive; deterministic lazy-index traversal guarantees distant cards and cache
pressure, independently of platform fling physics. It leaves/returns to Home and
Search, retries, disposes the screen during loading, checks finite scroll offsets,
unique row IDs, <=24 entries, one active cover load and unchanged Library/History.
Cold and warmed portions are in the same controlled scenario. Tests do not invoke
real HTTP, simulate a kernel low-memory kill, or measure Android's GPU.

Additional cache tests cover 1,200 real-dispatcher acquire/release operations from
eight coroutines with invalidation, plus deterministic non-cooperative late loads,
negative conversion results, oversized artwork and drawable evicted images. These
increase coverage; passing them cannot establish the original device crash cause.
No fake OOM recovery, storage deletion or reduction of security assertions is used.
The complete reader/progress/collection regressions remain enabled.

### Opt-in crash and stage diagnostics

Only an explicit debuggable Activity session enables the following additions:

* `InfinilectCrash`: bounded exception classes and code locations (three causes,
  sixteen frames each), without exception messages, filenames or thread names.
  The original uncaught handler always receives the same failure, even if the
  diagnostic sink fails. This does not swallow crashes or restart the app.
* `InfinilectExit`: at the next diagnostic launch, up to five **own-package**
  `ApplicationExitInfo` records on Android 11+, with reason/status, importance,
  PSS/RSS and age. No description, trace stream or process name is read. Older
  Android versions report that historical exit reasons are unavailable.
* `InfinilectPerf`: five-second/final aggregate total/layout/draw/sync/GPU timing
  histograms plus heap/PSS/GC samples, on the existing diagnostic thread. No
  unbounded frame history or per-frame logging. Percentile fields use upper bucket
  edges, not exact durations; GPU samples require API 31+ and platform support.
* Public Android trace sections around cover preflight, bounds decode, bitmap
  decode, pixel copy and presentation-bitmap creation. Static names only; begin/end
  stay on the same worker thread. Disabled sessions perform only an enabled check
  per image stage, not per frame. No book/query/path identifiers enter traces.

Five histogram arrays use about 40 KiB of bucket payload in a diagnostic session.
Periodic PSS sampling/logging has a cost: compare diagnostics-on/off and use
independent gfxinfo/Perfetto. No models are loaded or translated for diagnostics.
Crash/exit, memory and image-stage diagnostics remain enabled if an OEM rejects
FrameMetrics registration; `framesAvailable=false` identifies the missing frame
samples. These Android additions compile but require physical verification; JVM tests
verify histogram arithmetic and sanitized exception forwarding/privacy only.

### C55 capture instructions (ready to copy; device execution pending)

Do not uninstall, clear app data, delete publications or replace the signing key.
Keep the same font scale, refresh rate, battery/thermal conditions and dataset.
Record the installed build SHA and Android version separately. First, connect USB
and authorize debugging. Start one diagnostic session (the initial force-stop is
intentional and will itself appear as a user-requested exit):

```sh
adb devices
adb shell am force-stop org.infinilect.app
adb shell am start -W -n org.infinilect.app/.MainActivity --ez infinilect.performance true
```

In a second terminal, **before reproducing the closure**, record only sanitized app
logs. Leave this running while testing; stop with Ctrl-C after reopening the app:

```sh
adb logcat -v monotonic -s InfinilectPerf:I InfinilectCrash:E InfinilectExit:I '*:S' > infinilect-home-diagnostics.txt
```

If it closes, **do not force-stop again first**. Reopen with the same command so
Android's previous reason is reported. Note approximately when it closed:

```sh
adb shell am start -W -n org.infinilect.app/.MainActivity --ez infinilect.performance true
```

Reason examples: 3 = low memory, 4 = Java crash, 5 = native crash, 6 = ANR,
10 = user requested (including force-stop). A missing Java trace is not evidence
that no crash occurred. Native crashes, hard kills or allocation failure in the
logger may produce no `InfinilectCrash` lines; rely on the historical reason/system
trace. OEM Android may provide incomplete reasons. Do not classify an OOM from
PSS alone. A Java `OutOfMemoryError` class and a system low-memory kill are distinct.

For a 30-second warm-Home comparison, wait for thumbnails and requests to finish,
then capture independent frame and memory snapshots. Repeat three times. Stop
scrolling and press Android Home to emit the session's final summary:

```sh
adb shell dumpsys gfxinfo org.infinilect.app reset
adb shell dumpsys meminfo org.infinilect.app > infinilect-memory-before.txt
# Scroll vertically and horizontally for 30 seconds, reversing direction.
adb shell dumpsys gfxinfo org.infinilect.app framestats > infinilect-warm-frames.txt
adb shell dumpsys meminfo org.infinilect.app > infinilect-memory-after.txt
```

Use the difference in cumulative `gcCount`/`gcTimeMs` across samples, not their
absolute totals, to compare GC activity. PSS snapshots are not peak memory.
For cache-cold browsing use a **fresh process** while keeping app data/models, then
refresh discovery explicitly. Private HTTP metadata/disk caches may still be warm;
label that distinction rather than describing an app-data wipe as a cold cache.
Test once online while rows arrive, once after loading, once offline and once with
slow/unavailable networking. Switch Home → Search → Home during loading; reverse
both scroll axes repeatedly. Confirm Library/History and reopened reader positions.

If timing histograms do not isolate the cost, capture a public Perfetto system
trace for scheduling, rendering, GC and the static image-stage sections. The
following text config is intentionally bounded to 16 MiB/30 seconds; device/OEM
support must be verified. Run while the diagnostic Activity is foreground:

```sh
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/infinilect-home.perfetto-trace <<'CONFIG'
buffers { size_kb: 16384 fill_policy: RING_BUFFER }
duration_ms: 30000
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_waking"
  ftrace_events: "power/cpu_frequency"
  atrace_categories: "gfx"
  atrace_categories: "view"
  atrace_categories: "dalvik"
  atrace_apps: "org.infinilect.app"
} } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
CONFIG
adb pull /data/misc/perfetto-traces/infinilect-home.perfetto-trace .
```

Inspect locally in Android Studio System Trace/Perfetto: correlate slow frames with
main/render-thread scheduling, layout/draw, GPU, GC and `Infinilect.cover.*`/
`Infinilect.bitmap.create`. FrameTimeline needs Android 12+; unsupported sources
may be absent. Atrace view sections do not guarantee per-composable traces; use
Android Studio's supported Compose tools if finer attribution is needed. Traces
can contain system process/thread names: inspect/redact before sharing. Neither
this command nor the app records screen pixels, book text or search queries.
Prefer the sanitized app log for initial triage; do not post broad Logcat dumps.

### Phase 2 verification and host comparison

Final matrix: JDK 21, offline dependencies, one Gradle worker. The last complete
Desktop rerun passed in a successful 8m 32s matrix. Android-host and build tasks
with unchanged inputs reused their successful preceding results in that rerun.

| Check | Final result |
| --- | --- |
| Complete App Desktop | 1,542 passed; 0 failures/errors/skips |
| Complete App Android-host | 1,297 passed; 0 failures/errors/skips |
| Core JVM / Android-host | 59 / 59 successful results; unchanged code/tasks UP-TO-DATE |
| Android debug APK / Desktop compilation | Passed; no configuration or signing changes |
| Packaged en/es/ca bytes | Verified against generated resources |
| Diagnostic code in APK DEX | Verified, including crash/exit tags, availability flag and image-stage traces |
| Hygiene | 442 tracked/nonignored files inspected; no secrets/binaries added; headers, scope and diff whitespace checks passed |


One complete run on the final compact UI passed **1,541 Desktop tests and failed
one** (`PageSpreadLayoutTest.settingsPairsPagesFitsBothHalvesAndShowsLogicalRangeInEitherDirection`);
Android-host passed all 1,297. The failure was `Spread 0 must be fully presented`
in `Fixture.<init>` → `await(0)`, before the mode/pairing assertions. The same
initial-presentation failure pattern predates this PR and is documented in
[EPUB_TRANSLATION.md](EPUB_TRANSLATION.md) and [DISCOVERY.md](DISCOVERY.md).
That exact test then passed isolated without changing CBZ code, its fixture,
timeout or assertions. All 1,542 Desktop tests then passed in the complete rerun, with the same code and assertions. The intermittent fixture startup cause
remains unestablished; do not confuse this host assertion with the user's Home
process closure. No test was skipped or disabled. The earlier two-source-line
variant also passed a complete matrix; its results do not substitute for final-UI
verification.

Nine new test methods: four shared cache regressions, two shared crash-privacy/
forwarding tests, plus Desktop layout, drawable-eviction and Home stress scenes.
The selected cover/Home/crash/lifecycle classes total 38 Desktop and 33 Android-host
cases, included in the complete suites, not additional physical-device tests.

A contemporaneous control restored only `DiscoveryUi.kt` from starting `ab0db6a`
for the existing cached-Home benchmark, then restored the final source before all
final tests/builds. Both variants ran the same method alone in fresh JVMs without
another build/test running. Same JDK 21/Linux/2-CPU/8 GiB/software-Skia fixture as
above, five warmed samples of 60 scroll/render steps; no live network or models.
The fixture contains author/language metadata, so its purpose is to check warmed
render cost rather than reproduce missing-field height changes.

| Host measurement | Starting tile | Final tile |
| --- | ---: | ---: |
| Median / 60 steps | 375.07 ms | 399.88 ms |
| Range / 60 steps | 357.41–429.78 ms | 370.26–414.39 ms |
| Median calling-thread allocated bytes | 1,160,128 | 1,165,448 |
| Additional cover/catalog loads while scrolling | 0 / 0 | 0 / 0 |

Ranges overlap; **no rendering-time or allocation improvement is demonstrated**.
The final median is higher in this host run; this must not be reported as an
Android speedup. Historical phase-1 times were 363.20/366.22 ms; different runs
must not be substituted for this control to claim a gain. The verified benefit is
eliminating the metadata-dependent height change. Real frame-time improvement and
its dominant bottleneck still need the C55 trace. No further concurrency/cache/
engine changes are justified by these host timings alone.

### Remaining blocker and next evidence

The C55 closure may still be a Java/native error, OEM/driver issue or system kill;
none is established by the host tests. Warm-Home frame percentiles, peak memory,
CPU saturation, GPU costs and translation contention remain unmeasured on Android.
No change to cache size, worker concurrency or image resolution is justified yet.
Attach the sanitized crash/exit log and one warm/one loading trace before claiming
the closure fixed or the phone smooth. Preserve the user's accepted translation,
selection, locales, model ownership and reader behavior during that comparison.

## Mihon comparison and architecture decision

[MIHON_PERFORMANCE_COMPARISON.md](MIHON_PERFORMANCE_COMPARISON.md) compares pinned
Mihon and INFINILECT revisions, actual Android/Desktop dependencies, lists, image
ownership, navigation and readers. It evaluates three UI architectures, inventories
migration effort and proposes a controlled device experiment. This research changes
no runtime code and supplies no new physical-device measurements. Android already
uses Jetpack Compose artifacts; an independent Android UI is not a demonstrated
remedy for the unresolved Home closure or warm-scroll jank.

## Phase 3 — Visible-cover scheduling and render-work reduction (2026-10-16)

### Diagnóstico e implementación

Control de aplicación: `b1237576efcba0d6fabab5e2db53dc4d3e33b343`.
Se conserva la arquitectura compartida, las dimensiones y la apariencia. No se
modifican lectores, traducción, localización, fuentes, seguridad, firma ni presupuestos.

Los reports del compilador ya mostraban `HomeScreen`, `DiscoveryTile` y `HomeCover`
como skippable con StrongSkipping. No se añadieron anotaciones de estabilidad.
Las pruebas nuevas sí encontraron trabajo evitable en ejecución:

- **Estado:** Inicio observaba `DiscoveryState` completo para utilizar únicamente
  `entries`. Cuarenta ediciones de consulta sin cambiar publicaciones provocaban
  **80 entradas en scopes de composición**. `HomeScreen` ahora recuerda una
  proyección `map { entries }.distinctUntilChanged()`: el mismo caso registra **0**.
  Las publicaciones realmente nuevas siguen llegando por ese flujo; no hay una
  copia congelada, otro cliente de fuentes ni estado global adicional. Es una
  prueba controlada de granularidad, no una atribución del jank del C55 a ediciones
  de consulta que normalmente se realizan en otra pestaña.
- **Visibilidad:** entrar en composición lazy no implica estar colocado ni visible.
  Una tarjeta recortada pero aún compuesta conservaba su lease y podía retener el
  único trabajador hasta completar/cancelar el request o llegar al timeout de 10 s.
  `CoverViewport` usa APIs públicas `Modifier.Node`, `GlobalPositionAwareModifierNode`
  y `UnplacedAwareModifierNode`; intersecta límites recortados de ancestros con el
  root. No basta con estar dentro de la ventana cuando un carrusel o contenedor
  interior recorta el elemento.
- **Suscripción:** `coverState` mantiene como máximo una suscripción hija para una
  tarjeta visible; cancela y espera su liberación antes de readquirir. Las tarjetas
  totalmente recortadas/no colocadas no solicitan trabajo. `peek` sigue mostrando
  inmediatamente una imagen residente. La liberación no recicla la imagen que
  un frame ya recibió. La caché conserva su deduplicación y rechazo Entry/job.
- **Layout:** se elimina el `Box(fillMaxWidth)` que solo centraba `HomeCover` en
  `DiscoveryTile`; se utiliza alineación del `ColumnScope`. La cadena de layouts
  desde el título al root pasa de **5 a 4** en el fixture. Los cuatro PNG de control
  y final son **idénticos byte a byte**: claro/oscuro y fuente 100/150 %, portada
  cargada, título largo Unicode y metadatos. No es una afirmación sobre todos los
  dispositivos/fuentes. Se mantienen caption slots escalables y accesibilidad.

La geometría publica solamente cambios booleanos de visibilidad, no posiciones
por píxel en estado Compose. No retiene `LayoutCoordinates`, bitmaps ni un scope.
La variante final evita convertir cuatro esquinas a coordenadas de ventana cuando
solo necesita comprobar una intersección, y no utiliza un canal `transformLatest`
ni crea jobs para estados invisibles. Sin repositorio de portadas no instala el
observador. Se mantienen gradiente recordado, clip único de Surface, ripple y
metadatos: no se encontró evidencia para quitar efectos visuales. La visibilidad
es geométrica; no detecta oclusión por otra ventana/diálogo ni transparencia.

### Trabajo, cancelación y límites

Una prueba mantiene tres tarjetas compuestas dentro de un contenedor recortado
más pequeño que la ventana, con fuente al 150 %. Tras desplazar el contenedor,
antes la primera carga no se cancelaba y la segunda no empezaba; después se
cancela la primera y arranca la segunda sin esperar al timeout. Otra prueba deja
un hijo compuesto pero sin colocarlo: ahora cancela y puede volver a activarse.
Dos tarjetas visibles del mismo ID comparten carga/conversión; retirar una no
cancela el trabajo que la otra utiliza. Las tres regresiones fallan con el control
y pasan con el final; la prueba de duplicados pasa en ambos.

El límite de entradas es del repositorio, no un máximo global de RAM: la UI puede
retener un estado ya presentado y existen buffers temporales del decoder/framework.
No se ha medido el pico nativo/GPU de Android.

No se aumentó concurrencia: **un trabajador**, **24 entradas** (incluidos activos,
pendientes y negativos), miniaturas **192×288**, dos peticiones de catálogo y ocho
páginas de caché. Los lectores mantienen sus presupuestos. Cancelar la suscripción
Kotlin no interrumpe necesariamente un decode nativo: el repositorio sigue esperando
que termine antes de comenzar otro. El resultado obsoleto no puede publicarse en
una entrada nueva. Las pruebas existentes de cancelación no cooperativa, conversión
fallida, invalidación y dibujo tras evicción permanecen intactas.

Se compara la política anterior con visibilidad usando la misma concurrencia.
No se introduce un segundo trabajador: el bloqueo reproducido era trabajo que ya
no se necesitaba, no falta demostrada de paralelismo; más workers elevarían el pico
transitorio y la competencia por CPU sin una medición Android que lo justifique.
No se captura `Throwable`/OOM para ocultar un cierre.

### Recorrido de imagen y decisión sobre disco

La ruta sigue siendo fuente/ownership → bytes acotados → preflight/CRC y bounds →
subsampling → raster → ImageBitmap → lease/presentación. En Android,
`CoverThumbnailDecoder` crea el Bitmap reducido, copia a IntArray y recicla ese
Bitmap temporal; `rasterImageBitmap` crea el Bitmap de presentación. Son tres
asignaciones de payload a lo largo de la ruta y dos copias de píxeles, **no tres
bitmaps simultáneos**: el temporal se recicla antes de la conversión. Como estimación
de datos, un ARGB 192×288 contiene 221.184 bytes; el solapamiento de dos payloads
serían 442.368 bytes, excluyendo bytes codificados, stride, codec, objetos y memoria
nativa/GPU. No es un pico medido en Android.

No se cambia este contrato ni se añade Coil. El caso caliente no hace nuevas
cargas/conversiones; optimizar esa copia no explica por sí solo los tirones con
todas las portadas residentes. Un camino Android directo podría ahorrar copias,
pero faltan mediciones de `Infinilect.cover.*`/`Infinilect.bitmap.create` en el C55
para justificar su riesgo de ownership. Los stages de diagnóstico se conservan.

La biblioteca sintética demuestra misses de capacidad: 82 cargas,
54 repetidas tras evicción en el control; 82 y
54 en el final. Ese fixture usa raster sintético, sin ZIP, HTTP ni
codec real: **no demuestra un beneficio de una nueva caché de disco**. En Inicio
caliente hay cero cargas adicionales. Se difiere el cache de thumbnails derivados;
no se crea almacenamiento persistente adicional ni se elude el hash del payload
local. Si se mide un coste relevante de misses reales, deberá usar digest/revisión,
versión de decoder/tamaño, namespace privado, cuota/LRU, escritura atómica, validación
y eliminación de corruptos; no solo título, URL o mtime como autoridad.

### Protocolo comparable y resultados host

Linux x64, JDK 21.0.12.1, Gradle 9.7.1, dos CPU de cuota y 8 GiB; software Skia,
mismo harness, fixtures, dimensiones, flags de compilador y límites en control/final.
Los controles definitivos y el final se ejecutaron en esta sesión del 16 de octubre,
sin compilar otra variante simultáneamente. Tres JVM frescas por caso y variante.
La compilación/configuración Gradle queda fuera de las duraciones de los tests.
Se restauraron únicamente los tres archivos de producción modificados a la revisión
de control, manteniendo el mismo harness; luego se restauró el final. No se mezclan
estos números con el protocolo anterior de 60 pasos exclusivamente verticales.

- **A:** Home 390×720, tres filas con los mismos 12 IDs y portadas residentes.
  Cada muestra realiza 60 pasos verticales y cuatro drags horizontales reales
  (20 pasos render adicionales); un recorrido mixto previo se excluye.
  Cinco muestras/JVM, **15 muestras por variante**. Cero carga de red real;
  los contadores de catálogo/portadas no aumentan al desplazar.
- **B:** Home 360×640 con fuentes sintéticas lentas/fallidas, resultados duplicados,
  llegadas durante gestos, desplazamiento de ambos ejes/inversiones, presión de
  caché y salida/regreso entre pestañas. Tres recorridos completos por variante.
  La duración es del escenario entero, **no de un frame aislado**.
- **C:** biblioteca de 1.000 publicaciones originales sintéticas, cinco travesías
  por JVM entre índices 0/200/600/998/600/200/0, errores de imagen y remount.
  Se comprueban scroll y datos persistidos. Quince muestras por variante;
  no se compone simultáneamente el catálogo completo ni se afirma visitar cada libro.
- **D:** ocho coroutines/1.200 leases con churn e invalidación. Tres ejecuciones;
  límites y cleanup comprobados. Es presión de slots simulada, no OOM/LMKD Android.

| Escenario host | Antes | Después | Lectura |
| --- | --- | --- | --- |
| A, mediana de recorrido / rango | 575,29 ms / 510,14–670,14 ms | 556,92 ms / 521,42–688,02 ms | Mediana 3,2 % menor; rangos solapados, no demuestra una mejora Android |
| A, bytes asignados en hilo llamante, mediana | 3.353.528 | 3.602.664 | Excluye otros hilos/native/GPU; incluye harness |
| A, P95/P99 de pasos scheduler+render | 10,01 / 12,82 ms | 9,51 / 11,86 ms | No son FrameTimeline, FPS ni jank Android |
| A, máximo Java observado entre pasos | 27,28 MiB | 27,40 MiB | Muestras del heap del test, no PSS ni pico nativo |
| B, mediana / rango del test completo | 4,106 s / 3,825–4,227 s | 4,110 s / 3,827–4,120 s | Sin mejora de duración demostrada; menos trabajo sintético iniciado |
| B, cargas / intentos de conversión de raster sintético | 35 / 29 | 34 / 27 | No son mediciones de codec Android |
| C, mediana de travesía / rango | 267,90 ms / 237,03–456,46 ms | 260,54 ms / 228,68–438,83 ms | Mediana 2,7 % menor; rangos solapados, no demuestra una mejora Android |
| C, bytes asignados en hilo llamante, mediana | 5.362.472 | 6.170.944 | Incluye asignación de raster sintético y composición |
| D, mediana / rango del test completo | 0,310 s / 0,300–0,345 s | 0,294 s / 0,241–0,304 s | Variabilidad de concurrencia host; no se atribuye una mejora de frames |

La primera variante (`collectLatest` y `boundsInWindow`) aumentaba asignaciones un 18,7 % en A y un 31,5 % en C frente a su control de la sesión anterior; se sustituyó. El final todavía asigna un 7,4 % más en A y un 15,1 % más en C frente al control definitivo. **No se afirma una reducción de asignaciones ni de RAM.** Se conserva el seguimiento de visibilidad por el beneficio funcional reproducido: no dejar al lector esperando por una portada abandonada y liberar solicitudes no colocadas. Los presupuestos no aumentan; este coste adicional debe evaluarse físicamente antes de aceptar la PR. Las medianas de tiempo y sus rangos no demuestran una gran mejora de render ni significación estadística. Las muestras de una misma JVM están agrupadas, no son 15 dispositivos independientes.

| Optimización | Antes | Después | Resultado | Decisión |
| --- | --- | --- | --- | --- |
| Renderizado de tarjetas | 5 ancestros de layout del título en fixture | 4; cuatro PNG idénticos | Menos estructura, sin pérdida visual en controles; tiempos globales arriba | Mantener el cambio mínimo; no quitar efectos |
| Carga visible | Una carga recortada seguía ocupando worker | Cancelación al recortar/no colocar; siguiente visible puede empezar | Regresiones reproducidas y corregidas; máximo un worker | Mantener sin aumentar concurrencia |
| Conversión de imágenes | Ruta Bitmap → IntArray → Bitmap | Sin cambios | No hay medida de copia/GC Android que justifique sustituirla | Diferir camino Android directo |
| Caché de miniaturas | 24 slots; misses repetidos en C, cero en A caliente | Mismos límites; sin nuevo cache de disco | El fixture no mide I/O/codec real ni demuestra beneficio de disco | Diferir hasta perfilar misses reales |
| Estado de Inicio | 80 entradas en scopes / 40 ediciones de query | 0 con las mismas publicaciones | Trabajo ajeno al contenido eliminado | Mantener proyección deduplicada |

### Reproducción y regresión

Reports: `-Pinfinilect.composeReports=true`. El harness no tiene umbrales de tiempo
que puedan fallar por ruido; sus aserciones comprueban ownership, datos, contadores,
límites, cancelación y layout. Para repetir con un JDK21/SDK configurado:

```sh
INFINILECT_PERFORMANCE_DIRECTORY=/absolute/path/outside/repository \
  ./gradlew :app:desktopTest --rerun-tasks \
  --tests '*HomeResponsivenessTest.cachedArtworkScrollingDoesNotReloadOrAccumulateCoverResources' \
  -Pinfinilect.composeReports=true
# Ejecutar cada clase por separado en JVM fresca, tres veces por variante:
./gradlew :app:desktopTest --rerun-tasks --tests '*HomeLoadingStressTest'
./gradlew :app:desktopTest --rerun-tasks --tests '*LargeLibraryResponsivenessTest'
./gradlew :app:desktopTest --rerun-tasks --tests '*PublicationCoversTest.concurrentViewportChurnAndInvalidationRespectOwnershipAndTheHardLimit'
./gradlew :app:desktopTest --tests '*HomeRenderWorkTest' --tests '*DiscoveryLayoutFootprintTest'
```

Los logs/raw XML, muestras y PNG sintéticos de esta ejecución están fuera del
repositorio; no se publican binarios ni publicaciones. Las seis pruebas Desktop
nuevas cubren estado, clips internos/fuente grande, unplaced/reentrada, duplicados
visibles, layout/semántica/temas y biblioteca grande. Se ampliaron los gestos y
mediciones del benchmark existente sin quitar aserciones. El stress anterior y
las regresiones comunes Android-host de cache, lifecycle y lectores se conservan.

Verificación final, misma variante que las mediciones, sin desactivar tests:

| Comprobación | Resultado |
| --- | --- |
| `:app:desktopTest` completo | **1.548/1.548**, 0 fallos, 0 errores, 0 omitidos |
| `:app:testAndroidHostTest` completo | **1.297/1.297**, 0 fallos, 0 errores, 0 omitidos |
| `:core:jvmTest` / `:core:testAndroidHostTest` | **59/59 en cada uno**, 0 fallos/errores/omitidos |
| `:androidApp:assembleDebug` | **PASS**, APK debug compilado con JDK 21 |
| `:androidApp:verifyDebugLanguageResources` | **PASS**, recursos en APK comprobados |
| `:desktopApp:compileKotlin` | **PASS** |
| Reports Compose opt-in | Generados para Android/Desktop; no dependencia nueva |
| 24 ejecuciones comparables A/B/C/D | **PASS**, tres JVM por variante y caso |
| Control de regresiones nuevas | Tres fallos esperados con código anterior; final verde |
| Paridad visual de tiles | Cuatro PNG sintéticos antes/después idénticos |

La verificación completa terminó con `BUILD SUCCESSFUL in 10m 12s`. Se forzó la
ejecución de los cuatro tasks de tests para evitar presentar resultados `UP-TO-DATE`
como pruebas nuevas. Los tasks Gradle auxiliares sin fuentes/recursos pueden estar
marcados `SKIPPED`; los XML de tests registran **cero tests omitidos**. Las suites
incluyen EPUB2/EPUB3, ventanas/localizadores, selección/traducción, CBZ, PDF, TEXT,
seguridad, preferencias, Library/History y búsqueda. Se ejecutaron en el host;
no son instrumentación ni gestos en un dispositivo Android real.

Comando de verificación (equivalente con el wrapper del proyecto y JDK21/SDK):

```sh
./gradlew :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:verifyDebugLanguageResources \
  :desktopApp:compileKotlin -Pinfinilect.composeReports=true
```

No se modifican dependencias, manifests, bases de datos, adquisición, firma ni
configuración de release. Los fixtures son originales sintéticos; no se añaden
libros, APK, PNG, thumbnails, reports generados ni credenciales al repositorio.
`git diff --check` y la revisión de artefactos/secretos del árbol versionable pasan.


### Validación física pendiente y cierre

El cierre del realme C55 sigue sin causa confirmada: ninguna de estas pruebas
reproduce un proceso Android ni demuestra solucionar un OOM, un driver o LMKD.
Las nuevas correcciones resuelven patologías identificables del trabajo observado;
no se presenta el cambio como una corrección verificada del crash del teléfono.
Se mantienen los diagnósticos opt-in y comandos de la segunda fase, sin telemetría
ni texto de libros/queries/perfil en logs.

Checklist C55, comparando el mismo tipo de build/configuración:

1. Inicio completamente cargado: deslizar vertical/horizontal, invertir dirección
   y regresar; comprobar contenido estable y si se percibe una mejora reproducible.
2. Caché fría/red lenta: desplazarse mientras llegan recomendaciones; las portadas
   visibles deben poder avanzar sin esperar por una tarjeta abandonada. Verificar
   fallback/reintento existente al fallar fuente o imagen, sin cierres.
3. Alternar cinco pestañas y volver desde lector, confirmando scroll y ausencia de
   recarga de catálogo innecesaria; biblioteca grande y datos intactos.
4. EPUB largo/fuente grande/selección y Original/Traducido; CBZ rápido/RTL/dobles/zoom;
   PDF y TXT; posiciones al cerrar/reabrir y preferencias/idiomas intactos.
5. Repetir al volver de traducción pendiente, sin cargar modelos desde Inicio.
   Observar memoria/cierres cuando sea medible, sin borrar datos ni desinstalar.
6. Cuando haya PC, capturar sesión `infinilect.performance` antes del fallo y guardar
   Logcat/exit-info + trazas caliente/cargando. Sin PC, anotar build, escenario y
   momento del cierre; no inferir su causa de una grabación de pantalla.

**PR #32 sigue Draft, sin merge ni release.** No se promete fluidez del C55 hasta
la comparación física; la prioridad restante es capturar su perfil caliente y el
motivo del cierre.

## PR #33 — Investigación del cierre al abandonar Inicio durante la carga inferior

Informe físico posterior a `8614b9fa344a6059a389c3f71b74027a9f9676d7`:
el usuario ya no observa el cierre al cambiar de pestaña inmediatamente al arrancar.
Sí reproduce un cierre al desplazarse hasta la línea inferior de carga y abandonar
Inicio mientras está activa. Ese informe acota el escenario; no identifica la
excepción ni implica que los dos avisos de Android sean fallos distintos.

### Indicador exacto y cadena de propiedad

`ui/HomeScreen.kt`, item lazy **`discovery-loading`**, dibuja
`LinearProgressIndicator` si `online.loading`. Su tag técnico es
`home-discovery-loading`. La línea junto al aviso legal y bajo los carruseles
representa **el conjunto de solicitudes de descubrimiento de Inicio**, no una
paginación activada por scroll, un archivo, inferencia ni la carga de una portada.
Hay otro indicador de colecciones locales (`loading`) que solo aparece si faltan
las dos secciones locales y están cargándose; no es el indicador de descubrimiento.

Cadena real (rutas bajo `app/src/commonMain/kotlin/org/infinilect/app/`):

1. `HomeScreen` recoge `application.homeDiscovery.state`; su `LaunchedEffect`
   llama a `HomeDiscovery.refresh` cuando las preferencias están preparadas.
2. `discovery/HomeDiscovery.kt::refresh` activa `loading`, conserva las filas
   existentes y lanza un job del scope de sesión. Consulta fuentes generales en un
   lote y después hasta dos géneros reales (intereses/Philosophy/History).
3. `discovery/DiscoveryCatalog.kt::load` comparte solicitudes del mismo key mediante
   borrowers. Conserva dos permisos de red, ocho páginas, 25 entradas/página,
   timeout de 20 s y TTL de cinco minutos. Los proveedores reales son
   `jvmSharedMain/.../archive/InternetArchiveSource.kt::discover` y
   `.../gutenberg/GutenbergSource.kt::discover`; se conserva la separación adquisición/catálogo.
4. Las páginas validadas pasan por `ensureActive` y comprobación de generación antes
   de publicar filas con `MutableStateFlow.update`. El job completa `loading=false`.
   Errores recuperables de fuentes producen el estado de reintento existente.
5. `ApplicationSession.navigate` pausa `homeDiscovery` **antes** de cambiar destino.
   `pause` aumenta generación, cancela el job y desactiva `loading`; el
   `DisposableEffect.onDispose` de Home también lo pausa de forma idempotente.
   No cierra los clientes compartidos ni el repositorio de portadas.
6. `DiscoveryCatalog.load` libera el borrower en `NonCancellable` bajo mutex. El
   último borrower retira/cancela su task; un transporte no cooperativo puede tardar
   en terminar, pero no obtiene permiso para publicar una generación anterior.
7. Independientemente, cada `HomeCover` usa `CoverViewport` y `coverState`.
   Al salir de composición se cancela la presentación y se libera su lease en
   `NonCancellable`. `PublicationCovers` conserva un trabajador y 24 slots; no
   recicla manualmente el bitmap que aún pueda dibujarse. Cambiar pestaña no cierra
   ese cache. No se aumentan concurrencia ni presupuestos en esta intervención.
8. Android retiene `ApplicationSources` en `MainActivity.ReaderRuntime` (ViewModel).
   `onStop` pausa descubrimiento y solicita guardar progreso. Salir de Home no
   destruye dicho runtime. La localización vive por encima de las pantallas y no
   cancela solicitudes ni recrea el lector al actualizar una tabla.

### Reproducción controlada y límites

`desktopTest/.../HomeDepartureTest.kt` monta **App real**, su barra de cinco
pestañas, Home y sus lazy lists. Crea 40 publicaciones locales originales, desplaza
hasta el indicador y comprueba que está realmente en el viewport. No se sustituye
Home por un controlador aislado. Barreras `CompletableDeferred` retienen catálogo
y liberación de portadas; el scheduler controla las intercalaciones, sin esperas de
red ni sleeps para decidir el resultado.

Cuatro tests agrupan 28 recorridos: Biblioteca/Historial/Búsqueda/Ajustes × siete
secuencias (éxito/error antes de salir, éxito/error después, cancelación cooperativa,
salir/regresar con petición anterior y lote de géneros pendiente). Rotan en/es/ca;
se conservan los tests de arranque en Sistema. Incluyen dos cambios rápidos,
portadas lentas/fallidas, cleanup retenido, evicción, duplicados, metadatos escasos,
regreso frío/caliente y comprobación de biblioteca/historial y límites. Los tests
anteriores cubren drags táctiles reales de ambos ejes, fallos de conversión y
visibilidad recortada. Son **shared Compose headless/JVM**, no Android físico.

El mismo fixture de 28 recorridos se ejecutó contra los siete archivos de
presentación/control de `8614b9f` (único añadido: tag del indicador) y también
pasó: cuatro tests, cero fallos. Después se restauró la instrumentación y los
tests específicos de eventos. **No hay una regresión roja/verde que demuestre
corregir el crash.** El control evita atribuir a la instrumentación una solución
que no se ha observado.

**Estado del crash:** no se atribuye a una excepción concreta sin traza del C55.
Los tests host no reproducen un proceso Android, RenderThread, driver GPU,
fallo nativo, ANR ni LMKD. No se cambia la cancelación, el indicador, el contenido,
la navegación ni la arquitectura para ocultar el síntoma. La mejora de diagnóstico
no se presenta como una corrección demostrada del cierre.

### Diagnóstico local sin PC (solo desarrollo, consentimiento explícito)

En Android debuggable, Ajustes ofrece **Diagnóstico de desarrollo → Registrar
diagnóstico local**. Empieza desactivado. Activarlo persiste un marcador privado;
no inicia red, carga de modelos, inferencia ni muestreo de frames. Está ausente de
la interfaz release y Desktop. La propiedad del servicio está en el ViewModel de
runtime, sin retener Activity; rotar no instala múltiples propietarios.

- `diagnostics/TechnicalDiagnostics.kt`: vocabulario cerrado de eventos y contadores,
  sin parámetros de texto libre. Se registra Home visible/eliminado, destinos,
  generación, lotes, cancelación/fallo/completado y etapas de portadas. No se registra
  cada frame, ni ID de publicación/fuente, títulos, autores, consultas, perfil,
  paths, URLs, contenidos o credenciales.
- `jvmSharedMain/.../diagnostics/LocalDiagnosticJournal.kt`: ring de **128 líneas,
  snapshot máximo 40.000 bytes**. Productores añaden solo en memoria bajo sección
  corta; un canal conflado en IO agrupa escrituras cada 250 ms como máximo durante
  actividad. No hay polling continuo, lista creciente ni nuevo cache de imágenes.
  Snapshot temporal + reemplazo atómico (hasta 80.000 bytes transitorios para dos snapshots); entradas inválidas/corruptas se descartan. Dentro del mismo límite se conserva el último stack Java durante el churn del siguiente arranque, hasta otro crash o borrado explícito.
- `androidMain/.../diagnostics/AndroidLocalDiagnostics.kt`: guarda exclusivamente en
  `noBackupFilesDir/local-debug`, privado y excluido de copia de seguridad. Un
  manejador optativo utiliza el formateador ya existente `SanitizedCrashDiagnostic`:
  tres causas × hasta 16 frames, **sin Throwable.message/toString, nombres de hilo
  ni nombres/rutas de archivos**. Hace un flush de emergencia y delega siempre en
  el manejador anterior; no recupera ni oculta la excepción original. No promete
  persistir durante un OOM, SIGKILL o fallo nativo. Un wrapper externo existente no
  se desinstala a la fuerza; desactivado, el sink no registra nada.
- Al reabrir con diagnóstico habilitado, Android 11+ consulta hasta tres registros
  **del propio paquete** mediante `getHistoricalProcessExitReasons`: reason, status,
  PSS/RSS numéricos. No lee description, processName ni traceInputStream. Android
  puede no conservar una causa útil; Android 8–10 no ofrece esta API.
- **Ver y copiar diagnóstico** carga el informe solo al pedirlo. El usuario puede
  copiarlo explícitamente al portapapeles y pegarlo aquí. No se sube ni se comparte
  automáticamente. Pantalla desplazable, controles accesibles y cadenas en/es/ca.
  **Desactivar borra el marcador, el ring y los archivos de diagnóstico**; no toca
  libros, traducciones, modelos, preferencias, biblioteca, historial ni progreso.
- Un error de almacenamiento/permiso de esta función se muestra en Ajustes y no
  intercepta errores de aplicación. Las pruebas del journal cubren persistencia,
  corrupción, límites, IO fallido, concurrencia, desactivación y delegación.

No se activan FrameMetrics ni trazas de rendimiento desde esta opción. El opt-in
ADB de PR #32 se conserva independiente. Su handler y el local encadenan la misma
excepción hasta Android; no instalan un capturador que convierta todos los errores
en éxito.

### Validación física C55 y discriminación del fallo

1. Instalar la APK de esta revisión con la firma existente **sin desinstalar ni
   borrar datos**. Abrir Ajustes, ir al final y activar Registrar diagnóstico local.
2. Regresar a Inicio; si está caliente, cerrar/reabrir la app normalmente o usar el
   reintento existente cuando corresponda. No borrar datos para forzar cargas.
3. Desplazar hasta la línea de descubrimiento, cambiar de pestaña mientras aparece;
   repetir Biblioteca, Historial, Búsqueda y Ajustes, incluyendo regreso inmediato.
4. Si se cierra, reabrir y entrar en Ajustes → Diagnóstico de desarrollo → Ver y
   copiar diagnóstico. Pulsar **Copiar** y pegar el texto junto al nombre de APK y
   el destino pulsado. No enviar documentos, perfiles ni capturas de libros.
5. Si no falla, copiar igualmente un recorrido; repetir con portadas pendientes,
   caché caliente, offline/red lenta y restauración de scroll. No se promete que
   todas esas variantes produzcan el mismo fallo.
6. Desactivar el diagnóstico al terminar. Comprobar lectores EPUB/CBZ/PDF/TXT,
   traducción y progreso, idiomas y Library/History intactos.

Lectura de evidencia: `uncaught class=...` y frames identifican una excepción Java/
Kotlin (sin su mensaje). `exit reason=4` señala crash Java, `5` nativo, `6` ANR,
`3` low-memory; `2` es proceso señalado y necesita más contexto. No convertir un
PSS alto por sí solo en diagnóstico de OOM. La ausencia de handler Java no descarta
ninguna de las otras clases de terminación ni demuestra que la salida fuera normal.

Hipótesis pendientes, ordenadas por utilidad de la próxima evidencia:

| Hipótesis (no diagnóstico) | Evidencia para confirmar/descartar |
| --- | --- |
| Excepción al retirar nodos lazy/viewport o liberar leases | Clase y frames en `uncaught`, eventos HOME_PAUSED/NAV_*/HOME_DISPOSED/COVER_* |
| Excepción de coroutine/callback tras cancelar transporte | Frames de proveedor/catalog/flow y generación REQUEST_CANCELLED/FAILED/STALE_RESPONSE |
| Fallo nativo/RenderThread/GPU | reason 5 o señal + tombstone/Logcat/Perfetto; el journal Java no captura su stack nativo |
| Presión transitoria de memoria/LMKD | reason 3 y PSS/RSS + lmkd/GC/Perfetto; no inferir de un cierre visual |
| ANR o suspensión del sistema | reason 6, trazas de sistema cuando haya PC; no se deduce de pasar tests JVM |

Cuando haya PC, siguen disponibles comandos de la segunda fase, con salida local:

```sh
adb shell am start -W -n org.infinilect.app/.MainActivity --ez infinilect.performance true
adb logcat -v monotonic -s InfinilectPerf:I InfinilectCrash:E InfinilectExit:I '*:S' > infinilect-home-diagnostics.txt
# Tras el cierre, antes de hacer muchas reaperturas:
adb logcat -b crash -d > infinilect-crash.txt
adb shell dumpsys activity exit-info org.infinilect.app > infinilect-exit.txt
adb shell dumpsys meminfo org.infinilect.app > infinilect-memory.txt
```

Los buffers crash/exit-info del sistema pueden contener mensajes/rutas ajenos al
journal sanitizado. Revisarlos localmente y compartir únicamente lo necesario;
el informe copiable desde Ajustes evita esos campos de texto libre.

### Verificación de esta intervención

Matriz completa ejecutada en Linux x86_64, JDK 21.0.12.1 / Gradle 9.7.1 / SDK 37,
el 20/10/2026, sin desactivar tests ni reutilizar resultados de los cuatro tasks:

| Comprobación | Resultado |
| --- | --- |
| App Desktop completo | **1.581/1.581**, cero fallos/errores/omisiones |
| App Android-host completo | **1.314/1.314**, cero fallos/errores/omisiones |
| Core JVM / Core Android-host | **59/59 + 59/59**, cero fallos/errores/omisiones |
| Total | **3.013 pruebas aprobadas** |
| Android debug / compilación Desktop | **PASS** |
| Recursos dentro del APK | **PASS**, seis CVR en/es/ca comparados con los generados |
| Guardia de localización | **410 cadenas + 2 plurales** por idioma, 173 archivos Kotlin |
| `git diff --check`, higiene y dependencias | **PASS**, sin binarios/secretos ni cambios de Gradle/manifest/firma |
| Control de navegación contra `8614b9f` | Cuatro tests / **28 recorridos**, sin fallo reproducido |

La matriz completa terminó `BUILD SUCCESSFUL in 13m 41s`; es duración del trabajo
host, **no rendimiento del teléfono**. Las pruebas UI usan Skia software y las
pruebas de gestos CBZ originales se conservan. En el primer intento el harness no
pudo crear `/home/agent/.skiko` por el filesystem de solo lectura; se configuró
externamente `skiko.library.path` hacia un cache escribible con el **mismo binario
verificado contra el JAR fijado**. Ese fallo de entorno no es el crash C55.

Se añaden cuatro tests de App real (28 secuencias), dos tests comunes de eventos/
cancelación/reintento y siete de journal, ejecutados donde corresponde. El ajuste
final que conserva el último stack durante 1.000 eventos posteriores de portadas
y la resolución del directorio de diagnóstico solo en IO se comprueban de nuevo
con las pruebas enfocadas y ambas compilaciones. Los XML/logs y APK permanecen
fuera del repositorio; los fixtures son originales sintéticos.

Verificación enfocada final: **13/13 Desktop + 9/9 Android-host**, cero fallos/errores/omisiones;
Android debug, recursos empaquetados y Desktop recompilados correctamente
(`BUILD SUCCESSFUL in 3m 34s`). Incluye retención del último stack después de
1.000 eventos y su reemplazo por otro crash sintético, no por una terminación real.

Comando de la matriz (con JDK21/SDK y cache Skiko escribible):

```sh
./gradlew :core:jvmTest :core:testAndroidHostTest \
  :app:desktopTest :app:testAndroidHostTest \
  :androidApp:assembleDebug :androidApp:verifyDebugLanguageResources \
  :desktopApp:compileKotlin
```

No se presenta un APK compilado, una excepción sintética del journal o un test
host como una validación física del C55. **La PR #33 permanece Draft y bloqueada
para fusión hasta que el usuario vuelva a comprobar el escenario.**

## PR #33 — Traza del C55: cancelación de Gutenberg

**Seguimiento histórico de `55d694d` → `f8cbaef`.** La traza posterior de `f8cbaef`
y la corrección del adaptador de streams se documentan en el apartado siguiente;
la transferencia a IO por sí sola no resolvió el crash físico.

### Evidencia recibida y lo que todavía falta

Tras instalar `55d694d`, el usuario copió el journal del realme C55. El informe
contiene `HOME_PAUSED` → `REQUEST_CANCELLED` y una excepción no capturada:

```text
uncaught depth=0 class=kotlinx.coroutines.CompletionHandlerException
at org.infinilect.app.gutenberg.GutenbergSource.operation:55
uncaught depth=1 class=kotlinx.coroutines.CompletionHandlerException
at io.ktor.client.engine.UtilsKt$attachToUserJob$cleanupHandler$1.invoke:110
uncaught depth=2 class=kotlinx.coroutines.CompletionHandlerException
```

En el siguiente arranque aparecen salidas `reason=4` (crash Java/Kotlin). Otros
registros `reason=10` indican salida solicitada por usuario y no prueban un segundo
bug. El mismo informe termina con otro recorrido que sí llega a `NAV_SETTINGS` y
`HOME_DISPOSED`: el fallo depende de la intercalación; no falla toda cancelación.

**Confirmado físicamente:** la ruta de fallo incluye cancelación/limpieza de Ktor
originada en `GutenbergSource.operation`, antes de terminar la navegación. No es ya
una atribución genérica al scroll. **No confirmado:** la excepción terminal debajo
de las tres `CompletionHandlerException`, porque el primer formateador truncaba
la cadena a tres causas. No se afirma haber observado `NetworkOnMainThreadException`
en el teléfono ni una causa nativa/OOM específica.

### Defecto reproducible de afinidad de hilo

Se contrastaron los sources oficiales del **mismo Ktor 3.6.0 que utiliza la app**:

- [ktor-client-android-jvm 3.6.0 sources](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-android-jvm/3.6.0/ktor-client-android-jvm-3.6.0-sources.jar),
  `AndroidClientEngine.kt::execute` y
  `AndroidURLConnectionUtils.kt::disconnectOnCancellation`.
- [ktor-client-core-jvm 3.6.0 sources](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-core-jvm/3.6.0/ktor-client-core-jvm-3.6.0-sources.jar),
  `engine/Utils.kt::attachToUserJob`.

El motor Android registra `disconnect()` en un handler de cancelación síncrono.
Cambiar el dispatcher del motor no basta: el callback puede ejecutarse en el hilo
que invoca `Job.cancel`, aunque el HTTP se ejecutase en IO. En `55d694d`, la
operación Gutenberg heredaba el dispatcher del llamante y su `finally` hacía
`request.cancel()` directamente; la salida desde Compose puede iniciar esto en
Main. `close()` también cancelaba el lifetime y cerraba el transporte en el hilo
llamante. El cierre de una conexión puede efectuar IO. Ese es un defecto real de
hilo, compatible con la traza recibida, aun sin conocer su última causa física.

El camino de producción conserva ese contexto: `ApplicationSources.applicationSession`
crea el scope en `Dispatchers.Main.immediate` por defecto (Android no lo sustituye);
`ApplicationSession` y `DiscoveryCatalog` heredan el dispatcher. La liberación del
último borrower cancela su deferred; en la implementación anterior el `finally`
de Gutenberg podía ejecutar entonces la desconexión síncrona en Main. Los scopes
y límites del catálogo no necesitan cambiar para corregir esa frontera de la fuente.

### Regresión roja/verde con el motor Android real

`androidHostTest/.../gutenberg/GutenbergAndroidCancellationTest.kt` usa el motor
Android **real**, sus jobs y sus handlers Ktor reales. La única parte sintética es
la `HttpsURLConnection`: se mantiene pendiente mediante barreras y su guard falla
si se desconecta en el hilo UI equivalente del test. Es un test JVM/host, **no
StrictMode Android físico**. No utiliza `MockEngine` para esta ruta.

El transporte del fixture reescribe internamente la petición hacia un protocolo
JVM de prueba único, con una URLConnection original. Usa APIs públicas de Java;
no registra handlers para HTTP/HTTPS ni hace solicitudes de Internet. La reescritura
vive exclusivamente en test, después de la construcción normal de URLs de la fuente;
ninguna política de URL/TLS de producción se modifica.

Antes del cambio de producción:

- Cancelar una petición activa desde UI: **FAIL**, la conexión se desconecta en UI.
- Cerrar la fuente con petición activa desde UI: **FAIL**, mismo defecto.

Después del cambio se conservan esas dos aserciones y se añaden:

- Reintento explícito con Root OPDS2 + Search válidos tras cancelar.
- Desconexión retenida: una tarea UI sigue respondiendo **antes** de liberar el
  cierre; el job cancelado espera su cleanup sin bloquear navegación.
- `ApplicationSession`/`HomeDiscovery` reales: cuatro destinos y regreso con retry,
  resultados anteriores descartados, sin excepciones de completion handlers.
- Cancelación de una miniatura realmente anunciada por el catálogo, sin decodificar
  un JPEG ni adquirir libros para preparar el test.

Los tests de App/scroll de la fase anterior usaban fuentes sintéticas y no
recorrían esta implementación del transporte Android. Se conservan íntegros;
los nuevos tests cubren ese hueco concreto. El guard sintético reproduce el defecto
de hilo, pero no demuestra que su clase sea la última excepción del C55.

### Corrección limitada a la fuente Gutenberg

En `jvmSharedMain/.../gutenberg/GutenbergSource.kt`:

1. El child de cada operación conserva propiedad del lifetime de la fuente, pero
   ejecuta su control HTTP y sus fallos en `Dispatchers.IO`, no en Main heredado.
2. Si el llamante deja de esperar y la tarea no ha terminado, su `finally` cancela
   **y une** la tarea en `NonCancellable + Dispatchers.IO`. El resultado no puede
   regresar a una generación de Home abandonada; se mantienen sus fences.
3. `close()` rechaza nuevas operaciones inmediatamente con el CAS existente y
   vacía sus referencias de miniaturas. Programa **un solo** cleanup independiente
   en IO: cancelar lifetime, cerrar cliente/motor, unir los hijos. `awaitClosed`
   permite verificar terminación y propagar errores en tests. No se tragan errores
   fatales; no se convierte un fallo de cleanup en éxito.

No hay espera fija, navegación deshabilitada, ocultación del indicador, reintento
silencioso ni sustitución del proveedor. Se conservan el mutex del catálogo, el
permiso único de miniaturas, límites de respuesta, timeouts, OPDS2, validación de
URLs, ausencia de redirects y restricciones catalog-only. No cambian TLS, trust,
permisos, firma, ID, adquisición, lectores, modelos, localización ni límites de caché.
Esta corrección no se presenta como una auditoría de todos los motores/fuentes;
si una nueva traza señala otra ruta, se investigará con esa evidencia.
El cleanup sigue esperando la liberación del transporte, pero en IO. Al regresar,
una petición nueva puede esperar el mutex mientras termina la anterior; no se
elimina esa serialización ni se crea una cola adicional para aparentar rapidez.

### La causa profunda ya no queda oculta por wrappers

`SanitizedCrashDiagnostic` ahora inspecciona hasta **12 causas**, deteniéndose en
ciclos de identidad. Mantiene **el máximo anterior de 51 líneas**: hasta ocho
frames de la primera causa, dos por wrapper intermedio y hasta 16 para la última
causa disponible, ajustados al presupuesto restante. El journal v1 acepta los
nuevos índices 0–11 sin migrar datos ni crear otro almacenamiento.

No se añaden mensajes de Throwable, filenames/rutas, nombres de hilo, texto de
libros, búsquedas o datos del perfil. Continúa delegando el mismo error al handler
anterior incluso si falla la salida de diagnóstico. Tests nuevos fallan con el
formateador anterior (raíz profunda perdida y ciclo repetido), y comprueban la raíz,
privacidad, persistencia después de reabrir y los mismos límites 51/128/40.000.

### Verificación y aceptación física

| Comprobación | Resultado actual |
| --- | --- |
| Desktop completo | 1.584 / 1.584 |
| Android-host completo | 1.322 / 1.322 |
| Core JVM / Core Android-host | 59 / 59 + 59 / 59 |
| Total | **3.024 aprobadas, cero fallos, errores u omisiones** |
| Enfocados Gutenberg + diagnósticos | 48 / 48 |
| Enfocados finales catálogo + motor Android real | 22 / 22 (cinco compartidos con el grupo anterior) |
| Android debug / Desktop / recursos empaquetados | PASS |

Ejecución forzada de las cuatro suites, Linux/JDK21/Gradle9.7.1/SDK37, 20/10/2026:
`BUILD SUCCESSFUL in 10m 25s`. El harness usa el mismo binario Skiko verificado
en caché externa para evitar el home de solo lectura; no cambia el runtime de la app.
Son pruebas JVM/host/headless, no StrictMode ni mediciones de fotogramas en Android.

Tasks ejecutados:

```text
:core:jvmTest :core:testAndroidHostTest
:app:desktopTest :app:testAndroidHostTest
:androidApp:assembleDebug :androidApp:verifyDebugLanguageResources
:desktopApp:compileKotlin
```

El primer pase completo reveló una prueba que mezclaba un reloj virtual de
`HttpTimeout` con el worker IO real. Se corrige su planificación con dispatcher real
y barreras; mantiene la comprobación de metadata independiente y añade comprobar
que la portada sigue pendiente hasta después de completar el catálogo. No se
cambian timeouts de producción ni se desactiva ninguna aserción. La suite completa
se repitió después del ajuste. Recursos: 410 cadenas y dos plurales en cada idioma;
los seis CVR empaquetados se verifican contra los generados. `git diff --check` e
higiene del diff: PASS; sin dependencias, claves, firmas o binarios versionados.


Checklist C55:

1. Instalar la APK firmada de esta revisión sin desinstalar ni borrar datos.
2. En Ajustes, **desactivar y volver a activar Registrar diagnóstico local** para
   limpiar el stack anterior. El journal conserva intencionadamente el último
   crash; sin este paso podría confundirse la traza antigua con una nueva.
3. Inicio → final → línea activa → cada una de las cuatro pestañas; repetir diez
   veces con regreso inmediato, caché caliente/fría y red lenta/offline cuando sea
   posible. La navegación y la carga deben continuar disponibles.
4. Si vuelve a cerrarse, reabrir → Ver y copiar diagnóstico → Copiar y pegar aquí,
   incluyendo el nombre de APK y destino. La nueva cadena permite ver más allá
   de los tres wrappers iniciales. No garantiza stack nativo ni diagnóstico OOM.
5. Revisar fuentes IA/Gutenberg, búsqueda/retry, portadas, biblioteca/historial y
   lectores/progreso/traducción. Desactivar el diagnóstico después.

**Hay una corrección de hilo reproducida por regresión; la última causa del crash
físico y la aceptación del C55 siguen pendientes. PR #33 permanece Draft, sin merge
ni release.**

## PR #33 — Causa profunda: read/close del stream Android

### Lo que demuestra el nuevo journal

El usuario reprodujo el cierre con la APK `f8cbaef`. Dos salidas a Historial
completan; en la tercera, `HOME_PAUSED` → `REQUEST_CANCELLED` → `NAV_HISTORY`
precede el crash. Ahora el formateador conserva una cuarta causa:

```text
uncaught depth=0 class=kotlinx.coroutines.CompletionHandlerException
at kotlinx.coroutines.JobKt__JobKt.cancelAndJoin:510
uncaught depth=3 class=java.lang.IllegalStateException
at com.android.okhttp.okio.AsyncTimeout.enter:64
at com.android.okhttp.okio.AsyncTimeout$2.read:211
at com.android.okhttp.internal.http.Http1xStream$FixedLengthSource.read:395
at com.android.okhttp.internal.Util.discard:147
at com.android.okhttp.internal.http.Http1xStream$FixedLengthSource.close:412
at kotlinx.io.InputStreamSource.close:93
at io.ktor.utils.io.jvm.javaio.RawSourceChannel.closeSource:114
at kotlinx.coroutines.InvokeOnCancelling.invoke:1571
```

El arranque posterior vuelve a incluir `reason=4`. **Está confirmada físicamente
la excepción Java/Kotlin del cierre del body**, no un diagnóstico de OOM, ANR o GPU.
El mensaje de `IllegalStateException` no se registra por privacidad: no se inventa
su texto ni se deduce la versión exacta del Android del C55 a partir de la línea.
El aviso de Android tras varios cierres no requiere atribuir otro fallo diferente.

La corrección anterior mueve control/cancelación a IO, pero **IO no serializa la
lectura y el cierre** de un mismo stream. El nuevo registro demuestra que esa
corrección fue insuficiente para este crash; se conserva porque evita cleanup en UI.

### Cadena de lectura/cierre y reproducción

En el mismo [Ktor IO 3.6.0 publicado](https://repo.maven.apache.org/maven2/io/ktor/ktor-io-jvm/3.6.0/ktor-io-jvm-3.6.0-sources.jar),
`jvm/javaio/Reading.kt::RawSourceChannel`:

1. `awaitContent` ejecuta `source.readAtMostTo` en el contexto IO.
2. El job registra un completion handler `onCancelling = true` que llama a
   `closeSource`; este cambia el token y llama a `source.close()` directamente.
3. No espera a que termine la lectura bloqueante. El cierre puede entrar en el
   mismo stream mientras se está leyendo.
4. La traza física muestra que el stream Android intenta descartar el body durante
   `FixedLengthSource.close`: esa operación vuelve a leer y llega a `AsyncTimeout.enter`.
   El error de cierre sale del callback y se envuelve en `CompletionHandlerException`.

`GutenbergAndroidCancellationTest.cancellingDuringAResponseReadMustNotPropagateConcurrentCloseFailures`
utiliza la factoría **real** `platformHttpEngine`, Gutenberg y los jobs reales de
Ktor. Su InputStream sintético mantiene `read` pendiente por barrera y lanza una
`IllegalStateException` si `close` entra durante esa lectura. Con el engine anterior
falla determinísticamente: **uncaught CompletionHandlerException**. El transporte
HTTPS del fixture legado es el mismo protocolo Java de test, sin Internet; para
el nuevo engine se usa un interceptor público y un ResponseBody propio. No son
streams del framework Android: la traza física aporta esa parte de la evidencia.

Tras el cambio conserva la aserción de cero errores no capturados y añade cero
cierres concurrentes, cierre final y UI disponible antes de liberar la lectura.
La barrera del nuevo engine observa `Call.cancel` por `EventListener`; la del
legado observa el cierre prematuro que provoca el guard. El test no presupone que
ambos transports deban cerrar el body en el mismo instante. No se sustituye la
excepción física por una simulación presentada como verificación de dispositivo.

### Corrección del adaptador Android

Se reemplaza **solo** `app/androidMain/.../network/PlatformHttpEngine.kt` por el
adaptador `ktor-client-okhttp:3.6.0`. No se cambia de versión Ktor, proveedor,
endpoint, OPDS2 ni motor de lectura. La factoría expect/actual y todos los clientes,
validadores y repositorios compartidos siguen siendo los mismos. Desktop mantiene
`ktor-client-java`. `ktor-client-android` queda solo en Android-host para conservar
las cinco regresiones previas del transporte y la protección del hilo IO.

El [engine OkHttp 3.6.0 publicado](https://repo.maven.apache.org/maven2/io/ktor/ktor-client-okhttp-jvm/3.6.0/ktor-client-okhttp-jvm-3.6.0-sources.jar),
`OkHttpEngine.executeHttpRequest`/`BufferedSource.toChannel`, utiliza una llamada
cancelable y un writer propietario del body. La limpieza del body espera la
terminación del job de llamada y se ejecuta en el dispatcher del engine; no utiliza
el `RawSourceChannel` del adaptador InputStream Android. Se usa esa implementación
mantenida en lugar de copiar/parchear clases privadas de Ktor o del framework.

No se añade un `catch` de aplicación para esconder `IllegalStateException` o
`CompletionHandlerException`; el handler original y el diagnóstico siguen intactos.
Los errores HTTP, validación, TLS o lectura real mantienen sus contratos de error.
No se bloquea navegación, oculta la línea ni desactiva la carga.

Configuración explícita y límites:

- Dos llamadas simultáneas por engine/host, con el mismo dispatcher y pool entre
  variantes de timeout. Dos variantes de cliente en caché; dos conexiones idle.
- Connect 5 s y read/write 15 s por defecto; los timeouts de petición/miniaturas
  existentes se aplican por el plugin Ktor. No se aumentan presupuestos de imagen.
- Redirects HTTP/HTTPS deshabilitados también en engine; sin cookies o caché de
  respuestas en disco, sin recuperación automática de fallos de conexión.
- Trust store y comprobación de hostname por defecto del Android/OkHttp; ningún
  trust manager, hostname verifier permisivo, CA propia o excepción HTTP se añade.
- Los follow-ups HTTP de OkHttp no se describen como inexistentes: un 503 con
  `Retry-After: 0` puede producir un retry solicitado por el proveedor; 421 puede
  reencaminar una conexión HTTP/2 coalescida. No hay bucle de retry de la app.
  Permanecen gates/deadlines y las mismas URLs/derechos de adquisición.

Hay un coste de buffering distinto: el writer usa el `ByteChannel` estándar de Ktor
3.6.0, cuyo umbral `CHANNEL_MAX_SIZE` es 1 MiB. Es un umbral de backpressure,
**no una cota de RAM total**: hay buffers de lectura/escritura y del socket además
de los bytes validados de la fuente. Puede adelantarse a la validación del body;
no se presenta como tráfico nativo cero en un rechazo por headers. Los límites de
bytes consumidos/aceptados de cada fuente y la validación antes de adquisición no
cambian. No se modifica el umbral interno ni se añaden caches de libros/portadas o respuestas
en disco: se acepta este coste del adaptador mantenido para corregir el cierre, y memoria/arranque deben revisarse
en el C55. No se ha medido una reducción de RAM ni se aumenta el número de workers de portadas.

### Dependencias y empaquetado

El grafo Android real resuelve **OkHttp Android 5.5.0** y **Okio JVM 3.18.2**.
Los POMs y licencias de los tres componentes declaran Apache-2.0, compatible con
GPL-3.0-or-later; se conservan atribuciones/texto completo en un asset del APK.
Véanse [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md) y
[ANDROID_DEPENDENCIES](ANDROID_DEPENDENCIES.md), con versiones/fuentes primarias.

El asset **PublicSuffixDatabase.list** de OkHttp es un dato separado bajo **MPL-2.0**.
Se compara byte a byte con el tag `parent-5.5.0`: SHA-256
`c75ab827afd7ffff59a51b14729c25dbfc3b45237eb40ccf02bec7b7d44304a4`.
Su fuente editable del mismo tag se recompone (9.764 reglas y ocho excepciones)
y produce exactamente el mismo binario/hash. Su NOTICE original, URL de esa fuente
y texto MPL completo se incluyen en el asset de licencias; no se supone que Apache cubra este dato. No es
un peso/modelo de traducción ni se actualiza el dataset en esta corrección.

El AAR real declara minSdk 21 (la app sigue en 26), INTERNET ya existente y metadata
para el InitializationProvider **ya presente**. Su initializer selecciona el
platform Android y retiene contexto de aplicación: no solicita red, carga modelos,
traduce o descarga. No se elimina sin sustituir su contrato público. Su coste de
arranque en el C55 no se ha medido. Sin firma/ID/permisos nuevos, binarios nativos,
modelos o contenido de publicaciones. El coste en APK se registra después de construir;
no se afirma una reducción de RAM ni una mejora de FPS por cambiar de transporte.

### Pruebas y aceptación

Enfocados: **24/24**, cero fallos/errores/omisiones. Incluyen las cinco regresiones
previas del engine legado, la nueva carrera de lectura/cierre, **12 recorridos**
(tres repeticiones × cuatro destinos) con HomeDiscovery/ApplicationSession reales
y retorno/retry; cierra recursos, descarta generaciones anteriores y deja UI libre.
Las pruebas Compose/App/scroll de la fase anterior se conservan en la suite completa.

`AndroidHttpEngineTest` comprueba la configuración y la propiedad del dispatcher/pool
entre tres variantes de timeout, el cierre de ejecutores y ausencia de llamadas o
conexiones retenidas. Dos targets de redirect (HTTP/HTTPS) usan sockets loopback
reales. Otra prueba deja headers/body reales pendientes y cancela desde UI: termina
antes de dejar responder al servidor. No hay dependencias de mock webserver,
servicios externos, publicaciones descargadas ni certificados/trust alterados.

| Comprobación | Resultado |
| --- | --- |
| Desktop completo | 1.584 / 1.584 |
| Android-host completo | 1.327 / 1.327 |
| Core JVM / Core Android-host | 59 / 59 + 59 / 59 |
| Total | **3.029 aprobadas, cero fallos/errores/omisiones** |
| Android debug / Desktop / seis CVR empaquetados | PASS |
| Artefacto Android | Aviso/licencia incluido; engine legado y fixtures ausentes; mismos permisos |

Las cuatro suites se fuerzan a ejecutar, Linux/JDK21/Gradle9.7.1/SDK37, 20/10/2026.
El primer comando (9m 8s) aprueba Desktop y ambos Core, y detecta una
aserción Android que exigía la clase del engine retirado. `AndroidSourcesTest`
se actualiza al nuevo engine observando su builder real por API pública: conserva
connect 5 s/read 15 s y añade write 15 s y redirects deshabilitados. No se elimina
la prueba ni se cambian los límites. Se repite **Android-host completo** junto a
ambas compilaciones: `BUILD SUCCESSFUL in 55s`. La única modificación
posterior al pase Desktop/Core es ese test Android-host, que no forma parte de sus
source sets; no se presentan sus resultados como una repetición inexistente.
Se usa el mismo Skiko verificado en caché del harness, sin cambiar la configuración
de tests del repositorio ni omitir/debilitar pruebas. Primer comando:

```text
:core:jvmTest :core:testAndroidHostTest
:app:desktopTest :app:testAndroidHostTest
:androidApp:assembleDebug :androidApp:verifyDebugLanguageResources
:desktopApp:compileKotlin
```

Comando final (Desktop/Core ya aprobados con esta misma producción):

```text
:app:testAndroidHostTest :androidApp:assembleDebug
:androidApp:verifyDebugLanguageResources :desktopApp:compileKotlin
```

El nuevo APK Gradle debug ocupa **20,685,253 bytes** antes de volver a
firmarlo externamente con el certificado de desarrollo del usuario. El tamaño y
SHA-256 del APK entregado constan en la PR. Son bytes de artefacto, no métricas RAM,
CPU o FPS del teléfono. Tras añadir el NOTICE/fuente/MPL de la Public Suffix List,
se rehace solo el empaquetado (`androidApp:clean`, `assembleDebug`,
`verifyDebugLanguageResources`), PASS en 38 s: no hay cambios Kotlin/Gradle posteriores
a las suites. El ZIP payload pasa de 19,918,319 a 20,617,524 bytes frente a `f8cbaef`;
no se atribuyen a dependencias los huecos del empaquetado incremental descartado.
La licencia empaquetada se compara con el asset; seis CVR
con los generados; el grafo y DEX excluyen el engine Android legado del runtime.
Se conservan INTERNET y demás permisos existentes; sigue eliminada la inicialización
EmojiCompat y se incluye la metadata requerida de OkHttp en el provider existente.
Recursos 410 cadenas/dos plurales por idioma y `git diff --check`: PASS. Sin firmas,
claves, binarios de build o publicaciones añadidos al repositorio.

Las pruebas Compose siguen siendo headless y las de sockets son loopback JVM;
no son una ejecución de OkHttp/Conscrypt en el C55. No se mide ni garantiza fluidez,
arranque o consumo de memoria físicos. **Falta la aceptación de la nueva APK.**


Checklist físico, con la APK de esta revisión y sin borrar datos:

1. Desactivar y reactivar diagnóstico local para limpiar el stack de `f8cbaef`.
2. Inicio → final → línea activa → Historial, repetir diez veces; repetir para
   Biblioteca/Búsqueda/Ajustes y regreso inmediato, con portadas frías/calientes.
3. Comprobar red lenta/offline/recuperación, búsqueda IA/Gutenberg y retry; la línea
   y navegación deben seguir funcionando. Verificar scroll y arranque por si hay
   regresión de rendimiento, sin asumir mejoras de fotogramas no medidas.
4. Revisar biblioteca, historial, lectores/progreso y traducción/modelos existentes.
5. Si vuelve a cerrarse: reabrir, copiar el diagnóstico y enviar con nombre de APK.
   El registro conserva las 12 causas/51 líneas; no captura todo crash nativo/OOM.
6. Desactivar diagnóstico al acabar. PR #33 permanece Draft, sin merge ni release;
   **la corrección del transporte queda pendiente de aceptación física del C55**.
