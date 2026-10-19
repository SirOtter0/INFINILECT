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
