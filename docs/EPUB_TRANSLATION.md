# Local EPUB translation

PR #31 continues the original audit at `9b516669ed4b201a6b79f79c17dfae3be6204dc2`.
Base: `b08379c6a5072f7114685e4a4c9444e60edcdcc9` (merged PR #30).
Status: **user-reported translation/model-management success on realme C55; the latest APK exposed selection and Android resource-loading defects. This correction requires physical re-acceptance.** Desktop original reading remains
fully operational; its translation engine is explicitly unavailable. Test providers
are used only for deterministic shared/UI tests, never as native-engine evidence.

**UI localization update (PR #33):** the historical PR #31 scope below is superseded
by [LOCALIZATION_COMPLETION.md](LOCALIZATION_COMPLETION.md). Application screens,
reader controls and errors now use the shared en/es/ca resources. This does not
translate publication metadata or change the engine, models, storage or progress.

## PR #33 physical-feedback follow-up: Spanish/Catalan pairs

The four validated app descriptors are unchanged from the merged PR #31:
`en→es`, `es→en`, `en→ca`, `ca→en`. They are now testable shared **metadata** in
`VerifiedTranslationModels.kt`; the Android adapter uses the same values and its
existing descriptor, checksum, storage and native-lifetime validation. This does
not enable translation on Desktop.

The pinned Foxlet registry **and the checksummed 0.5.0 AAR's actual `models.tsv`**
were inspected: each has no direct `es→ca` or `ca→es` entry. The SDK bundles 106
other/direct directions in total; only the four app-validated directions are
advertised by INFINILECT. No Spanish/Catalan model disappeared in PR #33, and UI
locale does not filter the model list. The previous English-bridge prototype
below was explicitly not integrated. A bridge requires two model stages and
separate memory/quality/progress/cache validation; it is not a missing direct
model and is not silently enabled by this patch.

Settings now explains that limitation in en/es/ca; unsupported pairs keep Original
available and offer no invented download. Independent pinned registry excerpts
in `jvmSharedTest/resources/translation/foxlet-registry-supported.json` test sizes,
versions, directions, allowed HTTPS hosts and hashes in the SDK's filename order.
The factual metadata fixture attributes the MPL-2.0 source; existing license texts
remain in `third-party/foxlet-LICENSE.txt` and
`third-party/mozilla-translation-models-LICENSE.txt`. No weights or dependencies
were added. See [the physical-feedback report](LOCALIZATION_COMPLETION.md#correcciones-posteriores-a-pruebas-físicas--realme-c55)
for the startup limitation, UI/admin tests and final build results.

## Provider decision and licensing

Choose **Foxlet 0.5.0**, an Android adaptation of Mozilla's Bergamot/Marian engine.
Pin upstream source `0eff2a15032362115848a496ce1df8ed9fc68650` and AAR SHA-256
`5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e`.
The generated SDK is downloaded/verified by `prepareEpubTranslationSdk` under
`app/build/translation-sdk/`; no binary or model is tracked. Offline builds can
supply the exact verified AAR using `-Pinfinilect.foxletSdk=/absolute/path/to/foxlet-v0.5.0.aar`.
A checksum mismatch is a build error, not an accepted alternative SDK.

| Candidate | License and model boundary | Verified platform/maintenance findings | Decision |
| --- | --- | --- | --- |
| Original browsermt Bergamot | Engine MPL-2.0; Marian MIT; models separately licensed | Inspected head `9271618ebbdc5d21ac4dc4df9e72beb7ce644774`, last upstream push May 2024. Native/WASM API, not an Android Kotlin SDK. Current Mozilla development lives in `mozilla/translations/inference`. | Use the maintained engine adaptation, not assume Firefox's library is directly Android-ready. |
| Foxlet 0.5.0 | Original wrapper MIT; Bergamot MPL-2.0; Marian MIT; SentencePiece/ruy Apache-2.0; BSD/MIT/zlib/Unicode-3.0 and LLVM runtime licenses retained in its notices | Release September 2026; project is young. ARM64 only, SDK min API 28, explicit Kotlin/JNI lifecycle/model management. Built Android ARM64 and Linux ARM from pinned source; real models translated under QEMU. | Selected, with four pinned direct language pairs and guarded Android availability. |
| CTranslate2 | Engine MIT, CPU dependencies include Apache-2.0 ruy/cpu_features and MIT spdlog/cxxopts; engine does not license weights. Helsinki-NLP OPUS-MT en-es model card separately declares Apache-2.0. | Inspected head `8d91aa08225004755360daa43deb62b35b1ed65d`, active October 2026; official Linux x86/ARM64 and Windows x86 distributions. Android requires native cross-build, tokenizer/model conversion and Kotlin bindings; no such adapter was verified here. Default MKL/GPU binary redistribution was not audited or selected. | Viable future Desktop investigation, not silently substituted for this Android implementation. No CT2 model is bundled or used. |
| Android framework TranslationManager | Public system API; installed provider/model terms are independent | API 31+, advertised provider capabilities required. Not universally present on supported Android devices; no Desktop equivalent. | Not a universal fallback; not integrated. |
| ML Kit | SDK/models under its own Terms of Service, not the samples' Apache license | Prior audit preserved below. Current GPL distribution compatibility not established. | Not integrated; no linking exception or license change. |

The pinned Foxlet component index and complete bundled notices were inspected.
The selected MPL-covered code has no separate Exhibit B declaration marking it
incompatible with secondary licenses. MPL 2.0 section 3.3 permits combination with
GPL larger works subject to its source/notice requirements. INFINILECT stays
GPL-3.0-or-later; upstream files are not relicensed. The SDK packages complete
copyright/license texts, NOTICE and corresponding-source offer in Java resources,
which must remain in distributed APKs. No paid API, key or proprietary ML Kit
binary is included.

**Models are separately licensed.** Mozilla's model repository carries MPL-2.0;
a Mozilla collaborator explicitly confirms that current released weights use
MPL-2.0 in [issue 1434](https://github.com/mozilla/translations/issues/1434#issuecomment-4734030816).
Remote Settings records themselves currently lack a license field. This decision
relies on that primary statement and the model repository license, not merely on
free downloads or the wrapper's MIT license. Future models/providers require a
fresh distribution/license check; this is not a universal legal guarantee.
Preserve model attribution, source and MPL text when redistributing model files.

Primary sources:

- [Bergamot](https://github.com/browsermt/bergamot-translator), [MPL](https://github.com/browsermt/bergamot-translator/blob/9271618ebbdc5d21ac4dc4df9e72beb7ce644774/LICENSE)
- [Foxlet pinned source](https://github.com/yinvoke/foxlet-translate/tree/0eff2a15032362115848a496ce1df8ed9fc68650), [MIT](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/LICENSE), [NOTICE](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/NOTICE), [component license index](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/licenses/components.json), [vendor provenance](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/engine/UPSTREAM.md)
- [Foxlet release/checksums/source offer](https://github.com/yinvoke/foxlet-translate/releases/tag/v0.5.0)
- [Mozilla model license](https://github.com/mozilla/firefox-translations-models/blob/main/LICENSE), [pinned model registry](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/registry.json)
- [MPL larger works/secondary licenses](https://www.mozilla.org/MPL/2.0/)
- [CTranslate2 MIT](https://github.com/OpenNMT/CTranslate2/blob/8d91aa08225004755360daa43deb62b35b1ed65d/LICENSE), [installation/platforms](https://opennmt.net/CTranslate2/installation.html), [OPUS-MT model card](https://huggingface.co/Helsinki-NLP/opus-mt-en-es)
- [Android TranslationManager](https://developer.android.com/reference/android/view/translation/TranslationManager)

## Implemented architecture

`ApplicationSources` owns one `EpubTranslationService`, private result cache and
Android engine. Reader/presentation disposal cancels its own projection jobs but
does not close the retained application engine. Final application close rejects
new work, drains native work, releases models and closes the SDK. Shared contracts
contain no Context/JNI/engine types; another engine implements `EpubTranslationEngine`
and is supplied by the platform factory without rewriting the reader.

`OpenPublicationController` passes the source-authorized resource byte revision
into the EPUB controller. Import SHA-256 revisions are reused; titles/OPF IDs are
never content fingerprints. Unknown revisions bypass persistent result reuse.
`EpubTranslationController` owns one replaceable request, current semantic
projection and selection panel state. Publication, passage, language, Original,
Cancel and close retire stale jobs. A noninterruptible submitted native batch
finishes before the next can own the single inference permit; Kotlin cancellation
is **not** advertised as immediate native interruption. Its obsolete result cannot
publish, save progress or populate the cache.

The original `EpubChapter` stays authoritative. Translation builds an independent
list of projected runs, preserving original blocks, heading/list/quote kinds,
strong/emphasis flags, image references, owned internal-link targets, notes and
reading order. It translates plain text only (`TextFormat.Plain`), never XHTML,
IDs, resource paths or attributes. Explicit run provenance excludes synthetic
image messages and nested code/kbd/samp text; the private session semantic codec
retains this flag. Preformatted content and literal-URL runs stay original.
This deliberately translates each styled run independently: sentence context and
word reordering across formatting boundaries can reduce quality. It does not
promise exact typographic or word-alignment fidelity. Unsupported/oversized or
failed projection keeps Original accessible, with a clear pending/error state.

Public Compose `SelectionState.selectedTexts` captures selected annotated
fragments in layout order without reading the clipboard. Selection is scoped to
the reader/source window and cleared on replacement; image fallback messages are
excluded. The public Compose context-menu provider displays a direct **Translate** icon alongside **Copy** and **Select all**; an equivalent
custom accessibility action uses the same capture. Selection is limited to 2,048
Unicode code points, shows the real language pair/result, Copy and Close. Copy
writes only the result to the clipboard. Translated selections require returning
to Original; arbitrary language detection is not implemented.

Reader settings provide Original/Translated, supported language-pair choices, Cancel/Retry and cache clearing. Central model administration is in Settings → Language and translation; a missing model opens an explicit size/language download prompt in the reader. These live inside
the existing reader panel, not permanent artwork/text chrome. Pending passage
translation is explicitly labeled as showing Original. Settings opening does not
restart an active translation, reparse the EPUB or reopen its publication.
Application-resolved appearance is passed to EPUB System mode; explicit reader
Light/Dark preferences retain their meaning. Existing Back/focus/panel contracts
and Android appearance restoration remain in use.

## Languages, model installation and privacy

Verified direct pairs: **English→Spanish (2.1), Spanish→English (2.0), English→Catalan (2.0), Catalan→English (2.0)**. Spanish↔Catalan is not offered as a direct pair. EPUB
language metadata suggests the source when recognized; unsupported/absent metadata
requires an explicit supported pair. The destination is persisted; matching source/target
keeps Original rather than silently selecting another destination. Manual source overrides
can be reset with Use EPUB language metadata. No fake detection or pivot is offered.

- en-es: 36,576,277 bytes; es-en: 37,014,089 bytes; en-ca: 36,716,532 bytes; ca-en: 36,731,612 bytes, including vocabulary/shortlist. Exact version/asset hashes are pinned in `FoxletEpubTranslation.kt` and checked against the SDK catalog.
- Exact asset URLs, sizes and SHA-256 values come from Foxlet's pinned Mozilla
  registry; the adapter also verifies that its SDK descriptors match those pins.
- Opening an original EPUB does not download or create a native translator.
  Download requires tapping **Download model**, after seeing the approximate size.
- Downloads use verified HTTPS to Mozilla's attachment host; no redirects, insecure
  trust exceptions, automatic updates or arbitrary model URLs are added.
- Installed models are verified and stored in Android `noBackupFilesDir` under a
  dedicated owned namespace. Delete unloads native ownership first; failed deletion
  is an error. Cancelled temporary downloads are cleaned, and repairs retire damaged
  copies before creating a replacement. Models are not included in the APK.
- Model downloads reveal ordinary network information such as IP/User-Agent and
  requested model pair to Mozilla. They send no book text, profile, history or
  reading locator. Inference uses local files; no translation telemetry or remote
  text service is added. Book text/results are not logged.

Translation availability requires Android 9/API 28+, a 64-bit ARM process and a
non-low-RAM device. **The application minimum remains API 26**: only this optional
SDK's manifest minimum is overridden, with the feature guarded before SDK/native
creation. Original readers and other existing ABIs remain supported. The APK keeps
the existing graphics native libraries for all four ABIs. Desktop, older Android,
32-bit and low-RAM devices show an honest unavailable state and retain original
reading. Foxlet recommends at least a 4 GB device; real INFINILECT memory/performance
on representative Android hardware remains an acceptance task.

## Bounds, cache and position

- Source windows remain 128 blocks/65,536 UTF-16 units, with two source windows
  retained. The displayed rolling chapter may contain 256 blocks/131,072 units.
  XML/ZIP/security budgets and source ownership do not increase.
- Translation rejects oversized source presentations up front. One native worker,
  one loaded model and one active inference permit; no book-wide task queue,
  background chapter translation or neighboring translated-window cache.
- A run request is at most 8,192 code points, split into at most sixteen 2,048-point
  segments, preferring word boundaries; a segment result is at most 8,192 UTF-16 units. One complete active
  projection retains at most 256 blocks/262,144 translated UTF-16 units. Old
  projections release on replacement. A partial projection is not presented as final.
- This implementation prioritizes the current bounded passage, then displays its
  complete validated projection. While it is cold, Original is readable. It never
  requires translating all chapters first; every window can be translated as read.
- Private result cache: **4 MiB including writes, 256 records**, SHA-256 names and
  payload checksums, strict UTF-8/length decoding, LRU eviction, no symlink reads,
  owner/OS locks and atomic same-directory replacement. Cache keys include
  publication/resource identity, byte revision, exact original segment text,
  provider/config version, source/target and pinned model asset fingerprints.
  Corruption/storage failure becomes a cache miss; it cannot change the EPUB.
- Cache clearing invalidates only translation records. Model delete and cache clear
  leave publications, library, history and saved progress alone. The cache is
  disposable application-cache storage; installed models survive Android cache
  clearing and normal restarts.
- Model disk budget: **120 MiB including staged downloads, 128 filesystem entries**,
  bounded traversal with no symlink following. Only four pinned direct pairs are offered; all four do not fit simultaneously within this fixed budget, so an explicit unused-model deletion may be needed.
  one download runs at a time and reserves its descriptor size before construction.
- The existing typed `ReadingLocator.Epub` remains the durable location: original
  spine path, element-child ordinal path, Unicode offset and chapter progression.
  Each projected run maps source/display code points proportionally; endpoints and
  run boundaries are exact, within-run word placement is approximate. Mode/font/
  viewport changes restore the live original anchor and retire old UI tickets.
  Translation starts, partial output, failures and cancelled requests never commit
  a destination. Only the reader's valid presented/scroll callbacks can save progress.
- Existing preferences migrate from the checksummed 68-byte v1 record through the compatible 80-byte v2 record to an 80-byte v3 record, preserving typography/theme/timestamps and supporting an explicitly following default/Catalan language codes.
  No database, duplicate settings owner or progress-schema migration is introduced.

## Native proof and verification

Pinned source was compiled with CMake 4.4.4 (managed tool package), NDK r29
`29.0.13113456` for Android ARM64/API 28, and Debian GCC 14 for Linux ARM64.
Android JNI `libfoxlet.so` and smoke executable compiled; ELF load segments have
16 KiB alignment. The production AAR is the independently checksummed upstream
release, not an untracked replacement of its binaries.

Real en-es and es-en assets were downloaded **for this explicitly requested research**
outside the repository, with every size/hash verified. Linux ARM inference ran under
QEMU 10.0.13 with local model paths and no networking permission. Three original
synthetic sentences translated in each direction. Example en-es:
“The reader remembers your place in the book.” → “El lector recuerda tu lugar en el libro.”
The two en-es passes had matching output hashes. The upstream lifecycle probe
reported `live_models=0`, `live_graphs=0`, `release_reported_destroyed=yes` and a
matching output after reloading. A separate opt-in Kotlin JVM task invokes this
real ARM executable and validates results; this proves a local Kotlin/process
prototype, **not execution of Android's JNI adapter**.

QEMU/managed Linux, one worker, three short sentences: load 1,809 ms; first inference
1,882 ms; repeated inference 943 ms; peak RSS about 140 MiB. Emulator RSS and speed
include its environment and cannot establish phone PSS, latency or quality for a
full reading window. Foxlet's own device benchmarks are upstream evidence, not
INFINILECT device verification. No physical Android or native Desktop graphical
acceptance is claimed.

Opt-in proof (requires separately built/verified native executable and model):

```sh
gradle :app:epubNativeTranslationCheck --args='/absolute/qemu-aarch64 /absolute/arm-sysroot /absolute/smoke /absolute/en-es/config.yml'
```

The previous implementation at `e0937e7` was verified with JDK 21 and the existing Gradle configuration (historical results; follow-up results below):

| Check | Result |
| --- | --- |
| Focused translation, selection UI, import and CBZ lifecycle: Desktop | 125 passed, 0 failed/errors/skipped |
| Same focused coverage: Android-host | 121 passed, 0 failed/errors/skipped |
| Complete Desktop application regression | 1,491 passed, 0 failed/errors/skipped |
| Complete Android-host application regression | 1,268 passed, 0 failed/errors/skipped |
| Android debug application assembly | Passed |
| Desktop application Kotlin compilation | Passed |
| Opt-in Kotlin/native real-model proof | Passed; three synthetic sentences, offline |

Production/test/build fingerprints remained unchanged throughout the final run.
`git diff --check` and tracked/nonignored artifact/signing-secret checks passed.
Core is unchanged; unrelated core suites were not rerun. Coverage includes selection
UI, projected run structure, Unicode mappings, progressive long chapters,
cancellation/stale work, retry, language changes, model budget, private cache
corruption/eviction/cleanup, preference migration and durable progress.
Deterministic fake-provider tests are separate from the native proof above. Original
EPUB2/EPUB3, security, TEXT/PDF/CBZ, library/history/search tests remain enabled.

## Regression cleanup correction

The second full Android-host run exposed a pre-existing CBZ resource-registry race
in `LocalImportReaderIntegrationTest`: `CbzDocument.close()` snapshots handles under
its ownership monitor, while a render/decoder close removed a handle without that
monitor. The same callback is present at the original PR #31 head and merged main.
A deterministic gated-set/thread test reproduces the actual
`ConcurrentModificationException` before the fix. Handle unregistration now uses
that same existing monitor (one production-line change). The test instruments only
our own registry and requires successful/idempotent shutdown, empty ownership and
closed handles; it adds no production hook. Reading, decoding, gesture behavior,
archive validation and resource limits are unchanged. This correction is necessary
for regression acceptance, not a comic-reader redesign.

## Android acceptance (pending)

1. Upgrade using the matching development signing identity; retain existing library,
   profile and saved positions. Open existing EPUB2/EPUB3 publications deep in a chapter.
2. Reading settings: choose a supported source→target pair, inspect size, tap Download;
   check progress, cancellation/retry, installed status and Delete. Never install a model
   merely by opening a book. Test network/storage failure without blocking Original.
3. Select a word, Unicode phrase and mixed-format paragraph. Tap Translate in the
   native selection toolbar; verify real output/languages, Copy and Close, unchanged
   reading location and no image/error text entering the selection.
4. Choose Translated; confirm replacement **inside** paragraphs/headings, preserved
   illustrations/links/notes, and a clear Original fallback while pending or on failure.
   Return to Original. Code/URLs remain original; mixed-run quality may differ.
5. Read beyond 2,048 blocks, forward/backward across windows and TOC/internal anchors.
   Rapidly change passages and language; late responses must not replace newer content.
6. Download both supported pairs explicitly. Enable airplane mode and repeat selection
   and reading translation. Revisit translated passages, close/reopen, change typography
   and rotate; verify the same original logical place and reusable private cache.
7. Clear translation cache and delete a model separately. Check unchanged EPUB bytes,
   library/history/progress; retranslation after cache clearing still works offline if
   its model is installed. Check light/dark/automatic controls and Back/exit restoration.
8. Verify representative device RAM/latency and nontranslation readers. Report device,
   Android version and ABI; automated host tests are not this acceptance.

## Known limitations

Only four verified direct pairs; no automatic detection, whole-book export, exact word
alignment, translation of preformatted/URL runs or engine-backed Desktop support.
Within-run locator restoration is approximate. Long mixed-style sentences can lose
context because runs translate separately. Cold window translation can take time;
this PR does not promise device latency. Cancel discards a native result and waits
for its bounded batch to drain rather than interrupting C++ mid-operation. Desktop
x86/Windows reuse requires a separate native backend/build/tokenizer investigation.
The young upstream SDK and model-license statement should be monitored; no
unrestricted model update or inference server fallback is enabled.

## ML Kit: verified technical and distribution findings

The official Maven metadata and Android guide identify
`com.google.mlkit:translate:17.0.3`. Its minimum Android API is 23; INFINILECT's
minimum is 26, so that particular requirement is compatible. The interface has
`downloadModelIfNeeded`, `translate(String)` and `close`. Neither asynchronous
method accepts a cancellation token in this inspected API. Cancelling a coroutine
alone must not be described as cancelling underlying native/model work; a future
adapter must serialize ownership, reject stale results and close safely.

The published POM declares **ML Kit Terms of Service**, not Apache-2.0. Those
terms say:

> When using the ML Kit APIs, you may not reverse engineer or attempt to extract
> the source code or any related software, except to the extent that this
> restriction is expressly prohibited by applicable law.

The API terms also impose restrictions on activities/purposes. An Apache license
in the documentation footer applies to **code samples**, not automatically to
the SDK. The inspected AAR contains 124 third-party notice entries, but no entry
licensing the ML Kit/translation implementation itself under an alternative free
license. The inspected SDK artifact is 27,684,160 bytes, SHA-256
`b6194f7b42034309cf8299784b2d5d70a82a2e9287fbe1650dea4d1f3ad1fe55`.
It was inspected outside the repository and is not bundled or committed.

This evidence does **not establish compatibility** with distributing the SDK as
part of the current GPL application. No linking exception is assumed, added or
granted by this PR. A free provider or a properly established distribution/license
resolution is required before integrating ML Kit. This is an engineering
distribution gate, not a claim that all jurisdictions resolve the issue identically.

Privacy is also narrower than "no telemetry": the terms state that input text
and resulting output remain on-device, **and** that metrics about API performance
and utilization are sent to Google, with possible update/model contacts. Required
user disclosure cannot be omitted. No such SDK/network behavior has been added.

Models are downloaded dynamically; the guide describes language models as around
30 MB and recommends explicit, Wi-Fi downloads and deletion of unused models.
Translation between non-English languages uses English as an intermediary, with
quality limitations. Language metadata is not linguistic detection. Translation
guidelines require the applicable Google Cloud Translation attribution/branding
requirements and limit use to specified personal-computing device categories.

Primary sources inspected:

- [Published POM](https://dl.google.com/dl/android/maven2/com/google/mlkit/translate/17.0.3/translate-17.0.3.pom)
- [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms)
- [Google APIs terms](https://developers.google.com/terms)
- [Android translation guide](https://developers.google.com/ml-kit/language/translation/android)
- [Translation guidelines and attribution](https://developers.google.com/ml-kit/language/translation/translation-terms)
- [Translation behavior/quality](https://developers.google.com/ml-kit/language/translation)

## Model/language UX follow-up

The user physically confirmed selection translation and translated EPUB reading on
realme C55 with the previous APK. That evidence does not validate this follow-up's
new UI, Catalan models, retention or localization. Re-acceptance remains pending.

### Central management and preferences

Settings → **Language and translation** now owns interface language, default reading
language and model administration. Interface language is local profile data in the
existing appearance/profile record. Null follows the captured system language at
startup (en/es/ca; English fallback). A separate optional reading-language override
in that same record follows interface language until the user overrides it. Old
appearance/profile/discovery records remain readable; the bounded checksummed v4
record adds the independent reading preference without a database migration.
Onboarding's Set up later preserves an explicitly selected interface language.

The existing EPUB preference record now supports v3 (still 80 bytes), reading target
`default` and Catalan codes. V1 typography/theme and V2 explicit en/es targets are
preserved. Legacy targets remain reader overrides until **Use default reading
language** is selected; changing the global default does not silently erase them.
A late-loaded global default updates a reader using `default`, preserving its live
original anchor and retiring old translation work. Explicit reader choices win.

`TranslationModels` is application-owned and lazy. It creates no native client,
inspects no models and runs no inference at startup/Home. One administrative job is
accepted; duplicate downloads share that job, different requests are rejected
rather than queued. The explicitly approved download survives reader disposal.
Cancel/Retry are explicit. Progress is coalesced to integer percentage changes;
only the open model panel consumes it. Book text never leaves the device.

Installed status, verified download size, actual owned storage, pinning, deletion
and errors appear in the central panel. Contextual selection/reading requests with
an absent registered model show pair/size/Download/Continue with Original. Download
is temporary by default there, pinned by default when explicitly installed from
Settings. Progress callbacks carry the authoritative administrative command identity; a deterministic cancelled-download trace fails without that guard and passes with it. After completion the current reader request can resume; a disposed or
obsolete reader cannot publish a result. A failed operation leaves Original usable.

### Retention, recovery and budgets

Pin/last-use metadata lives in a separate resource-ownership record beside existing
EPUB preferences, not a second user-profile/settings database. It is at most 512
bytes/four records, checksummed and atomically replaced. Missing/corrupt metadata
conservatively protects installed models as pinned. Completed files whose retention
write fails remain protected; installation/storage errors are visible and retryable.

- Pinned models are never eligible for cleanup. Explicit confirmed deletion remains
  possible. Merely closing a book removes nothing.
- Temporaries are reused between books. Cleanup is **manual and confirmed**, only
  after 30 days plus a one-hour safety grace. Hourly usage writes avoid per-paragraph
  filesystem work; the grace protects use not yet persisted before unexpected exit.
- Active leases block deletion; cleanup skips them. The adapter drains/unloads and
  confirms native release before deleting owned model files. Other books, cached
  translations, Library/History/progress and profile/preferences are untouched.
- The existing **120 MiB/128-entry model budget**, staged-size reservation, verified
  assets and atomic SDK installation are unchanged. Four available pairs do not
  imply four resident/installed models: the disk budget fits three of these small
  models, and only one native model is loaded for inference.
- Interrupted downloads are repaired/cleaned by the SDK on the next explicit install. The SDK’s default superseded-version cleanup is explicitly disabled; partial-download recovery cannot silently evict retained versions.
  Unknown installed versions are never automatically updated or silently removed.

### Direct selection action and EPUB controls

Compose's public `TextContextMenuProvider` and its toolbar/dropdown composition
locals supply a bounded custom popup. The inspected `ProvideBasicTextContextMenu`
helper is **internal**, so it is not called, reflected or copied. The popup uses
public selectedTexts/selectAll APIs, keeps Copy/Select all and a direct translation
icon (when available), and clamps to the actual safe reader viewport. There is no
duplicate overflow Translate item. Copy writes the selected/result text; extraction
never reads the clipboard. The toolbar is nonmodal and does not take window focus: Compose owns handle dragging and normal outside-tap clearing; the reader clears selection before Back/Escape. Standard selection keyboard operations stay with the reading surface; Alt+T is a translation shortcut when a real engine is available. Accessibility uses the direct button and selection custom action. Desktop has no fake translation icon when its engine
is unavailable. Provider-specific OS smart-selection actions are not implemented.

EPUB Previous/Next are accessible 48-dp arrow controls with unchanged controller
callbacks/disabled states. Continuous-scroll viewport gestures, chapter/window
boundaries, TOC/internal links and logical progress semantics are unchanged.

### Multilingual investigation and actual native evidence

| Candidate | Primary evidence / license | Technical finding / decision |
| --- | --- | --- |
| Mozilla directional intgemm models | [Pinned registry](https://github.com/yinvoke/foxlet-translate/blob/0eff2a15032362115848a496ce1df8ed9fc68650/registry.json); separately MPL-2.0 weights per the Mozilla source/maintainer statement above; SentencePiece code Apache-2.0 | Model, shortlist and tokenizer hashes/sizes verified. English↔Catalan binaries loaded by the real existing ARM engine offline under QEMU and translated three original synthetic sentences in both directions; both repeated passes matched hashes. Added only these two verified directions. |
| English bridge | [Foxlet public API/integration guide](https://github.com/yinvoke/foxlet-translate/blob/0eff2a15032362115848a496ce1df8ed9fc68650/docs/getting-started.md), `translatePivot`; same separately licensed Mozilla assets | A real native es→en→ca smoke chain translated two synthetic sentences. Not integrated in application language choices: two resident models/workspaces and a second inference stage raise memory/latency and require separate quality/segmentation/cache validation. Sequential unload/reload is a possible future trade-off, not implemented. |
| OPUS-MT en-ROMANCE | [Primary model card](https://huggingface.co/Helsinki-NLP/opus-mt-en-ROMANCE): Apache-2.0, Marian architecture, separate tokenizer artifacts and required `>>id<<` target token | A multilingual Marian model is not automatically a Foxlet-ready quantized model. Published Hugging Face/checkpoint assets require conversion, quantization, compatible shortlist/tokenizer and target-prefix validation. No native load/conversion was demonstrated; not catalogued or downloaded. |
| M2M100 418M | [Primary model card](https://huggingface.co/facebook/m2m100_418M): MIT weights/card; tokenizer/runtime must be checked separately | Different model/tokenizer architecture, not a direct Bergamot model. Roughly 1.67 GB for 418M FP32 parameters alone (estimate, not RAM benchmark); unsuitable for this bounded modest-phone slice without another verified backend/quantization. Not integrated. |
| NLLB distilled 600M | [Primary model card](https://huggingface.co/facebook/nllb-200-distilled-600M): CC-BY-NC-4.0 | Noncommercial restriction fails the chosen redistributable GPL-compatible model boundary; different architecture and much larger resources. Not integrated or downloaded. |

Measured **Linux ARM/QEMU**, one worker/cache zero, three short synthetic sentences:
en→ca load 1,156 ms, first inference 1,146 ms, repeat 664 ms; ca→en load 1,102 ms,
first 1,183 ms, repeat 677 ms; reported peak RSS about 139 MiB. The two-sentence
resident es→en→ca chain reported about 210 MiB, load 2,199 ms, first 1,663 ms and
repeat 1,011 ms. These are distinct workloads, not an apples-to-apples phone speed
comparison, and do not establish realme C55 PSS/latency. Source/weights/vocabulary
and auxiliary assets remain separately licensed; permissive code does not license
arbitrary models. Android ARM64 uses the existing SDK; Desktop translation remains
unavailable although native Linux ARM prototype inference is verified.

### Localization: implemented and pending

Compose Multiplatform `components-resources:1.12.1` (Apache-2.0, matching the existing
Compose version) supplies compiled static strings in `values`, `values-es`,
`values-ca`. Public getString and Java locale APIs load a finite catalog off the UI
thread only on startup/language change. A composition-local immutable string table
updates labels without recreating reading sessions. English is the fallback; resource
failure retains the last successfully applied language rather than crashing. No model-generated UI strings.

Historical PR #31 coverage (see the PR #33 update linked above):

| Surface | Actual coverage |
| --- | --- |
| Interface/default reading settings and model management | en/es/ca strings, errors, confirmation, progress and accessibility |
| Profile/onboarding editor | en/es/ca, real local preference persistence and country labels |
| Application Settings (appearance/discovery preferences) | en/es/ca; source filter subjects/behavior remain unchanged |
| EPUB controls, reading settings, selection/result/download panels | en/es/ca, arrows, accessibility, known EPUB/translation errors |
| Main navigation/import accessibility and Home greeting/local section labels | Localized; remaining Home/discovery/details text is pending |
| Library, History, Search/acquisition/details; TXT/PDF/CBZ controls/errors | Full extraction/translation remains pending; original functionality retained |

In PR #31 the UI described that partial scope; PR #33 removes the obsolete notice. **The historical PR #31 table does not describe current application coverage.** Original publication metadata and source rights statements are not
rewritten. System-language changes outside the app while its process remains alive
may require a normal relaunch; explicit en/es/ca changes apply within the app without
restarting it. Broad Home scrolling/recomposition work belongs to PR #32; no phone
performance improvement is claimed here.

### Model/language follow-up verification at `3f3ac2a` (historical)

| Check | Result |
| --- | --- |
| Focused Desktop / Android-host | 110 / 80 passed; 0 failures/errors/skips |
| Full Desktop app | 1,510 passed; 0 failures/errors/skips |
| Full Android-host app | 1,284 passed; 0 failures/errors/skips |
| Android debug APK assembly / Desktop Kotlin compilation, JDK 21 | Both passed |
| Real Catalan model / bridge prototypes | Offline native ARM/QEMU passed; not physical Android |
| Diff / secret/artifact hygiene / verified source fingerprint | Passed / clean / unchanged |

The original UI regression found a missing contextual Close-panel accessibility label; production preserves it and the existing assertion passes. The cancelled-download progress trace fails before the command-identity guard and passes afterward. No tests are weakened/disabled. Core is unchanged; unrelated core suites were not rerun. Physical acceptance of this follow-up remains pending.

1. Upgrade the existing development installation; verify profile, Library/History,
   reader typography and deep original locators. Open Settings → Language and translation.
2. Choose en/es/ca interface and an independent reading default; restart and verify
   both preferences/country. Check the documented partial localization, themes,
   large fonts, Back/Escape and keyboard focus.
3. Inspect four actual directions/sizes/storage. Explicitly download, Cancel/Retry,
   pin/unpin and confirm Delete. Attempt a fourth installed model against the fixed
   budget; no hidden download or automatic pinned deletion is allowed.
4. On an original EPUB with a missing supported pair, select Unicode/mixed text →
   direct translation icon → pair/size consent → Download or keep Original. Repeat
   for translated reading. Leave the reader during download and inspect central status.
5. Check Copy/Select all, one-tap Translate and Copy result; no duplicate overflow
   action. Check arrows/disabled states, viewport navigation and long windows.
6. Test English↔Spanish and English↔Catalan with appropriate original languages.
   Spanish↔Catalan must report no direct pair, not simulate a bridge. Download needed
   resources explicitly, enable airplane mode and test selection/reading/cache/reopen.
7. Check preserved original anchors after Original/Translated, typography, rotation,
   language changes and errors. Pinned/recent/active models must survive cleanup;
   closing a book must not delete a model. General Home jank remains PR #32 work.

## Selection, resource packaging and Settings correction (PR #31 follow-up)

Starting HEAD: `3f3ac2aed209e9953f7eb230bd49466290077c65`. The user reported blocked
selection handles/two-tap cancellation at large text size, missing Spanish resources,
and an unclear Settings access row. Translation and model management were reported
working on a physical realme C55; this correction has not yet been tested there.

### Confirmed implementation defects and reproduction

- The custom selection toolbar used `PopupProperties(focusable = true)`: a separate
  modal window owned input instead of allowing underlying selection-handle windows
  and the reading surface to operate normally. Outside dismissal completed only the
  menu session, leaving the actual `SelectionState` selected. The deterministic
  `oneOutsideTapClosesMenuAndClearsRealSelection` pointer trace failed before correction
  and passes afterward. The real long-press/two-handle pointer trace also fails with the old focusable popup and passes with the correction. Android handle behavior requires physical re-acceptance;
  Desktop pointer traces and public SelectionState checks are distinguished below.
- The APK had **no** `assets/composeResources/.../strings.commonMain.cvr` files for
  any of its three languages. AGP's Kotlin Multiplatform library has Android resource
  support disabled by default; `app` had not enabled it. Desktop tests read their
  classpath copies and could not detect the installed-artifact defect. The new
  `:androidApp:verifyDebugLanguageResources` task failed against the old configuration
  with Missing packaged language resource, and passes after enabling
  `androidResources { enable = true }` on the existing KMP Android target. It checks
  English/Spanish/Catalan APK assets against the generated resources byte for byte.
  No hand-copied resources, native locale override or permanent English fallback
  replaces Compose's supported packaging pipeline.

### Selection ownership and interaction

The same public provider is retained, but its toolbar is non-focusable and does not
intercept/dismiss on touches outside its buttons. This is essential: a toolbar
outside-click listener must not clear selection when a handle is touched. Compose's
SelectionContainer retains real handles, drag recognition, Unicode/styled fragments,
Copy and Select all. The toolbar follows the selection/menu session and closes when
the actual selection is cleared; explicit dismissal/Copy/Translate also clear it.
The existing final-pass tap observer remembers whether selection was active at
pointer down: a valid unconsumed dismissal tap clears selection instead of also
turning a viewport or opening chrome. It does not globally consume events; links,
scrolls, long presses and handle gestures retain their normal priority.

Back/Escape clears active selection first. Typography changes, source/window
replacement and reader disposal retire selection and menu ownership. Translation
captures exactly public `selectedTexts` before clearing, without clipboard reads.
Alt+T provides a keyboard equivalent while the reading surface keeps focus; Ctrl/Cmd+A selects currently composed text;
Copy/Select all retain standard selection behavior. Select all with LazyColumn
covers currently composed selectable nodes, **not the whole book**. Selection and
long-press/drag capability remain subject to Compose's real composed-text/window
limits; no fake cross-chapter or unloaded-text selection is introduced.

### Language loading and Settings layout

A public ResourceEnvironment snapshot is captured once per request. The finite
resource table is loaded off the main thread and committed atomically; stale or
cancelled loads cannot replace newer strings or their loading state. A real failure
keeps the last successfully applied table/language, exposes Retry, and emits only
`INFINILECT.Locale stage=load-interface-resources language=<code> exception=<class>`.
It never logs exception messages, resource/filesystem paths, profile or publication
text. Language preferences and reading-language overrides retain their prior format
and owner. That PR #31 correction retained **partial** localization. PR #33 extends it as
documented in LOCALIZATION_COMPLETION.md; the subsequent PR #32 performance changes
are preserved.

Language and translation and Edit profile are full-width settings rows with a
leading glyph, title, short description, disclosure chevron, native click/ripple,
keyboard focus and button semantics. The language modal measures within its own
safe/IME-constrained viewport; only the body scrolls, while title/close and Done
reserve their own space. It uses existing Compose components/dependencies and
application-resolved colors. Model downloads/storage/retention and native inference
remain unchanged.

### Verification and physical checklist

Final verification (JDK 21, Gradle 9.7.1, managed Linux environment):

| Check | Result |
| --- | --- |
| Focused Desktop / Android-host | 223 / 129 passed; zero failures/errors/skips |
| Complete Desktop application | 1,521 passed; zero failures/errors/skips |
| Complete Android-host application | 1,284 passed; zero failures/errors/skips |
| Android debug / Desktop compilation | Passed |
| Packaged en/es/ca resource bytes / Compose context provider | Verified |
| Source/build/resource fingerprint during final verification | Unchanged (350 paths) |

An initial complete Desktop run passed 1,520 tests and failed one unchanged CBZ
fixture's initial page-presentation wait (`Spread 0 must be fully presented`),
before its edge gesture began. That exact test passed isolated, then all 1,521
passed in the complete rerun with one Gradle worker. The intermittent startup cause
is not established; no CBZ code/test or assertion was changed. The complete
Android-host run passed all 1,284. No tests were disabled or skipped. Core is
unchanged and unrelated core suites were not rerun.

Before/after regression receipts and generated diagnostic artifacts remain outside
Git. Full Android-host/Desktop tests cover the original EPUB and other readers,
model/progress/persistence/security contracts. The APK resource assertion complements
JVM resource/key/placeholder and runtime locale tests; it is not Android execution.

On realme C55:

1. Upgrade the development APK; verify existing Library/History/profile/country,
   installed/pinned models, appearance and deep EPUB saved positions.
2. Settings → Language and translation: en/es/ca/System, without relaunch; localized
   Settings/EPUB must follow the choice. Restart and verify the independent reading
   target. Other not-yet-localized screens remain explicitly out of scope.
3. Large font/long Unicode paragraph: long-press a word, drag both handles in both
   directions and across visible lines/paragraphs. One outside tap clears handles
   and menu without turning the viewport or opening controls; select another word.
4. Translate selected styled/Unicode text, Copy, Select all; return from the result
   without moving the logical position. Back first cancels selection, then follows
   normal reader behavior. Test hyperlinks, scrolling and center controls.
5. Change chapter/font/margins and close/reopen with selection active: no stale
   handles/menu/text capture, no progress loss. Check long-window boundaries.
6. Light/dark, portrait/landscape, small viewport and large accessibility fonts:
   settings rows visibly respond; scroll all language/model options and confirm Done
   remains reachable. Check Escape/keyboard equivalents on Desktop.
7. If a genuine resource error appears, collect the targeted diagnostic with
   `adb logcat -v threadtime INFINILECT.Locale:W '*:S'`; no book text is included.

Native Android touch/handle behavior, TalkBack, IME/system-inset integration and
native Desktop graphical acceptance require real-platform testing. Automated
headless scenes/builds are not represented as physical acceptance.
